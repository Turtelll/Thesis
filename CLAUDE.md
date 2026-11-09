# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repository is

Research repository for a Bachelor Thesis on finding new encoding strategies to
reduce MapLibre Tile (MLT) size for `VertexBuffer`s and `IndexBuffer`s (vector
tile geometry data). `README.md` is the source of truth for research intent —
read it before starting work. Every encoding tried so far (what won, what
regressed, and why) is documented in `research-java/docs/results/` — check it
before re-deriving a result.
The extraction/encoding pipeline lives in `research-java/`; `research-java/README.md`
covers extraction, the measurement harness, and adding a new encoding; `research-java/docs/`
holds architecture, the evaluation protocol, the bitstream, and the results log in detail.

## Commands (from `research-java/`)

Everything runs through one Gradle task, `research`, one command per measurement or tool:
`./gradlew research --args="<command> ..."` (`./gradlew research` lists the commands; each command
prints its own usage on `--help`).

```
./gradlew test # unit + round-trip + property + architecture tests
./gradlew research --args="extract --mbtiles ../data/MVT/<tileset>.mbtiles --out ../data/extracted" # extract VertexBuffers
./gradlew research --args="measure" # = --step layer: baselines vs DEFAULT vs the per-layer default (forLayer), per tile under gzip/brotli/zstd (defaults to ../data/extractedBaseline)
./gradlew research --args="measure ../data/extracted --csv out.csv"
./gradlew research --args="measure --layer transportation --by-zoom" # gzip bytes per tile zoom
./gradlew research --args="measure --step s8 --by-zoom" # one step per run: s0..s8, selector
./gradlew research --args="measure --step ladder --by-zoom" # the pipeline built up decision by decision, sizes only
./gradlew research --args="measure --step layer --by-zoom" # the per-layer default (PipelineRegistry.forLayer) vs the single DEFAULT row
./gradlew research --args="measure --step tile --by-zoom" # one composition per (tile, layer) out of the 48, vs the per-layer default
./gradlew research --args="measure --step tile --trials gzip,order --features build/tile-features.csv" # opt-in selector report: cheap-feature selectors
./gradlew research --args="measure --step frame --layer building --by-zoom" # ring frame prior (QUAD_CORNER_FRAME) beside QUAD_CORNER on building
./gradlew research --args="measure --step framelines --layer transportation --by-zoom" # frame prior and heading frame on line parts
./gradlew research --args="measure --step oracle" # per-buffer best of 32 compositions vs ref (--oracle does the same for any other --step)
./gradlew research --args="measure --step best --by-zoom" # real oracle: best of 96 compositions (every predictor x stages x reorder) per (tile, layer), alone and trialled on the tile (--search gzip,zstd searches under more backends, --size-all sizes the choices under gzip/brotli/zstd, --exclude TURN_SCALAR,CHAIN,.. takes options out of the grid; the run prints per-option pick shares, drop cost and a greedy elimination order)
./gradlew research --args="tile-size ../data/MVT/munich-plantiler.mbtiles --by-layer" # whole-tile MLT bytes, the property side of the pipeline reorder
./gradlew research --args="dump --out ../data/golden/<name>" # golden master: SHA-256 and sizes of every row on every buffer
./gradlew research --args="dump --diff ../data/golden/<old> ../data/golden/<new>" # compare two dumps; any changed byte of a kept row is a regression
./gradlew research --args="dump --grid best --out ../data/golden/<name>" # dump the 96 compositions of bestGrid() instead of the registry rows (golden reference of the Rust port)
./gradlew research --args="workshop --layer building --by-zoom" # prediction workshop: Workshop predictors over Varint, mean/median |x|,|y| residual, Varint + per-tile gzip vs baselines
./gradlew research --args="residual-bits --layer building" # probe: mean residual bits, zero pairs, per-vertex change vs DELTA_ZIGZAG
./gradlew research --args="decode-time ../data/extractedBaseline --encoding <ROW> --layer transportation" # decode time vs baseline, flags rows over --max-ratio (default 1.5)
./gradlew research --args="shared-vertex ../data/extractedBaseline --layer transportation" # per-zoom share of vertices repeating a position within a buffer
./gradlew research --args="turn-radius ../data/extractedBaseline --layer transportation" # per-zoom turn radius/curvature
./gradlew research --args="orientation-prior ../data/extractedBaseline --layer building" # ring frame predicted from decoded neighbours vs plain delta
./gradlew research --args="line-frame-prior ../data/extractedBaseline ../data/extracted-chicago --layer transportation" # line-run edges in a predicted frame vs plain delta
./gradlew research --args="buffer-point ../data/MVT/munich-plantiler.mbtiles" # MLT bytes with vs without buffer points (outside tile extent)
```

