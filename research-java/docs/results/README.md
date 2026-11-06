# Results index

> **Consolidation note.** The Gradle task(s) benchmark named below were removed from the build (the code is at git tag `pre-consolidation`); `research measure` and the other `research` commands replace the rest. The numbers below are unchanged history.

> All figures in these files were measured on a **retired corpus** unless a file says
> otherwise — either `munich.mbtiles` (OpenMapTiles v3.11, planet time 2020-02-10, 8.366
> buffers, ~1.355 low-zoom **world mask** tiles taking 63% of its vertex mass) or the
> `germany.mbtiles`-cut Munich AOI that superseded it (planetiler 0.9.1, 2025-07-08: 358
> tiles, 4.272 buffers, `data/extracted`). **The current baseline corpus is
> `munich-plantiler.mbtiles`** (`data/extractedBaseline`, the benchmark's
> default extraction directory): 358 tiles, 4.370 buffers, 75,2% z14 vertex mass — a
> materially different mix from the corpus above (boundary vertices down ~66%,
> low-zoom/world-mask mass near zero). **Percentages below predate this corpus and are
> not comparable to a current run** — re-measure before citing. See
> [reordering.md](reordering.md) for the one result confirmed across corpora.

## Encodings were renamed — old to new

Segmentation and reordering are not baked into an encoding's identity (segmentation is the
`Layout` half of every row, reordering the `--reorder` add-on), so the names flipped: a predictor's bare name
is the core idea measured interleaved, and `_SEG` marks the segmented residual layout. Every
file below still uses the old names; read them through this table rather than rewriting them.

The transform name comes first, then the codec, then any reorder suffix — so
`SEGMENT_QUAD_CORNER_FASTPFOR` reads as `QUAD_CORNER_SEG_FASTPFOR` today.

| old name | new name | what changed |
|---|---|---|
| `SEGMENT_PAIR_*` | `PART_START_SEG_*` | renamed for what it does (groups part starts); segmented layout now explicit |
| `SEGMENT_QUAD_CORNER_*` | `QUAD_CORNER_SEG_*` | segmented layout now explicit in the name |
| `SEGMENT_QUAD_CORNER_ROT90_*` | `QUAD_CORNER_ROT90_SEG_*` | same |
| `ROT90_FIXED_*` | `ROT90_FIXED_SEG_*` | same |
| `SEGMENT_QUAD_CORNER_INTERLEAVED_*` | `QUAD_CORNER_*` | the interleaved control is now the core row, so it carries the bare name |
| `SEGMENT_QUAD_CORNER_RANS_SEG` | `QUAD_CORNER_SEG_RANSSEG` | `RANSSEG` = per-region frequency tables, a different lever from the layout `_SEG` |
| `*_REORDER` / `*_REORDER_REV` | same suffix, but produced by `--reorder` | no longer a registered row |

Renamed again when research encodings became self-contained (each owns its layout,
reordering and serialization; `--reorder`, the `Layout` x `IntCodec` matrix, the FastPFOR
oracle and the `*_RECENT` reference rows are gone). A current row is byte-identical to the old
segmented Varint-split row until its layout is tuned:

| old name | new name |
|---|---|
| `QUAD_CORNER_SEG_VARINT_SPLIT` | `QUAD_CORNER` |
| `LINE_CONTINUATION_SEG_VARINT_SPLIT` | `LINE_CONTINUATION` |
| `QUAD_CORNER_SHARED_SEG_VARINT_SPLIT` | `QUAD_CORNER_SHARED` |
| `QUAD_CORNER_LINE_SHARED_SEG_VARINT_SPLIT` | `QUAD_CORNER_LINE_SHARED` |
| `LINE_CONTINUATION_SHARED_DICT_SEG_VARINT_SPLIT` | `LINE_CONTINUATION_SHARED_DICT` |
| `LINE_CONTINUATION_SHARED_MARKED_SEG_VARINT_SPLIT` | `LINE_CONTINUATION_SHARED_MARKED` |

Ordered by effect size. "Superseded by" marks results a later measurement overturned or
replaced — read the superseding file for the current answer.

