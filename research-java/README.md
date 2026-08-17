# research-java

Extraction pipeline for the thesis baseline: flattens geometries from
`data/MVT/munich.mbtiles` into MLT-compatible `VertexBuffer`s, exactly as
`maplibre-tile-spec`'s `GeometryEncoder` would for a feature table's geometry
column (one buffer per MVT layer per tile). See the repo root `README.md` for
the research context.

## Prerequisites

- JDK 21+ (a Gradle toolchain will download one automatically if not on `PATH`)
- The `maplibre-tile-spec` git submodule must be populated:
  ```
  git submodule update --init --recursive
  ```

## Project setup

This project has its own Gradle wrapper — use it directly from `research-java/`:

```
./gradlew <task>          # Linux/macOS/Git Bash
.\gradlew.bat <task>       # Windows PowerShell / cmd
```

It depends on `mlt-core` via a Gradle composite build (`includeBuild` in
`settings.gradle`, pointing at `../maplibre-tile-spec/java`), so `mlt-core` is
built from source on the fly whenever you run a task here — no manual
`publishToMavenLocal` step needed, and the submodule itself is never modified.

## Build & test

```
./gradlew build
./gradlew test
```

`test` runs `VertexBufferExtractorFidelityTest`, which pins the extractor
against the real (private) `GeometryEncoder.prepareGeometry` via reflection —
this is what proves the extracted VertexBuffers match what MLT itself would
produce. If this test ever fails after updating the `maplibre-tile-spec`
submodule, the extractor's copied logic has drifted from upstream and needs
to be re-synced.

## Running the extractor

```
./gradlew run --args="--mbtiles <path> --out <dir> [--zoom minZ-maxZ] [--layer name[,name...]] [--limit N]"
```

Example — extract everything from the thesis dataset into `data/extracted/`
(gitignored):

```
./gradlew run --args="--mbtiles ../data/MVT/munich.mbtiles --out ../data/extracted"
```

Example — quick iteration on one zoom level and layer:

```
./gradlew run --args="--mbtiles ../data/MVT/munich.mbtiles --out /tmp/extract-test --zoom 14-14 --layer building --limit 20"
```

### Flags

| Flag | Required | Description |
|---|---|---|
| `--mbtiles <path>` | yes | Path to the source `.mbtiles` file |
| `--out <dir>` | yes | Output directory (created if missing) |
| `--zoom <minZ-maxZ>` | no | Zoom filter, inclusive. Single value (`--zoom 14`) means minZ=maxZ=14. Default: all zooms |
| `--layer <name[,name...]>` | no | Only extract these MVT layer names. Default: all layers |
| `--limit <N>` | no | Stop after writing N (tile, layer) extractions. Default: unlimited |

### Output format

For each (tile, layer) with at least one geometry, two files are written to
`--out`, named `<z>_<x>_<y>_<layer>.*`:

- **`.bin`** — the raw VertexBuffer as `int32` little-endian, `x,y`
  interleaved (`[x0, y0, x1, y1, ...]`). Undelta'd, unencoded — these are
  plain tile-local integer coordinates, the neutral starting point for any
  later encoding experiment.
- **`.json`** — sidecar with tile/layer identity and the topology streams
  needed to interpret the buffer: `tileExtent`, `geometryTypes`,
  `numGeometries`, `numParts`, `numRings`, `vertexCount`.

A single **`manifest.json`** is written once at the end, indexing every
extraction (`z`, `x`, `y`, `layerName`, `featureCount`, `vertexCount`,
`binFile`, `jsonFile`) for a benchmark harness to iterate over.

Layers with zero features (after filtering out features with no geometry) or
zero vertices are skipped and do not appear in the output.

## Project layout

```
research-java/
  gradlew, gradlew.bat, gradle/wrapper/   Gradle wrapper (self-contained, no external gradlew needed)
  settings.gradle             composite build -> ../maplibre-tile-spec/java (mlt-core)
  build.gradle
  src/main/java/org/maplibre/mlt/research/
    VertexBufferExtractor.java   core flattening logic (copied from GeometryEncoder)
    MbtilesSource.java           .mbtiles reading + decompression + TMS->XYZ
    ExtractionWriter.java        .bin/.json/manifest.json output
    ExtractCli.java              CLI entry point
  src/test/java/org/maplibre/mlt/research/
    VertexBufferExtractorFidelityTest.java   reflective pin + round-trip tests
```

## Notes on fidelity to MLT

- `VertexBufferExtractor` is a **verbatim copy**, not a reimplementation from
  the spec, of `GeometryEncoder.prepareGeometry` and its private helpers
  (`flatPolygon`, `flatLineString`, `ringToLineString`, `addLineString`,
  `containsPolygon`). It is copied rather than called because the original
  method is private in `mlt-core`.
- Two rules the MLT spec text does not state, but the extractor (matching the
  real encoder) applies:
  - Polygon/ring closing vertices are **dropped** — a ring's last coordinate,
    which duplicates the first, is not stored.
  - LineString vertex counts go to `numRings` if the column contains any
    (Multi)Polygon, otherwise to `numParts` — decided once per column, not
    per geometry.
- Vertex order matches feature order in the MVT layer (no reordering) —
  this mirrors MLT's default (non-sorted) feature table encoding.
