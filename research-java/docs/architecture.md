# Architecture

`research-java` is a standalone Gradle project (JDK 21) that depends on `mlt-core`
via a composite build (`includeBuild('../maplibre-tile-spec/java')` in
`settings.gradle`, substituting `org.maplibre:mlt`) — `mlt-core` builds from
source on every run, so the submodule is never published or modified.

There is **one encoder stack**, `encoder/` (composed by `registry/`), and **one entry point**,
`./gradlew research --args="<command> ..."` (`cli/Research`; `./gradlew research` lists the commands).
The pre-consolidation code (the per-idea `Encoding` classes, `BenchmarkCli`, the
FastPFOR/rANS rows, the one-off probes) is at git tag `pre-consolidation`; the plan, the inventory and
the deviations are in [consolidation-inventory.md](consolidation-inventory.md).

## Packages

The packages follow the stages of the bitstream ([bitstream.md](bitstream.md)) for the encoder, and
sit above it for everything that measures. Under `org.maplibre.mlt.research`:

**The encoder side** — what a decoder needs; none of it knows about measurement, compression
backends, extraction or the command line.

- **`buffer/`** — the contract and the shape of the data. `Encoding`: `name()`,
  `encode(BufferContext)`, `decode(EncodedBuffer, BufferContext, vertexCount)`, with `BufferContext`
  and `EncodedBuffer` nested in it. `decode` is mandatory and must not read `ctx.vertices()`, since
  every run round-trips every row on every buffer and fails loudly on mismatch — an unverified
  encoding is not a result. `BufferContext` carries the topology streams alongside the vertices, so a
  predictor derives structural reset points (part starts, ring corners) identically on encode and
  decode without signalling anything: `VertexRuns` is the list of runs (rings, line parts, points),
  `VertexRunIndex` the per-vertex view of them. `StreamMetadata` is the header MLT writes in front of
  a stream.
- **`encoder/reorder/`** — S1, not in the payload: `Reorder` (the geometry orders) and
  `GeometryReorder` (what applies them).
- **`encoder/prediction/`** — S2: `Prediction`, the predictor ordinal the header carries.
  **`encoder/prediction/mechanism/`** holds the mechanisms the predictions are built from, each a
  self-contained idea with its own mechanism test: `ClassifiedPredictor` (a per-vertex class plus what
  each class predicts), `SegmentQuadCorner`, `QuadCornerLine`, `LineContinuation`, `TurnScalar` and the
  ring/line frame prior `FramePrior`. Nothing here is an `Encoding`.
- **`encoder/reference/`** — the shared-reference block: `RingReferences` and `LineReferences` (the
  references to an earlier ring's or line part's positions).
- **`encoder/segmentation/`** — S3: `Segmentation`, how the residuals are split into streams and
  in what order.
- **`encoder/stream/`** — S4/S6/S7 and the byte terminals: `ResidualMap` (zigzag only), `ByteWidth`,
  `Transpose`, `StreamCodec` (map, width and transpose of one stream), `IntCodec` (`int[] <-> byte[]`)
  and `PayloadBytes` (varint lengths and the payload cursor).
- **`encoder/format/`** — the self-describing payload: `Composition` is one row (the per-stage
  choices plus the reorder), `PayloadHeader` the two-byte header, `PipelineEncoding` its `Encoding`.
  Every choice except the reorder is in the header and stream lengths follow from the topology.
- **`baseline/`** — the **two baselines** (`DELTA_ZIGZAG_FASTPFOR`, `DELTA_ZIGZAG_VARINT`,
  `Baselines.baseline()`): `VertexTransform` (`List<Vertex> <-> int[]`) and an `IntCodec` composed
  in a `VertexBufferCodec`.
- **`registry/`** — the compositions. `PipelineRegistry` holds the stage chain (`AFTER_S0` ...
  `DEFAULT`, the end-to-end choice), the step switch (`step(name)`), `ladder()` and `all()`.
  `LayerCompositions` is the per-layer default (`forLayer`) and the only place where the MVT layer
  names appear, as constants. `CompositionGrids` holds the fit grid, the 48-row oracle grid, the
  252-composition `bestGrid()` of the per-(tile, layer) oracle and the selectors' trial sets.
  `StepRows` builds the multi-row steps (`turn`, `frame`, `shared`, `framelayers`, `framelines`,
  `linesshared`, S3, S6-S8).

**Above the encoder**

- **`extraction/`** — `MbtilesExtraction` (the `extract` command) reads an `.mbtiles` (via
  `MbtilesSource`) and `VertexBufferExtractor` turns each (tile, layer) into a `VertexBuffer` exactly
  as `maplibre-tile-spec`'s `GeometryEncoder` would (interleaved little-endian `int32`
  `[x0,y0,x1,y1,...]`, undelta'd, unencoded). `ExtractionWriter` writes the `.bin` +
  `.json` sidecar (topology streams: `tileExtent`, `geometryTypes`, `numParts`,
  `numRings`, `vertexCount`) and a `manifest.json` indexing the run; `ExtractionReader` reads
  them back; `Corpus` is the tile and layer naming the measurements share. See
  [mlt-fidelity.md](mlt-fidelity.md) for how this stays pinned to upstream.
