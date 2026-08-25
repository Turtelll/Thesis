# research-java

A lightweight harness for testing VertexBuffer encoding ideas against the baseline.
It extracts geometries from `data/MVT/munich.mbtiles` into MLT-compatible
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
./gradlew run --args="--mbtiles ../data/MVT/munich.mbtiles --out ../data/extracted"
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

## Baseline

Baselines 1 and 2 from the root `README.md`: componentwise delta + zigzag over a
bit-aligned (`DELTA_ZIGZAG_FASTPFOR`) and a byte-aligned (`DELTA_ZIGZAG_VARINT`)
physical codec. Sizes are payload **plus** the `StreamMetadata` header MLT writes in
front of a stream, since that is what lands in the tile — an encoding that shrinks the
payload but needs a longer header has not necessarily won.

Baseline 3 (general-purpose Gzip/Zstd) is not currently implemented.

`research()` currently holds `SEGMENT_PAIR_FASTPFOR` and the composition
`SEGMENT_QUAD_CORNER_FASTPFOR` — see **Results** for what they do, and for what has been
measured and rejected.

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
    BuildingShapeCli.java         characterize geometric properties of >4-vertex building rings (encodes nothing)
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
        RightAngleTurn.java    >4-ring 90°-turn predictor with a signalled cw/ccw bit stream (regression)
        SegmentQuadCornerTurn.java  quad-corner for 4-rings + signalled 90°-turn for >4-rings, 4 regions (regression)
        ContextDelta.java      run-length context over recent deltas picks predictor + FastPFOR region (weak win)
  src/test/java/org/maplibre/mlt/research/
    extraction/VertexBufferExtractorFidelityTest.java   reflective pin against mlt-core
    encoding/IntCodecTest.java            physical-layer round trips
    encoding/VertexTransformTest.java     logical-layer round trips
    encoding/VertexBufferCodecTest.java   round trips every registered encoding
    encoding/research/GeometryReorderMechanismTest.java   pins the chain, the topology rebuild and the equivalence gate
    encoding/research/GeometryReorderGridTest.java        pins the spatial index against a brute-force chain
    encoding/research/SegmentPairMechanismTest.java   pins SegmentPair's grouping and decode
    encoding/research/SegmentQuadCornerMechanismTest.java   pins the composition reduces to each part
    encoding/research/RightAngleTurnMechanismTest.java   pins the signalled 90°-turn predictor + decode
    encoding/research/SegmentQuadCornerTurnMechanismTest.java   pins the length-routed corner/turn composition
    encoding/research/ContextDeltaMechanismTest.java   pins the context build-up, both reset paths and the causal one-vertex lag
```
