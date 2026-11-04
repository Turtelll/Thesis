//! The tile oracle of the research pipeline encoding as the vertex encoder of MapLibre Tile v2.
//!
//! A tile is a list of layers. Each layer is written by the patched `mlt-core` with the pipeline codec
//! as its vertex stream (`EncoderConfig::with_vertex_pipeline`). The composition of the codec is chosen
//! per layer by a [`VertexMode`]:
//!
//! * [`VertexMode::Default`]: `for_layer(layer name)`;
//! * [`VertexMode::Fixed`]: one composition for every layer;
//! * [`VertexMode::Oracle`]: `TileSelector.oracle`, a coordinate descent over the gzip size of the whole tile
//!   across the 96 compositions of `bestGrid()`.
//!
//! A composition's reorder permutes whole layers (id and every property column follow), so each candidate
//! is a complete layer encoding. `mlt-oracle compare` and `mlt convert --vertex-pipeline` share this code.

use flate2::Compression;
use flate2::write::GzEncoder;
use mlt_core::encoder::{EncoderConfig, Network, Profile, Tessellation, WireVersion};
use mlt_core::{Decoder, Parser, TileLayer};
use mlt_pipeline::registry::{best_grid, for_layer};
use mlt_pipeline::{Composition, Reorder};
use rayon::prelude::*;
use std::io::Write;
use std::panic::{AssertUnwindSafe, catch_unwind};

/// The pass cap of `TileSelector.ORACLE_MAX_PASSES`.
pub const ORACLE_MAX_PASSES: usize = 10;

/// How the composition of the vertex codec is chosen for the layers of a tile.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum VertexMode {
    /// The stock v2 vertex encoding.
    Off,
    /// `for_layer(layer name)` for every layer.
    Default,
    /// The tile oracle, at most `max_passes` descent passes.
    Oracle { max_passes: usize },
    /// One composition (of `best_grid()`) for every layer.
    Fixed(Composition),
}

impl VertexMode {
    /// A composition of `best_grid()` by its `Composition::name()`.
    pub fn fixed(name: &str) -> Option<Self> {
        best_grid().into_iter().find(|c| c.name() == name).map(Self::Fixed)
    }
}

/// What happened to one layer of an encoded tile.
#[derive(Clone, Debug)]
pub struct LayerChoice {
    pub name: String,
    /// The composition that wrote the vertex stream; `None` where the layer fell back to the stock vertex layout.
    pub composition: Option<Composition>,
}

/// An encoded tile: the layers' bytes concatenated.
#[derive(Debug)]
pub struct TileEncoding {
    pub bytes: Vec<u8>,
    /// The bytes of each layer, in order (ytes is their concatenation; a layer without features has none).
    pub lengths: Vec<usize>,
    pub layers: Vec<LayerChoice>,
    /// With verification on: how the decoded geometry relates to the stock decode, per pipelined layer.
    pub geometry: Vec<(String, GeomStats)>,
}

/// The stock v2 encoder as `mlt-convert --mlt-version 2` configures it, tessellation off.
pub fn stock_config() -> EncoderConfig {
    EncoderConfig::from_profile(Profile::Native(Network::default()))
        .with_wire_version(WireVersion::V02)
        .with_tessellation(Tessellation::Off)
        .with_float_alp(true)
        .with_float_dict(true)
        .with_packed_dict_codes(true)
        .with_triangles_only(false)
}

