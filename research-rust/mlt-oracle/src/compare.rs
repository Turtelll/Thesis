//! `compare`: whole-tile MLT v2 bytes under gzip, the stock encoder against the tile oracle.
//!
//! Per tile of an `.mbtiles` tileset (MVT, converted layer by layer to MLT v2 and concatenated):
//!
//! * **stock**: `mlt-core` at the pinned commit, wire version 2, default profile, tessellation off and
//!   the `mlt-convert --mlt-version 2` defaults for the v2 codecs (ALP, float dictionaries, packed codes);
//! * **stock + sort**: the same with the encoder's own feature-order trials (Hilbert, feature ID);
//! * **per-layer default**: every vertex stream written by the pipeline codec with `for_layer(layer)`;
//! * **alone-best**: per layer the composition whose layer bytes gzip smallest alone;
//! * **tile oracle, raw-length stream selection**: `TileSelector.oracle`, coordinate descent over the gzip size of
//!   the whole tile across all 96 compositions of `bestGrid()`, from the smaller of the default and the alone-best
//!   tile, with the other streams (ids, geometry lengths, properties) encoded as stock picks them: raw byte length;
//! * **stock + gzip stream selection**: stock with every stream-encoding competition decided by gzip size
//!   (`gzip_config`);
//! * **tile oracle**: the same descent with every candidate on `gzip_config`, what `mlt convert --vertex-pipeline
//!   oracle` writes. The per-zoom and per-layer tables, the picks, `--write-tiles` and the csv `oracle_*` columns are
//!   this row.
//!
//! A composition's reorder permutes whole layers (id and every property column follow), so each of the
//! 96 candidates is a complete layer encoding. The pipeline codec is the vertex stream of the patched
//! `mlt-core` copy (`research-rust/mlt-core-pipeline`).

use flate2::read::GzDecoder;
use mlt_core::TileLayer;
use mlt_core::mvt::mvt_to_tile_layers;
use mlt_pipeline::registry::best_grid;
use mlt_pipeline::{Composition, Reorder};
use mlt_tile_oracle::{
    GeomStats, LayerTrial, ORACLE_MAX_PASSES, alone_sizes, concat, fixed_choice, gzip_config, gzip_len, raw_len, select,
    stock_config, trial_layer, verify,
};
use rayon::prelude::*;
use std::collections::BTreeMap;
use std::io::Read;
use std::path::PathBuf;

const USAGE: &str = "compare --mbtiles <file> [--csv <file>] [--by-zoom] [--by-layer] [--layer <name>] [--limit-tiles <n>] [--no-verify] [--no-stock-sorted] [--max-passes <n>] [--write-tiles <dir>] [--verify-all-compositions]\n\
  Whole-tile MLT v2 under gzip (zlib level 6): stock vs the pipeline codec as the vertex encoder.\n\
  --write-tiles writes <z>_<x>_<y>.stock.mlt and .oracle.mlt, so the sizes can be checked with other tools\n\
  (`gzip -6 -c file | wc -c` is the compressed size compare reports).\n\
  --verify-all-compositions round-trips every one of the 96 grid compositions on every pipelined\n\
  layer (not just the oracle's chosen one), through the real tile container.";

struct Options {
    mbtiles: PathBuf,
    csv: Option<PathBuf>,
    by_zoom: bool,
    by_layer: bool,
    layer: Option<String>,
    limit: usize,
    verify: bool,
    verify_all: bool,
    stock_sorted: bool,
    max_passes: usize,
    write_tiles: Option<PathBuf>,
}

