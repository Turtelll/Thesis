# Consolidation inventory (Phase 1)

Status: **Phase 0 and Phase 1 delivered; Phases 2 and 3 are not started** and wait for the decisions in
[section 7](#7-decisions-needed). Nothing in `src/` was changed by this inventory, so no number the thesis cites
has moved.

The goal: `pipeline/` is the only encoder stack and one harness does everything `BenchmarkCli` does, without
changing a cited number unless approved. This file is the evidence for what to keep, merge, port and delete.

## 0. How the evidence was produced

| what | how | where it lives |
|---|---|---|
| Golden dump (Phase 0) | `./gradlew goldenDump` (`GoldenDumpCli`): 194 rows x 4.368 buffers x 358 tiles of `data/extractedBaseline`; SHA-256 of the encoded bytes, raw length, per-buffer and per-tile gzip / Brotli 11 / zstd 19 | `data/golden/pre-consolidation/` (gitignored), manifest committed as `research-java/golden/pre-consolidation.manifest.tsv` |
| Figure map (section 2) | every `docs/results/` file read against the CLI code path it names; commands re-run where the flags still exist | this file |
| Runtime coverage | JaCoCo 0.8.13 agent over 49 commands on `data/coverage-subset` (13 Munich tiles, 128 buffers, all 16 layers) and the Munich `.mbtiles`: `scripts/coverage.ps1`, report `scripts/coverage_report.py` | `data/coverage/` (gitignored) |
| Static unused code | ProGuard 7.7 `-printusage` rooted at the `main()` of every figure CLI (once alone, once also rooted at every test class), PMD 7.17 unused-member rules: `scripts/unused.ps1`, `scripts/unused_report.py`, `scripts/pmd-unused.xml` | `data/coverage/unused-*.txt` |

Caveats that matter when reading the coverage numbers:

- The coverage run is a **subset** (128 buffers). A class is reached by any buffer of the right shape, so the
  subset reaches the same code as the corpus; it does not reproduce sizes.
- ProGuard's test-rooted pass used the `build/classes/java/test` that was already on disk (compiled 2026-10-23;
  two test sources are newer). The offline Gradle cache has no JUnit, so the tests could not be recompiled
  here. The figure-rooted pass (no tests) is unaffected.
- "Figure command" means a command a current-corpus results file cites. The other probes (`entropy`,
  `condEntropy`, `ringPrior`, `buildingShape`, `featureBoundary`, `quadCornerResidual`, `quadRotation`,
  `tileSize`) were run as a second group, so "reached by a probe only" is separable from "reached by nothing".

## 1. Phase 0 state

- Format, options and determinism: see `GoldenDumpCli`'s class comment. A 60-tile run in parallel and in
  `--serial` mode produced an identical manifest.
- Header records the repository commit, the submodule commit (`bf9359d2`, bumped to what is checked out), JDK,
  gzip level (6, `GZIPOutputStream`), Brotli quality 11 (`brotli4j-1.23.0`), zstd level 19 (`zstd-jni-1.5.7-22`)
  and the corpus `manifest.json` hash.
- Full run 1: 194 rows x 4.368 buffers in 358 tiles. The README says 4.370 buffers and `openzl-pipeline.md`
  says 4.368: the dump agrees with the latter.
- "Before" metrics (also in the committed manifest): `src/main` 99 files, 26.592 lines (24.141 non-blank);
  `src/test` 45 files, 6.507 lines; 31 files in `cli/`; 26 `main` entry points; 25 `tasks.register` in
  `build.gradle`.

**Verification (acceptance gate for Phase 0, passed):**

- Two full runs, `pre-consolidation` and `pre-consolidation-2`: `--diff` reports `IDENTICAL: every compared row is
  byte for byte the same`. The two dump files differ in exactly one line, header line 3 `repo_dirty_research_java`
  (`false` for run 1, `true` for run 2, because the scripts and this file were added to `research-java/` while it
  ran); all 847.406 / 69.466 data lines of `buffers.tsv.gz` / `tiles.tsv.gz` are identical.
- Cross-check against the old harnesses on the full corpus (`scripts/crosscheck.py`, logs in
  `data/golden/crosscheck/`): `./gradlew benchmark`, `./gradlew pipeline --step layer` and `--step s8 --with-old`
  print 36 lines over 28 rows (both baselines, `QUAD_CORNER_SHARED_FRAME_RING_CANONICAL_REORDER`, the other
  `Baselines.research()` rows, `DEFAULT` = `PIPE_QUAD_CORNER_LINE_CLASS_ZIGZAG_ESCAPE_PLANES`, `PER LAYER`, and the
  S8 rows). Raw, order-0, gzip, Brotli 11 and zstd 19 totals equal the dump **to the byte, 0 mismatches**.
- Because both old harnesses and the dump size tiles through `TileCompression` / `Compressor`, this confirms the
  dump measures what the harnesses measure; it does not test them against an independent compressor.

