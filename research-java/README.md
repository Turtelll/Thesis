# research-java

A lightweight harness for testing VertexBuffer encoding ideas against the baseline.
It extracts geometries from `data/MVT/munich-de.mbtiles` into MLT-compatible
`VertexBuffer`s (exactly as `maplibre-tile-spec`'s `GeometryEncoder` would, one buffer
per MVT layer per tile), then measures every registered encoding against the baseline
encodings from the repo root `README.md` — overall, and **per layer**.

The per-layer breakdown is the point. An encoding that is much better on
`transportation` and much worse on `building` can be net-negative on the corpus and
still worth shipping on `transportation` alone; a single corpus number cannot express
that.

## Prerequisites

- JDK 21+ (the Gradle toolchain downloads one automatically if not on `PATH`)
- The `maplibre-tile-spec` submodule must be populated:
  ```
  git submodule update --init --recursive
  ```

Use this project's own wrapper from `research-java/` (`./gradlew`, or `.\gradlew.bat`
on Windows). It depends on `mlt-core` through a Gradle composite build
(`includeBuild` in `settings.gradle`), so `mlt-core` builds from source on the fly —
no `publishToMavenLocal`, and the submodule is never modified.

## Build & test

```
./gradlew test
```

## Pipeline

### 1. Extract (once)

```
./gradlew run --args="--mbtiles ../data/MVT/munich-de.mbtiles --out ../data/extracted"
```

`munich-de.mbtiles` is the Munich area cut out of the full-Germany tileset
`data/MVT/germany.mbtiles` (275.735 tiles, far too large to extract whole). `ExtractCli`
has no bbox filter, so the cut happens first, with `analysis-python/cut_aoi.py`:

```
python cut_aoi.py --src ../data/MVT/germany.mbtiles --dst ../data/MVT/munich-de.mbtiles --bbox 11.359,48.061,11.723,48.248
```

That yields 358 tiles (~20 MB) -> 4.272 buffers, 5.244.123 vertices.

#### Overture corpus (second, independent dataset)

`munich-de.mbtiles` and `NY-maptiler.mbtiles` are both OpenMapTiles-schema tilesets
built by planetiler/OpenMapTiles. An encoding tuned on them may be exploiting quirks
of that one producer. `munich-overture.mbtiles` covers the **same Munich AOI** from
Overture Maps instead — different source data, different schema, different tiler
(tippecanoe) — as a check that a win generalizes.

It is **not** a size comparator against `munich-de.mbtiles`: the layer sets, attributes
and generalization differ, so a byte difference between the two measures dataset and
tiler, not encoding. Compare encodings *within* each corpus.

Two steps, because tippecanoe is not available natively on Windows:

```
python ../data-analysis/overture_fetch.py            # -> data/overture/*.geojsonseq
wsl -e bash ../data-analysis/overture_tile.sh        # -> data/MVT/munich-overture.mbtiles
```

`overture_fetch.py` pulls the seven themes overlapping the AOI straight from Overture's
S3 parquet with DuckDB (`pip install duckdb`); `--release` pins the Overture release
(default `2026-08-19.0`). Tiling needs `sudo apt install tippecanoe` inside WSL once.
Then extract as usual:

```
./gradlew run --args="--mbtiles ../data/MVT/munich-overture.mbtiles --out ../data/extracted-overture"
```

| Flag | Required | Description |
|---|---|---|
| `--mbtiles <path>` | yes | Source `.mbtiles` |
| `--out <dir>` | yes | Output directory (created if missing) |
| `--zoom <minZ-maxZ>` | no | Zoom filter, inclusive. `--zoom 14` means minZ=maxZ=14 |
| `--layer <name[,...]>` | no | Only these MVT layers |
| `--limit <N>` | no | Stop after N (tile, layer) extractions |

Per (tile, layer) with at least one geometry, writes `<z>_<x>_<y>_<layer>.bin` (the raw
VertexBuffer as interleaved little-endian `int32`, `[x0,y0,x1,y1,...]` — undelta'd and
unencoded, the neutral starting point for any experiment) plus a `.json` sidecar with
the topology streams (`tileExtent`, `geometryTypes`, `numGeometries`, `numParts`,
`numRings`, `vertexCount`). A `manifest.json` indexes them all.

### 2. Benchmark

```
./gradlew benchmark --args="../data/extracted"
```

| Flag | Required | Description |
|---|---|---|
| `<extractionDir>` | yes | Must contain `manifest.json` |
| `--csv <file>` | no | Also dump per-buffer sizes (one column per encoding) |
| `--layer <name>` | no | Restrict the run to one layer |
| `--reorder <mode>` | no | Reorder geometries before encoding: `none` (default), `feature`, `feature-rev` |

Prints corpus totals, then a breakdown by layer and by dominant geometry type. Each
encoding is shown as a percentage against the **best baseline for that group**, so a
new encoding's effect is readable per segment:

```
encoding                       bytes       vs raw      vs base
DELTA_ZIGZAG_FASTPFOR     13.399.028        30,7%         base
DELTA_ZIGZAG_VARINT       13.818.767        31,7%        +3,1%

--- by layer ---
layer                buffers  DELTA_ZIGZAG_FASTPFOR    DELTA_ZIGZAG_VARINT
water                   1478       916.048     base       951.783    +3,9%
water_name               762        17.065   +26,4%        13.506     base
transportation           634     4.232.495     base     4.288.158    +1,3%
housenumber              244       735.180     base       849.969   +15,6%
```

Negative means smaller, which is the goal.

With `--reorder`, every buffer is additionally checked for geometry preservation before it is
encoded: reordering may change the order and nothing else, so the multiset of parts must be
unchanged (parts compared up to reversal). The run fails naming the tile and layer otherwise.

Every run **round-trips every encoding on every buffer** — encode, decode, compare
against the extraction — and fails naming the encoding, tile and layer if any does not
reconstruct the buffer exactly. This is not optional and has no flag: an encoding that
discards data reports a small size and would look like the best result in the table
*because* it is broken, so a size from an unverified encoding is not a result.

## Adding an encoding

Everything the harness measures is one kind of thing:
`org.maplibre.mlt.research.encoding.Encoding` — `name()`, `encode(BufferContext)`,
`decode(EncodedBuffer, BufferContext, vertexCount)`. `decode` is mandatory, for the
reason above. Both halves get the `BufferContext`, so a transform that resets on
part/ring boundaries derives the same runs when encoding and decoding. `decode` must
not read `ctx.vertices()` — that is the buffer it is reconstructing, and reading it
would make even a codec that discards the buffer round-trip successfully.

1. **Write it.** Most ideas decompose into a logical transform (`List<Vertex> <-> int[]`)
   plus a physical codec (`int[] <-> byte[]`): implement a `VertexTransform` and/or an
   `IntCodec` and pair them in a `VertexBufferCodec`, reusing
   `VertexTransform.ZigZagDelta` / `IntCodec.Varint` / `IntCodec.FastPfor` for whichever
   half you are not changing. Anything that does not fit that split implements
   `Encoding` directly.

   `BufferContext` carries the topology streams (`numParts`, `numRings`,
   `geometryTypes`) alongside the vertices, for encodings with structural reset points
   (the predictors in `ideen.md`) to condition on.

2. **Register it** — one line in `Baselines.research()`.

3. **Run the benchmark.** Your encoding appears as a new column in every table, and is
   round-tripped over the whole corpus automatically. `VertexBufferCodecTest`
   parameterizes over `Baselines.all()`, so `./gradlew test` covers it too without a
   new test method.

With `research()` empty the run reports the two baselines only — the null result that
shows the harness works before any new encoding is judged by it. `research()` currently
holds `SEGMENT_PAIR_FASTPFOR` and `SEGMENT_QUAD_CORNER_FASTPFOR`; see **Results** for what
they do and for the encodings that have been measured and rejected.

## Results

> **The figures below were measured on the previous corpus** — the Munich extract of
> OpenMapTiles v3.11 (`munich.mbtiles`, planet time 2020-02-10), 8.366 buffers, which
> also carried ~1.355 low-zoom **world mask** tiles (that tileset set `maskLevel=5`, so
> z0-z5 held the whole world, not Munich) and took 63% of its vertex mass from them.
>
> The corpus is now the Munich AOI cut from `germany.mbtiles` (planetiler 0.9.1,
> 2025-07-08): 358 tiles, 4.272 buffers, AOI-only at every zoom. Different vendor,
> 5 years newer, no world mask tiles — so **percentages here are not comparable to a
> current run** and should be re-measured before being cited.


### Reordering geometries — `--reorder feature-rev`, **-11,2% NY / -11,8% Munich**

Not an encoding but a **pre-pass on the buffer**, and currently the largest single effect
measured here. It reorders whole features so each one starts near where the previous ended,
shrinking the part-start residual that a continuous delta cursor pays at every boundary.

```
                                   plain baseline   reorder   reorder+rev
DELTA_ZIGZAG_FASTPFOR                  13.399.028  12.036.928  11.821.207  -11,8%
SEGMENT_PAIR_FASTPFOR                  12.795.818  11.807.430  11.497.757  -14,2%
SEGMENT_QUAD_CORNER_FASTPFOR           12.579.088  11.595.318  11.285.948  -15,8%
```