- **`measurement/`** — everything that sizes, compares or verifies, never part of the payload:
  - `compression/`: `Compressor` (gzip, Brotli, zstd) and `TileCompression` (one tile's buffers
    concatenated and compressed one-shot).
  - `verification/`: `RoundTrip` (checks a row's round trip, also for reordering rows and point
    columns) and `GeometryEquivalence` (the order-independent geometry check).
  - `selection/`: `BufferSelector` and `TileSelector` (the opt-in selection reports; `TileSelector.oracle`
    is the `--step best` oracle over `bestGrid`), `FeatureRules`, `BufferFeatures`.
  - `report/`: `OracleReport`, `SelectionReport`, `ChoiceCorrelation`, the report halves of `measure`.
  - `stats/`: `StepProbes`, each step's probes.
  - `harness/`: `MeasureHarness`, the `measure` command's engine (an `Options` record in, tables out).
  - `golden/`: `GoldenDump` (SHA-256 and sizes of every row on every buffer) and `GoldenDiff`.
- **`workshop/`** — fast predictor experiments: a `Predictor` is a name plus a one-line rule over the
  decoded prefix and a per-vertex `VertexRunIndex`; `Workshop.known()` holds the pipeline's predictors
  through `PredictionTransform`, `Workshop.experiments()` the new ones; `WorkshopReport` is the
  `workshop` command's engine.
- **`probe/`** — the measurement-only probes (no encoder, no bytes written): `ResidualBitsProbe`,
  `SharedVertexProbe`, `TurnRadiusProbe`, `OrientationPriorProbe`, `LineFramePriorProbe`,
  `DecodeTimeProbe`, `BufferPointProbe`, `TileSizeProbe`. Each has a `run(...)` over plain
  arguments or an `Options` record.
- **`cli/`** — `Research` dispatches the commands (`extract`, `measure`, `tile-size`, `dump`,
  `workshop`, `decode-time`, `residual-bits`, `shared-vertex`, `turn-radius`, `orientation-prior`,
  `line-frame-prior`, `buffer-point`); each has a `<Command>Cli` that only parses the arguments and
  calls the engine above it.