/// Encodes the layers of one tile.
///
/// `base` is the stock configuration of the layers: the pipeline needs tessellation off and no feature sorts
/// (the composition's reorder is the feature order). With `verify` the written tile is decoded and compared
/// with the stock decode of the same layers.
///
/// # Errors
/// When a layer cannot be written at all, or the written tile fails verification.
pub fn encode_tile(layers: Vec<TileLayer>, base: EncoderConfig, mode: VertexMode, verify: bool) -> Result<TileEncoding, String> {
    match mode {
        VertexMode::Off => {
            let mut bytes = Vec::new();
            let mut lengths = Vec::new();
            let mut choices = Vec::new();
            for layer in &layers {
                let encoded = encode_layer(layer, base).ok_or_else(|| format!("layer {}: stock encode failed", layer.name()))?;
                lengths.push(encoded.len());
                bytes.extend(encoded);
                choices.push(LayerChoice { name: layer.name().to_owned(), composition: None });
            }
            Ok(TileEncoding { bytes, lengths, layers: choices, geometry: Vec::new() })
        }
        VertexMode::Default | VertexMode::Fixed(_) => {
            let mut parts: Vec<(Vec<u8>, Option<Composition>)> = Vec::new();
            for layer in &layers {
                let composition = match mode {
                    VertexMode::Fixed(c) => c,
                    _ => for_layer(layer.name()),
                };
                parts.push(match encode_with(layer, composition, base) {
                    Some(bytes) => (bytes, Some(composition)),
                    None => {
                        let stock = encode_layer(layer, base).ok_or_else(|| format!("layer {}: stock encode failed", layer.name()))?;
                        (stock, None)
                    }
                });
            }
            finish(layers, parts, base, verify)
        }
        VertexMode::Oracle { max_passes } => {
            let grid = best_grid();
            let trials: Vec<LayerTrial> = layers
                .into_iter()
                .map(|l| trial_layer(l, &grid, false, base))
                .collect::<Result<_, _>>()?;
            let alone = alone_sizes(&trials);
            let sel = select(&trials, &grid, &alone, &|_| true, max_passes);
            let parts: Vec<(Vec<u8>, Option<Composition>)> = trials
                .iter()
                .zip(&sel.chosen)
                .map(|(t, &c)| (t.candidates[c].clone().expect("chosen is allowed"), t.pipelined.then(|| grid[c])))
                .collect();
            let layers = trials.into_iter().map(|t| t.source).collect();
            finish(layers, parts, base, verify)
        }
    }
}

fn finish(
    layers: Vec<TileLayer>,
    parts: Vec<(Vec<u8>, Option<Composition>)>,
    base: EncoderConfig,
    verify: bool,
) -> Result<TileEncoding, String> {
    let bytes: Vec<u8> = parts.iter().flat_map(|(b, _)| b.iter().copied()).collect();
    let mut geometry = Vec::new();
    if verify {
        // A layer without features encodes to no bytes and is not in the decoded tile.
        let checked: Vec<VerifyLayer> = layers
            .iter()
            .zip(&parts)
            .filter(|(l, _)| l.feature_count() > 0)
            .map(|(l, (_, c))| VerifyLayer { source: l, composition: *c })
            .collect();
        verify_layers(&bytes, &checked, base, &mut geometry)?;
    }
    let choices = layers
        .iter()
        .zip(&parts)
        .map(|(l, (_, c))| LayerChoice { name: l.name().to_owned(), composition: *c })
        .collect();
    let lengths = parts.iter().map(|(b, _)| b.len()).collect();
    Ok(TileEncoding { bytes, lengths, layers: choices, geometry })
}

/// The layer written with `composition`: its feature reorder applied to whole features, the codec as vertex stream.
/// `None` where the layer cannot be written that way (z coordinates, geometry collections, ...).
fn encode_with(layer: &TileLayer, composition: Composition, base: EncoderConfig) -> Option<Vec<u8>> {
    let buffer = layer.vertex_buffer().ok()?;
    let order = catch_unwind(AssertUnwindSafe(|| composition.reorder.feature_order(&buffer))).ok().flatten();
    let permuted = order.map_or_else(|| layer.clone(), |o| layer.permuted(&o));
    encode_layer(&permuted, pipeline_config(base, composition))
}

// --- sizes --------------------------------------------------------------------------------------

struct Counter(usize);

impl Write for Counter {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        self.0 += buf.len();
        Ok(buf.len())
    }
    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

/// The size of `parts` concatenated, gzip level 6 (the zlib default, as `java.util.zip` deflates).
pub fn gzip_len<'a>(parts: impl IntoIterator<Item = &'a [u8]>) -> usize {
    let mut encoder = GzEncoder::new(Counter(0), Compression::new(6));
    for part in parts {
        encoder.write_all(part).expect("a counter takes every write");
    }
    encoder.finish().expect("a counter takes every write").0
}

// --- encoders -----------------------------------------------------------------------------------

/// `base` with the composition as vertex stream and the feature sorts off: the reorder is the composition's.
pub fn pipeline_config(base: EncoderConfig, composition: Composition) -> EncoderConfig {
    base.with_spatial_morton_sort(false)
        .with_spatial_hilbert_sort(false)
        .with_id_sort(false)
        .with_vertex_pipeline(Some(composition))
}