fn parse(args: &[String]) -> Result<Option<Options>, String> {
    let mut o = Options {
        mbtiles: PathBuf::new(),
        csv: None,
        by_zoom: false,
        by_layer: false,
        layer: None,
        limit: usize::MAX,
        verify: true,
        verify_all: false,
        stock_sorted: true,
        max_passes: ORACLE_MAX_PASSES,
        write_tiles: None,
    };
    let mut have = false;
    let mut it = args.iter();
    while let Some(arg) = it.next() {
        let mut value = |name: &str| it.next().cloned().ok_or_else(|| format!("{name} needs a value\n{USAGE}"));
        match arg.as_str() {
            "--mbtiles" => {
                o.mbtiles = PathBuf::from(value("--mbtiles")?);
                have = true;
            }
            "--csv" => o.csv = Some(PathBuf::from(value("--csv")?)),
            "--by-zoom" => o.by_zoom = true,
            "--by-layer" => o.by_layer = true,
            "--layer" => o.layer = Some(value("--layer")?),
            "--limit-tiles" => o.limit = value("--limit-tiles")?.parse().map_err(|e| format!("--limit-tiles: {e}"))?,
            "--max-passes" => o.max_passes = value("--max-passes")?.parse().map_err(|e| format!("--max-passes: {e}"))?,
            "--write-tiles" => {
                let dir = PathBuf::from(value("--write-tiles")?);
                std::fs::create_dir_all(&dir).map_err(|e| format!("{}: {e}", dir.display()))?;
                o.write_tiles = Some(dir);
            }
            "--no-verify" => o.verify = false,
            "--verify-all-compositions" => o.verify_all = true,
            "--no-stock-sorted" => o.stock_sorted = false,
            "--help" | "-h" => {
                println!("{USAGE}");
                return Ok(None);
            }
            other => return Err(format!("unknown argument {other}\n{USAGE}")),
        }
    }
    if !have {
        return Err(format!("--mbtiles is required\n{USAGE}"));
    }
    Ok(Some(o))
}

// --- sizes --------------------------------------------------------------------------------------

// --- the tileset --------------------------------------------------------------------------------

struct TileResult {
    z: u32,
    x: u32,
    y: u32,
    layers: Vec<LayerResult>,
    stock_raw: usize,
    stock_gzip: usize,
    sorted: Option<(usize, usize)>,
    default_raw: usize,
    default_gzip: usize,
    alone_raw: usize,
    alone_gzip: usize,
    oracle_raw: usize,
    oracle_gzip: usize,
    /// The oracle with the other streams on the stock raw-length selection.
    rawsel_raw: usize,
    rawsel_gzip: usize,
    /// Stock with gzip stream selection.
    stock_gs_raw: usize,
    stock_gs_gzip: usize,
    /// Every layer on plain delta + split varint through the codec: the Java `DELTA_ZIGZAG_VARINT` reference.
    plain_raw: usize,
    plain_gzip: usize,
    /// The oracle restricted to compositions without a feature reorder: the vertex codec alone.
    noreorder_raw: usize,
    noreorder_gzip: usize,
    verified: Result<(), String>,
    geometry: Vec<(String, GeomStats)>,
    /// With `--verify-all-compositions`: every (composition, error) that failed round trip on this tile.
    all_compositions_failed: Vec<(String, String)>,
}

struct LayerResult {
    name: String,
    stock_gzip: usize,
    default_gzip: usize,
    oracle_gzip: usize,
    choice: Option<Composition>,
    pipelined: bool,
}