## General-purpose compression is the delivery size

gzip, Brotli and zstd are what a browser decodes natively, and FastPFOR's bit-packed output is
opaque to them: per tile, `DELTA_ZIGZAG_FASTPFOR` is +6,7% to +7,4% against `DELTA_ZIGZAG_VARINT`
once compressed. `./gradlew benchmark` now measures only that direction — ideas over Varint,
interleaved and segmented, each tile compressed on its own, against the smaller baseline under
gzip. The flags older result files cite (`--compress`, `--segmented`, `--revisit`, `--std-delta`,
`--tile-std-delta`, `--rans`) are gone; the byte serializers they measured (`VARINT_SPLIT`,
`VARINT_XY`, `BYTESPLIT`, `RAW`) are in `Baselines.retired()`, not benchmarked. The best row found,
`QUAD_CORNER_SHARED_SEG_VARINT_SPLIT`, is -5,58% gzip / -6,79% Brotli / -6,53% zstd per tile
against `DELTA_ZIGZAG_VARINT`. Full matrix, and what did not carry over:
[general-purpose-compression.md](general-purpose-compression.md).

The retired predictors were then re-measured over segmented split varints, ranked by their
smallest compressed tileset size: of fifteen, only `QUAD_CORNER_SHARED_RING_CANONICAL`
(shared references over canonically rotated rings) is the smallest row on any layer
(`building`, -13,62% against -13,36%), so it joined `research()` flagged for follow-up and the
other fourteen went back to `retired()`. Rotating rings to the canonical start and then chaining
features over the rotated starts (`QUAD_CORNER_SHARED_RING_CANONICAL_REORDER`) is the smallest
tileset row: -9,50% against -9,39% for `QUAD_CORNER_SHARED_REORDER`, -13,79% on `building`:
[revisit-varint-split.md](revisit-varint-split.md).