| segment | baseline | reorder | +reversal |
|---|---|---|---|
| `transportation` | 4.232.495 | -15,9% | **-17,6%** |
| `boundary` | 1.169.821 | -19,0% | **-22,3%** |
| `building` | 3.228.047 | -11,1% | **-13,5%** |
| `landuse` | 492.037 | -4,4% | -5,2% |
| `landcover` | 1.194.048 | -3,1% | -3,9% |
| `water` | 916.048 | -0,6% | -0,7% |
| LINESTRING | 5.992.732 | -15,7% | **-17,7%** |
| POLYGON | 5.713.018 | -7,4% | -9,0% |
| POINT / `housenumber` / `poi` | — | byte-identical | byte-identical |

**Confirmed on the NY corpus.** 35.641 buffers, identical row sets across all three modes:

```
                                       none      feature   feature-rev
DELTA_ZIGZAG_FASTPFOR            58.403.996   52.910.127   51.845.247   -11,2%
SEGMENT_PAIR_FASTPFOR            55.686.995   51.844.248   50.957.202   -12,8%
SEGMENT_QUAD_CORNER_FASTPFOR     54.695.817   50.841.123   49.960.832   -14,5%
```

| NY segment | baseline | feature-rev |
|---|---|---|
| `boundary` | 1.471.037 | **-18,6%** |
| `transportation` | 16.536.440 | **-15,9%** |
| `building` | 23.160.287 | -13,5% |
| `transportation_name` | 4.011.771 | -9,3% |
| `landuse` | 1.603.241 | -3,7% |
| `landcover` | 3.713.513 | -2,1% |
| `water` | 2.062.937 | -0,8% |
| `housenumber` (point-only) | 3.513.170 | byte-identical |
| LINESTRING | 22.459.493 | **-14,6%** |
| POLYGON | 30.368.015 | -10,8% |
| POINT | 5.253.329 | byte-identical |
| **z14 alone** (73,4% of mass) | 41.649.791 | **-10,4%** |

**Why it is free.** The earlier junction work rejected reordering because an explicit
permutation costs ceil(log2 P)+1 bits per part — "+32,4% of baseline ... for a net +22,2%"
(see *Junction back-references* below). **That cost does not exist.** The order of geometries in
the buffer carries no information that has to be reconstructed: no coordinate changes, only a
geometry's position *within* the buffer, and the spec says so directly
(`maplibre-tile-spec/docs/specification.md`):

> **Feature order is not necessarily a stable round-trip property.** [...] MLT encoders may
> preserve order, but they may also **sort features by id or spatial locality to improve
> compression** when that optimization is enabled.

So nothing is signalled and no decoder changes — a decoder emits the order it reads. This is the
"reordering can only pay if the permutation is *derived* rather than stored" constraint being
satisfied in the strongest way available: the permutation is not derived either, it is simply
not needed.

**What it does.** `GeometryReorder` greedily chains features: repeatedly take the unused feature
whose entry endpoint is nearest the current cursor (Manhattan distance — that is what the two
componentwise delta cursors actually pay), emit it, advance the cursor to its exit endpoint.
`feature-rev` additionally emits a feature backwards when its far endpoint is nearer.

Two constraints it respects: a multi-part feature's parts stay contiguous and in order (splitting
them changes the feature), and a buffer whose runs are not derivable from the topology streams is
returned untouched — which is why every point-only column above is byte-identical rather than
merely unchanged in aggregate.

**It is not additive with `SEGMENT_PAIR`, but still worth combining.** Both levers attack the same
part-start population — one shrinks those residuals, the other moves them into their own FastPFOR
region:

| | corpus |
|---|---|
| `SEGMENT_PAIR` alone | -4,50% |
| reorder alone | -11,78% |
| both | -14,19% |
| *(if they were additive)* | *(-16,28%)* |

The overlap is 2,09pp. Combining still beats either alone, and the best measured configuration on
this corpus is reorder + `SEGMENT_QUAD_CORNER_FASTPFOR` at **-15,8%**.

**Reversal is the riskier half.** It is free for rendering — a LineString reversed is the same
LineString — but it destroys direction semantics (one-way streets, direction-dependent labelling).
It is a separate mode (`--reorder feature` vs `feature-rev`) so the two are always reported apart;
it is worth roughly 2pp of the total.

**Two caveats that bound this result.**

1. **The property columns are not measured.** It is the *feature table* that is sorted, not the
   vertex buffer alone — attributes must travel with their feature, which is what
   `MltConverter.sortFeaturesAndEncodeGeometryColumn` already does for the existing sorts. A
   spatial sort can make the attribute columns compress *worse* (weaker RLE runs), and the spec
   notes sorting by `id` tested more efficient in their case. **-11,8% on the geometry is not a
   net win on the tile** until that counter-cost is measured. This is the next measurement.