fn process(z: u32, x: u32, y: u32, data: &[u8], o: &Options, grid: &[Composition]) -> Result<Option<TileResult>, String> {
    let mvt = if data.starts_with(&[0x1f, 0x8b]) {
        let mut out = Vec::new();
        GzDecoder::new(data).read_to_end(&mut out).map_err(|e| format!("gunzip {z}/{x}/{y}: {e}"))?;
        out
    } else {
        data.to_vec()
    };
    let layers = mvt_to_tile_layers(&mvt).map_err(|e| format!("{z}/{x}/{y}: {e}"))?;
    let layers: Vec<TileLayer> = layers.into_iter().filter(|l| o.layer.as_deref().is_none_or(|n| n == l.name())).collect();
    if layers.is_empty() {
        return Ok(None);
    }
    let trials_gs: Vec<LayerTrial> = layers
        .iter()
        .map(|l| trial_layer(l.clone(), grid, false, gzip_config(stock_config())).map_err(|e| format!("{z}/{x}/{y}: {e}")))
        .collect::<Result<_, _>>()?;
    let trials: Vec<LayerTrial> = layers
        .into_iter()
        .map(|l| trial_layer(l, grid, o.stock_sorted, stock_config()).map_err(|e| format!("{z}/{x}/{y}: {e}")))
        .collect::<Result<_, _>>()?;

    let stock_gzip = gzip_len(trials.iter().map(|t| t.stock.as_slice()));
    let stock_raw = trials.iter().map(|t| t.stock.len()).sum();
    let sorted = o.stock_sorted.then(|| {
        let bytes = |t: &LayerTrial| t.stock_sorted.clone().unwrap_or_else(|| t.stock.clone());
        let all: Vec<Vec<u8>> = trials.iter().map(bytes).collect();
        (all.iter().map(Vec::len).sum(), gzip_len(all.iter().map(Vec::as_slice)))
    });

    let alone = alone_sizes(&trials);
    let rawsel = select(&trials, grid, &alone, &|_| true, o.max_passes);
    let sel = select(&trials_gs, grid, &alone_sizes(&trials_gs), &|_| true, o.max_passes);
    let no_reorder = select(&trials, grid, &alone, &|c| c.reorder == Reorder::None, o.max_passes);
    let plain_composition = Composition {
        reorder: Reorder::None,
        predictor: mlt_pipeline::Prediction::Delta,
        segmentation: mlt_pipeline::Segmentation::Flat,
        terminal: mlt_pipeline::Terminal::VarintSplit,
    };
    let plain = fixed_choice(&trials, grid, plain_composition);
    let (verified, geometry) = if o.verify {
        let (rawsel_verified, _) = verify(&trials, grid, &rawsel.chosen, stock_config());
        let (verified, geometry) = verify(&trials_gs, grid, &sel.chosen, stock_config());
        (rawsel_verified.and(verified), geometry)
    } else {
        (Ok(()), Vec::new())
    };

    let all_compositions_failed = if o.verify_all {
        grid.iter()
            .filter_map(|&composition| {
                let chosen = fixed_choice(&trials, grid, composition);
                let (outcome, _) = verify(&trials, grid, &chosen, stock_config());
                outcome.err().map(|e| (composition.name(), e))
            })
            .collect()
    } else {
        Vec::new()
    };

    if let Some(dir) = &o.write_tiles {
        let name = |kind: &str| dir.join(format!("{z}_{x}_{y}.{kind}.mlt"));
        let stock_tile: Vec<u8> = trials.iter().flat_map(|t| t.stock.iter().copied()).collect();
        let oracle_tile: Vec<u8> = concat(&trials_gs, &sel.chosen, None).flat_map(|b| b.iter().copied()).collect();
        std::fs::write(name("stock"), stock_tile).map_err(|e| e.to_string())?;
        std::fs::write(name("oracle"), oracle_tile).map_err(|e| e.to_string())?;
    }

    let layers = trials
        .iter()
        .zip(&trials_gs)
        .enumerate()
        .map(|(l, (t, t_gs))| {
            let default = t.candidates[rawsel.tile_default[l]].as_deref().expect("start is allowed");
            let oracle = t_gs.candidates[sel.chosen[l]].as_deref().expect("chosen is allowed");
            LayerResult {
                name: t.name.clone(),
                stock_gzip: gzip_len([t.stock.as_slice()]),
                default_gzip: gzip_len([default]),
                oracle_gzip: gzip_len([oracle]),
                choice: t_gs.pipelined.then(|| grid[sel.chosen[l]]),
                pipelined: t_gs.pipelined,
            }
        })
        .collect();
    Ok(Some(TileResult {
        z,
        x,
        y,
        layers,
        stock_raw,
        stock_gzip,
        sorted,
        default_raw: raw_len(&trials, &rawsel.tile_default),
        default_gzip: rawsel.default_gzip,
        alone_raw: raw_len(&trials, &rawsel.alone),
        alone_gzip: rawsel.alone_gzip,
        oracle_raw: raw_len(&trials_gs, &sel.chosen),
        oracle_gzip: sel.oracle_gzip,
        rawsel_raw: raw_len(&trials, &rawsel.chosen),
        rawsel_gzip: rawsel.oracle_gzip,
        stock_gs_raw: trials_gs.iter().map(|t| t.stock.len()).sum(),
        stock_gs_gzip: gzip_len(trials_gs.iter().map(|t| t.stock.as_slice())),
        plain_raw: raw_len(&trials, &plain),
        plain_gzip: gzip_len(concat(&trials, &plain, None)),
        noreorder_raw: raw_len(&trials, &no_reorder.chosen),
        noreorder_gzip: no_reorder.oracle_gzip,
        verified,
        geometry,
        all_compositions_failed,
    }))
}

fn read_tiles(o: &Options) -> Result<Vec<(u32, u32, u32, Vec<u8>)>, String> {
    let db = rusqlite::Connection::open_with_flags(&o.mbtiles, rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY)
        .map_err(|e| format!("{}: {e}", o.mbtiles.display()))?;
    let mut statement = db
        .prepare("SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles ORDER BY zoom_level, tile_column, tile_row")
        .map_err(|e| e.to_string())?;
    let rows = statement
        .query_map([], |r| {
            let z: u32 = r.get(0)?;
            let x: u32 = r.get(1)?;
            let tms: u32 = r.get(2)?;
            let data: Vec<u8> = r.get(3)?;
            Ok((z, x, (1u32 << z) - 1 - tms, data))
        })
        .map_err(|e| e.to_string())?;
    rows.take(o.limit).collect::<Result<_, _>>().map_err(|e| e.to_string())
}