/// `base` with the encoder's own feature-order trials (Hilbert, feature id).
pub fn sorted_config(base: EncoderConfig) -> EncoderConfig {
    base.with_spatial_hilbert_sort(true).with_id_sort(true)
}

pub fn encode_layer(layer: &TileLayer, config: EncoderConfig) -> Option<Vec<u8>> {
    catch_unwind(AssertUnwindSafe(|| layer.encode(config))).ok()?.ok()
}

pub fn decode_layers(bytes: &[u8]) -> Result<Vec<TileLayer>, String> {
    let layers = Parser::default().parse_layers(bytes).map_err(|e| e.to_string())?;
    let mut dec = Decoder::default();
    let mut out = Vec::new();
    for layer in layers {
        let tile = layer.into_tile(&mut dec).map_err(|e| e.to_string())?;
        out.push(tile.ok_or("an unknown layer")?);
    }
    Ok(out)
}

// --- one layer ----------------------------------------------------------------------------------

/// A layer of a tile with all its candidate encodings.
pub struct LayerTrial {
    pub name: String,
    pub stock: Vec<u8>,
    pub stock_sorted: Option<Vec<u8>>,
    /// The layer bytes per grid composition, `None` where the layer cannot be written that way.
    /// When no composition is allowed this is the stock bytes alone.
    pub candidates: Vec<Option<Vec<u8>>>,
    /// Whether the candidates are the pipeline's (false: the layer fell back to stock).
    pub pipelined: bool,
    /// Index of `for_layer(name)` in the grid.
    pub default: usize,
    /// The layer with the feature order of each reorder, for verification.
    pub source: TileLayer,
}

pub fn trial_layer(layer: TileLayer, grid: &[Composition], sorted: bool, base: EncoderConfig) -> Result<LayerTrial, String> {
    let name = layer.name().to_owned();
    let stock = encode_layer(&layer, base).ok_or_else(|| format!("layer {name}: stock encode failed"))?;
    let stock_sorted = if sorted { encode_layer(&layer, sorted_config(base)) } else { None };
    let default_composition = for_layer(&name);
    let default = grid.iter().position(|c| *c == default_composition).expect("the grid holds every default");

    let permuted: Vec<(Reorder, TileLayer)> = match layer.vertex_buffer() {
        Ok(buffer) => [Reorder::None, Reorder::HilbertCentroidRotMp, Reorder::Chain]
            .into_iter()
            .map(|reorder| {
                let order = catch_unwind(AssertUnwindSafe(|| reorder.feature_order(&buffer))).ok().flatten();
                (reorder, order.map_or_else(|| layer.clone(), |o| layer.permuted(&o)))
            })
            .collect(),
        Err(_) => Vec::new(),
    };
    let candidates: Vec<Option<Vec<u8>>> = if permuted.is_empty() {
        Vec::new()
    } else {
        grid.par_iter()
            .map(|c| {
                let (_, permuted) = permuted.iter().find(|(r, _)| *r == c.reorder).expect("every reorder is permuted");
                encode_layer(permuted, pipeline_config(base, *c))
            })
            .collect()
    };
    if candidates.iter().all(Option::is_none) {
        return Ok(LayerTrial {
            name,
            stock: stock.clone(),
            stock_sorted,
            candidates: vec![Some(stock)],
            pipelined: false,
            default: 0,
            source: layer,
        });
    }
    Ok(LayerTrial { name, stock, stock_sorted, candidates, pipelined: true, default, source: layer })
}

// --- the oracle ---------------------------------------------------------------------------------

pub struct Selection {
    pub chosen: Vec<usize>,
    pub alone: Vec<usize>,
    pub tile_default: Vec<usize>,
    pub default_gzip: usize,
    pub alone_gzip: usize,
    pub oracle_gzip: usize,
}

pub fn concat<'a>(trials: &'a [LayerTrial], chosen: &[usize], swap: Option<(usize, usize)>) -> impl Iterator<Item = &'a [u8]> {
    trials.iter().enumerate().map(move |(l, trial)| {
        let c = match swap {
            Some((layer, with)) if layer == l => with,
            _ => chosen[l],
        };
        trial.candidates[c].as_deref().expect("a chosen candidate is allowed")
    })
}