2. **~~This corpus overstates boundary-dominated levers.~~ Measured on both corpora — it does
   not.** The concern was that Munich carries 63% of its vertex mass on z4/z5 overview tiles, where
   part boundaries are a large share of the bits, and that NY (43,7% on z14, with boundary deltas
   only 25,7% of bits against Munich's 37,6%) would therefore be much weaker. It is not:

   | | Munich (8.366 buffers) | NY (35.641 buffers) |
   |---|---|---|
   | corpus | -11,8% | **-11,2%** |
   | z14 only | -10,8% | **-10,4%** (73,4% of NY's mass) |
   | + `SEGMENT_QUAD_CORNER` | -15,8% | **-14,5%** |

   The lever is not the one `SEGMENT_PAIR` depends on. `SEGMENT_PAIR` exploits the *width gap*
   between boundary and interior deltas, which does shrink with zoom; reordering shrinks the
   boundary residuals themselves, and a road network at z14 has just as many features needing to be
   visited in a sensible order as one at z5.

Verified on every buffer: reordering may change the order and nothing else, so
`GeometryEquivalence` checks that the multiset of parts is unchanged — same coordinates, same part
structure, parts compared up to reversal — and the run fails naming the tile and layer otherwise.
Every encoding is still round-tripped exactly against the reordered buffer, so the strict contract
that protects every other result is untouched.

### Composing both — `SEGMENT_QUAD_CORNER_FASTPFOR`, **-6,1% corpus / -12,2% building**

Registered, and now the single best encoding on the whole corpus. It is the composition of
the two transforms below — `SEGMENT_PAIR`'s part-start grouping and `QUAD_CORNER`'s
parallelogram-corner predictor — which the per-geometry tables show win on *disjoint*
populations (SEGMENT_PAIR on LINESTRING, QUAD_CORNER on POLYGON). Because they touch
different residuals, they stack rather than compete.

```
DELTA_ZIGZAG_FASTPFOR            13.399.028   ref
DELTA_ZIGZAG_VARINT              13.818.767  +3,1%
QUAD_CORNER_FASTPFOR             13.181.440  -1,6%
SEGMENT_PAIR_FASTPFOR            12.795.818  -4,5%
SEGMENT_QUAD_CORNER_FASTPFOR     12.579.088  -6,1%  *
```

**What it does.** Three residual populations instead of two, each packed as its own
contiguous FastPFOR region so it is sized to its own width: part starts (delta vs previous
vertex — the wide boundary spikes), interior vertices (delta vs previous vertex — the narrow
population), and fourth corners of four-vertex rings (predicted `p0+p2-p1`). The three classes
are mutually exclusive and both derivable from the topology streams, so nothing is signalled;
`prevX/prevY` stay continuous, so there are still no reset seeds.

**How it stacks.**

| segment | SEGMENT_PAIR | QUAD_CORNER | SEGMENT_QUAD_CORNER |
|---|---|---|---|
| **corpus** | -4,5% | -1,6% | **-6,1%** |
| `building` | -5,2% | -7,1% | **-12,2%** |
| POLYGON | -3,9% | -4,1% | **-8,0%** |
| `transportation` | **-6,5%** | +0,3% | -6,2% |
| LINESTRING | **-6,3%** | +0,3% | -6,1% |

On `building` the two effects are nearly additive (-5,2% and -7,1% → -12,2%). On the line
segments it keeps almost all of SEGMENT_PAIR's win; the small gap (-6,2% vs -6,5% on
`transportation`) is the four-vertex *line* parts being pulled into their own region, a
negligible reshuffle of block boundaries. The oracle confirms it dominates: it is the best
single encoding, and a free per-buffer selector over it plus `SEGMENT_PAIR` and the two
baselines improves on always-using-it by only ~0,4%.

**Concepts and methods it exploits.** The encoding rests on four ideas, each measured:

1. **The layer's geometric prior.** `building` is single-ring quadrilaterals, and the quads
   are *rotated parallelograms*, not grid rectangles:

   | property of the `building` corpus | measured |
   |---|---|
   | polygons with `numParts == 1` (no holes) | **99,5%** |
   | rings storing exactly 4 vertices | **75,7%** |
   | 4-vertex rings that are exact parallelograms (`p1-p0 == p2-p3`) | **40,7%** |
   | 4-vertex rings axis-aligned to the tile grid | 4,2% |

2. **Model-based prediction instead of delta coding.** The baseline predicts each vertex as
   the *previous vertex* (a first-order delta). For the fourth corner of a quad this
   throws away a known constraint: in a parallelogram `p3 = p0 + p2 - p1` exactly, so
   predicting `p0+p2-p1` and storing the *residual* makes it zero for the 40,7% that are
   parallelograms and small for the rest — where a previous-vertex delta would carry a full
   edge length. This is the general **predict + store-residual** scheme (`ideen.md`): the
   decoder recomputes the same prediction from already-decoded vertices and adds the residual
   back, so the transform is symmetric and lossless.

3. **Residual-population separation for a bit-packed codec.** FastPFOR packs each block to the
   **bit width of its widest value**, so mixing a narrow population with a wide one taxes the
   narrow one up to the wide width. Three populations here have distinct widths — part-start
   deltas (wide boundary spikes), interior deltas (narrow), and corner residuals (near-zero) —
   so the transform emits each as its own contiguous region, letting FastPFOR size each
   independently. This is the same block-width lever `SEGMENT_PAIR` found; the corner
   predictor's -9,6% per-value zigzag-bit saving only becomes a real byte win *because* the
   corners are separated (interleaved, it measured -0,0%: one zero value in a block of wide
   deltas changes nothing).

4. **Derived, not signalled, structure — and no reset seeds.** Which vertex is a part start
   or a fourth corner is derivable from the topology streams (`numParts`/`numRings`), so both
   encoder and decoder compute the same partition and *nothing is written to the tile* to
   describe it — no permutation, no flags. And prediction stays continuous across the buffer
   (independent x/y cursors advancing through every vertex): the transform only changes *which*
   predictor and *which* block a residual lands in, never restarting a run, so it pays none of
   the ~10% seed cost the `DELTA_RESET_*` experiments incurred.

**What was left on the table.** Exact-shape repetition (32,4% of building rings share a
translation-invariant shape) was **not** exploited: 90% of repeated shapes span multiple
tiles, which a single-tile codec cannot address — the "redundancy abundant, addressing
expensive" wall the junction/reordering experiments hit. The 24,3% of quads that are *not*
parallelograms are also untouched; a second-order corner predictor was judged unlikely to pay
on `building` (better prospect on `landcover`).

The two sections that follow record the component mechanisms — `SEGMENT_PAIR` (part-start
grouping) is still registered as it remains the best on LINESTRING; the standalone
`QUAD_CORNER` was measured (-7,1% building / -1,6% corpus) and **removed once the composition
strictly dominated it**.

### Grouping part starts — `SEGMENT_PAIR_FASTPFOR`, **-4,5% corpus / -6,5% transportation**

Registered. The first encoding here to beat the baseline, and it does so on every layer
except the point-only ones, where it degrades to the baseline exactly.

```
DELTA_ZIGZAG_FASTPFOR     13.399.028        ref
DELTA_ZIGZAG_VARINT       13.818.767      +3,1%
SEGMENT_PAIR_FASTPFOR     12.795.818      -4,5%  *
```

| segment | baseline | SEGMENT_PAIR | |
|---|---|---|---|
| `transportation` | 4.232.495 | 3.958.179 | **-6,5%** |
| `boundary` | 1.159.184 | 1.088.496 | **-6,1%** |
| `building` | 3.228.047 | 3.059.154 | -5,2% |
| `landcover` | 1.194.048 | 1.162.968 | -2,6% |
| `water` | 916.048 | 905.852 | -1,1% |
| `housenumber` (point-only) | 735.180 | 735.180 | byte-identical |
| LINESTRING | 5.992.732 | 5.614.175 | **-6,3%** |
| POLYGON | 5.713.018 | 5.489.845 | -3,9% |
| POINT | 1.512.324 | 1.512.324 | byte-identical |

**What it does.** The same componentwise delta + zigzag residuals as the baseline, in a
different order: every part-start residual first, then every within-part delta. No
reordering of vertices, no reset seeds, nothing signalled — the split point is derived
from the topology streams on both sides, so encoder and decoder agree without a flag.

**Why it wins.** The starting observation was that `transportation` is not what the
previous round assumed. It is not long polylines; it is a fragmented road graph:

| property of the `transportation` corpus | measured |
|---|---|
| parts that are exactly 2 vertices | **70,4%** |
| parts ≤ 5 vertices | 92,1% |
| part-boundary deltas, share of all deltas | 30,2% |
| …their share of total zigzag bits | **35,5%** |
| mean zigzag width, interior delta | 10,30 bits |
| mean zigzag width, boundary delta | 13,10 bits |

Boundaries are not rare events between long runs — they are a third of the buffer, and
they are 2,8 bits wider on average than the interior deltas they sit between. Interleaved,
the wide population sets the block width for the narrow one, because **FastPFOR packs a
block to the width of its widest value**. Grouping the two populations lets each be packed
at its own width. Simulating just that block-width effect over 60 buffers predicts -20,2%
on the packed bits, and the measured byte win follows from it.

This is the same mechanism the delta-of-delta result identified as the reason *it* lost —
"both physical codecs price the tail, not the median". Here the tail is separated from the
median instead of being thickened.

It follows that the win is specific to a bit-aligned codec. `SEGMENT_PAIR` over Varint was
measured and is **byte-identical to `DELTA_ZIGZAG_VARINT`**: a byte-aligned codec prices
each value independently, so reordering values cannot change the total. It is not
registered, since a column that can never differ from a baseline is not a result worth a
column in every table.

### Junction back-references — regression, unregistered

The other structural property of `transportation` is junction sharing: **45%** of parts
share an endpoint with an adjacent part, **37,3%** of vertices exactly repeat another
position in the same buffer, and **18,3%** of part-start residuals against the previous
vertex are already exactly zero. Delta coding cannot capture this, because the repeats are
not adjacent.

The redundancy is real but **could not be addressed cheaply enough to pay for itself.**
Measured as zigzag bit-width sums over samples of the extracted buffers:

- Back-referencing each part start to the best of a window of K recent part endpoints
  shrinks *boundary* bits by up to **-44,1%** (K=32, 28,1% exact hits) — but charging
  ⌈log2(2K+1)⌉ bits per boundary for the reference index makes every K ≥ 2 a **net loss**
  (+2,7% to +3,8%). Only K=1, which needs no index, wins: **-2,7%**.
- A derivable candidate set with an entropy-coded rank (rank entropy 2,40 bits against
  3,00 flat, 46% choose rank 0) nets **-1,4%**.
- A `JUNCTION_REF` transform choosing between {previous vertex, previous part's start} by
  a rule the decoder re-runs was implemented and measured **+1,6%** on `transportation`.

The reason it cannot be rescued by a better rule: a **free, perfect oracle** choosing
between those two references saves only **5,4%** of boundary bits (~1,9% of the buffer),
and the alternative is the better reference at just 24,5% of boundaries. There is not
enough headroom for any decoder-reproducible rule to pay off, so the failure is in the
idea, not in the heuristic.

**The governing constraint, and the reason reordering was not pursued further:** the
redundancy in this layer is abundant but the *addressing* is expensive. Greedy chaining of
parts by shared endpoints is worth **-16,8%** (top-25 buffers) / **-10,2%** (random 40),
but an explicit permutation costs **+32,4%** of baseline — ⌈log2 P⌉ + 1 bits per part, with
P often in the thousands — for a net **+22,2%**. Reordering can only pay if the permutation
is *derived* rather than stored.

> **Superseded on the permutation point.** The premise above — that the new order must be
> transmitted — is wrong: feature order is not a round-trip property, so there is no
> permutation to pay for. See *Reordering geometries* at the top of **Results**, where the
> same greedy chaining measures **-11,8%** corpus-wide. The junction *back-reference* results
> in this section stand; only the reordering verdict is overturned.

### Delta-of-delta and reset variants — regression, unregistered

**These encodings are no longer in `research()`** — they were removed after measuring.
The numbers are kept because a negative result with an explained mechanism is worth not
re-deriving; to reproduce them, reimplement the transforms and re-register them.

Six variants over two axes: differencing order (first vs second) and reset strategy
(continuous vs restart at each part/ring boundary). All are lossless over all 8.366
corpus buffers; all are **larger than the baseline**:

```
DELTA_ZIGZAG_FASTPFOR     13.399.028        ref     (baseline: 1st order, continuous)
DELTA_RESET_VARINT        14.661.673      +9,4%
DOD_CONT_FASTPFOR         14.828.938     +10,7%
DOD_RESET_VARINT          14.883.608     +11,1%
DELTA_RESET_FASTPFOR      15.078.047     +12,5%
DOD_CONT_VARINT           15.155.705     +13,1%
DOD_RESET_FASTPFOR        15.282.605     +14,1%
```

**The resets, not the second differencing, are the dominant cost.**
`DELTA_RESET_FASTPFOR` is +12,5% while still being plain first-order — nearly all of
the +14,1% that the full delta-of-delta variant loses. Each run restart stores a
full-magnitude raw coordinate (up to the ~13-bit tile extent) instead of a small
residual, and map layers have many short runs, so the seeds cost more than the boundary
spikes they avoid. Second-order differencing alone (`DOD_CONT_FASTPFOR`, +10,7%) is
*cheaper* than resetting alone; combining them is worst.

Two clean controls fall out of the per-layer table: on `housenumber` (point-only) and on
`POINT` geometry, `DELTA_RESET_*` is byte-identical to the baseline — with no runs to
reset on, the transform degrades exactly to it.

Second differencing is a real cost on its own. Over a 300-buffer sample (357k
positions) the second-order residual is smaller at **60,1%** of positions, yet its mean
zigzag width **rises from 6,18 to 7,14 bits**, because differencing roughly doubles the
magnitude wherever consecutive deltas disagree in sign:

| zigzag width | ≥ 8 bits | ≥ 12 bits |
|---|---|---|
| first-order  | 24,9%    | 6,2%      |
| second-order | 36,4%    | 10,1%     |

Both physical codecs price the tail, not the median — FastPFOR packs a block to the
width of its widest value, and a varint crossing 127 costs another whole byte. Winning
the median while thickening the tail is a net loss: the predicted varint cost,
1,250 → 1,369 bytes/value (+9,5%), matches the measured `DOD_CONT_VARINT`.

Worst on `transportation` and `MULTILINESTRING` — the long polylines expected to benefit
most. Real map vertices are quantized to the tile grid, so successive deltas carry
rounding noise that a second difference amplifies rather than cancels. That the segments
predicted to win lost hardest is the useful part of the result.

### Predictors for >4-vertex rings — measured, no encoder written

`SEGMENT_QUAD_CORNER`'s parallelogram predictor fires only on *exactly*-four-vertex rings.
Rings with more vertices — **27% of all rings** (163k), predominant in `landcover` (68%),
`water` (67%), `landuse` (57%) — get plain first-order delta. The question was whether any
model-based predictor beats that delta on their *interior* vertices (those with two
predecessors, so an edge is defined). `RingPriorCli` measures it directly — it encodes
nothing, it just sums the zigzag bit width of each candidate residual over every >4-vertex
ring in the pure-polygon columns (75% of these rings, zero linestring contamination):

```
./gradlew ringPrior --args="../data/extracted"
```

Three predictors, all integer-exact (lossless), against the baseline `B: pred = p[i-1]`:

- **C — edge continuation** (constant velocity, for collinear runs): `pred = p[i-1] + (p[i-1]-p[i-2])`.
- **R — right-angle turn** (rectilinear): `pred = p[i-1] + rot90(p[i-1]-p[i-2])`, both turn
  directions and a per-vertex best-of.

Mean residual bits per interior vertex (lower is better), corpus-wide over 1,35M vertices:

| predictor | corpus | building | landcover | water |
|---|---|---|---|---|
| **B baseline** | **10,41** | 8,81 | 11,57 | 10,41 |
| C continuation | 11,53 | 10,91 | 12,28 | 10,79 |
| R rot-best (free turn choice) | 10,04 | 7,78 | 11,64 | 10,26 |
| best{B,C,R} (free per-vertex oracle) | 8,69 | 6,92 | 9,93 | 8,80 |

**No predictor beats B on any layer that matters.** C is *wider* than B almost everywhere —
the same reason uniform delta-of-delta lost: on quantized map vertices the second difference
amplifies rounding noise. R edges B only on `building`/`water` and loses on
`landcover`/`landuse`. The separable near-zero population that made `QUAD_CORNER` win (40,7%
exact parallelograms) **does not exist here**: across every layer the share of residuals that
are zero or ≤1 bit is under 1,2% (building's best case, 3,2%). Larger rings are neither
rectilinear nor collinear enough for a fixed model to zero out an edge.

**The oracle settles it.** Even a *free, perfect* per-vertex choice among all four predictors
drops the mean only ~1,7 bits/vertex (10,41 → 8,69). Signalling a 4-way per-vertex choice
costs 2 bits/vertex, so the oracle is **net negative on every layer** (corpus −0,3, building
−0,1, landcover −0,4 bits/vertex) before the block-width separation lever is even applied —
and that lever cannot help a population with ~0% near-zero residuals. So there is no encoder
to write: a first-order delta over a short ring edge is already near-optimal, and the two
priors that could have beaten it (rectilinearity, collinearity) do not hold on this corpus.
`RingPriorCli` is kept so the measurement need not be re-derived.

**The rectilinear-staircase refinement, and why it also fails.** `building` is CCW-wound and
mostly right-angled, so a natural idea is a *derived* turn direction instead of a signalled one:
force each turn to be the 90° rotation *opposite* to the previous actual turn (an alternating
`staircase`), reading the previous turn's sign from `p[i-3..i-1]` so nothing is signalled. It
measures **9,39 bits on building — worse than the baseline (8,81) and worse than a fixed
rot90-ccw (8,71).** Two direct measurements say why:

| property of `building` >4-ring interior turns (446k) | measured |
|---|---|
| turn is ~90° (80°–100°) | **82,2%** |
| turn *alternates* sign from the previous turn | **51,7%** |

The rectilinearity is real, but the walk is **not a zigzag**: a concave outline turns in *runs*
of same-direction corners with occasional reversals at notches, so the alternation is a coin
flip and forcing it adds error rather than removing it. And even with the direction right, the
rotation predicts an edge's *direction*, not its *length* — adjacent walls differ in length, so
`p[i-1] + rot90(prevEdge)` still carries a full residual edge (fixed rot90-ccw reaches only
1,9% ≤1bit). A rectilinear prior cannot be converted into a near-zero residual here, because
neither the turn sign nor the edge length is derivable.

### Resolving >4-vertex rings by vertex count and parity — measured, opens WP2/WP3

The pooled `>4`-vertex conclusion above averages across two populations that behave
differently: **a closed rectilinear ring must have an even vertex count**, so pooling
odd-length rings in with even ones dilutes any rectilinearity-driven predictor. Two
tools now resolve this directly: `RingPriorCli` buckets `>8`-vertex rings by parity
(`>8even`/`>8odd`) and tracks **per-component** zero counts (`%zero-comp`), since the
paired `%<=1bit` metric ANDs x and y and structurally cannot see a predictor that zeroes
exactly one axis; `BuildingShapeCli` (new, `./gradlew buildingShape --args="../data/extracted"`)
reports the same per-vertex-count resolution for ring shape rather than residual cost.

**`BuildingShapeCli` on `building`** (289.554 rings, pure-polygon columns):

```
                            4         5         6         7         8        >8       all
turns ~90deg            98,3%     54,9%     91,8%     57,3%     90,0%     78,5%     92,4%
fully right-angled      96,5%      0,0%     78,0%      0,0%     71,4%     40,4%     87,7%
grid-aligned (<5deg)    24,6%     17,7%     24,2%     21,6%     25,7%     29,0%     24,8%
```

The parity law is exact in this corpus: **0,0% of 5- and 7-vertex rings are fully
right-angled**, against 78,0% (n=6) and 71,4% (n=8). This is the number the pooled `>4`
measurement could not see. But `grid-aligned` — how far the ring's own frame sits off the
*tile* axes — stays ~25% regardless of bucket: buildings are rectilinear in their own
frame, almost never in the tile's. That distinction is exactly what separates WP2
(frame-relative, needs no grid alignment) from WP3/fixed-direction rot90 (implicitly
assumes a frame, loses when the ring is rotated relative to it).

**`RingPriorCli` on `building`, parity-split** (446.179 interior vertices):

```
predictor               5        6        7        8    >8even   >8odd
B baseline            9,56     9,13     9,54     8,89     8,35     9,03
R rot90-ccw (fixed)    9,43     8,22     9,43     8,57     8,75     9,61
R rot-best (oracle)    9,24     7,46     8,77     7,66     7,64     8,58
rot-best %zero-comp    4,03%    9,93%    5,50%    9,94%   12,35%    6,95%
```

**n=6 is the strongest signal in the whole table.** Fixed, unsignalled `rot90-ccw`
already beats baseline by **-0,91 bit** (9,13 → 8,22) — much better than the pooled
`>4` figure (8,81 → 8,71, -0,10 bit) suggested, because pooling mixed it with the
odd buckets where rot90 cannot fire usefully. The oracle (`rot-best`, free per-vertex
turn choice) reaches **-1,67 bit** with 9,93% of components exactly zero, against
5,80% for baseline delta. n=8 shows the same pattern, weaker (fixed -0,32 bit, oracle
-1,23 bit). Odd buckets (5, 7, `>8odd`) show a much smaller or reversed effect, exactly
as the parity law predicts.

**What this changes.** The repo's existing verdict — `RIGHT_ANGLE_TURN`'s *signalled*
per-vertex turn choice loses because the 1-bit signal costs more than the ~1-bit
direction saving — still stands; nothing here contradicts it. What is new is that the
**fixed**, unsignalled direction (no signal, `VertexTransform` not `Encoding`) was
previously measured only pooled, where its win looked negligible (-0,10 bit). Resolved
to n=6/n=8, the same fixed rule is worth -0,91 / -0,32 bit for free. Whether that
survives FastPFOR's block-maximum pricing (the same mechanism that sank `CONTEXT_DELTA`
and the delta-of-delta family) is not yet measured in bytes — that is `Rot90Fixed`
below.

### Fixed rot90 on even rings — `ROT90_FIXED_FASTPFOR`, weak win, registered

Measures whether the parity-resolved bit-width win above survives FastPFOR. **It does.**
`Rot90Fixed` predicts every interior vertex of an even-length, `>4`-vertex ring as
`p[i-1] + rot90ccw(prevEdge)` — direction fixed by winding, nothing signalled — and packs
starts / interior / turn residuals into three regions, same lever as `SEGMENT_QUAD_CORNER`.

```
DELTA_ZIGZAG_FASTPFOR     3.228.047        ref
ROT90_FIXED_FASTPFOR      3.065.822      -5,0%  *
ROT90_FIXED_VARINT        3.334.186      +3,3%
```

The Varint column confirms the win is FastPFOR-specific, as with every other
region-separation encoding here: a byte-aligned codec prices each value independently,
so reordering into regions cannot change the total on its own — the gain comes entirely
from block-width separation.

**It does not compose with `SEGMENT_QUAD_CORNER`.** `SEGMENT_QUAD_CORNER_ROT90` routes
`n=4` rings to the parallelogram corner and even `n>4` rings to `Rot90Fixed`'s predictor,
following the same disjoint-population pattern that made `SEGMENT_QUAD_CORNER` itself a
win over its two parts. Measured:

```
SEGMENT_QUAD_CORNER_FASTPFOR              2.834.672     -12,2%  *
SEGMENT_QUAD_CORNER_ROT90_FASTPFOR        2.834.236     -12,2%  (statistically identical)
SEGMENT_QUAD_CORNER_FASTPFOR_REORDER_REV  2.534.515     -21,5%  *
SEGMENT_QUAD_CORNER_ROT90_FASTPFOR_REORDER_REV  2.581.746 -20,0%  (worse than plain, with reorder)
```

Unlike `SEGMENT_QUAD_CORNER` composing `SegmentPair` and `QuadCorner` — which win on
*disjoint* geometry types (LINESTRING vs POLYGON) and are close to additive — quad-corner
and rot90-fixed both target the same layer's *same* rings, and quad-corner already claims
the highest-value population: `n=4` is 76,4% of building rings (`BuildingShapeCli`), while
`n=6`/`n=8` together are only 15,5%. The oracle confirms the marginal contribution is
small: with reordering, `SEGMENT_QUAD_CORNER_ROT90_FASTPFOR_REORDER_REV` is picked on 29
of 311 buffers for 23.264 bytes, against 636.322 bytes plain quad-corner-reorder already
captures — and the composition is *worse* than plain quad-corner once reordering is in
play, likely because folding the turn region into the buffer changes which vertices group
with which under `GeometryReorder`'s greedy chaining, a second-order interaction not
present in the unreordered case where the two are equal. **`ROT90_FIXED` stays registered
standalone**, since it is a real, if modest, unconditional win; `SEGMENT_QUAD_CORNER_ROT90`
is kept as a measured composition that does not pay for its added decoder complexity.

**A separate alternating-axis scheme (one signed scalar per edge instead of a 2D turn
residual) was considered and not built.** The original idea was that ring closure
(`Σdx=0, Σdy=0`) lets the last two edges of an even rectilinear ring be *derived* rather
than stored — but closure is a tautology of any closed polygon, true regardless of shape,
so it supplies no information on its own. `Rot90Fixed` above and a 2-scalar alternating
scheme both actually rest on the same real assumption — this ring is rectilinear — and
both pay a residual, not a correctness failure, when a ring is not. Since `Rot90Fixed`
already measures that assumption end-to-end (a real but modest win, does not compose
with quad-corner), a second predictor testing the same prior in a different residual
shape was judged not to add enough to justify building and verifying separately. If a
future pass wants the comparison, the open design question is whether a 1-scalar-per-edge
residual (rather than a 2D turn residual with one component structurally near-zero)
packs differently under FastPFOR — untested here.

### Context-adaptive delta for LineStrings — `CONTEXT_DELTA`, weak win, registered

Answers the question `ideen.md` leaves open as *Idee 2*, and the answer is **no**.

`ideen.md` measured that edge continuation (`pred = p[i-1] + (p[i-1]-p[i-2])`) beats the baseline
by **-10,1% in mean zigzag bits** on NY z14 with nothing signalled, but is **+1,9% as bytes** under
FastPFOR: it wins the median (5,91 -> 5,31 bits) and widens the tail (p99,9 rises 11 -> 12), and
FastPFOR prices the block maximum. The open question was whether the positions where continuation
loses are recognizable from a *derivable* feature — if so, a gate would suppress the tail and no
rANS would be needed.

**What it does.** A run-length state machine over already-reconstructed vertices, the `c = min(run,3)`
shape `ideen.md` specifies. A step is *smooth* when the turn between the last two edges is shallow
by an integer cross/dot test (`|cross| <= |dot| >> turnShift`, `dot > 0` — no floating point). The
context picks the predictor (`c=0` plain delta, `c>=1` the mean of the last `c` edges) **and** the
FastPFOR region the residual lands in, so a rough population never sets a smooth one's block width.
A mispredicted vertex (residual wider than `missThreshold`) collapses the run — the "Streikzähler"
of `ideen.md`'s decoder state. Nothing is signalled per vertex; only the four region *counts* are
written, since the decoder cannot derive region offsets from topology alone (see below).

**Measured on both corpora, LINESTRING:**

```
                            Munich LINESTRING        NY transportation
DELTA_ZIGZAG_FASTPFOR            5.992.732  ref         16.536.440  ref
CONTEXT_DELTA_T2_M8              5.968.948  -0,4%       16.410.704  -0,8%
CONTEXT_DELTA_T2_M32             5.925.249  -1,1%       16.242.958  -1,8%
SEGMENT_PAIR_FASTPFOR            5.614.175  -6,3%       15.386.675  -7,0%
REORDER_REV_SEGMENT_PAIR         4.668.030 -22,1%       13.625.028 -17,6%
```

**The gate works; the predictor is not worth gating.** It is *not* the +1,9% regression that
unconditional continuation produced — the gating and the per-context regions did suppress the tail,
so the mechanism does what it was designed to do. It simply buys very little, and it loses to
`SEGMENT_PAIR` by a factor of six.

**Why, measured directly** (NY z14, 300 buffers, share of vertices per context and mean residual
width in bits):

| `missThreshold` | c0 | c1 | c2 | c3 |
|---|---|---|---|---|
| 8 | 58,4% w=14,40 | 35,5% w=10,67 | **4,1%** w=9,05 | **1,9%** w=8,86 |
| 32 | 47,7% w=15,07 | 35,5% w=10,55 | 8,2% w=9,82 | 8,5% w=9,60 |
| unbounded | 34,3% w=16,09 | 33,5% w=10,67 | 10,9% w=11,22 | 21,4% w=11,30 |

**Only ~6% of vertices ever reach context 2 or 3.** The multi-edge smoothing that was supposed to
damp quantization noise almost never fires; 94% of the buffer sits in c0 (plain delta) or c1 (plain
continuation), so the encoding is essentially the baseline plus the continuation that was already
known to be worth nothing here. And the parameter cannot be tuned out of it: loosening
`missThreshold` grows c2/c3 but **their residuals widen in lockstep** (8,86 -> 11,30 bits), because
the vertices it admits are exactly the ones the predictor handles badly. There is no setting at
which a large *and* narrow population exists.

**The structural reason, and the limit of the whole approach.** The context of vertex `i` is read
*before* `i` is decoded, so it describes the run as of `p[i-1]` — the machine cannot know the line
turns at `i` until it has seen it turn. Every corner is therefore coded once at full context, with a
residual *wider* than a plain delta would have been, and only the following vertex is corrected.
That one-vertex lag is not an implementation detail to be fixed; it is what "derived from already
decoded data" means. A road at z14 turns often enough that the lag consumes the gain.

**One honest cost this pays that the other region-splitting encodings do not.** `SEGMENT_PAIR` and
`SEGMENT_QUAD_CORNER` classify each vertex from the topology streams, so a decoder computes every
region's offset before decoding anything. Here the context depends on the vertices being
reconstructed, so region sizes are not derivable — the four counts are written into the payload
(a handful of bytes, charged in `totalBytes()` via a second `StreamMetadata` header). This is why it
is an `Encoding` directly rather than a `VertexTransform`.

**Verdict.** Registered with its mechanism test so the negative need not be re-derived, same status
as `RIGHT_ANGLE_TURN`. The per-buffer oracle picks a `CONTEXT_DELTA` variant on 149 of 8.366 Munich
buffers (~2,4 KB), so it is never worth shipping as a whole-buffer encoding. **The trail now points
back to `ideen.md`'s *Idee 1*:** an entropy coder prices the median, a bit packer the maximum —
continuation's gain is real in the distribution and only invisible to FastPFOR, so rANS remains the
one measured constellation where it could survive. What this result adds is that the cheaper escape
route is closed: the tail cannot be gated away by a derivable rule, because the rule is one vertex
too late.

### Signalling the turn — `RIGHT_ANGLE_TURN_FASTPFOR`, regression, registered

`RingPriorCli` measured the turn direction as a *free* per-vertex oracle (`R rot-best`, 8,81 →
7,78 bits on `building`). `RIGHT_ANGLE_TURN_FASTPFOR` is the honest, decodable version of that:
for every interior vertex of a >4-vertex ring it predicts `p[i-1] + rot90(prevEdge)`, picks the
turn direction (cw/ccw) with the smaller residual, and **signals it as one bit per such vertex**
in a side stream. It is the first encoding here that adds a signalled channel rather than only
reordering, so it is an `Encoding` directly (not a `VertexTransform`) and counts a second
`StreamMetadata` header for the bit stream, so `totalBytes()` carries the true signal cost.

Measured end-to-end, it is a regression on every layer — **`building` +1,5%** (3.277.603 vs
3.228.047), `transportation` +2,3%, `landcover` +6,2%, `water` +8,6%; **POLYGON +3,8%** — and
the per-buffer oracle picks it on **1 of 8366 buffers** (8 bytes). This confirms the
`RingPriorCli` inference at the byte level: the ≈1,0-bit direction saving on `building` does not
cover the 1-bit signal, and the edge *length* still carries a full residual (rot90 leaves only
1,9% of residuals ≤1 bit). Kept, with its mechanism test, so the negative result need not be
re-derived — same status as the removed `QUAD_CORNER`, but registered because there is a concrete
encoder to keep.

### Composing turn with quad-corner — `SEGMENT_QUAD_CORNER_TURN_FASTPFOR`, regression, registered

`SEGMENT_QUAD_CORNER` wins on exactly-four-vertex rings but leaves >4-vertex rings on plain delta.
The natural composition routes by ring length: a 4-ring keeps the parallelogram corner, a >4-ring
gets `RIGHT_ANGLE_TURN`'s signalled 90°-turn predictor on its interior vertices (`i >= start+2`),
and the residuals segment into four FastPFOR regions (starts | interior | corners | turns). Unlike
`SEGMENT_QUAD_CORNER` it therefore carries the signalled turn-bit side channel, so it is an
`Encoding` directly.

It is **worse than plain `SEGMENT_QUAD_CORNER` on every layer** — `building` -11,6% (against
-12,2%), `landcover` +3,6% (against -2,8%), `water` +7,5% (against -1,2%); **POLYGON -4,6% against
-8,0%**. The turn path is net-negative wherever it fires (the standalone `RIGHT_ANGLE_TURN` result),
so adding it to the winning quad-corner path only dilutes the winner: it costs little on `building`
(few >4 rings, so only 0,6pp given back) and flips `landcover`/`water` (68%/67% >4 rings) from a win
to a loss. The per-buffer oracle still picks it on **74 of 8366 buffers** (84.126 bytes) — a small
tail of strongly rectilinear buffers — but as a whole-buffer encoding it never beats plain
quad-corner. Registered with its mechanism test so the composition need not be re-derived.

### Shape-reuse feasibility scan (WP5-E5.1) — killed, below the 20% gate

`BuildingShapeCli`'s corpus-wide shape index already showed 32,4% of building rings share
a translation-invariant shape with some other ring, and 88-92% of those repeats are
cross-tile — the "redundancy abundant, addressing expensive" wall the junction/reordering
experiments hit. That measurement doesn't say whether a codec with only a small
*back-window* (not a corpus-wide dictionary) could still exploit it, since a corpus-wide
index can find a match arbitrarily far away in the buffer. `BuildingShapeCli` was
extended with a k-window scan: for k ∈ {1, 4, 16}, does this ring's canonical shape
signature match one of the k most-recently-seen ring signatures in extraction (buffer)
order, exact translation and also allowing 90°-rotation multiples.

```
                       4        5        6        7        8      all
k=1 window hit      0,66%    0,16%    0,04%    0,04%    0,07%    0,52%
  ...+rot90          0,97%    0,16%    0,04%    0,04%    0,07%    0,76%
k=4 window hit      1,79%    0,19%    0,09%    0,04%    0,11%    1,39%
  ...+rot90          2,63%    0,19%    0,09%    0,04%    0,13%    2,03%
k=16 window hit     3,36%    0,23%    0,20%    0,04%    0,20%    2,61%
  ...+rot90          4,87%    0,23%    0,20%    0,08%    0,22%    3,76%
```

**Killed against the plan's own gate (≥20% same-tile hit rate).** Even at k=16 with
rotation, the best bucket (n=4) reaches only 4,87%, and the pooled rate is 3,76% — an
order of magnitude below the threshold, and this is generous to the idea, since real
addressing would also need to encode *which* of the k candidates matched
(⌈log2(k+1)⌉ bits, charged nowhere in this scan). Confirms directly what the corpus-wide
cross-tile share implied: shape repetition in `building` is real but not locally
clustered in buffer order, so a small-window codec cannot reach the redundancy a
corpus-wide dictionary could — and a corpus-wide dictionary is the addressing-cost wall
already measured and rejected for `transportation`'s junctions. **E5.2–E5.5 not
implemented**, per the plan's stop rule.

### Two-rectangle decomposition for L-shaped hexagons — analysis only, no encoder written

`BuildingShapeCli` shows `n=6` rings are 78,0% fully right-angled, well clear of the
~1% seen pooled across every `>4`-vertex ring, so the natural next idea is a shape
template: decompose the ring into two axis-aligned rectangles and predict the vertices
that complete them, the way `QUAD_CORNER` predicts a parallelogram's fourth corner. It
does not reach an encoder, for a structural reason worth recording.

**An L-hexagon has 4 free scalars, not 3.** A parallelogram (`n=4`) has exactly three
independent edge vectors — the fourth vertex is always `p0+p2-p1`, zero residual, no
exceptions. Parametrize a canonical CCW L-hexagon as `(0,0), (a,0), (a,b), (c,b), (c,d),
(0,d)`: **four** free scalars (`a,b,c,d`), so **only one** of the six vertices can ever
be a zero-residual prediction from the rest in general position — there is no second
derivable corner to complete a "second rectangle" the way the name suggests.

**Which vertex is derivable is not fixed — it depends on winding and starting vertex,
which vary per ring in the corpus.** In the canonical parametrization above, the
*closing* vertex is derivable — `p[end-1]` always shares one coordinate with `p[start]`,
since the closing edge is axis-parallel by definition of a rectilinear ring. But that is
not a property specific to L-hexagons or to two-rectangle decomposition at all — it is
`Σdx=0, Σdy=0` on the whole ring, the same closure constraint a general alternating-axis
scheme (one signed scalar per edge, axis implied by alternation, last H/V edge derived
from the sum) generalizes to every even rectilinear ring regardless of vertex count. A
"two-rectangle" predictor that only reaches the closing vertex is not a distinct idea
from that one.

**The other candidate — predicting the reflex (notch) corner specifically — is not
decoder-causal.** The reflex vertex is identifiable by a turn-sign test (`cross(p[i-1],
p[i], p[i+1]) < 0` in a CCW ring), but that test needs `p[i]` itself to evaluate the turn
*at* `i`. A decoder predicting `p[i]` cannot first ask "is `i` the notch?" — the answer
depends on the value being predicted. This is the same one-vertex-lag wall
`CONTEXT_DELTA` hit for a different reason (`research-java/README.md`, *Context-adaptive
delta*): a rule that needs vertex `i` to classify vertex `i` cannot be used to predict
vertex `i`. The only ways around it are (a) a fixed template position, which the DOF
analysis above shows does not generalize across the corpus's actual mix of starting
vertices and windings, or (b) signalling the notch position per ring, an `Encoding` with
a real cost — and `RIGHT_ANGLE_TURN`'s per-vertex signal already lost at a much smaller
per-bit cost than a per-ring position signal would carry.

**Verdict: no encoder written.** The idea reduces either to a general alternating-axis
scheme (more general, worth building on its own merits — see below) or to a signalling
problem this corpus's other signalled
experiments (`RIGHT_ANGLE_TURN`, `SEGMENT_QUAD_CORNER_TURN`) already show does not pay.
Kept as an analysis note rather than a registered negative result, since there is no
encoder to pin with a mechanism test.

### Midpoint reflection — regression, unregistered

Targeted `landcover`, the layer the quad-corner predictor reaches worst
(`SEGMENT_QUAD_CORNER` is only -2,8% there against -12,2% on `building`). The idea: its
larger, blocky rings are often *centrally symmetric*, so instead of first-order delta
across the whole ring, delta only through the first half and predict each second-half
vertex as the point-reflection of a first-half partner through the midpoint of the ring's
`start → apex` diagonal — `pred = start + apex - partner`, integer-exact, so lossless. A
symmetric ring's residual is then zero; separated into its own FastPFOR region (starts |
interior | reflect) it should pack narrow, the same block-width lever `SEGMENT_PAIR` uses.
Two pairings were measured: `SYMMETRIC` (partner `L - r`, axis reflection) and `SPLIT`
(partner `r - L/2`, central symmetry). Both are lossless over all 8.366 corpus buffers.

Both are **larger than the baseline** on the target layer and everywhere else:

```
                                  corpus    landcover POLYGON   POLYGON
DELTA_ZIGZAG_FASTPFOR               ref          ref             ref
SEGMENT_QUAD_CORNER_FASTPFOR       -6,1%        -2,8%*          -8,0%*
MIDPOINT_REFLECT_SPLIT_FASTPFOR    +0,9%        +7,0%          +1,8%
MIDPOINT_REFLECT_SYM_FASTPFOR      +1,7%        +9,9%          +3,5%
```

The oracle picks a reflect variant on **0** landcover buffers (17+10 across the whole
corpus, ~8 KB), so it is never the best on the layer it was built for.

**Why it fails: the symmetry prior is wrong for this corpus.** Landcover rings are not
symmetric enough for `start + apex - partner` to beat predicting the *immediately
previous* vertex. A first-order delta spans one short edge; the reflection spans the
deviation from a global symmetry the ring does not have, which is *wider*, not narrower.
Pulling those wide residuals into their own block then makes it worse, not better — the
`SEGMENT_PAIR` lever only pays when the separated population is genuinely narrow (near-zero
quad corners), and here it is the widest of the three. Worst on `water` (+23,5%), whose
rings are the least parallelogram-like of all. The transform and its
`MidpointReflectMechanismTest` are kept so the negative result need not be re-derived; it
is removed from `research()`.

### Would a tile ever pick one of these?

*(This section is about the delta-of-delta family above. For `SEGMENT_PAIR` the answer is
different — see below.)*

No — which is why they were removed. The tables above choose one encoding per corpus or per layer, but a real tile selects
per stream — `StreamMetadata` already carries the technique per stream, so an encoder may
legitimately encode each buffer with whichever candidate is smallest. The benchmark prints
that as a **per-buffer selection oracle**:

```
always DELTA_ZIGZAG_FASTPFOR        13.399.028
best baseline per buffer     13.325.818   -0,5%
best of all per buffer       13.307.179   -0,7%
```

A perfect selector over all eight encodings saves **0,7%** against simply always using
`DELTA_ZIGZAG_FASTPFOR`, and the six research encodings contribute only **0,14%**
(18.639 bytes) of that on top of selecting between the two baselines.

Win counts alone would have been misleading here: `DOD_RESET_VARINT` is the smallest
encoding on 911 buffers, which sounds meaningful until you see it saves ~17 bytes each
(94 at most) on buffers of median 110 vertices. It wins often, by nothing.

The oracle charges nothing for signalling and never guesses wrong, so 0,14% is a
ceiling, not an estimate. A real selector needs signalling per stream — at even one byte
per stream that is ~8,4 KB across 8.366 buffers, against 18,6 KB saved — plus a decoder
for every candidate it might pick. **None of the research encodings earn a place in a
tile**, per-buffer selection included.

The practical takeaway for later predictor work in `ideen.md`: **structural reset points
are not free.** A predictor that restarts per part or ring has to beat the baseline by
enough to pay for its own seeds first, which on this corpus is a ~10% handicap before
the predictor does anything.

`SEGMENT_PAIR` clears that bar without ever paying it: it has no reset seeds, because it
never restarts a prediction — it only changes which residuals share a FastPFOR block. Its
oracle line is also a different shape from the family above, since it does not need to be
selected *against* the baseline to be worth having:

```
always SEGMENT_PAIR_FASTPFOR        12.795.818
best baseline per buffer     13.325.818   +4,1%
best of all per buffer       12.757.039   -0,3%
```

Always using it beats a perfect free selector over both baselines by 4,1%, and a perfect
selector that also includes it improves on always-using-it by only 0,3%. That is the
opposite of the delta-of-delta result: the win does not depend on per-stream signalling,
so it needs no selector and no extra header — one technique, applied unconditionally.

**On `building`, the same conclusion holds even more strongly.** The current best
(`SEGMENT_QUAD_CORNER_FASTPFOR_REORDER_REV`) leaves a research-oracle gap of only
**0,08%** (2.534.515 always vs 2.532.507 oracle, 2.008 bytes across 311 buffers) — far
inside the "oracle within ~5% of Mode A" threshold that would make per-feature selection
worth its own signalling. **No Mode-B (per-feature enum) selector was built for this
layer.**

### Entropy cost-model estimate (WP7) — a real gap exists, no coder built

No rANS/FSE implementation exists anywhere in this repository or the `maplibre-tile-spec`
submodule — confirmed by exhaustive search. Building one is a substantial, separate
undertaking (context plumbing through `IntCodec`, which is currently context-free and
cannot see part boundaries or the run-length context `ideen.md`'s design needs; static
vs per-buffer frequency tables; a real bitstream coder). Per the user's decision, WP7
stops at a **cost-model estimate**: does the gap between what FastPFOR actually charges
and what order-0 entropy says is achievable justify that investment at all?

`EntropyCli` (`./gradlew entropy --args="../data/extracted --layer building"`) measures
this for `SEGMENT_QUAD_CORNER`'s three residual regions (starts | interior | corners),
311 Munich building buffers, 2.951.676 residual values:

```
interleaved (one population)      7,153 bits/value  ->    2.639.235 bytes
  starts        584.546 values   10,365 bits/value  ->      757.364 bytes  (alphabet 8.345, table ~50.070 bytes)
  interior    1.924.736 values    6,447 bits/value  ->    1.550.987 bytes  (alphabet 883, table ~5.298 bytes)
  corners       442.394 values    1,720 bits/value  ->       95.126 bytes  (alphabet 163, table ~978 bytes)
partitioned (3 regions)                              ->    2.403.477 bytes  (+ ~56.346 bytes static tables)

Real FastPFOR bytes for the same stream: 2.832.684

Gap: FastPFOR - partitioned entropy = 429.207 bytes (15,15% of FastPFOR)
```

**E7.1, the mixture penalty, confirms the region-split lever directly in entropy terms**,
not just FastPFOR bytes: partitioning by region before computing entropy saves 235.758
bytes (8,93%) over treating the residuals as one population — the same block-width
mechanism `SEGMENT_PAIR`/`SEGMENT_QUAD_CORNER` exploit, restated as an information-
theoretic fact rather than inferred from a bit-packed codec's behavior.

**E7.2/gap.** FastPFOR spends 429.207 more bytes than partitioned order-0 entropy says
is achievable — 15,15% of its own total, and about 373K bytes (13,2%) after subtracting
the ~56K bytes a real static-table implementation would cost to ship. That is a real,
non-trivial ceiling, not a rounding artifact — consistent with `CONTEXT_DELTA`'s finding
that FastPFOR prices the block *maximum* while an entropy coder prices the *median*, and
with `ideen.md`'s prediction that continuation-style predictors specifically should
survive under rANS where they lose under FastPFOR.

**What this does and does not establish.** It is a **ceiling**, not an achievable number:
order-0 entropy assumes an ideal coder with no per-symbol overhead, ignores the
context-modelling `ideen.md`'s actual design uses (which could do better by conditioning
on run state, or worse if that context fragments the alphabet further), and the ~56K
table-cost estimate is a rough per-region flat cost (`alphabet × 6 bytes`), not a design
for a real table format. E7.3 (FastPFOR block-occupancy check) was not run separately,
since the gap above already answers the question this measurement was scoped to answer.
**No rANS/FSE coder was implemented.** The 13,2%-of-FastPFOR ceiling is large enough that
a real coder is not obviously not worth building, but the decoder-complexity and
`IntCodec` redesign costs are unmeasured, so this stops at "the gap is real and worth
knowing," not "build it" — consistent with the user's WP7 scope decision.