`data-analysis/` (Python 3.13, stdlib + numpy/matplotlib) analyzes raw `.mbtiles`
tilesets independently of the Java pipeline (`mvt_stats.py` for byte composition,
`vertex_stats.py` for vertex distribution — counting rule verified to match
`ExtractCli`'s VertexBuffer count exactly). See `data-analysis/README.md` for usage;
caches to `data-analysis/out/*.json` (gitignored), and large tilesets take 40-60 min to
scan, so don't rerun without `--force`.

`tools/map-viewer/` is an independent Node/TypeScript + MapLibre GL JS app for visually
inspecting an `.mbtiles` tileset in a browser; see its own README.

## Pipeline stages

The encoder is composed of stages after the [OpenZL-style design](results/DesingDocOpenzl.md):
`Reorder` (S1) -> `Prediction` (S2, pure predictors) -> `Segmentation` (S3) -> `ResidualMap`
(S4, zigzag only) -> `ByteWidth` (S6) -> `Transpose` (S7), then the general-purpose backend (S8,
applied per tile by `measure`). S5 (a per-stream GCD) was measured and removed; the later stage
numbers are unchanged. One `Composition` is one row, `PipelineEncoding` its `Encoding`.

## Baseline

Two baselines, both componentwise delta + zigzag, differing only in the physical codec:
`DELTA_ZIGZAG_FASTPFOR` (bit-aligned) and `DELTA_ZIGZAG_VARINT` (byte-aligned). Sizes are
payload **plus** the `StreamMetadata` header MLT writes in front of a stream, since that
is what lands in the tile — an encoding that shrinks the payload but needs a longer
header has not necessarily won.

General-purpose compression (gzip, Brotli, zstd) is not an `Encoding`. `measure` applies it as the
last stage **per tile**: every buffer of one tile is concatenated (one tile is one HTTP response)
and compressed on its own, one-shot, and the sizes are summed over tiles (`TileCompression`). The
reference for every percentage is `DELTA_ZIGZAG_VARINT` under gzip, the smaller baseline.

`Baselines` has one list now: `baseline()` (`all()` is the same list).

See [results/README.md](results/README.md) for what the pipeline's stages do and for what has been
measured and rejected.

## Dependency rules

`ArchitectureTest` enforces the layering (an arrow means "may depend on"):

```
cli -> probe -> workshop -> measurement -> registry -> encoder
                                 |  \-> baseline -> encoder
                                 \----> extraction -> buffer
encoder -> buffer
```

- every layer (`buffer`, `encoder`, `baseline`, `extraction`, `registry`, `measurement`, `workshop`,
  `probe`, `cli`) is only accessed from the layers above it (`cli` from none, `probe` from `cli`,
  `workshop` from `probe` and `cli`, ...);
- inside `encoder`, `format` is above `segmentation`, above `prediction`, above `reference`, above
  `reorder`; `stream` is used by `format` and `segmentation` only;
- no two packages depend on each other in a cycle;
- `buffer` and `encoder` only depend on each other, `java.lang`/`util`/`io`/`nio` and mlt-core
  (`converter`, `decoder`, the FastPFOR integer compression it uses), so a decoder needs no
  measurement code;
- `buffer`, `encoder`, `baseline` and `registry` never depend on measurement, probes, the
  workshop, the command line, extraction or a compression backend (brotli4j, zstd, `java.util.zip`);
- nothing outside `cli` depends on `cli`.

The package graph (jdeps) has 21 packages, 101 edges and no cycle. At class level the four classes of
`registry` reference each other (`PipelineRegistry` holds the stage chain and the step switch, the
other three the per-layer defaults, the grids and the step rows built on that chain); the cycle
stays inside the package and no static initialiser runs in a loop.

A restructure commit never changes a byte: the golden dump (`research dump --diff`) of the tree
before and after must show zero changed rows.

## Known duplication

PMD CPD over `src/main` (the restructure's before/after, `--minimum-tokens 50 / 75`): exact-token
duplicates 38 / 16 before, 36 / 12 after; with identifiers and literals ignored 92 / 32 before, 93 / 28
after. Duplication between the probes and the mechanisms is zero on exact tokens at both thresholds
and one block at 50 tokens with identifiers ignored. The copies that were byte for byte the same
(the angle fold and the zigzag varint bytes of `LineFramePriorProbe` and `OrientationPriorProbe`) are
`probe/ProbeMath`; the argument parser of the per-layer probes is `cli/ExtractionArgs`. What is left is
kept on purpose:

- **Probe against probe** (`LineFramePriorProbe`/`OrientationPriorProbe`: the nearest-point grid and
  the frame-error tables; `SharedVertexProbe`/`TurnRadiusProbe`: the manifest walk over one layer).
  Each probe is a measurement whose printed numbers are quoted in `docs/results/`; the copies differ
  in element type or in what is accumulated, so a shared helper would have to be generic over both,
  and a change in either would move a published figure. A probe stays self-contained.
- **Probe against mechanism** (`OrientationPriorProbe` against `FramePrior`: the nearest-ring
  search). The probe measures the idea independently of the mechanism it motivated, on purpose: it must
  not change when the mechanism does.
- **Inside one class** (`StepRows`, `TileSizeProbe`, `OracleReport`, `MeasureHarness`,
  `DecodeTimeProbe`, `StepProbes`): table-printing and grid-building loops that differ in one
  constant per copy.
- **`buffer`/`encoder` mechanisms among themselves** (`VertexRuns`, `GeometryReorder`, `Reorder`,
  `LineContinuation`, `QuadCornerLine`, `TurnScalar`, `RingReferences`, `LineReferences`): each
  re-derives the runs of a buffer, and they disagree on single-run buffers. They are not merged,
  because merging would change the bytes of a kept row; the golden dump pins each one.

## Project layout

```
research-java/
  settings.gradle             composite build -> ../maplibre-tile-spec/java (mlt-core)
  build.gradle                research (JavaExec, -Xmx6g), test, run
  golden/                     manifest of the pre-consolidation golden dump (the dumps themselves are in data/)
  src/main/java/org/maplibre/mlt/research/
    buffer/                   Encoding (+ BufferContext, EncodedBuffer), StreamMetadata, VertexRuns, VertexRunIndex
    encoder/
      reorder/                Reorder, GeometryReorder
      reference/              RingReferences, LineReferences
      prediction/             Prediction
        mechanism/            ClassifiedPredictor, SegmentQuadCorner, QuadCornerLine, LineContinuation,
                                TurnScalar, FramePrior
      segmentation/           Segmentation
      stream/                 StreamCodec, ResidualMap, ByteWidth, Transpose, IntCodec, PayloadBytes
      format/                 Composition, PayloadHeader, PipelineEncoding
    baseline/                 Baselines, VertexBufferCodec, VertexTransform
    extraction/               MbtilesSource, VertexBufferExtractor, ExtractionWriter, ExtractionReader,
                                MbtilesExtraction, Corpus
    registry/                 PipelineRegistry, LayerCompositions, CompositionGrids, StepRows
    measurement/              compression/, verification/, selection/, report/, stats/, harness/, golden/
    workshop/                 Workshop, Predictor, PredictorTransform, PredictionTransform, WorkshopReport
    probe/                    the eight *Probe classes (+ ProbeMath)
    cli/                      Research (dispatcher) and one <Command>Cli per command
  src/test/java/org/maplibre/mlt/research/
    ArchitectureTest          the dependency rules above
    baseline/                 VertexBufferCodecTest, VertexTransformTest (the baselines)
    extraction/               VertexBufferExtractorFidelityTest (reflective pin against mlt-core)
    encoder/format/           PipelineEncodingTest (round trip of every row), PipelineRoundTripProperties
    encoder/<stage>/          one mechanism test per idea (pins how it works on a small fixture)
    measurement/              OracleReportTest, BufferSelectorTest, TileSelectorTest
    registry/                 LadderTest, LayerDefaultTest
    workshop/                 WorkshopTest
```