The best single row is now the pipeline's, the frame prior with shared references,
`PIPE_HILBERT_CENTROID_ROT_MP_QUAD_CORNER_SHARED_FRAME_CLASS_ZIGZAG_ESCAPE_PLANES`: **-9,95% gzip /
-15,97% Brotli / -11,68% zstd** per tile (every column against the smaller baseline's gzip, 11.237.124 B,
the benchmark's convention; geometry buffers, 4.368 buffers of `munich-plantiler`). It replaced the old
`QUAD_CORNER_SHARED_FRAME_RING_CANONICAL_REORDER` row (-8,19% gzip / -15,21% Brotli / -9,92% zstd,
-13,35% gzip on `building`: [orientation-prior.md](orientation-prior.md)), which no longer exists as code
(it is at git tag `pre-consolidation`); the pipeline row is 198.207 B smaller on gzip. The frame is also a predictor of the pipeline
(`Prediction.QUAD_CORNER_FRAME`, `--step frame`): on `building` with `HILBERT_CENTROID_ROT_MP`, class
streams and escape width it takes the layer's composition from -9,20% to -12,69% gzip, -13,61% to
-19,80% Brotli, -10,41% to -15,36% zstd, and it is now `forLayer("building")`, and since
`--step framelayers` also of `landcover`, `landuse` and `aeroway` (a win under all three backends; `water`,
`boundary`, `park` stay plain; the later `QUAD_CORNER_SHARED_FRAME` on `building`, `landcover`, `landuse`, `water` and
`LINE_CONTINUATION_SHARED_FRAME_HEADING` on `transportation`, `transportation_name` are in `forLayer` today): the
per-layer default over the whole corpus is **-11,36% gzip / -17,18% Brotli / -13,03% zstd** (9.960.430 B gzip,
9.306.400 B Brotli, 9.773.069 B zstd; geometry buffers, per tile, every column against `DELTA_ZIGZAG_VARINT`
gzip; current `research measure --step layer`), at 5,86x FastPFOR decode on `building`
([openzl-pipeline.md](openzl-pipeline.md#per-layer-default)). Earlier figures of the same row, kept as
history: -7,45% / -13,66% / -9,29% (10.399.717 B gzip, `building` alone -7,36% / -13,55% / -9,16%), measured before
`forLayer` took the shared-reference predictors.

## rANS is retired from reporting

rANS is retired as a research direction: it is not spec-compatible, it adds substantial
encoder complexity, and it does not combine with gzip (browser-native, and therefore the
compressor that matters at delivery). `BenchmarkCli` no longer measures or prints
rANS-based encodings — `RansCodec`/`SegmentedRansCodec` and every encoding built on them
stay registered in `Baselines.retired()` (still round-tripped by `VertexBufferCodecTest`). The rANS figures below (`QUAD_CORNER_SEG_RANS` at -27,16%,
`QUAD_CORNER_SEG_FASTPFOR` at -6,1%'s superseding row, `RANSSEG`, sparse tables, and
`decode-time.md`'s rANS-vs-FastPFOR comparison) are real measurements and are kept as
history, but a current run does not reproduce them. The target is compressed bytes per tile
(`./gradlew benchmark`); residual bits are a probe (`./gradlew research --args="residual-bits"`).

## Wins (registered)

Names below are the **new** ones; the bodies of the linked files still use the old names.

| encoding | effect | file | superseded by |
|---|---|---|---|
| `QUAD_CORNER_SEG_RANS` + `--reorder feature-rev` | -27,16% corpus | [rans-entropy-coder.md](rans-entropy-coder.md) | — |
| Reordering (`--reorder feature-rev`) | -11,2% NY / -11,8% Munich | [reordering.md](reordering.md) | — |
| `QUAD_CORNER_SEG_FASTPFOR` | -6,1% corpus / -12,2% building | [segment-quad-corner.md](segment-quad-corner.md) | rANS variant above |
| `QUAD_CORNER_SHARED_SEG_VARINT_SPLIT` (shared references, segmented, first-byte varint split; the general-purpose row) | **current corpus, per tile:** -5,58% gzip / -6,79% Brotli / -6,53% zstd vs `DELTA_ZIGZAG_VARINT`; segmentation pays here even though the raw size is unchanged. The 1,5x decode budget does not apply: the client decompresses with the browser's native gzip/Brotli/zstd | [general-purpose-compression.md](general-purpose-compression.md) | — |
| `QUAD_CORNER_SHARED_*` (references to vertices shared with earlier rings) | **current corpus:** -2,25% vs `QUAD_CORNER_FASTPFOR` (-7,71% landcover, -3,22% building); `_SEG` -2,66% vs `QUAD_CORNER_SEG_FASTPFOR` (-8,97% vs `QUAD_CORNER_FASTPFOR`; regions ordered starts-last for FastPFOR's VariableByte tail, -0,27%; 128-value blocks a further -0,18%); -5,2% all-in mean bits; previous-ring-only entries (`_PREV` +0,19%, flagless `_PREVOFF` +0,89%) lose to the 4-ring window | [shared-vertices.md](shared-vertices.md) | — |
| `LINE_CONTINUATION_SHARED_DICT_*` (line parts reference earlier parts' positions at entry/exit through a whole-buffer dictionary; parts reversed so end-only junctions start the part) | **current corpus:** `transportation` -8,48% vs `LINE_CONTINUATION_FASTPFOR`, `_SEG` -6,11% vs `LINE_CONTINUATION_SEG_FASTPFOR` (-12,58% vs `DELTA_ZIGZAG_FASTPFOR`; new best on `transportation`); corpus -2,25% / -1,74%; Chicago -6,54% / -5,33%, Tokyo -4,70% / -2,84%; `waterway` -6,0%. 4-part window -0,12% and follow flags (worse on every corpus) measured and removed. Decode `_SEG` 1,44x-1,47x; reversal changes line direction. `_MARKED` (dictionary holds only part ends the encoder flags as recurring, decoder needs no position table): `transportation` -5,61% / `_SEG` -3,72%, so +3,13% / +2,55% vs `_DICT`, but decode `_SEG` 1,15x-1,24x vs `_DICT_SEG` 1,59x-1,85x in the same runs | [line-shared-vertices.md](line-shared-vertices.md) | — (current best on `transportation`) |
| `QUAD_CORNER_SHARED_FRAME` (ring edges coded in the frame of the nearest decoded ring, refined by the ring's own walls; an exactly invertible integer rotation, nothing transmitted; `building`, `landcover`, `landuse`, `water`) | **current corpus, per tile vs `DELTA_ZIGZAG_VARINT`:** `QUAD_CORNER_SHARED` -5,58% → -6,47% gzip / -11,98% → -13,62% Brotli; composed with the canonical-ring reorder, `QUAD_CORNER_SHARED_FRAME_RING_CANONICAL_REORDER` -8,19% gzip / -15,21% Brotli / -9,92% zstd, the best row at the time (was -7,34% / -13,54% / -8,77%; since replaced by the pipeline row, -9,95% / -15,97% / -11,68%); `building` -13,35% gzip / -20,78% Brotli. Probe: a pre-pass of buffer frames loses to the free decoded neighbour. Decode 5,1x FastPFOR on `building` | [orientation-prior.md](orientation-prior.md) | — |
| `LINE_CONTINUATION_ROAD_CHAIN` (line features chained end to start, forward only, so a road of many features is one continuation run; one join bit per line part, the junction vertex dropped) | **current corpus, `transportation`, per-tile gzip vs `DELTA_ZIGZAG_VARINT`:** Munich -8,38% (`LINE_CONTINUATION` -3,47%, nearest-endpoint feature chaining `_REORDER` -7,83%, `_SHARED_DICT` -9,49%); Chicago -7,04% (`_SHARED_DICT` -5,69%, the smallest row there). Smallest on z12/z13 (Munich -18,6% / -11,6%). Most of it is the order, the joins add 0,3 points. Gate 4 is worse. Lossy `_ROAD_MERGE` (features fused, boundaries dropped for later linear referencing; `Baselines.lossy()`, own section, never in the oracle) a further -0,6% / -0,7% of the row in the vertex buffer and 20-25% fewer line-feature topology entries | [road-chain.md](road-chain.md) | — (not combined with `_SHARED_DICT`) |
| `LINE_CONTINUATION_FRAME` (pipeline predictor: the ring frame prior on line parts — the plain-delta edges `LINE_CONTINUATION` leaves, coded in the frame of the nearest decoded run, refined by the run's own edges; an exactly invertible integer rotation, nothing transmitted; `transportation`) | **`transportation`, per tile vs `DELTA_ZIGZAG_VARINT`, `HILBERT_CENTROID_ROT_MP`, class streams, escape width, planes:** Munich -5,23% → -6,20% gzip / -9,57% → -10,68% Brotli / -6,53% → -7,56% zstd; Chicago -1,33% → -1,50% / -7,57% → -7,97% / -3,35% → -3,64%; Overture -5,63% → -6,33% / -9,71% → -10,43% / -7,17% → -7,93%. Probe: the neighbour's frame beats the tile axes on Munich and Overture and ties on Chicago's axis-aligned grid; the heading frame for the continued vertices is the next row. Now `forLayer("transportation")`; whole-corpus per-layer default -8,25% → -8,40% gzip. Decode 3,31x FastPFOR | [line-frame-prior.md](line-frame-prior.md) | — |
| `LINE_CONTINUATION_HEADING` / `LINE_CONTINUATION_FRAME_HEADING` (pipeline predictors: the continuation residual of a continued line vertex rotated into the direction of the incoming edge, along-track in x and sideways in y; exact integer rotation, the frame is the two vertices before, nothing transmitted; `_FRAME_HEADING` adds the neighbour frame on the plain edges; `transportation`, `transportation_name`) | **`transportation`, per tile vs `DELTA_ZIGZAG_VARINT`, `HILBERT_CENTROID_ROT_MP`, class streams, escape width, planes, `LINE_CONTINUATION_FRAME` → `_FRAME_HEADING`:** Munich -6,20% → -6,89% gzip / -10,68% → -11,55% Brotli / -7,56% → -8,25% zstd; Chicago -1,50% → -1,78% / -7,97% → -8,29% / -3,64% → -3,91%; Overture -6,33% → -7,47% / -10,43% → -11,76% / -7,93% → -9,07%. On Munich gzip the heading alone is worth 0,69 points over plain `LINE_CONTINUATION` (the frame alone 0,97) and the two together 1,66: they add. Now `forLayer("transportation")` and `("transportation_name")`; whole-corpus per-layer default (Munich) -8,40% → -8,64% gzip / -14,57% → -14,82% Brotli / -10,32% → -10,54% zstd. Decode 4,9x FastPFOR (one `atan2` per continued vertex) | [line-frame-prior.md](line-frame-prior.md) | — |
| `PART_START_SEG_FASTPFOR` | -4,5% corpus / -6,5% transportation | [segment-pair.md](segment-pair.md) | — |
| `LINE_CONTINUATION_SEG_FASTPFOR` | -6,7% transportation/LINESTRING (beats `PART_START_SEG`); -0,7% corpus mean residual bits, gated free by geometry type | [line-continuation.md](line-continuation.md) | `LINE_CONTINUATION_SHARED_DICT_SEG_FASTPFOR` on `transportation` ([line-shared-vertices.md](line-shared-vertices.md)) |
| `ROT90_FIXED_SEG_FASTPFOR` | -5,0% building | [rot90-fixed.md](rot90-fixed.md) | — |
| `CONTEXT_DELTA_T2_M32` | -1,1% to -1,8% (weak) | [context-delta.md](context-delta.md) | — |
| Sparse rANS frequency table | -5,1% on `QUAD_CORNER_SEG_RANSSEG`; RANSSEG-vs-RANS corpus verdict goes from +9,62% loss to a wash | [shared-tables.md](shared-tables.md) | — |

## Measured, no encoder built

| topic | finding | file |
|---|---|---|
| >4-vertex ring predictors | baseline delta near-optimal pooled; parity resolution opens WP2/WP3 | [failed/ring-predictors.md](failed/ring-predictors.md) |
| Entropy cost-model (WP7) | 15,15% gap estimate | [rans-entropy-coder.md](rans-entropy-coder.md) (superseded by the coder built) |
| Dropping the zero residual of the predicted quad corner (one-off probe, removed) | residual is rounding noise, exact on only 37 to 42% of corners; flag-gated drop saves 0,0 to 0,3% (bound if all were droppable: 4 to 5%) | [corner-stream.md](corner-stream.md) |
| Two-rectangle L-hexagon decomposition | reduces to alternating-axis scheme or a signalling problem | [failed/two-rectangle.md](failed/two-rectangle.md) |
| Stage 0: second-order corner predictors | dead on arrival | [segment-quad-corner.md](segment-quad-corner.md) |
| Stage 0: turn-scalar | dead on arrival; 2026-09 re-run looked promising on `building` bits/vertex, encoder then regressed (see Regressions) | [failed/turn-scalar.md](failed/turn-scalar.md) |
| Cross-buffer rANS table sharing | needs addressing; not pursued (sparse table alone — see Wins — got the win without it) | [shared-tables.md](shared-tables.md) |
| Stage 0: does `transportation` turn radius vary with zoom | flat on baseline corpus, real trend on Overture corpus — corpus-dependent, no predictor promoted | [turn-radius-by-zoom.md](turn-radius-by-zoom.md) |
| `LINE_CONTINUATION` per zoom (`--by-zoom`, interleaved) | **current corpus:** -1,4% to -4,9% bits on every zoom; bytes -0,3% to -2,3% on z4–z11 (major roads only), ~-0,1% on z12–z14 where `path`/`service` dominate — gain follows road class, not zoom. **Chicago** (`data/extracted-chicago`): net +0,2% bits / +0,3% bytes — grid streets, alleys and bridges erase the win | [line-continuation-by-zoom.md](line-continuation-by-zoom.md) |
| MultiLineString part chaining (`_PART_CHAIN`, workshop only) | **current corpus, `transportation`, per-tile gzip vs `DELTA_ZIGZAG_VARINT`:** `LINE_CONTINUATION_PART_CHAIN_REV` -4,85% (`LINE_CONTINUATION` alone -1,37%), `DELTA_PART_CHAIN_REV` -3,49%, without reversal -3,24% / -1,89%. No two parts of any MultiLineString share an endpoint (0 of 220.720 boundaries), so the joins the idea targets never happen and `CHAIN_CONTINUATION` adds nothing; the whole gain is nearest-endpoint ordering of the parts (mean part-start jump 650 to 438 units). Not yet promoted to `./gradlew benchmark` | [line-part-chain.md](line-part-chain.md) |
| Buffer points (Point features outside the tile extent, never drawn by GL JS) | **current corpus:** 10,74% of vertices are buffer vertices; dropping buffer points saves -24,46% total MLT bytes (mostly properties) — tiling policy (Planetiler `--max-point-buffer=0`; breaks raster rendering), not lossless, out of `VertexBuffer` scope | [buffer-points.md](buffer-points.md) |

## Regressions (unregistered or kept for mechanism tests)

| encoding | effect | file |
|---|---|---|
| Delta-of-delta / reset variants | +9,4% to +14,1% | [failed/delta-of-delta.md](failed/delta-of-delta.md) |
| `DELTA2_ZIGZAG` (port of upstream Rust `ComponentwiseDelta2`, #1806) | **current corpus:** `transportation` +9,8% Munich / +8,8% Chicago (FastPFOR), smaller on 0 of 673 buffers | [failed/upstream-delta2.md](failed/upstream-delta2.md) |
| Junction back-references | net loss for K≥2 | [failed/junction-backref.md](failed/junction-backref.md) |
| `RIGHT_ANGLE_TURN_FASTPFOR` | +1,5% to +8,6% | [failed/right-angle-turn.md](failed/right-angle-turn.md) |
| `QUAD_CORNER_TURN_SEG_FASTPFOR` | worse than plain quad-corner everywhere | [failed/right-angle-turn.md](failed/right-angle-turn.md) |
| `MIDPOINT_REFLECT_*` | +0,9% to +9,9% | [failed/midpoint-reflect.md](failed/midpoint-reflect.md) |
| Shape-reuse feasibility scan (WP5-E5.1) | below the 20% gate | [failed/shape-reuse.md](failed/shape-reuse.md) |
| `TURN_SCALAR_*` (quad-corner + winding-fixed 90° length scalar on even n>4 rings) | building +11,8% / +3,2% seg vs `QUAD_CORNER`; other polygon layers +15–17% | [failed/turn-scalar-encoder.md](failed/turn-scalar-encoder.md) |
| `QUAD_CORNER_SHARED_TURN_SCALAR` (references on 4-vertex rings, turn scalar on larger ones; code removed after measuring) | `building` gzip +1,5% vs `QUAD_CORNER_SHARED` at z14 (+3,4% with the `SIZE` selector); the `_ALL` variant (references on every ring) ties on gzip, -1,2% Brotli / -1,0% zstd at z14, one layer, not benchmarked corpus-wide | [shared-turn-scalar.md](shared-turn-scalar.md) |
| RDP simplification + interpolated residual (`RDP_RESIDUAL_*`) | +2,5% interleaved, -0,2% segmented — still 0 buffer wins on `transportation` | [failed/rdp-residual.md](failed/rdp-residual.md) |
| `STD_DELTA_*` (one fixed encoding for all layers: deltas as tokens from a global vocabulary of standardized lengths — `CHUNK_S`, `QUOTREM_S`, `POW2`, `SNAP` — over fixed `UINT16` / `UINT16_PLANES`; `--compress --std-delta`) | **current corpus, per tile:** best row `PLAIN_UINT16_PLANES` +2,05% gzip / -0,21% Brotli / -1,08% zstd vs `DELTA_ZIGZAG_VARINT`, still behind `VARINT_SPLIT`; every decomposition worse (+2,3% to +32% zstd). LZ77 matches the runs, but only the redundancy the tokens added — Varint is already a layer-independent code | [failed/standard-delta-uint16.md](failed/standard-delta-uint16.md) |
| Standard deltas learned per tile over all layers (`TILE_STD_DELTA_*`, `--compress --tile-std-delta`) | best `VECTOR_K1_SINGLE_VARINT_SPLIT` (one entry per delta) +7,48% gzip / +4,77% Brotli / +3,98% zstd vs `DELTA_ZIGZAG_VARINT`, uncapped +14,17% / +9,40% / +7,31%; larger K is worse | [failed/tile-standard-delta.md](failed/tile-standard-delta.md) |
| BWT / MTF / BWT+MTF in front of gzip/Brotli/zstd (`*_INT_*`, `*_BYTE_*` over every predictor; OpenZL-style, `BwtMtfCodec`) | **current corpus, per tile:** all 48 rows lose to their own plain predictor under every compressor. Best, `QUAD_CORNER_SHARED_REORDER_INT_BWT`, -4,73% gzip / -10,73% Brotli / -6,26% zstd against -7,27% / -13,44% / -8,61% plain; MTF adds +5 to +9 points of gzip and raises byte entropy | [failed/bwt-mtf.md](failed/bwt-mtf.md) |
| `TURN90_BITMAP_*` (previous edge turned ±90°, 1 signalled bit per vertex) + reflex-signal probe | building +3,7% bytes (FastPFOR) despite -2,6% all-in bits; winding derivable (100% spec), but no signal packing pays (best: low-bit flag +2,4%) | [failed/turn90-bitmap.md](failed/turn90-bitmap.md) |

## Cross-cutting analysis

- [openzl-pipeline.md](openzl-pipeline.md) — the separate OpenZL-style pipeline
  (`org.maplibre.mlt.research.pipeline`), step by step S0 to S8 and the per-buffer selection, each with
  a keep/discard verdict. Pure predictors, no shared-vertex references: `DEFAULT` is -3,63% gzip on the
  geometry buffers against the smaller baseline (-6,58% with the reorder, which costs more in the property
  columns than it saves, so the end-to-end default has none). The transpose into byte planes is its
  biggest late win; role streams, the bias map and the GCD do not pay. The per-buffer selection by trial
  compression gains 0,3 to 0,65% isolated but loses 0,0 to 0,3% per tile, and is discarded.

- [mlt-v2-rust-port.md](mlt-v2-rust-port.md) — the pipeline codec ported to Rust (`research-rust`, bit-exact with the Java
  golden dump) and used as the vertex encoder of MapLibre Tile v2: whole-tile gzip of the stock v2 encoder against the tile
  oracle (96 compositions, whole layers permuted so properties follow): -9,25% gzip (+1,2% raw), -5,97% with the fixed per-layer default.

- [tile-oracle.md](tile-oracle.md) — the real per-(tile, layer) oracle (`measure --step best`): the
  best of 252 compositions (96 since the retirement; every predictor x streams x terminal x reorder) per buffer, chosen alone and
  on the compressed tile, against the fixed per-layer default; and which options of the grid the oracle
  rarely picks and what dropping them costs (`--exclude`).
- [general-purpose-compression.md](general-purpose-compression.md) — the delivery size:
  per-tile gzip/Brotli/zstd, why FastPFOR loses there, and which predictor and byte layout
  survive it.
- [selection-oracle.md](selection-oracle.md) — "would a tile ever pick one of these?" —
  the per-buffer selection oracle and what it says about signalling cost.
- [rans-entropy-coder.md](rans-entropy-coder.md) — also covers whether per-region rANS
  tables (`SegmentedRansCodec`) help: conditional on buffer size, net loss corpus-wide
  under the original dense table (superseded — see [shared-tables.md](shared-tables.md)
  for the sparse-table re-measurement, a wash corpus-wide).
- [decode-time.md](decode-time.md) — decode time, rANS vs FastPFOR, interleaved vs
  segmented: rANS decodes faster than FastPFOR corpus-wide, and per-region tables' fixed
  decode cost tracks the same buffer-size crossover as their byte cost.