### WP7 follow-up — the coder was built; it captures 38,6% of the estimated gap

`RansCodec` (static rANS, 12-bit precision, 32-bit state / 8-bit renormalisation) now
exists as an `IntCodec`, so it composes with every transform the benchmark already
measures. It needs no `IntCodec` redesign: the alphabet is the residual's **bit-length
bucket** (33 symbols) and the `bucket-1` bits below the leading one are written raw into
a separate forward region. The context is derived from the value itself, so nothing has
to be plumbed through the context-free `IntCodec` interface. The frequency table is
per-buffer (33 × 2 bytes = 66 bytes), which keeps every byte the decoder needs inside the
measured payload — no corpus-trained side table.

Round-tripped over the whole corpus by `BenchmarkCli` on every buffer, plus
`RansCodecTest` (skewed, uniform-wide, all-zero, single-value, empty, extreme, and
all-buckets-present cases).

**Building layer, 311 buffers, same transform on both sides:**

```
SEGMENT_QUAD_CORNER_FASTPFOR   2.834.672
SEGMENT_QUAD_CORNER_RANS       2.668.361   -5,87%   (-166.311 bytes)
order-0 partitioned ideal      2.403.477   (+ ~56K table = 2.459.823)
```

The estimate said up to 431.195 bytes (15,15%) were available. The real coder took
**166.311 bytes — 38,6% of that gap.** The shortfall is not implementation slack, it is
the bucket alphabet: splitting a residual into `bucket + raw bits` is itself lossy
relative to order-0 entropy, because the raw bits below a bucket are *not* uniformly
distributed. A synthetic control confirms the direction — on a geometric source,
`H(bucket) + rawbits` overshoots true order-0 entropy by ~21%. So the WP7 ceiling was
correct as a ceiling, but no bucketed coder can reach it; only a coder over the full
residual alphabet could, at a table cost the 8.345-symbol `starts` region makes
prohibitive.