// --- reports ------------------------------------------------------------------------------------

fn pct(value: usize, base: usize) -> String {
    if base == 0 {
        return "n/a".into();
    }
    format!("{:+.2}%", (value as f64 / base as f64 - 1.0) * 100.0)
}

#[derive(Default, Clone, Copy)]
struct Sum {
    tiles: usize,
    stock_raw: usize,
    stock_gzip: usize,
    default_raw: usize,
    default_gzip: usize,
    alone_gzip: usize,
    oracle_raw: usize,
    oracle_gzip: usize,
    plain_raw: usize,
    plain_gzip: usize,
    noreorder_raw: usize,
    noreorder_gzip: usize,
    rawsel_raw: usize,
    rawsel_gzip: usize,
    stock_gs_raw: usize,
    stock_gs_gzip: usize,
}

impl Sum {
    fn add(&mut self, t: &TileResult) {
        self.tiles += 1;
        self.stock_raw += t.stock_raw;
        self.stock_gzip += t.stock_gzip;
        self.default_raw += t.default_raw;
        self.default_gzip += t.default_gzip;
        self.alone_gzip += t.alone_gzip;
        self.oracle_raw += t.oracle_raw;
        self.oracle_gzip += t.oracle_gzip;
        self.plain_raw += t.plain_raw;
        self.plain_gzip += t.plain_gzip;
        self.noreorder_raw += t.noreorder_raw;
        self.noreorder_gzip += t.noreorder_gzip;
        self.rawsel_raw += t.rawsel_raw;
        self.rawsel_gzip += t.rawsel_gzip;
        self.stock_gs_raw += t.stock_gs_raw;
        self.stock_gs_gzip += t.stock_gs_gzip;
    }
}

fn print_header(first: &str) {
    println!(
        "{first:<26} {:>7} {:>12} {:>12} {:>12} {:>12} {:>12} {:>9} {:>9} {:>9}",
        "tiles", "stock gz", "default gz", "alone gz", "oracle gz", "stock raw", "default", "alone", "oracle"
    );
}

fn print_row(label: &str, s: &Sum) {
    println!(
        "{label:<26} {:>7} {:>12} {:>12} {:>12} {:>12} {:>12} {:>9} {:>9} {:>9}",
        s.tiles,
        s.stock_gzip,
        s.default_gzip,
        s.alone_gzip,
        s.oracle_gzip,
        s.stock_raw,
        pct(s.default_gzip, s.stock_gzip),
        pct(s.alone_gzip, s.stock_gzip),
        pct(s.oracle_gzip, s.stock_gzip)
    );
}

