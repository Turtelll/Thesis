# Architecture

`research-java` is a standalone Gradle project (JDK 21) that depends on `mlt-core`
via a composite build (`includeBuild('../maplibre-tile-spec/java')` in
`settings.gradle`, substituting `org.maplibre:mlt`) — `mlt-core` builds from
source on every run, so the submodule is never published or modified.

There is **one encoder stack**, the modular `pipeline/`, and **one entry point**,
`./gradlew research --args="<command> ..."` (`cli/Research`; `./gradlew research` lists the commands).
The pre-consolidation code (the per-idea `Encoding` classes, `BenchmarkCli`, the
FastPFOR/rANS rows, the one-off probes) is at git tag `pre-consolidation`; the plan, the inventory and
the deviations are in [consolidation-inventory.md](consolidation-inventory.md).

## Packages

Under `org.maplibre.mlt.research`:

- **`extraction/`** — `cli/ExtractCli` reads an `.mbtiles` (via `MbtilesSource`) and
  `VertexBufferExtractor` turns each (tile, layer) into a `VertexBuffer` exactly as
  `maplibre-tile-spec`'s `GeometryEncoder` would (interleaved little-endian `int32`
  `[x0,y0,x1,y1,...]`, undelta'd, unencoded). `ExtractionWriter` writes the `.bin` +
  `.json` sidecar (topology streams: `tileExtent`, `geometryTypes`, `numParts`,
  `numRings`, `vertexCount`) and a `manifest.json` indexing the run; `ExtractionReader` reads
  them back. See [mlt-fidelity.md](mlt-fidelity.md) for how this stays pinned to upstream.
- **`encoding/`** — the contracts and the building blocks the pipeline reuses.
  `Encoding`: `name()`, `encode(BufferContext)`, `decode(EncodedBuffer, BufferContext,
  vertexCount)`. `decode` is mandatory and must not read `ctx.vertices()`, since every run
  round-trips every row on every buffer and fails loudly on mismatch — an unverified encoding is
  not a result. `BufferContext` carries the topology streams alongside the vertices, so a
  predictor derives structural reset points (part starts, ring corners) identically on encode and
  decode without signalling anything (`PartBoundaries`). `ClassifiedPredictor` is a per-vertex class
  plus what each class predicts. `VertexTransform` (`List<Vertex> <-> int[]`) and `IntCodec`
  (`int[] <-> byte[]`) composed in a `VertexBufferCodec` are the **two baselines**
  (`DELTA_ZIGZAG_FASTPFOR`, `DELTA_ZIGZAG_VARINT`, `Baselines.baseline()`), and the byte building
  blocks of the pipeline's width stage. `GeometryEquivalence` is the order-independent geometry
  check for rows that reorder.
- **`pipeline/`** — the encoder, composed of stages after the
  [OpenZL-style design](results/DesingDocOpenzl.md): `Reorder` (S1) -> `Prediction` (S2, pure
  predictors) -> `Segmentation` (S3) -> `ResidualMap` (S4, zigzag only) -> `ByteWidth` (S6) ->
  `Transpose` (S7), then the general-purpose backend (S8, applied per tile by the CLI). S5 (a
  per-stream GCD) was measured and removed; the later stage numbers are unchanged. One
  `PipelineConfig` is one row, `PipelineEncoding` its `Encoding`; every choice except the reorder is
  in a two-byte header and stream lengths follow from the topology
  ([bitstream.md](bitstream.md)). `PipelineRegistry` lists the rows per step (`DEFAULT` = the
  end-to-end choice, `forLayer` = the per-layer default, `bestGrid` = the 252 compositions of the
  per-(tile, layer) oracle); `Selector` / `TileSelector` are the opt-in selection reports
  (`TileSelector.oracle` is the `--step best` oracle over `bestGrid`); `PipelineStats` holds each step's probes; `Verification` checks round trips
  that reorder (also point columns); `PredictionTransform` reads a predictor's residual pairs
  through the `FLAT` split for the workshop and the probes.