## 2. Figure map

Corpus status: **current** = measured on `munich-plantiler.mbtiles` / `data/extractedBaseline`;
**historical** = retired corpus, a retired flag, or an encoding that no longer exists. "In dump" says whether the
golden dump pins the numbers the file cites (it pins every registered `Baselines` and `PipelineRegistry` row
on the Munich corpus; it does not cover Chicago, Overture, Tokyo, timings, or probe output).

### 2.1 Figures a current run can reproduce

| results file | what it cites | command | code path | in dump |
|---|---|---|---|---|
| `openzl-pipeline.md` | S0-S8 tables, ladder, oracle, per-layer default | `pipeline --step s0..s8 / ladder / oracle / layer / selector / tile` | `PipelineCli` -> `PipelineRegistry`, `PipelineEncoding`, `Verification`, `TileCompression` | yes (all 176 `PIPE_*` rows and `PIPE_PER_LAYER`); drift in section 6 |
| `road-chain.md` | 9 rows on `transportation` (order, chain, merge, gate 4), Chicago column, Stage 0 probe | `benchmark --layer transportation --by-zoom`; `roadChain` | `BenchmarkCli` -> `Baselines.research()/lossy()` -> `RoadChainEncoding`, `RoadMerge`, `GeometryReorder`; `RoadChainCli` | yes for Munich; Chicago no |
| `line-frame-prior.md` | `LINE_CONTINUATION_FRAME`, `_HEADING`, `_FRAME_HEADING` on Munich, Chicago, Overture | `lineFramePrior`; `pipeline --step framelines --layer transportation --by-zoom` | `LineFramePriorCli`, `PipelineCli` -> `Prediction` -> `RingFramePrior`, `LineContinuation` | Munich yes; Chicago, Overture no |
| `orientation-prior.md` | `QUAD_CORNER_SHARED_FRAME*` rows, `--step frame/framelayers`, workshop rows, decode 5,1x | `benchmark`; `pipeline --step frame/framelayers`; `workshop --layer building`; `decodeTime --encoding QUAD_CORNER_SHARED_FRAME --layer building` | `Baselines.research()` -> `SharedVertexRef` + `RingFramePrior`; `Prediction.QUAD_CORNER_FRAME` | rows yes; decode time no; the probe table is **lost** (file says TODO) |
| `buffer-points.md` | 10,74% buffer vertices, -24,46% total MLT bytes | `bufferPoint` | `BufferPointCli` -> `MltConverter` on the `.mbtiles` | no (whole-tile MLT bytes, not VertexBuffer) |
| `turn-radius-by-zoom.md` | per-zoom turn radius, Munich vs Overture | `turnRadius` | `TurnRadiusCli` | no (probe) |
| `failed/turn-scalar-encoder.md` | `TURN_SCALAR` pipeline rows on `building` | `pipeline --step turn --layer building --by-zoom --with-old` | `PipelineCli` -> `Prediction.TURN_SCALAR` -> `failed/TurnScalar` | yes |
| `line-continuation-by-zoom.md` | `LINE_CONTINUATION` per zoom, Munich and Chicago | `benchmark --layer transportation --by-zoom --verbose`; `residualBits` for the bit figures | `BenchmarkCli`; `ResidualBitsCli` | bytes yes (Munich); bit figures no; `z7_check.py` is **lost** |
| `line-part-chain.md` | `_PART_CHAIN` rows, 0 of 220.720 shared endpoints | `workshop --layer transportation --by-zoom --reorder` | `WorkshopCli` -> `Workshop.known()` | no (workshop only); the no-joins probe is **lost** |
| `decode-time.md` | decode ratio vs FastPFOR | `decodeTime` (default grid, or `--encoding ... --layer ...`) | `DecodeTimeCli` | no (timing is not deterministic) |
| `failed/turn-scalar.md` | Stage 0 bits/vertex re-run | `turnScalar` | `TurnScalarCli` | no (probe) |
| `shared-vertices.md` (the `sharedVertex` probe part) | per-zoom share of repeated vertices | `sharedVertex` | `SharedVertexCli` | no (probe) |
| `line-shared-vertices.md` (Munich `_SEG`-era numbers excluded) | `LINE_CONTINUATION_SHARED_DICT` / `_MARKED` rows now in `QUAD_CORNER`-style naming | `benchmark`; `pipeline --step linesshared`; `pipelineTileSize --joint transportation` | `LineSharedVertexRef`, `Prediction.LINE_CONTINUATION_SHARED` | rows yes; `--joint` whole-tile no |
| `general-purpose-compression.md` (current-corpus rows only) | `QUAD_CORNER_SHARED` -5,58% gzip / -6,79% Brotli / -6,53% zstd | `benchmark` | `BenchmarkCli` | yes; gzip and zstd reproduce to the digit (own-compressor convention), Brotli does not (the file used quality 9, the default is 11: -7,86% now) |
| `README.md` (results index), best-row sentences | `QUAD_CORNER_SHARED_FRAME_RING_CANONICAL_REORDER` -8,19% gzip / -15,21% Brotli / -9,92% zstd | `benchmark` | `BenchmarkCli` | yes; reproduces exactly (-8,19% / -15,21% / -9,92% in the benchmark's gzip-reference convention) |

### 2.2 Figures that are history (no current command produces them)

| results file | why it does not reproduce |
|---|---|
| `general-purpose-compression.md` (matrix), `revisit-varint-split.md`, `shared-tables.md`, `segment-quad-corner.md`, `shared-vertices.md`, `line-shared-vertices.md` (FastPFOR tables), `failed/standard-delta-uint16.md`, `failed/tile-standard-delta.md`, `failed/bwt-mtf.md`, `failed/upstream-delta2.md` | `--compress`, `--segmented`, `--revisit`, `--set`, `--reorder`, `--std-delta`, `--tile-std-delta`, `--rans` are gone: `BenchmarkCli` accepts `--csv --layer --verbose --by-zoom --brotli-oracle --brotli-quality` only. The `revisited()` rows are private to `Baselines.retired()`, so only the current-row columns of `revisit-varint-split.md` reproduce; the decode commands in the shared-vertices files name rows that no longer exist |
| `rans-entropy-coder.md`, `selection-oracle.md`, `reordering.md`, `segment-pair.md`, `rot90-fixed.md`, `context-delta.md`, `line-continuation.md`, `corner-stream.md` | retired corpus (`data/extracted`, `munich.mbtiles`) and/or rANS; `corner-stream.md`'s `CornerProbe` is already removed |
| `failed/right-angle-turn.md`, `failed/midpoint-reflect.md`, `failed/rdp-residual.md`, `failed/ring-predictors.md`, `failed/two-rectangle.md`, `failed/shape-reuse.md`, `failed/delta-of-delta.md`, `failed/junction-backref.md`, `failed/turn90-bitmap.md`, `shared-turn-scalar.md` | retired corpus or FastPFOR-era, and the encoder is registered only in `retired()` or already removed (`QUAD_CORNER_SHARED_TURN_SCALAR`: "code removed after measuring") |

`DesingDocOpenzl.md` (the file name has a typo), `architecture.md`, `evaluation-protocol.md` and
`mlt-fidelity.md` cite no results figure; `architecture.md` is stale (see section 6).

## 3. Code on no path

### 3.1 Runtime coverage (JaCoCo) by group

Full per-class table: `data/coverage/report.md` (regenerate with `scripts/coverage_report.py`).

- **Reached by the figure commands (>= 85% of instructions):** all of `pipeline/` except `PipelineStats` (73%) and
  `StageRules` (0%), the mechanisms the pipeline still calls (`SharedVertexRef`, `LineSharedVertexRef`,
  `RingFramePrior`, `QuadCornerLine`, `SegmentQuadCorner`, `LineContinuation`, `GeometryReorder`,
  `ClassRegions`, `RoadChainEncoding`, `RoadMerge`), `Baselines`, `GeometryEquivalence`, `MergeEquivalence`,
  `PartBoundaries`, `VertexTransform`, `PredictedTransform`, the `extraction/` classes and `workshop/`.
- **Reached by no figure command but by a probe (0% figures, 96-99% all):** `BuildingShapeCli`,
  `CondEntropyCli`, `EntropyCli`, `FeatureBoundaryCli`, `QuadCornerResidualCli`, `QuadRotationCli`,
  `RingPriorCli`; `TileSizeCli` (82%, no Gradle task).
- **Reached by nothing (0% in all runs):** `StageRules` (only `--step tile` trials named `rules_*` and
  `TileSelectorTest` call it; the coverage run did not pass those trials), `failed/TileStandardDelta`
  (`GoldenDumpCli` is 0% only because the dump was not part of this run).
- **Reached only as constructors (<= 14%):** `failed/EvenRingTurn`, `MidpointReflect`, `RdpResidual`,
  `RdpResidualSegmented`, `SegmentQuadCornerTurn`, `BwtMtfCodec`, `StandardDelta`, `research/SegmentPair`. They run
  because `Baselines.retired()` constructs them, never because a row is measured. `ContextDelta` (33%) and
  `Turn90Bitmap` (51%) are reached through `Workshop` and `Turn90BitmapCli`.
- **Reached because of the rANS grid:** `RansCodec` (92%), `SegmentedRansCodec` (95%), `SegmentedEncoding`
  (100%): only `DecodeTimeCli`'s default grid, which the README already calls retired.

### 3.2 Static (ProGuard) and PMD

- Whole classes no figure `main()` reaches: `BuildingShapeCli`, `CondEntropyCli`, `EntropyCli`,
  `FeatureBoundaryCli`, `QuadCornerResidualCli`, `QuadRotationCli`, `RingPriorCli`, `TileSizeCli`,
  `failed/TileStandardDelta` (plus its nested types), `PartBoundaries.GeometryTypeOrdinal`. This matches the
  runtime result.
- Inside the code worth keeping the dead material is small: unused constants (`GeometryReorder.POINT`,
  `Reorder.POINT`, ...), private constructors of utility classes, and a few accessors:
  `workshop.Predictor.parallelogram`, `workshop.Topology.vertexCount()` / `isRing(int)`,
  `ExtractionWriter.ManifestEntry.featureCount()`, `ExtractionWriter.Sidecar` accessors, `ByteWidth.variableLength()`,
  `StreamCodec.Span.max()/exceptions()/maxException()`, `Workshop.Reordered.inner()/reorder()`,
  `Workshop.FramedTransform.prediction()`.
- PMD (unused private members, parameters, locals; full text in `data/coverage/pmd-unused.txt`), six findings:
  `BuildingShapeCli.pct(long,long)`, `FeatureBoundaryCli:196` parameter `runStarts`,
  `OrientationPriorCli.ANGLE_BYTES`, `RoadChainCli:292` parameter `position`, `PartBoundaries.LINESTRING`,
  `SegmentQuadCorner.classify(int[],int)`.

## 4. Classification

Rules used: **keep** = stays, possibly relocated; **merge** = its behaviour moves into the single harness, the
class goes; **port** = the mechanism becomes a pipeline stage (or already is one), the old class goes after the
port; **delete** = removed, recoverable from the `pre-consolidation` tag, the results file stays as a historical
record. "Needs decision" refers to section 7.

### 4.1 `cli/` (31 files)

| class | lines | verdict | note |
|---|---:|---|---|
| `BenchmarkCli` | 847 | **merge** | reports (baselines vs oracle, per layer/geometry type/zoom, Brotli oracle, CSV) and the round-trip verification become harness reports over any set of rows |
| `PipelineCli` | 641 | **merge** | each `--step` becomes a named row set / report of the harness |
| `PipelineTileSizeCli` | 515 | **merge** | the only place whole-tile MLT bytes (with properties) exist; becomes the harness's `tile-size` mode (needs `MltConverter`) |
| `GoldenDumpCli` | 563 | **keep** | becomes the harness's `golden` command; defines the acceptance gate |
| `Compressor`, `TileCompression` | 59, 137 | **merge** | the measurement core (gzip 6 / Brotli 11 / zstd 19, per tile) |
| `OracleReport`, `SelectionReport`, `ChoiceCorrelation` | 248, 104, 263 | **merge** | report pieces shared by both old harnesses |
| `DecodeTimeCli` | 509 | **merge** | `decode-time` command; the rANS grid goes (D4) |
| `ExtractCli` | 117 | **merge** | `extract` command (README extraction example still names `munich-de.mbtiles`) |
| `WorkshopCli` | 362 | **merge** | `workshop` command rebased on the pipeline `Prediction` (D6) |
| `ResidualBitsCli` | 459 | **merge** | probe, cited by `line-continuation-by-zoom.md` |
| `SharedVertexCli` | 212 | **merge** | probe, cited by `line-shared-vertices.md` (current corpus) |
| `OrientationPriorCli`, `LineFramePriorCli` | 698, 499 | **merge** | probes, cited by `orientation-prior.md`, `line-frame-prior.md`; they call `RingFramePrior`, so they follow its port |
| `TurnRadiusCli` | 344 | **merge** | probe, cited by `turn-radius-by-zoom.md` |
| `BufferPointCli` | 115 | **merge** | probe, cited by `buffer-points.md` |
| `RoadChainCli` | 432 | **merge or delete** | depends on D3 (road chain frozen or ported) |
| `TurnScalarCli`, `Turn90BitmapCli`, `ReflexSignalCli` | 227, 122, 389 | **delete** (D5) | probes for ideas that lost; figures live in `failed/`; `ReflexSignalCli`'s doc number is FastPFOR-era |
| `ShareTableCli` | 402 | **delete** (D4) | rANS sparse tables; `rANS` is retired |
| `EntropyCli`, `CondEntropyCli` | 200, 345 | **delete** (D4) | rANS-era, only `rans-entropy-coder.md` (retired corpus) cites them |
| `RingPriorCli`, `BuildingShapeCli` | 391, 743 | **delete** (D5) | only `failed/ring-predictors.md` (retired `data/extracted`) cites them |
| `QuadCornerResidualCli`, `QuadRotationCli`, `FeatureBoundaryCli`, `TileSizeCli` | 179, 167, 349, 34 | **delete** (D5) | `quadCornerResidual` cited only by retired `segment-quad-corner.md`; `quadRotation`, `featureBoundary`: no results file; `TileSizeCli` has no task |

### 4.2 `encoding/` (core)

| class | verdict | note |
|---|---|---|
| `Encoding` | **keep** | the contract (`PipelineEncoding` implements it) |
| `VertexBufferCodec` | **keep, trim** | `BufferContext`, `EncodedBuffer` are used by the whole pipeline; the codec composition stays for the two baseline rows |
| `VertexTransform` | **keep, trim** | `ZigZagDelta` (baselines); `ZigZagDelta2` only feeds the retired revisited rows and `failed/upstream-delta2.md` (D5) |
| `IntCodec` | **keep, trim** | VARINT, FASTPFOR, VARINT_SPLIT are reached; `RAW`, `VARINT_XY`, `BYTESPLIT`, `UINT16(_PLANES)` serve only retired rows (36% covered) |
| `Baselines` | **keep, trim** | `baseline()` stays as the two references; `retired()`, `revisited()`, `bwtMtf()`, `tileStandardDelta()` go with their rows (D5) |
| `PartBoundaries`, `ClassifiedPredictor`, `StreamMetadata` | **keep** | used by `pipeline/`; candidates to move into the package that owns them |
| `GeometryEquivalence`, `MergeEquivalence` | **merge** | into the harness's verification beside `Verification`; `MergeEquivalence` only if the lossy rows stay (D3) |
| `PredictedTransform`, `Layout` | **keep until D6** | used by `Workshop`, the revisited rows and `failed/`; free to go once the workshop runs on `Prediction` |
| `RansCodec`, `SegmentedRansCodec`, `SegmentedEncoding` | **delete** (D4) | rANS retired; reached only through the rANS decode grid and round-trip tests |

### 4.3 `encoding/research/` (mechanisms and old row shells)

| class | lines | verdict | note |
|---|---:|---|---|
| `SharedVertexRef` | 787 | **port** | scan/walk logic is already called by `Prediction`, `Segmentation`, `PipelineEncoding`; what is left is to move it into a stage and drop the `Encoding` shell (it builds the rows `QUAD_CORNER_SHARED*`) |
| `LineSharedVertexRef` | 530 | **port** | `Prediction` uses only `Table.ALL_PARTS`; `Table.MARKED` has no pipeline analogue (section 5, row B) |
| `RingFramePrior` | 329 | **port** | `Prediction.QUAD_CORNER_FRAME` and the line frames call it |
| `SegmentQuadCorner`, `QuadCornerLine`, `LineContinuation` | 97, 85, 108 | **port** | small predictors the pipeline and the workshop call |
| `ClassRegions` | 121 | **port** | class streams used by `Segmentation` |
| `GeometryReorder` | 931 | **port (required)** | `Reorder.CHAIN` delegates to `GeometryReorder.FEATURE`, and `PipelineRegistry.forLayer` uses `Reorder.CHAIN` on `transportation`, `transportation_name`, `waterway`, `water`, `aeroway`: **the pipeline's per-layer default depends on this class**. `CANON_CHAIN` (`RING_CANONICAL_THEN_FEATURE`) is a registered reference row; `HILBERT_CENTROID_ROT_MP` does not use it (section 5, row D). The `Reorder` Javadoc ("here only as references") is stale |
| `QuadCornerEncoding`, `LineContinuationEncoding` | 39, 39 | **delete** | row shells; `QUAD_CORNER`, `LINE_CONTINUATION` are `ResidualBitsCli` inputs, which can call the stage |
| `RoadChainEncoding`, `RoadMerge`, `RoadMergeEncoding` | 177, 170, 93 | **port or freeze** | D3 |
| `Rot90Fixed`, `SegmentPair` | 83, 64 | **delete** (D5) | `SegmentPair` reached at 8%; `Rot90Fixed` only by the workshop and a retired row |

### 4.4 `encoding/research/failed/` (13 classes, 3.493 non-blank lines)

| class | verdict | note |
|---|---|---|
| `TurnScalar` | **port** | `Prediction.TURN_SCALAR` calls it; pipeline `PIPE_*_TURN_SCALAR_*` rows are in the dump |
| `BwtMtfCodec`, `EvenRingTurn`, `MidpointReflect`, `RdpResidual`, `RdpResidualSegmented`, `SegmentQuadCornerTurn`, `SegmentQuadCornerRot90`, `StandardDelta`, `TileStandardDelta`, `Turn90Bitmap` | **delete** (D5) | each a measured negative result; the mechanism tests go with them; docs stay |
| `ContextDelta`, `RightAngleTurn` | **delete** (D5) | also workshop predictors (`CONTEXT_DELTA`, `RIGHT_ANGLE_TURN`) that disappear with them |

### 4.5 `pipeline/` (all **keep**)

`PipelineEncoding`, `PipelineConfig`, `PipelineRegistry`, `Prediction`, `Segmentation`, `Reorder`, `Transpose`,
`ResidualMap`, `StreamCodec`, `ByteWidth`, `Bytes`, `Verification`: keep (Phase 2 renames/relocates the stage
code). `PipelineStats` (336 lines, 73% reached): **merge** into the harness's `--stats` report.
`Selector`, `TileSelector`, `StageRules`, `BufferFeatures`: **keep or delete, D7**: the selection machinery whose
documented verdict is "discarded" (`openzl-pipeline.md`).

### 4.6 `workshop/` and `extraction/`

- `Predictor`, `PredictorTransform`, `Topology`, `Workshop`: **merge** into the harness as the `workshop`
  command (D6). Unreached accessors: `Predictor.parallelogram`, `Topology.vertexCount()`, `Topology.isRing(int)`.
- `ExtractionReader`, `ExtractionWriter`, `MbtilesSource`, `VertexBufferExtractor`: **keep**
  (`VertexBufferExtractorFidelityTest` covers fidelity).

### 4.7 Tests

45 test classes. Tests of deleted classes go with them (`BwtMtfCodecTest`, `RansCodecTest`,
`SegmentedRansCodecTest`, every `failed/*Test`, `Rot90FixedMechanismTest`, `SegmentPairMechanismTest`, ...). The
pipeline's `*MechanismTest`s and `PipelineEncodingTest`, `LadderTest`, `LayerDefaultTest`, `SelectorTest`,
`TileSelectorTest` stay. `VertexBufferCodecTest` loses the retired rows it round-trips.

## 5. The five old research rows: port as a stage, or freeze as a recorded number

Sizes are the sum of per-tile compressed sizes over the whole corpus (geometry buffers only, 4.368 buffers), from
the golden dump. Percentages use the **benchmark convention** (`./gradlew benchmark`, README): every column against
the smaller baseline's *gzip* size, `DELTA_ZIGZAG_VARINT` = 11.237.124 B (so a Brotli or zstd row can read lower than
its gzip row). Against each compressor's own `DELTA_ZIGZAG_VARINT` (10.734.783 B Brotli 11, 11.201.331 B zstd 19)
the same rows read, for example, -9,90% Brotli for row A's pipeline row;
`scripts/golden_summary.py` prints both. "Stake" = bytes the old row still saves that the pipeline's best row
**with the same mechanism** does not.

