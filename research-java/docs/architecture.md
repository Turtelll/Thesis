# Architecture

`research-java` is a standalone Gradle project (JDK 21) that depends on `mlt-core`
via a composite build (`includeBuild('../maplibre-tile-spec/java')` in
`settings.gradle`, substituting `org.maplibre:mlt`) — `mlt-core` builds from
source on every run, so the submodule is never published or modified.

## Pipeline, in package order

Package order under `org.maplibre.mlt.research`:

- **`extraction/`** — `cli/ExtractCli` reads an `.mbtiles` (via `MbtilesSource`) and
  `VertexBufferExtractor` turns each (tile, layer) into a `VertexBuffer` exactly as
  `maplibre-tile-spec`'s `GeometryEncoder` would (interleaved little-endian `int32`
  `[x0,y0,x1,y1,...]`, undelta'd, unencoded). `ExtractionWriter` writes the `.bin` +
  `.json` sidecar (topology streams: `tileExtent`, `geometryTypes`, `numParts`,
  `numRings`, `vertexCount`) and a `manifest.json` indexing the run. See
  [mlt-fidelity.md](mlt-fidelity.md) for how this stays pinned to upstream.
- **`encoding/`** — the core abstraction is `Encoding`: `name()`, `encode(BufferContext)`,
  `decode(EncodedBuffer, BufferContext, vertexCount)`. `decode` is mandatory (never
  optional) and must not read `ctx.vertices()`, since every run round-trips every
  encoding on every buffer and fails loudly on mismatch — an unverified encoding is
  not a result. Most encodings decompose into a `VertexTransform` (`List<Vertex> <->
  int[]`, e.g. `ZigZagDelta`) plus an `IntCodec` (`int[] <-> byte[]`, e.g. `Varint`,
  `FastPfor`, `RansCodec`), composed in a `VertexBufferCodec`; `SegmentedEncoding`
  handles encodings that split residuals into multiple FastPFOR-packed regions
  (part-starts / interior / predicted corners — see `SegmentPair`, `SegmentQuadCorner`).
  `BufferContext` carries the topology streams alongside vertices so a transform can
  derive structural reset/split points (part starts, ring corners) identically on
  encode and decode without signalling anything. That transform x codec composition is what
  the baselines, `failed/` and the probes use. A research encoding is instead one
  self-contained `Encoding` class owning its region layout, any geometry reordering and its
  byte serialization (see `QuadCornerEncoding`, `SharedVertexRef`), so each buffer layout is
  tuned individually; it is registered as one line in `Baselines.research()` and automatically
  gets one benchmark row, round-trip coverage, and a `VertexBufferCodecTest` parameterization.
  `encoding/research/` holds encodings still considered viable; `encoding/research/failed/`
  holds ones kept only for their negative-result mechanism test (see
  [results/README.md](results/README.md)).
- **`BenchmarkCli`** — measures research encoding → general-purpose compressor. Encodes both
  baselines and `Baselines.research()` over an extraction directory, verifies round-trips, and
  reports sizes compressed per tile (`TileCompression`, `Compressor`): baselines vs research
  oracle, per encoding, oracle composition, and gzip breakdowns per layer / geometry type / zoom.
  An encoding that reorders geometries (`GeometryReorder`, called inside the encoding) is verified
  via `GeometryEquivalence`.
- **`ResidualBitsCli`, `RingPriorCli`, `BuildingShapeCli`, `EntropyCli`, `CondEntropyCli`,
  `FeatureBoundaryCli`, `QuadCornerResidualCli`** — measurement-only tools (no encoder, no bytes written) used
  to test a predictor's or transform's viability *before* investing in a full `Encoding`
  implementation. See [evaluation-protocol.md](evaluation-protocol.md).
- **`pipeline/`** — a second, separate encoding pipeline, composed of six stages after the
  [OpenZL-style design](results/DesingDocOpenzl.md): `Reorder` (S1) -> `Prediction` (S2, pure
  predictors) -> `Segmentation` (S3) -> `ResidualMap` (S4, zigzag only) ->
  `ByteWidth` (S6) -> `Transpose` (S7), then the general-purpose backend (S8, applied per tile by the
  CLI). S5 (a per-stream GCD) was measured and removed; the later stage numbers are unchanged.
  One `PipelineConfig` is one row, `PipelineEncoding` its `Encoding`; every choice except the
  reorder is in a two-byte header, and stream lengths follow from the topology. `Selector` is the
  selection loop: trial compression per buffer over 24 configurations, the smallest kept.
  `PipelineRegistry` lists the rows per step (`DEFAULT` = the end-to-end choice,
  `GEOMETRY_BEST` = with the reorder); `PipelineStats` holds each step's probes and `Verification`
  checks round trips that reorder (also point columns). It reuses `IntCodec`, `ClassifiedPredictor`
  and the verification contract of the old pipeline but changes none of it, and is not in
  `Baselines`. `PipelineCli` (`./gradlew pipeline`) and `PipelineTileSizeCli` (`pipelineTileSize`, the
  property side of the reorder) measure it; results in
  [results/openzl-pipeline.md](results/openzl-pipeline.md).

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