pub fn run(args: &[String]) -> Result<(), String> {
    let Some(o) = parse(args)? else { return Ok(()) };
    let grid = best_grid();
    let tiles = read_tiles(&o)?;
    println!("{}: {} tiles", o.mbtiles.display(), tiles.len());

    let results: Vec<Result<Option<TileResult>, String>> =
        tiles.par_iter().map(|(z, x, y, data)| process(*z, *x, *y, data, &o, &grid)).collect();
    let mut ok: Vec<TileResult> = Vec::new();
    let mut errors: Vec<String> = Vec::new();
    for r in results {
        match r {
            Ok(Some(t)) => ok.push(t),
            Ok(None) => {}
            Err(e) => errors.push(e),
        }
    }
    println!("{} tiles compared, {} failed to encode", ok.len(), errors.len());
    for e in errors.iter().take(5) {
        println!("  {e}");
    }

    let mut total = Sum::default();
    let mut sorted = (0usize, 0usize);
    for t in &ok {
        total.add(t);
        if let Some((raw, gz)) = t.sorted {
            sorted.0 += raw;
            sorted.1 += gz;
        }
    }
    println!("\nWHOLE-TILE MLT v2, gzip (zlib level 6), summed over {} tiles", total.tiles);
    println!("{:<48} {:>14} {:>9} {:>14} {:>9}", "row", "gzip bytes", "vs stock", "raw bytes", "vs stock");
    let rows = [
        ("stock v2 (default profile, no tessellation)", total.stock_gzip, total.stock_raw),
        ("stock v2 + own sort trials (hilbert, id)", sorted.1, sorted.0),
        ("stock v2 + gzip stream selection", total.stock_gs_gzip, total.stock_gs_raw),
        ("pipeline, plain delta + split varint", total.plain_gzip, total.plain_raw),
        ("pipeline, per-layer default (forLayer)", total.default_gzip, total.default_raw),
        ("pipeline, per-layer alone-best", total.alone_gzip, 0),
        ("pipeline, tile oracle, no feature reorder (32)", total.noreorder_gzip, total.noreorder_raw),
        ("pipeline, tile oracle (96), raw-length streams", total.rawsel_gzip, total.rawsel_raw),
        ("pipeline, tile oracle (96) + gzip streams", total.oracle_gzip, total.oracle_raw),
    ];
    for (label, gzip, raw) in rows {
        if gzip == 0 {
            continue;
        }
        let raw_text = if raw == 0 { "-".into() } else { raw.to_string() };
        let raw_pct = if raw == 0 { "-".into() } else { pct(raw, total.stock_raw) };
        println!("{label:<48} {gzip:>14} {:>9} {raw_text:>14} {raw_pct:>9}", pct(gzip, total.stock_gzip));
    }
    println!(
        "steps: stock -> plain delta {}, plain delta -> no-reorder oracle {}, no-reorder oracle -> full oracle {}, full oracle -> + gzip streams {}",
        pct(total.plain_gzip, total.stock_gzip),
        pct(total.noreorder_gzip, total.plain_gzip),
        pct(total.rawsel_gzip, total.noreorder_gzip),
        pct(total.oracle_gzip, total.rawsel_gzip)
    );

    let failed: Vec<&TileResult> = ok.iter().filter(|t| t.verified.is_err()).collect();
    if o.verify {
        println!("\nverification: {} of {} tiles decode as the codec emitted them", ok.len() - failed.len(), ok.len());
        for t in failed.iter().take(5) {
            println!("  {}/{}/{}: {}", t.z, t.x, t.y, t.verified.as_ref().unwrap_err());
        }
        print_geometry(&ok);
    }

    if o.verify_all {
        let total_failed: usize = ok.iter().map(|t| t.all_compositions_failed.len()).sum();
        let grid_len = grid.len();
        println!(
            "\nverify-all-compositions: {} of {} (tile, composition) round trips failed ({} tiles x {} compositions)",
            total_failed,
            ok.len() * grid_len,
            ok.len(),
            grid_len
        );
        for t in &ok {
            for (name, err) in t.all_compositions_failed.iter().take(5) {
                println!("  {}/{}/{} [{name}]: {err}", t.z, t.x, t.y);
            }
        }
    }

    if o.by_zoom {
        println!("\nBY ZOOM");
        print_header("zoom");
        let mut zooms: BTreeMap<u32, Sum> = BTreeMap::new();
        for t in &ok {
            zooms.entry(t.z).or_default().add(t);
        }
        for (z, s) in &zooms {
            print_row(&format!("z{z}"), s);
        }
    }

    if o.by_layer {
        println!("\nBY LAYER (each layer compressed on its own, summed over tiles)");
        println!(
            "{:<22} {:>7} {:>12} {:>12} {:>12} {:>9} {:>9}",
            "layer", "layers", "stock gz", "default gz", "oracle gz", "default", "oracle"
        );
        let mut by_layer: BTreeMap<&str, (usize, usize, usize, usize)> = BTreeMap::new();
        for t in &ok {
            for l in &t.layers {
                let e = by_layer.entry(l.name.as_str()).or_default();
                e.0 += 1;
                e.1 += l.stock_gzip;
                e.2 += l.default_gzip;
                e.3 += l.oracle_gzip;
            }
        }
        let mut sum = (0, 0, 0, 0);
        for (name, e) in &by_layer {
            println!(
                "{name:<22} {:>7} {:>12} {:>12} {:>12} {:>9} {:>9}",
                e.0, e.1, e.2, e.3, pct(e.2, e.1), pct(e.3, e.1)
            );
            sum = (sum.0 + e.0, sum.1 + e.1, sum.2 + e.2, sum.3 + e.3);
        }
        println!(
            "{:<22} {:>7} {:>12} {:>12} {:>12} {:>9} {:>9}",
            "ALL LAYERS", sum.0, sum.1, sum.2, sum.3, pct(sum.2, sum.1), pct(sum.3, sum.1)
        );
    }

    print_picks(&ok);

    if let Some(path) = &o.csv {
        let mut out = String::from(
            "z,x,y,layers,stock_raw,stock_gzip,stock_sorted_raw,stock_sorted_gzip,default_raw,default_gzip,alone_raw,alone_gzip,oracle_raw,oracle_gzip,plain_raw,plain_gzip,noreorder_raw,noreorder_gzip,verified,oracle_rawsel_raw,oracle_rawsel_gzip,stock_gzsel_raw,stock_gzsel_gzip\n",
        );
        for t in &ok {
            let (sr, sg) = t.sorted.unzip();
            out.push_str(&format!(
                "{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{}\n",
                t.z,
                t.x,
                t.y,
                t.layers.len(),
                t.stock_raw,
                t.stock_gzip,
                sr.map_or(String::new(), |v| v.to_string()),
                sg.map_or(String::new(), |v| v.to_string()),
                t.default_raw,
                t.default_gzip,
                t.alone_raw,
                t.alone_gzip,
                t.oracle_raw,
                t.oracle_gzip,
                t.plain_raw,
                t.plain_gzip,
                t.noreorder_raw,
                t.noreorder_gzip,
                t.verified.is_ok(),
                t.rawsel_raw,
                t.rawsel_gzip,
                t.stock_gs_raw,
                t.stock_gs_gzip
            ));
        }
        std::fs::write(path, out).map_err(|e| format!("{}: {e}", path.display()))?;
        println!("\nper-tile figures written to {}", path.display());
    }
    if !failed.is_empty() {
        return Err(format!("{} tiles failed verification", failed.len()));
    }
    Ok(())
}