| # | mechanism, old row(s) | old best (gzip / Brotli / zstd) | pipeline best with it | stake | proposal |
|---|---|---|---|---:|---|
| A | **Shared-vertex references** (`SharedVertexRef`): `QUAD_CORNER_SHARED`, `_FRAME`, `_REORDER`, `QUAD_CORNER_LINE_SHARED` | `QUAD_CORNER_SHARED` -5,58% / -11,98% / -6,83% | `PIPE_HILBERT_CENTROID_ROT_MP_QUAD_CORNER_SHARED_CLASS_ZIGZAG_ESCAPE_PLANES` -8,84% / -13,93% / -10,24% (366.751 B smaller on gzip) | 0 B | **port as a stage (finish it)**: the logic already runs inside `Prediction`; move it into the stage and drop the `Encoding` shell. **Freeze** the four old rows as recorded numbers; every one is beaten on all three compressors |
| B | **Line shared dictionary** (`LineSharedVertexRef`): `LINE_CONTINUATION_SHARED_DICT`, `_MARKED` | `_DICT` -2,62% / -8,75% / -3,88% (`transportation` -9,49%); `_MARKED` -2,15% / -8,38% / -3,46% | `PIPE_HILBERT_CENTROID_ROT_MP_LINE_CONTINUATION_SHARED_CLASS_ZIGZAG_ESCAPE_PLANES` -5,86% / -10,63% / -6,92% (364.433 B smaller than `_DICT`); `PIPE_PER_LAYER` `transportation` -12,06% vs `_DICT` -9,49% | `_DICT`: 0 B. `_MARKED`: a decode-speed variant (no position table, 1,15x-1,24x vs `_DICT` 1,59x-1,85x FastPFOR decode); the pipeline has no `Table.MARKED` | **port `ALL_PARTS` (done), freeze `_MARKED`**. Costs 52.404 B (0,47% of tile gzip) in size against `_DICT`; port it only if the thesis wants the decode-speed trade-off as a pipeline option |
| C | **Road chain / merge** (`RoadChainEncoding`, `RoadMerge`, `RoadMergeEncoding`): `LINE_CONTINUATION_ROAD_ORDER`, `_ROAD_CHAIN`, `_GATE4`, `_ROAD_MERGE`, `_ROAD_MERGE_GATE4` | `_ROAD_CHAIN` -2,60% / -8,91% / -3,90% (`transportation` -8,38%); lossy `_ROAD_MERGE` -2,80% / -9,11% / -4,08% | `PIPE_PER_LAYER` `transportation` -12,06% isolated gzip: it uses the greedy feature chaining (`Reorder.CHAIN`) but not the forward-only road chain or any join | joins add 8.612 B over the road order (`transportation` isolated gzip: `_ROAD_ORDER` 2.569.993, `_ROAD_CHAIN` 2.561.381); the lossy merge a further 15.883 B in the vertex buffer, plus 20-25% fewer line-feature topology entries that **no harness measures** | **freeze** the vertex-buffer rows (all nine are in the dump, `road-chain.md` is reproducible from it). Port only if the merge is to be stacked on the pipeline *and* topology savings are to be counted; that needs the topology stream in the harness (not in scope of this refactor) |
| D | **Canonical ring reorder** (`GeometryReorder.RING_CANONICAL*`, `_THEN_FEATURE`); the greedy feature chaining it sits on (`GeometryReorder.FEATURE`) is **live** in the pipeline | `QUAD_CORNER_SHARED_RING_CANONICAL_REORDER` -7,34% / -13,54% / -8,77% (vs `_REORDER`, feature chaining alone, -7,27% / -13,44% / -8,61%) | `CANON_CHAIN` is a registered reference row; `CHAIN` is used by `forLayer` on five layers. On `building`, `HILBERT_CENTROID_ROT_MP` (ring rotated to start nearest the previous vertex) gives 3.214.369 B isolated gzip against 3.246.110 B for the chain row and 3.276.195 B for the old canonical+chain+frame row | the canonical start alone is worth 7.713 B (0,07%) over feature chaining on the old rows; on the registry's `CHAIN` vs `CANON_CHAIN` rows with the line predictor 25.636 B tile gzip, of which 24.249 B on `building` and 230 B on `transportation` | **freeze the canonical start** (`ROT` supersedes it on rings, lines do not use it); **port `FEATURE` chaining**, which `forLayer` needs. `CANON_CHAIN` stays only if the figures in `openzl-pipeline.md` that compare against it must remain reproducible |
| E | **Frame with references** (`QUAD_CORNER_SHARED_FRAME[_RING_CANONICAL_REORDER]`, i.e. `RingFramePrior` on top of A and D) | `QUAD_CORNER_SHARED_FRAME_RING_CANONICAL_REORDER` -8,19% / -15,21% / -9,92% (the README's "best row") | `PIPE_HILBERT_CENTROID_ROT_MP_QUAD_CORNER_SHARED_FRAME_CLASS_ZIGZAG_ESCAPE_PLANES` -9,95% / -15,97% / -11,68%: 198.207 B smaller on gzip | 0 B | **port (done)**; **freeze** the old row. The README sentence that calls the old row "the best row since then" needs a decision (D2): it is no longer the best row, and it is the number the thesis cites |

One caveat on every "the pipeline wins" statement above: the pipeline reorders (`HILBERT_*`, `CHAIN`) change the
order of features, and `openzl-pipeline.md` records that the reorder costs more in the property columns than it
saves in the vertex buffer (the end-to-end default has none). All sizes here are geometry buffers only. The
whole-tile figure (`pipelineTileSize`) is what decides whether the reordered rows are real wins.

## 6. Defects and inconsistencies found

1. **`build.gradle` task `chart` points at `org.maplibre.mlt.research.cli.ChartCli`, which does not exist.**
   `./gradlew chart` fails. `TileSizeCli` (34 lines) has no task.
2. **`architecture.md` is stale**: it still describes `SegmentedEncoding` and rANS as live.
3. **`research-java/README.md` extraction example** uses `data/MVT/munich-de.mbtiles`; the current corpus is
   `munich-plantiler.mbtiles` (lines 4, 38, 41-59).
4. **Cited per-layer default has drifted.** `README.md` and `openzl-pipeline.md` cite the whole-corpus `PER LAYER` row at
   10.399.717 B gzip (-7,45%), 9.702.273 B Brotli (-13,66%), zstd -9,29% (and -8,25% to -8,64% gzip, 10.811.249 B). A current
   `./gradlew pipeline --step layer` and the dump both give 9.960.430 B gzip (-11,36%), 9.306.400 B Brotli (-17,18%),
   9.773.069 B zstd (-13,03%), same benchmark convention, to the byte: `forLayer` now uses
   `QUAD_CORNER_SHARED_FRAME` on `building`, `landcover`, `landuse`, `water` and
   `LINE_CONTINUATION_SHARED_FRAME_HEADING` on `transportation`, `transportation_name`; the file's per-layer table
   was never updated. The `openzl-pipeline.md` S2-S7 tables are `_ROT` runs while the registry base is now `_MP`.
   `forLayer` is fitted on the same Munich corpus the numbers are measured on (`--step fit`, in-sample), and five
   layers use `Reorder.CHAIN`, so it depends on `GeometryReorder` and on the property-column trade-off the file
   describes. The `Reorder` Javadoc still calls `CHAIN`/`CANON_CHAIN` "only references".
5. **`openzl-pipeline.md` has no tile-selection figures**, although `--step tile`, `TileSelector` and logs exist.
6. **Two percentage conventions are mixed across the results files.** The benchmark, `orientation-prior.md`, the README
   best-row sentence and `openzl-pipeline.md` compare every column against the smaller baseline's *gzip* size;
   `general-purpose-compression.md` compares each compressor with its own `DELTA_ZIGZAG_VARINT` (and used Brotli
   quality 9, the default is now 11). The same row therefore reads -15,21% Brotli in one file and -11,24% in another.
   Both reproduce from the dump; no file states which convention it uses.
7. **`decode-time.md`** still runs the retired rANS grid and names a stale corpus.
8. **Buffer counts differ**: README 4.370, `openzl-pipeline.md` 4.368 (the dump: 4.368).
9. **Lost, not reproducible**: the `orientation-prior.md` probe table (TODO in the file), `corner-stream.md`
   (`CornerProbe` removed), `line-continuation-by-zoom.md` `z7_check.py`, the `line-part-chain.md` no-joins
   probe, `delta2_by_position.py` (it lives in `data/extractedBaseline-reports/`, not in the repo).
10. **The dump covers Munich only.** The Chicago, Overture, Tokyo and Sao Paulo columns that several files cite
    (`road-chain.md`, `line-frame-prior.md`, `line-continuation-by-zoom.md`, `turn-radius-by-zoom.md`) are not
    pinned by it. Section 7, D8.
11. **Environment**: building through the Gradle JaCoCo plugin corrupted the sandbox Gradle transform cache
    ("immutable workspace ... modified"); the analysis here uses the JaCoCo jars directly and a separate
    `GRADLE_USER_HOME`. Not a repository defect, but `scripts/coverage.ps1` documents the jar route.

## 7. Decisions needed

Phase 2 (picocli harness, mechanisms ported, bit-stream spec, ArchUnit, jqwik) starts only after these.

- **D1, scope of "the harness".** Merge `BenchmarkCli`, `PipelineCli`, `PipelineTileSizeCli`, `DecodeTimeCli`,
  `WorkshopCli`, `ExtractCli`, `GoldenDumpCli` and the figure-citing probes (section 4.1) into one picocli program,
  keeping a Gradle task per old command that forwards to it? Or keep the probes as separate mains?
- **D2, the cited "best row".** The thesis cites `QUAD_CORNER_SHARED_FRAME_RING_CANONICAL_REORDER` -8,19% gzip as the
  best row. The pipeline row in section 5 is -9,95% gzip. Do I keep the old number as a labelled "recorded reference"
  and add the new one beside it, and who updates README/`results/README.md`? (No number changes without your approval;
  this is the one place the sentence itself would change.)
- **D3, road chain and merge (row C).** Freeze (recommended: 8,6 kB of joins at stake, topology gain unmeasured by
  this harness) or port with a topology stream in the harness. Deletes `RoadChainCli`, `RoadChainEncoding`,
  `RoadMerge*`, `MergeEquivalence` if frozen.
- **D4, rANS and its probes.** Delete `RansCodec`, `SegmentedRansCodec`, `SegmentedEncoding`, `ShareTableCli`,
  `EntropyCli`, `CondEntropyCli` and the rANS decode grid; keep the results files as history?
- **D5, failed mechanisms and dead probes.** Delete the 12 `failed/` classes that no pipeline row calls (all but `TurnScalar`), their tests
  and `Baselines.retired()/revisited()/bwtMtf()/tileStandardDelta()`; delete the probes without a current figure
  (`TurnScalarCli`, `Turn90BitmapCli`, `ReflexSignalCli`, `RingPriorCli`, `BuildingShapeCli`, `QuadCornerResidualCli`,
  `QuadRotationCli`, `FeatureBoundaryCli`, `TileSizeCli`) and the dead `chart` task? Recoverable from the
  `pre-consolidation` tag; the `failed/` docs stay and get a "code removed at tag" line.
- **D6, the workshop.** Rebase it on the pipeline `Prediction` (the predictors become `Prediction` values or
  experiments) and drop `PredictedTransform` / `Layout`, or keep it as it is?
- **D7, selection machinery.** `Selector`, `TileSelector`, `StageRules`, `BufferFeatures`, `ChoiceCorrelation`,
  `SelectionReport`: the documented verdict is "discarded". Keep as an opt-in report, or delete?
- **D8, corpora in the dump.** Add Chicago, Overture (and Tokyo, Sao Paulo) extractions to the golden dump so the
  cross-corpus columns of `road-chain.md`, `line-frame-prior.md` are pinned too? The dump scales with corpus size
  (Munich: about 1,5 h).
- **D9, the drifted per-layer numbers (section 6, item 4).** Update the cited figures to the current run, or pin
  the old composition as a named row so the old figure reproduces?