/// Every candidate's gzip size on its own (`usize::MAX` where it cannot be written).
pub fn alone_sizes(trials: &[LayerTrial]) -> Vec<Vec<usize>> {
    trials
        .par_iter()
        .map(|t| t.candidates.par_iter().map(|c| c.as_ref().map_or(usize::MAX, |b| gzip_len([b.as_slice()]))).collect())
        .collect()
}

/// Which candidates a search may take: written, and kept by `keep`. A layer that `keep` leaves nothing
/// keeps every written candidate.
fn allowed_candidates(trials: &[LayerTrial], grid: &[Composition], keep: &dyn Fn(&Composition) -> bool) -> Vec<Vec<bool>> {
    trials
        .iter()
        .map(|t| {
            let kept: Vec<bool> =
                (0..t.candidates.len()).map(|c| t.candidates[c].is_some() && (!t.pipelined || keep(&grid[c]))).collect();
            if kept.iter().any(|&k| k) { kept } else { t.candidates.iter().map(Option::is_some).collect() }
        })
        .collect()
}

/// Where a layer's descent starts: its default if allowed, else the allowed candidate with the default's
/// predictor and terminal, else the first allowed one (`TileSelector.start`).
fn start_index(t: &LayerTrial, grid: &[Composition], allowed: &[bool]) -> usize {
    if !t.pipelined {
        return 0;
    }
    if allowed[t.default] {
        return t.default;
    }
    let d = grid[t.default];
    (0..allowed.len())
        .find(|&c| allowed[c] && grid[c].predictor == d.predictor && grid[c].terminal == d.terminal)
        .or_else(|| allowed.iter().position(|&a| a))
        .expect("a layer keeps one candidate")
}

/// A port of `TileSelector.search` with `startFromAlone`, over the gzip size of the whole tile,
/// among the candidates `keep` allows.
pub fn select(
    trials: &[LayerTrial],
    grid: &[Composition],
    alone_sizes: &[Vec<usize>],
    keep: &dyn Fn(&Composition) -> bool,
    max_passes: usize,
) -> Selection {
    let allowed = allowed_candidates(trials, grid, keep);
    let start: Vec<usize> = trials.iter().enumerate().map(|(l, t)| start_index(t, grid, &allowed[l])).collect();
    let mut chosen = start.clone();
    let mut best = gzip_len(concat(trials, &chosen, None));
    let default_gzip = best;

    let alone: Vec<usize> = alone_sizes
        .iter()
        .zip(&allowed)
        .map(|(sizes, allowed)| {
            let mut winner = allowed.iter().position(|&a| a).expect("a layer keeps one candidate");
            for c in 0..sizes.len() {
                if allowed[c] && sizes[c] < sizes[winner] {
                    winner = c;
                }
            }
            winner
        })
        .collect();
    let alone_gzip = gzip_len(concat(trials, &alone, None));
    if alone_gzip < best {
        chosen.clone_from(&alone);
        best = alone_gzip;
    }

    for _ in 0..max_passes {
        let mut changed = false;
        for l in 0..trials.len() {
            let held = chosen.clone();
            let sizes: Vec<usize> = (0..trials[l].candidates.len())
                .into_par_iter()
                .map(|c| {
                    if c == held[l] || !allowed[l][c] {
                        usize::MAX
                    } else {
                        gzip_len(concat(trials, &held, Some((l, c))))
                    }
                })
                .collect();
            let mut winner = chosen[l];
            for (c, &size) in sizes.iter().enumerate() {
                if size < best {
                    best = size;
                    winner = c;
                }
            }
            if winner != chosen[l] {
                chosen[l] = winner;
                changed = true;
            }
        }
        if !changed {
            break;
        }
    }
    Selection { chosen, alone, tile_default: start, default_gzip, alone_gzip, oracle_gzip: best }
}

/// Every layer on `composition` where it was written (the layer's own default index otherwise).
pub fn fixed_choice(trials: &[LayerTrial], grid: &[Composition], composition: Composition) -> Vec<usize> {
    let index = grid.iter().position(|c| *c == composition).expect("the grid holds the composition");
    trials
        .iter()
        .map(|t| {
            if !t.pipelined {
                0
            } else if t.candidates[index].is_some() {
                index
            } else {
                t.candidates.iter().position(Option::is_some).expect("a layer keeps one candidate")
            }
        })
        .collect()
}

pub fn raw_len(trials: &[LayerTrial], chosen: &[usize]) -> usize {
    concat(trials, chosen, None).map(<[u8]>::len).sum()
}

// --- verification -------------------------------------------------------------------------------

