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
shows the harness works before any new encoding is judged by it. That is the current
state; see **Results** for encodings that have been measured and rejected.

## Results

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

### Would a tile ever pick one of these?

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

## Baseline

Baselines 1 and 2 from the root `README.md`: componentwise delta + zigzag over a
bit-aligned (`DELTA_ZIGZAG_FASTPFOR`) and a byte-aligned (`DELTA_ZIGZAG_VARINT`)
physical codec. Sizes are payload **plus** the `StreamMetadata` header MLT writes in
front of a stream, since that is what lands in the tile — an encoding that shrinks the
payload but needs a longer header has not necessarily won.

Baseline 3 (general-purpose Gzip/Zstd) is not currently implemented.

`research()` is currently empty — see **Results** for what has been measured and rejected.

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
    extraction/
      VertexBufferExtractor.java   core flattening logic (copied from GeometryEncoder)
      MbtilesSource.java           .mbtiles reading + decompression + TMS->XYZ
      ExtractionWriter.java        .bin/.json/manifest.json output
      ExtractCli.java              extract-step CLI (default mainClass)
    encoding/
      Encoding.java            name + encode + decode; the whole contract
      VertexTransform.java     logical layer, List<Vertex> <-> int[] (+ ZigZagDelta)
      IntCodec.java            physical layer, int[] <-> byte[] (+ Varint, FastPfor)
      VertexBufferCodec.java   one transform + one codec = an Encoding; also holds
                                 BufferContext and EncodedBuffer
      StreamMetadata.java      size of the header MLT writes in front of a stream
      Baselines.java           the two baselines + the research registry
  src/test/java/org/maplibre/mlt/research/
    extraction/VertexBufferExtractorFidelityTest.java   reflective pin against mlt-core
    encoding/IntCodecTest.java            physical-layer round trips
    encoding/VertexTransformTest.java     logical-layer round trips
    encoding/VertexBufferCodecTest.java   round trips every registered encoding
```