The Rust port of the tile-oracle pipeline as the MLT v2 vertex encoder is in `research-rust/`
(`research-rust/README.md`; results in `research-java/docs/results/mlt-v2-rust-port.md`). It needs the
Rust toolchain (rustup stable, MSVC), run from `research-rust/`:

```
cargo test -p mlt-pipeline
cargo build --release -p mlt-oracle
mlt-oracle golden-check --golden ../data/golden/<dump> # Rust codec vs the Java golden dump, bit for bit (dump with --grid best)
mlt-oracle compare --mbtiles ../data/MVT/munich-plantiler.mbtiles --by-zoom --by-layer # whole-tile MLT v2 gzip: stock vs the tile oracle, with the decomposition and the geometry check
mlt-oracle compare --mbtiles ../data/MVT/munich-plantiler.mbtiles --write-tiles target-results/tiles # also write the stock and oracle tiles; python check_tiles.py <dir> re-measures them
mlt-oracle decode-time target-results/munich-stock.mbtiles target-results/munich-oracle.mbtiles --by-zoom # whole-tile decode time of MLT .mbtiles (min of --reps, decompression untimed), first archive (stock v2, `mlt convert --mlt-version 2`) as reference
```

The same encoder is in the `mlt` CLI (`research-rust/mlt-cli-pipeline`, a patched copy of the submodule's `mlt`), to encode whole
tilesets and look at them in the terminal visualizer:

```
cargo build --release -p mlt-cli-pipeline # target/release/mlt
mlt convert ../data/MVT/munich-plantiler.mbtiles out.mbtiles --mlt-version 2 --vertex-pipeline oracle --verify # off | default | oracle | one composition name; the tile oracle for the whole tileset
mlt ui out.mbtiles # terminal visualizer, the world map of an MLT .mbtiles (also: mlt ui tile.mlt, mlt ui <directory>, mlt ls/decode)
python check_convert.py out.mbtiles target-results/tiles # converted tiles byte for byte against compare --write-tiles
mlt-oracle diff ../data/MVT/munich-plantiler.mbtiles out.mbtiles # completeness: both tilesets (MVT/MLT, stock/pipeline) hold the same tiles, layers, features (order, ring start, line direction free)
```

`research-rust/mlt-core-pipeline` and `research-rust/mlt-cli-pipeline` are patched copies of the submodule's `mlt-core` and `mlt` (the
submodule stays untouched); `research-rust/mlt-tile-oracle` is the shared tile-oracle library.

Probes and mechanisms removed in the consolidation (rANS and its probes, road chain/merge, the old
`BenchmarkCli` and the `Baselines.research()` rows, the entropy/ring-shape probes) are at git tag
`pre-consolidation`; `research-java/docs/consolidation-inventory.md` lists each with the reason.

Single test class/method: `./gradlew test --tests "org.maplibre.mlt.research.pipeline.PipelineEncodingTest"`.

On Windows use `./gradlew` directly — `just` may not be installed.

## Architecture

`research-java` is a standalone Gradle project (JDK 21) that depends on `mlt-core`
via a composite build (never published or modified). Full package-by-package
walkthrough: `research-java/docs/architecture.md`; the wire format: `research-java/docs/bitstream.md`.
`ArchitectureTest` enforces the layering below (`cli` > `probe` > `workshop` > `measurement` > `registry`/`baseline`/`extraction` > `encoder` > `buffer`; the encoder never depends on measurement).

The encoder side is what a decoder needs; the layers above it measure.

- **`buffer/`** — the `Encoding` contract (`name`/`encode`/`decode`, round-trip mandatory) with its
  `BufferContext`/`EncodedBuffer`, `StreamMetadata`, and the runs (`VertexRuns`, `VertexRunIndex`).