**The entropy coder and the transform are additive, not competing.** rANS on the plain
baseline transform is already worth -12,96%; stacking it on `SEGMENT_QUAD_CORNER` gives
-17,34%, and with reordering **-27,16%** — the corpus's new best, beating the previous
best (`SEGMENT_QUAD_CORNER_FASTPFOR_REORDER_REV`, -21,48%) by 5,7 points.

Corpus-wide, `SEGMENT_QUAD_CORNER_RANS_REORDER_REV` wins transportation (-31,0%),
building (-27,2%), landcover (-10,8%), LINESTRING (-29,9%) and POLYGON (-18,6%), and
lifts the always-one-encoding total from 13.399.028 baseline bytes to **10.601.321**.

**What did not work: conditioning y on x.** `CondEntropyCli` (`./gradlew condEntropy`)
measures the three claims a context-modelled coder would rest on. The axis-alignment
hypothesis — that on building walls a zero x-residual predicts a large |y| — is
**worth almost nothing**: H(y|coarse(x)) is only 0,015-0,092 bits below H(y), a total of
8.380 bytes (0,3% of FastPFOR) on `SEGMENT_QUAD_CORNER`, and only 20.499 bytes (0,7%) on
the raw delta stream where the predictors have not yet absorbed it. The correlation was
never strong, so **coding x and y as separate streams costs essentially nothing**, and
the interleaved single-state layout is preferable only for its one-flush overhead.
Order-1 over the bucket alphabet (`bucket | previous bucket`) is worth more but still
modest — 46.548 bytes, concentrated almost entirely in the `starts` region (38.046),
which is the one region whose alphabet is too wide to model cheaply.