/// The chosen tile decodes: ids and properties as the stock encoding of the same feature order, and
/// the vertices as the codec's emitted buffer.
///
/// It also compares every feature's geometry with the stock decode of the same layer, which the
/// vertex pass (ring rotation, MultiPoint sort) and line reversal may reorder but never change.
pub fn verify(
    trials: &[LayerTrial],
    grid: &[Composition],
    chosen: &[usize],
    base: EncoderConfig,
) -> (Result<(), String>, Vec<(String, GeomStats)>) {
    let mut stats = Vec::new();
    let outcome = verify_tile(trials, grid, chosen, base, &mut stats);
    (outcome, stats)
}

/// How the vertices of a decoded feature relate to the stock decode of the same feature.
#[derive(Default, Clone, Copy, Debug)]
pub struct GeomStats {
    pub features: usize,
    pub identical: usize,
    pub reversed: usize,
    pub reordered: usize,
    pub different: usize,
}

impl GeomStats {
    pub fn add(&mut self, other: &Self) {
        self.features += other.features;
        self.identical += other.identical;
        self.reversed += other.reversed;
        self.reordered += other.reordered;
        self.different += other.different;
    }
}

type Xy = (i32, i32);

/// One connected piece of a geometry: what a decoder may rewrite without changing the shape.
#[derive(Clone, PartialEq, Eq, PartialOrd, Ord)]
enum Part {
    /// A closed ring, stored with its closing vertex.
    Ring(Vec<Xy>),
    Line(Vec<Xy>),
    /// A MultiPoint (or single point): an unordered set.
    Points(Vec<Xy>),
}

fn parts_of(geometry: &mlt_core::geo_types::Geometry<i32>, out: &mut Vec<Part>) {
    use mlt_core::geo_types::{Coord, Geometry as G, LineString, Polygon};
    fn xy(c: &Coord<i32>) -> Xy {
        (c.x, c.y)
    }
    fn line(l: &LineString<i32>) -> Vec<Xy> {
        l.0.iter().map(xy).collect()
    }
    fn polygon(p: &Polygon<i32>, out: &mut Vec<Part>) {
        out.extend(std::iter::once(p.exterior()).chain(p.interiors()).map(|r| Part::Ring(line(r))));
    }
    match geometry {
        G::Point(p) => out.push(Part::Points(vec![(p.x(), p.y())])),
        G::Line(l) => out.push(Part::Line(vec![(l.start.x, l.start.y), (l.end.x, l.end.y)])),
        G::LineString(l) => out.push(Part::Line(line(l))),
        G::Polygon(p) => polygon(p, out),
        G::MultiPoint(m) => out.push(Part::Points(m.0.iter().map(|p| (p.x(), p.y())).collect())),
        G::MultiLineString(m) => out.extend(m.0.iter().map(|l| Part::Line(line(l)))),
        G::MultiPolygon(m) => m.0.iter().for_each(|p| polygon(p, out)),
        G::Rect(r) => polygon(&r.to_polygon(), out),
        G::Triangle(t) => polygon(&t.to_polygon(), out),
        G::GeometryCollection(c) => c.0.iter().for_each(|g| parts_of(g, out)),
    }
}

/// The smallest cyclic rotation of an open ring.
fn canonical_rotation(open: &[Xy]) -> Vec<Xy> {
    (0..open.len())
        .map(|s| open[s..].iter().chain(&open[..s]).copied().collect::<Vec<Xy>>())
        .min()
        .unwrap_or_default()
}

/// The part with the freedoms of the codec removed. With `directed` false a line also reads the same
/// backwards; a ring keeps its winding either way, so a flipped ring is still a difference.
fn normalized(part: &Part, directed: bool) -> Part {
    match part {
        Part::Ring(v) if v.len() > 1 => {
            let mut ring = canonical_rotation(&v[..v.len() - 1]);
            if let Some(&first) = ring.first() {
                ring.push(first);
            }
            Part::Ring(ring)
        }
        Part::Ring(v) => Part::Ring(v.clone()),
        Part::Line(v) if !directed => {
            let mut back = v.clone();
            back.reverse();
            Part::Line(v.clone().min(back))
        }
        Part::Line(v) => Part::Line(v.clone()),
        Part::Points(v) => {
            let mut sorted = v.clone();
            sorted.sort_unstable();
            Part::Points(sorted)
        }
    }
}