General-purpose compression (gzip, Brotli, zstd) is not registered as an `Encoding`.
`BenchmarkCli` applies it as the last pipeline stage **per tile**: every buffer of one tile is
concatenated (one tile is one HTTP response) and compressed on its own, one-shot, and the sizes
are summed over tiles (`TileCompression`). Each row also gets an `order-0` column, the
byte-entropy bound of each tile's bytes before any LZ77 match. The reference for every
percentage is the smaller of the two baselines under gzip.

The registry (`Baselines`) has three lists:

- `baseline()` — the two rows above.
- `research()` — one self-contained encoding per idea (predictors and shared-vertex
  references). These are the rows the benchmark reports and the research oracle selects from.
- `retired()` — rows no run measures any more (rANS, `RAW` and the other byte serializers over
  the baseline transform, `failed/` encodings), kept for round-trip tests and `DecodeTimeCli`.

See [results/README.md](results/README.md) for what the encodings do and for what has
been measured and rejected.

## Project layout

```
research-java/
  settings.gradle             composite build -> ../maplibre-tile-spec/java (mlt-core)
  build.gradle
  src/main/java/org/maplibre/mlt/research/
    cli/                          every entry point (also: DecodeTimeCli, ShareTableCli, QuadRotationCli,
                                    TurnRadiusCli, TurnScalarCli, Turn90BitmapCli, BufferPointCli,
                                    ReflexSignalCli, TileSizeCli — see build.gradle for the task of each)
      ExtractCli.java              extract-step CLI (default mainClass)
      BenchmarkCli.java             baselines vs candidates vs oracles, compressed per tile
      Compressor.java               gzip / Brotli / zstd, one-shot size of a byte[]
      TileCompression.java          per-tile accumulator: raw, order-0 and compressed bytes per row
      ResidualBitsCli.java          mean residual bits, zero pairs, per-vertex change vs DELTA_ZIGZAG (encodes nothing)
      RingPriorCli.java             measure predictor residual bit widths on >4-vertex rings (encodes nothing)
      BuildingShapeCli.java         characterize geometric properties of >4-vertex building rings (encodes nothing);
                                      also the WP5-E5.1 k-window shape-reuse feasibility scan
      EntropyCli.java                WP7 cost-model estimate: order-0 entropy vs FastPFOR bytes (encodes nothing)
      CondEntropyCli.java             conditional-entropy probe: y|x, bucket/raw split, order-1 bucket context
      FeatureBoundaryCli.java         cost of part/feature boundaries under FastPFOR's block-max pricing
      QuadCornerResidualCli.java      Stage 0 protocol probe: 2nd-order corner predictors, no encoder (encodes nothing)
    extraction/
      VertexBufferExtractor.java   core flattening logic (copied from GeometryEncoder)
      MbtilesSource.java           .mbtiles reading + decompression + TMS->XYZ
      ExtractionWriter.java        .bin/.json/manifest.json output
    encoding/
      Encoding.java            name + encode + decode; the whole contract
      PartBoundaries.java      vertex-run offsets derived from the topology streams
      VertexTransform.java     logical layer, List<Vertex> <-> int[] (+ ZigZagDelta)
      IntCodec.java            physical layer, int[] <-> byte[] (+ Varint, FastPfor)
      VertexBufferCodec.java   one transform + one codec = an Encoding; also holds
                                 BufferContext and EncodedBuffer
      StreamMetadata.java      size of the header MLT writes in front of a stream
      Baselines.java           the registry: baseline(), research(), retired()
      GeometryEquivalence.java order-independent geometry check, for encodings that reorder
      ClassifiedPredictor.java per-vertex class + prediction, layout-agnostic (see evaluation-protocol.md)
      Layout.java              INTERLEAVED vs SEGMENTED residual placement for a ClassifiedPredictor
      PredictedTransform.java  ClassifiedPredictor + Layout -> VertexTransform
      research/
        GeometryReorder.java   greedy endpoint chaining of whole features (a building block an Encoding calls itself)
        ClassRegions.java      a ClassifiedPredictor's residuals as one stream per vertex class
        QuadCornerEncoding.java       SegmentQuadCorner with its own byte layout (QUAD_CORNER)
        LineContinuationEncoding.java LineContinuation with its own byte layout (LINE_CONTINUATION)
        SegmentPair.java       ClassifiedPredictor: groups part-start residuals ahead of interior deltas
        SegmentQuadCorner.java ClassifiedPredictor: part starts + 4th-corner (p0+p2-p1) prediction, 3 classes
        Rot90Fixed.java        ClassifiedPredictor: fixed-ccw 90°-turn on even >4-vertex rings (weak win)
        SegmentQuadCornerRot90.java  ClassifiedPredictor: quad-corner for 4-rings + fixed rot90 for even >4-rings, 4 classes (no gain over plain quad-corner)
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