### Does the region split help rANS? Only on large buffers — it is a table-cost tradeoff

`SEGMENT_QUAD_CORNER` already packs residuals into three contiguous regions, but
`SEGMENT_QUAD_CORNER_RANS` codes all three with **one shared frequency table**, pricing
every region at the mixture of all three. `SegmentedRansCodec` gives each region its own
table (region lengths written into the payload, so decode stays context-free; the extra
bytes are inside every number reported here).

`CondEntropyCli`'s C4 measures the ideal on the alphabet rANS actually codes — buckets,
not full residuals, since the raw sub-bucket bits are unaffected by any split:

```
one shared bucket table   ->  1.239.357 bytes
per-region bucket tables  ->  1.031.482 bytes (+ 132 bytes extra tables)
net gain from splitting:        207.743 bytes (16,76%)
```

**Building layer, isolating the split as the only variable:**

```
SEGMENT_QUAD_CORNER_RANS              2.668.361
SEGMENT_QUAD_CORNER_RANS_SEG          2.510.165   -5,93%
SEGMENT_QUAD_CORNER_RANS_REORDER_REV      2.351.388
SEGMENT_QUAD_CORNER_RANS_SEG_REORDER_REV  2.277.120   -3,16%
```

`SEGMENT_QUAD_CORNER_RANS_SEG_REORDER_REV` is the building layer's new best at **-29,5%**
against the baseline.