- **`pipeline/mechanism/`** — the mechanisms the stages are built from, each a self-contained idea
  with its own mechanism test: the predictors (`SegmentQuadCorner`, `LineContinuation`,
  `QuadCornerLine`, `TurnScalar`), the ring frame (`RingFramePrior`), the shared-vertex references
  (`SharedVertexRef`, `LineSharedVertexRef`) and the geometry reordering (`GeometryReorder`).
  Nothing here is an `Encoding`.
- **`cli/`** — `Research` dispatches the commands: `extract`, `measure` (`PipelineCli`, the
  measurement harness), `tile-size`, `dump` (`GoldenDumpCli`), `workshop`, `decode-time`, and the
  probes `residual-bits`, `shared-vertex`, `turn-radius`, `orientation-prior`, `line-frame-prior`,
  `buffer-point`. `Compressor` / `TileCompression` size per tile; `OracleReport`, `SelectionReport`
  and `ChoiceCorrelation` are the report halves of `measure`.
- **`workshop/`** — fast predictor experiments: a `Predictor` is a name plus a one-line rule over the
  decoded prefix and a per-vertex `Topology`; `Workshop.known()` holds the pipeline's predictors
  through `PredictionTransform`, `Workshop.experiments()` the new ones.

`data-analysis/` (Python 3.13, stdlib + numpy/matplotlib) analyzes raw `.mbtiles`
tilesets independently of the Java pipeline (`mvt_stats.py` for byte composition,
`vertex_stats.py` for vertex distribution — counting rule verified to match
`ExtractCli`'s VertexBuffer count exactly). See `data-analysis/README.md` for usage;
caches to `data-analysis/out/*.json` (gitignored), and large tilesets take 40-60 min to
scan, so don't rerun without `--force`.

`tools/map-viewer/` is an independent Node/TypeScript + MapLibre GL JS app for visually
inspecting an `.mbtiles` tileset in a browser; see its own README.

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

`ArchitectureTest` enforces the layering: `pipeline` and `encoding` never depend on `cli` or
`workshop`; `encoding` does not depend on `pipeline`; `extraction` depends only on the `encoding` contract (`ExtractionReader` fills its `BufferContext`) and never on `pipeline`, `cli` or `workshop`.

## Project layout

```
research-java/
  settings.gradle             composite build -> ../maplibre-tile-spec/java (mlt-core)
  build.gradle                research (JavaExec, -Xmx6g), test, run
  golden/                     manifest of the pre-consolidation golden dump (the dumps themselves are in data/)
  src/main/java/org/maplibre/mlt/research/
    cli/                      Research (dispatcher), PipelineCli (measure), GoldenDumpCli, ExtractCli, the probes
    extraction/               MbtilesSource, VertexBufferExtractor, ExtractionWriter, ExtractionReader
    encoding/                 Encoding, VertexBufferCodec (+ BufferContext, EncodedBuffer), VertexTransform,
                                IntCodec, ClassifiedPredictor, PartBoundaries, StreamMetadata,
                                GeometryEquivalence, Baselines
    pipeline/                 Reorder, Prediction, Segmentation, ResidualMap, ByteWidth, Transpose,
                                PipelineConfig, PipelineEncoding, PipelineRegistry, Selector, TileSelector, ...
      mechanism/              predictors, RingFramePrior, SharedVertexRef, LineSharedVertexRef, GeometryReorder
    workshop/                 Workshop, Predictor, PredictorTransform, Topology
  src/test/java/org/maplibre/mlt/research/
    extraction/               VertexBufferExtractorFidelityTest (reflective pin against mlt-core)
    encoding/                 IntCodecTest, VertexTransformTest, VertexBufferCodecTest (the baselines)
    pipeline/                 PipelineEncodingTest (round trip of every row), one test per stage,
                                SharedVertexMechanismTest, ArchitectureTest, ...
    pipeline/mechanism/       one mechanism test per idea (pins how it works on a small fixture)
    workshop/                 WorkshopTest
    cli/                      report tests
```