fn signature(parts: &[Part], directed: bool) -> Vec<Part> {
    let mut all: Vec<Part> = parts.iter().map(|p| normalized(p, directed)).collect();
    all.sort();
    all
}

fn classify(
    reference: &mlt_core::geo_types::Geometry<i32>,
    got: &mlt_core::geo_types::Geometry<i32>,
    stats: &mut GeomStats,
) {
    let (mut a, mut b) = (Vec::new(), Vec::new());
    parts_of(reference, &mut a);
    parts_of(got, &mut b);
    stats.features += 1;
    if a == b {
        stats.identical += 1;
    } else if signature(&a, false) != signature(&b, false) {
        stats.different += 1;
    } else if signature(&a, true) == signature(&b, true) {
        stats.reordered += 1;
    } else {
        stats.reversed += 1;
    }
}

/// One layer of a written tile, for verification: the layer as read from the source, and the composition
/// that wrote its vertex stream (`None`: the stock layout, which is not checked).
pub struct VerifyLayer<'a> {
    pub source: &'a TileLayer,
    pub composition: Option<Composition>,
}

fn verify_tile(
    trials: &[LayerTrial],
    grid: &[Composition],
    chosen: &[usize],
    base: EncoderConfig,
    geometry: &mut Vec<(String, GeomStats)>,
) -> Result<(), String> {
    let tile: Vec<u8> = concat(trials, chosen, None).flat_map(|b| b.iter().copied()).collect();
    let layers: Vec<VerifyLayer> = trials
        .iter()
        .zip(chosen)
        .map(|(t, &c)| VerifyLayer { source: &t.source, composition: t.pipelined.then(|| grid[c]) })
        .collect();
    verify_layers(&tile, &layers, base, geometry)
}

/// The `tile` decodes into `layers`: ids and properties as the stock encoding of the same feature order, and
/// the vertices of every pipelined layer as the codec's emitted buffer. Every feature's geometry is compared
/// with the stock decode of the same layer; the vertex pass (ring rotation, MultiPoint sort) and line reversal
/// may reorder it but never change the shape.
///
/// # Errors
/// On the first layer that does not decode as expected.
pub fn verify_layers(
    tile: &[u8],
    layers: &[VerifyLayer],
    base: EncoderConfig,
    geometry: &mut Vec<(String, GeomStats)>,
) -> Result<(), String> {
    let decoded = decode_layers(tile)?;
    if decoded.len() != layers.len() {
        return Err(format!("{} layers decoded, {} written", decoded.len(), layers.len()));
    }
    for (verify, layer) in layers.iter().zip(&decoded) {
        let Some(composition) = verify.composition else { continue };
        let source = verify.source;
        let name = source.name();
        let buffer = source.vertex_buffer().map_err(|e| e.to_string())?;
        let order = composition.reorder.feature_order(&buffer);
        let permuted = order.map_or_else(|| source.clone(), |o| source.permuted(&o));
        let expected = mlt_pipeline::encode(&permuted.vertex_buffer().map_err(|e| e.to_string())?, &composition).emitted;
        let got = layer.vertex_buffer().map_err(|e| format!("layer {name}: {e}"))?;
        if got != expected {
            return Err(format!("layer {name} ({}): decoded vertices differ from the codec's", composition.name()));
        }
        let stock = encode_layer(&permuted, base.with_spatial_hilbert_sort(false)).ok_or("stock re-encode failed")?;
        let reference = decode_layers(&stock)?;
        let Some(reference) = reference.first() else { return Err(format!("layer {name}: no stock layer")) };
        if reference.feature_count() != layer.feature_count() || reference.name() != layer.name() {
            return Err(format!("layer {name}: feature count or name differs"));
        }
        let mut layer_stats = GeomStats::default();
        for (i, (a, b)) in reference.features().iter().zip(layer.features()).enumerate() {
            if a.id() != b.id() || a.properties() != b.properties() {
                return Err(format!("layer {name} ({}): feature {i} id or properties differ", composition.name()));
            }
            classify(a.geometry(), b.geometry(), &mut layer_stats);
        }
        if layer_stats.different > 0 {
            return Err(format!(
                "layer {name} ({}): {} features hold other vertices than the stock decode",
                composition.name(),
                layer_stats.different
            ));
        }
        geometry.push((name.to_owned(), layer_stats));
    }
    Ok(())
}