**Corpus-wide, however, the split is a net loss of 9,62%** — and the reason is entirely
buffer size, not geometry. Splitting costs a fixed ~152 bytes per buffer (two extra
66-byte tables plus 20 bytes of region headers), which has to be recovered from the
mixture penalty:

```
vertices              RANS         SEG    delta   bufs   fixed cost as % of RANS
0-50               414.993   1.095.062 +163,87%   3543        129,8%
50-200             580.908     885.320  +52,40%   1637         42,8%
200-1000         2.319.476   2.641.480  +13,88%   1963         12,9%
1000-5000        4.482.117   4.529.651   +1,06%    980          3,3%
5000-inf         4.188.332   3.987.602   -4,79%    243          0,9%
```

The crossover is around **1.000-5.000 vertices**. Below it the tables cost more than the
mixture penalty they remove; above it the split pays and keeps paying. Building wins
because its buffers are large; the corpus loses because 5.180 of 8.366 buffers have fewer
than 200 vertices.

**So segmentation is not a free win under rANS, and the earlier per-region entropy figures
overstate it.** C4's 16,76% ideal is computed over the pooled corpus, where the large
buffers dominate the value count — it does not price the per-buffer table cost that
decides the outcome on small buffers. The honest form of the result is conditional: worth
taking above ~1.000 vertices, harmful below, which makes it a per-buffer decision rather
than a universal encoding improvement. This matches the project's standing rule that
segmentation is a niche add-on, not a baseline comparator — here it is measured as an
isolated variable against the same transform and codec, with and without reordering
reported separately.