- **`encoder/`** — the only encoder stack, one package per bitstream stage: `reorder/` (`Reorder`,
  `GeometryReorder`), `prediction/` (`Prediction`, what is subtracted: delta, `QUAD_CORNER`,
  `LINE_CONTINUATION`, the frame and shared-reference variants) with `prediction/mechanism/` (the
  mechanisms the predictions are built from: `SegmentQuadCorner`, `LineContinuation`,
  `FramePrior`, `ClassifiedPredictor`), `retired/` (`QuadCornerLine`, `TurnScalar`: the options the tile
  oracle showed to be dead weight, out of the grids and the registry), `reference/`
  (`RingReferences`, `LineReferences`), `segmentation/` (how residuals are split into streams),
  `stream/` (`StreamCodec`, `ResidualMap`, `ByteWidth`, `Transpose`, `IntCodec`) and `format/`
  (`Composition`, `PayloadHeader`, `PipelineEncoding`: the header that makes a payload
  self-describing). Each buffer layout is tuned individually; the output is gzip/Brotli/zstd
  compressed per tile.
- **`baseline/`** — the baselines (`Baselines.baseline()`); `VertexBufferCodec`/`VertexTransform` are
  the baseline composition only. rANS and FastPFOR are retired as research directions: the target is
  compressed bytes per tile against the smaller baseline under gzip; see
  `research-java/docs/results/general-purpose-compression.md`.
- **`registry/`** — `PipelineRegistry` (the stage chain, `DEFAULT`, `step(name)`, `all()`),
  `LayerCompositions` (the per-layer default `forLayer(layer)`, the only place with MVT layer
  names), `CompositionGrids` (the 32-row oracle grid, the 96-composition `bestGrid()`, the fit grid)
  and `StepRows`.
- **`extraction/`** — `.mbtiles` → `VertexBuffer` + topology-stream sidecar.
- **`measurement/`** — `harness/MeasureHarness` (`measure`: encodes baselines + rows, verifies
  round-trips, prints baselines-vs-oracles and per-encoding tables compressed per tile plus
  per-layer/per-geometry-type/per-zoom breakdowns), `compression/`, `verification/`, `selection/`,
  `report/`, `stats/` and `golden/` (`GoldenDump`, `GoldenDiff`).
- **`workshop/`** — fast predictor experiments: a `Predictor` is a name plus a one-line rule over
  the decoded prefix and a per-vertex `VertexRunIndex`; `Workshop.known()` holds the existing
  predictors as pipeline `Prediction` rows (`PredictionTransform`), `Workshop.experiments()` new ones.
  `WorkshopReport` (`research workshop`) reports residual size, Varint and per-tile gzip bytes vs baselines.
- **`probe/`** — the measurement-only probes (`ResidualBitsProbe`, `SharedVertexProbe`,
  `TurnRadiusProbe`, `OrientationPriorProbe`, `LineFramePriorProbe`, `DecodeTimeProbe`,
  `BufferPointProbe`, `TileSizeProbe`) — no encoder, no bytes written.
- **`cli/`** — `Research` (the dispatcher) and one thin `<Command>Cli` per command (`MeasureCli`,
  `DumpCli`, `ExtractCli`, `WorkshopCli`, the probe `*Cli`s) that only parses the arguments and
  calls the engine above it.
`data-analysis/` (Python 3.13) analyzes raw `.mbtiles` tilesets independently of
the Java pipeline — see `data-analysis/README.md`; large tilesets take 40-60 min
to scan, so don't rerun without `--force`.

`tools/map-viewer/` is an independent Node/TypeScript + MapLibre GL JS app for visually
inspecting an `.mbtiles` tileset in a browser; see its own README.

## Conventions

- `maplibre-tile-spec/` is a **git submodule** (`Turtelll/maplibre-tile-spec`,
  a fork of the upstream spec). Not populated by a plain clone — run
  `git submodule update --init --recursive` first. It is included for
  reference, for reusing its converter/encoder code (copy-paste basis — the
  baseline encoders live under
  `maplibre-tile-spec/java/mlt-core/src/main/java/org/maplibre/mlt/converter/encodings/`),
  and for comparison — it is **not** to be modified or reformatted as part of
  the thesis work, and its pinned commit must not be bumped unless explicitly
  asked.
- `data/extracted*/` is gitignored — do not commit generated baseline
  extractions, encodings, or reports.
- Keep small nested types (e.g. `Vertex`, geometry-type enums, small metadata
  records) inside their owning class rather than splitting them into separate
  files.
- Every new encoding must round-trip losslessly (encode → decode → compare
  against the MVT extraction); this is enforced automatically by the harness
  (`research measure`), by `PipelineEncodingTest` and by the jqwik property
  `PipelineRoundTripProperties`, not optional per-encoding test-writing.
- A change to a kept row must leave its golden-dump bytes unchanged
  (`research dump --diff`); a deliberate change needs the new figures written into
  `research-java/docs/results/` in the same commit.