/// How the chosen tiles' geometries relate to the stock decode of the same layers.
fn print_geometry(ok: &[TileResult]) {
    let mut by_layer: BTreeMap<&str, GeomStats> = BTreeMap::new();
    let mut total = GeomStats::default();
    for t in ok {
        for (name, stats) in &t.geometry {
            by_layer.entry(name.as_str()).or_default().add(stats);
            total.add(stats);
        }
    }
    if total.features == 0 {
        return;
    }
    println!("\nGEOMETRY vs the stock decode of the same layer (vertices of every feature, storage order)");
    println!(
        "{:<22} {:>10} {:>10} {:>10} {:>10} {:>10}",
        "layer", "features", "identical", "reversed", "reordered", "different"
    );
    for (name, s) in by_layer.iter().map(|(n, s)| (*n, *s)).chain(std::iter::once(("ALL LAYERS", total))) {
        println!(
            "{name:<22} {:>10} {:>10} {:>10} {:>10} {:>10}",
            s.features, s.identical, s.reversed, s.reordered, s.different
        );
    }
    println!(
        "  identical: same vertices in the same order; reordered: same shape, rings start elsewhere, MultiPoints sorted or parts moved;\n  reversed: the same shape with at least one line running the other way; different: another shape (fails verification)"
    );
}

/// What the oracle chose, per stage and the most frequent whole compositions.
fn print_picks(ok: &[TileResult]) {
    let mut picks: Vec<Composition> = Vec::new();
    let mut fallbacks = 0usize;
    for t in ok {
        for l in &t.layers {
            match l.choice {
                Some(c) => picks.push(c),
                None if !l.pipelined => fallbacks += 1,
                None => {}
            }
        }
    }
    if picks.is_empty() {
        return;
    }
    println!("\nORACLE PICKS over {} (tile, layer) buffers ({fallbacks} layers fell back to stock)", picks.len());
    let share = |title: &str, key: &dyn Fn(&Composition) -> String| {
        let mut counts: BTreeMap<String, usize> = BTreeMap::new();
        for c in &picks {
            *counts.entry(key(c)).or_default() += 1;
        }
        let mut sorted: Vec<_> = counts.into_iter().collect();
        sorted.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(&b.0)));
        println!("  {title}");
        for (name, n) in sorted.iter().take(12) {
            println!("    {:>6.1}%  {name}", *n as f64 * 100.0 / picks.len() as f64);
        }
    };
    share("reorder", &|c| format!("{:?}", c.reorder));
    share("predictor", &|c| c.predictor.name().to_string());
    share("segmentation", &|c| c.segmentation.name().to_string());
    share("terminal", &|c| c.terminal.name().to_string());
    share("composition (top 12)", &Composition::name);
}