## Baseline

Baselines 1 and 2 from the root `README.md`: componentwise delta + zigzag over a
bit-aligned (`DELTA_ZIGZAG_FASTPFOR`) and a byte-aligned (`DELTA_ZIGZAG_VARINT`)
physical codec. Sizes are payload **plus** the `StreamMetadata` header MLT writes in
front of a stream, since that is what lands in the tile — an encoding that shrinks the
payload but needs a longer header has not necessarily won.

Baseline 3 (general-purpose Gzip/Zstd) is not currently implemented.

`research()` currently holds `SEGMENT_PAIR`, `SEGMENT_QUAD_CORNER`, `ROT90_FIXED`, and
`SEGMENT_QUAD_CORNER_ROT90`, each over FastPFOR and/or Varint and, where it helps,
`GeometryReorder` — see **Results** for what they do, and for what has been measured and
rejected.

## Notes on fidelity to MLT

- `VertexBufferExtractor` is a **verbatim copy**, not a reimplementation, of
  `GeometryEncoder.prepareGeometry` and its private helpers (`flatPolygon`,
  `flatLineString`, `ringToLineString`, `addLineString`, `containsPolygon`). It is
  copied rather than called because the original is private in `mlt-core`.
  `VertexBufferExtractorFidelityTest` pins it against the real method via reflection —
  that is what proves the extracted buffers match what MLT itself would produce. If it
  fails after updating the submodule, the copy has drifted and needs re-syncing.
- `VertexTransform.ZigZagDelta` delegates to `GeometryEncoder.zigZagDeltaEncodeVertices`,
  so the baseline transform cannot drift from upstream either.
- Two rules the spec text does not state, but the extractor (matching the real encoder)
  applies:
  - Polygon/ring closing vertices are **dropped** — a ring's last coordinate, which
    duplicates the first, is not stored.
  - LineString vertex counts go to `numRings` if the column contains any
    (Multi)Polygon, otherwise to `numParts` — decided once per column, not per geometry.
- Vertex order matches feature order in the MVT layer (no reordering), mirroring MLT's
  default non-sorted feature table encoding.

## Project layout

```
research-java/
  settings.gradle             composite build -> ../maplibre-tile-spec/java (mlt-core)
  build.gradle
  src/main/java/org/maplibre/mlt/research/
    BenchmarkCli.java             encode everything, report sizes overall and per layer
    RingPriorCli.java             measure predictor residual bit widths on >4-vertex rings (encodes nothing)
    BuildingShapeCli.java         characterize geometric properties of >4-vertex building rings (encodes nothing);
                                    also the WP5-E5.1 k-window shape-reuse feasibility scan
    EntropyCli.java                WP7 cost-model estimate: order-0 entropy vs FastPFOR bytes (encodes nothing)
    extraction/
      VertexBufferExtractor.java   core flattening logic (copied from GeometryEncoder)
      MbtilesSource.java           .mbtiles reading + decompression + TMS->XYZ
      ExtractionWriter.java        .bin/.json/manifest.json output
      ExtractCli.java              extract-step CLI (default mainClass)
    encoding/
      Encoding.java            name + encode + decode; the whole contract
      PartBoundaries.java      vertex-run offsets derived from the topology streams
      VertexTransform.java     logical layer, List<Vertex> <-> int[] (+ ZigZagDelta)
      IntCodec.java            physical layer, int[] <-> byte[] (+ Varint, FastPfor)
      VertexBufferCodec.java   one transform + one codec = an Encoding; also holds
                                 BufferContext and EncodedBuffer
      StreamMetadata.java      size of the header MLT writes in front of a stream
      Baselines.java           the two baselines + the research registry
      GeometryEquivalence.java order-independent geometry check, for the reordering pre-pass
      research/
        GeometryReorder.java   greedy endpoint chaining of whole features (a pre-pass, not an Encoding)
        SegmentPair.java       groups part-start residuals ahead of interior deltas
        SegmentQuadCorner.java part starts + 4th-corner (p0+p2-p1) prediction, 3 grouped populations
        Rot90Fixed.java        fixed-ccw 90°-turn predictor on even >4-vertex rings, unsignalled (weak win)
        SegmentQuadCornerRot90.java  quad-corner for 4-rings + fixed rot90 for even >4-rings, 4 regions (no gain over plain quad-corner)
        failed/
          RightAngleTurn.java    >4-ring 90°-turn predictor with a signalled cw/ccw bit stream (regression)
          SegmentQuadCornerTurn.java  quad-corner for 4-rings + signalled 90°-turn for >4-rings, 4 regions (regression)
          ContextDelta.java      run-length context over recent deltas picks predictor + FastPFOR region (weak win)
          MidpointReflect.java   central-symmetry reflection predictor for landcover-style rings (regression)
          EvenRingTurn.java      quad-corner-style parity-gated signalled turn predictor (regression; reimplemented from its orphaned test)
  src/test/java/org/maplibre/mlt/research/
    extraction/VertexBufferExtractorFidelityTest.java   reflective pin against mlt-core
    encoding/IntCodecTest.java            physical-layer round trips
    encoding/VertexTransformTest.java     logical-layer round trips
    encoding/VertexBufferCodecTest.java   round trips every registered encoding
    encoding/research/GeometryReorderMechanismTest.java   pins the chain, the topology rebuild and the equivalence gate
    encoding/research/GeometryReorderGridTest.java        pins the spatial index against a brute-force chain
    encoding/research/SegmentPairMechanismTest.java   pins SegmentPair's grouping and decode
    encoding/research/SegmentQuadCornerMechanismTest.java   pins the composition reduces to each part
    encoding/research/Rot90FixedMechanismTest.java   pins the parity gate and the reflex-corner residual on an L-shape
    encoding/research/SegmentQuadCornerRot90MechanismTest.java   pins the corner/turn composition reduces to each part
    encoding/research/failed/RightAngleTurnMechanismTest.java   pins the signalled 90°-turn predictor + decode
    encoding/research/failed/SegmentQuadCornerTurnMechanismTest.java   pins the length-routed corner/turn composition
    encoding/research/failed/ContextDeltaMechanismTest.java   pins the context build-up, both reset paths and the causal one-vertex lag
    encoding/research/failed/MidpointReflectMechanismTest.java   pins the reflection predictor's zero residual on a symmetric ring
    encoding/research/failed/EvenRingTurnMechanismTest.java   pins the parity gate; specification for the reimplemented encoder
```
