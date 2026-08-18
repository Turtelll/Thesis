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
`decode(EncodedBuffer, vertexCount)`. `decode` is mandatory, for the reason above.

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
shows the harness works before any new encoding is judged by it.

## Baseline

Baselines 1 and 2 from the root `README.md`: componentwise delta + zigzag over a
bit-aligned (`DELTA_ZIGZAG_FASTPFOR`) and a byte-aligned (`DELTA_ZIGZAG_VARINT`)
physical codec. Sizes are payload **plus** the `StreamMetadata` header MLT writes in
front of a stream, since that is what lands in the tile — an encoding that shrinks the
payload but needs a longer header has not necessarily won.

Baseline 3 (general-purpose Gzip/Zstd) is not currently implemented.

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
