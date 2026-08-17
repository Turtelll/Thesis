# research-java

Baseline pipeline for the thesis: extracts geometries from `data/MVT/munich.mbtiles`
into MLT-compatible `VertexBuffer`s (exactly as `maplibre-tile-spec`'s
`GeometryEncoder` would for a feature table's geometry column, one buffer per MVT
layer per tile), then encodes them with the baseline encodings every later research
encoding will be benchmarked against. See the repo root `README.md` for the research
context.

## Prerequisites

- JDK 21+ (a Gradle toolchain will download one automatically if not on `PATH`)
- The `maplibre-tile-spec` git submodule must be populated:
  ```
  git submodule update --init --recursive
  ```

This project has its own Gradle wrapper — use it directly from `research-java/`
(`./gradlew` on Linux/macOS/Git Bash, `.\gradlew.bat` on Windows PowerShell/cmd). It
depends on `mlt-core` via a Gradle composite build (`includeBuild` in
`settings.gradle`, pointing at `../maplibre-tile-spec/java`), so `mlt-core` is built
from source on the fly whenever you run a task here — no manual
`publishToMavenLocal` step needed, and the submodule itself is never modified.

## Build & test

```
./gradlew build
./gradlew test
```

Tests include round-trip checks for every encoding (encode, decode, compare against
the original extraction) and `VertexBufferExtractorFidelityTest`, which pins the
extractor against the real (private) `GeometryEncoder.prepareGeometry` via
reflection — this is what proves the extracted VertexBuffers match what MLT itself
would produce. If that test ever fails after updating the `maplibre-tile-spec`
submodule, the extractor's copied logic has drifted from upstream and needs to be
re-synced.

## Pipeline

### 1. Extract

```
./gradlew run --args="--mbtiles <path> --out <dir> [--zoom minZ-maxZ] [--layer name[,name...]] [--limit N]"
```

Example — extract everything from the thesis dataset into `data/extracted/`
(gitignored):

```
./gradlew run --args="--mbtiles ../data/MVT/munich.mbtiles --out ../data/extracted"
```

| Flag | Required | Description |
|---|---|---|
| `--mbtiles <path>` | yes | Path to the source `.mbtiles` file |
| `--out <dir>` | yes | Output directory (created if missing) |
| `--zoom <minZ-maxZ>` | no | Zoom filter, inclusive. Single value (`--zoom 14`) means minZ=maxZ=14. Default: all zooms |
| `--layer <name[,name...]>` | no | Only extract these MVT layer names. Default: all layers |
| `--limit <N>` | no | Stop after writing N (tile, layer) extractions. Default: unlimited |

For each (tile, layer) with at least one geometry, two files are written to `--out`,
named `<z>_<x>_<y>_<layer>.*`:

- **`.bin`** — the raw VertexBuffer as `int32` little-endian, `x,y` interleaved
  (`[x0, y0, x1, y1, ...]`). Undelta'd, unencoded — plain tile-local integer
  coordinates, the neutral starting point for any encoding experiment.
- **`.json`** — sidecar with tile/layer identity and the topology streams needed to
  interpret the buffer: `tileExtent`, `geometryTypes`, `numGeometries`, `numParts`,
  `numRings`, `vertexCount`.

A single **`manifest.json`** is written once at the end, indexing every extraction
(`z`, `x`, `y`, `layerName`, `featureCount`, `vertexCount`, `binFile`, `jsonFile`)
for the encode step (or any benchmark harness) to iterate over. Layers with zero
features or zero vertices are skipped and do not appear in the output.

### 2. Encode

```
./gradlew run -PmainClass=org.maplibre.mlt.research.EncodeCli --args="--in <extractionDir> --out <dir>"
```

Example — encode everything previously extracted into `data/extracted/`:

```
./gradlew run -PmainClass=org.maplibre.mlt.research.EncodeCli --args="--in ../data/extracted --out ../data/extracted-encoded"
```

| Flag | Required | Description |
|---|---|---|
| `--in <dir>` | yes | Extraction directory produced by the extract step (must contain `manifest.json`) |
| `--out <dir>` | yes | Output directory (created if missing) |

For each (tile, layer) listed in the input `manifest.json`, this runs every
`VertexBufferCodec.Baseline` variant (currently `DELTA_ZIGZAG_FASTPFOR` and
`DELTA_ZIGZAG_VARINT` — componentwise delta + zigzag, per the root `README.md`'s
baseline definition) and writes one `<basename>.<VARIANT>.bin` per variant, plus a
single `encoded-manifest.json` recording `vertexCount`/`encodedBytes` per (tile,
layer, variant) for benchmarking against the raw `.bin` size.

## Project layout

```
research-java/
  gradlew, gradlew.bat, gradle/wrapper/   Gradle wrapper (self-contained)
  settings.gradle             composite build -> ../maplibre-tile-spec/java (mlt-core)
  build.gradle
  src/main/java/org/maplibre/mlt/research/
    EncodeCli.java                encode-step CLI entry point
    extraction/
      VertexBufferExtractor.java   core flattening logic (copied from GeometryEncoder)
      MbtilesSource.java           .mbtiles reading + decompression + TMS->XYZ
      ExtractionWriter.java        .bin/.json/manifest.json output
      ExtractCli.java              extract-step CLI entry point (default mainClass)
    encoding/
      IntCodec.java            physical-layer contract, int[] <-> byte[]; nested
                                 IntCodec.Varint (byte-aligned) and IntCodec.FastPfor
                                 (bit-aligned) implementations, both delegating to
                                 mlt-core's EncodingUtils/DecodingUtils
      VertexTransform.java     logical-layer contract, List<Vertex> <-> int[]; nested
                                 VertexTransform.ZigZagDelta implementation
                                 (componentwise delta+zigzag, delegates to
                                 GeometryEncoder)
      VertexBufferCodec.java   pairs one VertexTransform with one IntCodec; nested
                                 enum VertexBufferCodec.Baseline registers the
                                 required baseline variants
  src/test/java/org/maplibre/mlt/research/
    extraction/
      VertexBufferExtractorFidelityTest.java   reflective pin + round-trip tests
    encoding/
      IntCodecTest.java            physical-layer round-trip tests
      VertexTransformTest.java     logical-layer round-trip tests
      VertexBufferCodecTest.java   end-to-end round-trip against real extractions
```

Adding a new research encoding: implement whichever side of `VertexBufferCodec` you
are changing — a new predictor is a `VertexTransform`, a new bit-packing scheme is an
`IntCodec` — and reuse the other side. Register the new combination as another entry
in `VertexBufferCodec.Baseline` (or a sibling registry) rather than branching inside
existing classes.

## Notes on fidelity to MLT

- `VertexBufferExtractor` is a **verbatim copy**, not a reimplementation from the
  spec, of `GeometryEncoder.prepareGeometry` and its private helpers
  (`flatPolygon`, `flatLineString`, `ringToLineString`, `addLineString`,
  `containsPolygon`). It is copied rather than called because the original method
  is private in `mlt-core`.
- Two rules the MLT spec text does not state, but the extractor (matching the real
  encoder) applies:
  - Polygon/ring closing vertices are **dropped** — a ring's last coordinate, which
    duplicates the first, is not stored.
  - LineString vertex counts go to `numRings` if the column contains any
    (Multi)Polygon, otherwise to `numParts` — decided once per column, not per
    geometry.
- Vertex order matches feature order in the MVT layer (no reordering) — this
  mirrors MLT's default (non-sorted) feature table encoding.
