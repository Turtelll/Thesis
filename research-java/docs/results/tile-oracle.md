# Real per-(tile, layer) oracle over predictors and stages

**Status:** measured (`measure --step best`); an upper bound, not a pipeline row
**Corpus:** `munich-plantiler.mbtiles` (`data/extractedBaseline`)

## Question

`PipelineRegistry.forLayer` fixes one composition per layer. How much compression does that leave on
the table if every (tile, layer) buffer may take whichever composition of predictor and stages is
best for it, encoding time and decoder complexity disregarded? A layer's best predictor is not the
best on every buffer: on some, plain `DELTA` beats `QUAD_CORNER_SHARED_FRAME`.

## What is searched

`PipelineRegistry.bestGrid()`, 252 compositions: every `Prediction` (14, `TURN_SCALAR` included) x
`FLAT` or `CLASS` streams x three byte terminals (escape with byte planes, escape, split varint) x three
reorders (`NONE`, `HILBERT_CENTROID_ROT_MP`, `CHAIN`), all on `DEFAULT`'s zigzag map. Every choice but
the reorder is in the buffer's two header bytes, and the reorder needs no signal, so a choice per
buffer costs nothing on the wire. The grid holds `DEFAULT` and every `forLayer` composition.
The stages that lost in S3 to S7 (role streams, the fixed 1/4-byte widths, the x/y-separating
transposes) and the other curve reorders are not in the grid.

Two oracles per backend:

- `**ORACLE <backend>**`: per buffer, the composition smallest when the buffer is compressed alone. The
per-buffer oracles of [openzl-pipeline.md](openzl-pipeline.md#oracle-which-combination-compresses-best)
showed that this over-selects: a tile compresses the concatenation of its buffers.
- `**ORACLE TILE <backend>**`: coordinate descent on the compressed tile (`TileSelector.oracle`). It
starts from the smaller of two tiles (every buffer on its `forLayer` composition, or every buffer on
its alone-best), then one buffer at a time takes the candidate that makes the whole tile smallest with
the others held, until a pass changes nothing (at most 10 passes). It is never larger than either
start under its own compressor. It is a local optimum, not the exact one, which would need every
assignment of the 252 candidates to the tile's buffers.

The trial sees the geometry buffers only. What a reorder costs a layer's property columns is not in it,
as in every other `measure` row.

## Command

```
./gradlew research --args="measure --step best --by-zoom"                 # gzip, the reference metric
./gradlew research --args="measure --step best --search gzip,zstd"         # also search under zstd (gzip is always searched)
./gradlew research --args="measure --step best --size-all"                 # gzip search, the choices sized under gzip, Brotli 11, zstd 19
```

Only the baselines and the chosen buffers are round-tripped in the run; `PipelineEncodingTest`
round-trips the whole grid.

## Smoke run (10 tiles, z0 to z9, 64 buffers, gzip)

Not representative of the corpus (no z14, where 73% of the buffers are); it shows what the report says.

| row | gzip per tile | vs ref |
|---|---|---|
| `DELTA_ZIGZAG_VARINT` (ref) | 326.381 | ref |
| best single composition of the grid | 316.894 | -2,91% |
| `PER LAYER` (`forLayer`) | 315.138 | -3,44% |
| `ORACLE gzip` (alone-best per buffer) | 314.376 | -3,68% |
| `ORACLE TILE gzip` (descent on the tile) | 314.013 | **-3,79%** |

The tile oracle is 0,36% under `PER LAYER`, the alone oracle 0,24%. On these tiles plain `DELTA` is the
alone-best on 45% of the buffers (all of `park`, `mountain_peak`, `aerodrome_label`, most of `place`),
and the tile oracle still takes it on 28%. The best fixed composition per layer on the grid (buffers alone) is
smaller than `forLayer` on 7 of 11 layers, by more than 0,3% on 5 of them and by up to -1,97% (`boundary`:
`CHAIN` + `QUAD_CORNER_FRAME`); 64 buffers are too few to refit `forLayer` on.

## Full corpus (gzip)

`measure --step best --by-zoom`: 4.368 buffers, 358 tiles, 22 minutes, log `research-java/build/pipeline-best.txt`;
baselines and every chosen buffer round-tripped. Per tile, payload bytes, against `DELTA_ZIGZAG_VARINT`.

| row | gzip | vs ref |
|---|---|---|
| `DELTA_ZIGZAG_VARINT` (ref) | 11.237.124 | ref |
| best single composition of the grid | 10.118.776 | -9,95% |
| `PER LAYER` (`forLayer`) | 9.960.430 | -11,36% |
| `ORACLE gzip` (alone-best per buffer) | 9.925.196 | -11,67% |
| `ORACLE TILE gzip` (descent on the tile) | 9.895.841 | **-11,94%** |

The tile oracle is 0,65% (64.589 B) under the fixed per-layer default and 2,20% under the best single
composition; the alone oracle is 0,35% under the default, so choosing on the tile finds almost twice the
gain of choosing on the buffer. This is the ceiling of the pipeline's mechanisms on this corpus under
gzip, with the choice free: about 0,65 points more than `forLayer` already has.

**Does the per-layer fit leave anything?** The best fixed composition per layer on the grid (buffers
alone) equals `forLayer` on 14 of 16 layers. It differs on `transportation` (-0,40%, `CHAIN` +
`LINE_CONTINUATION_SHARED_FRAME_HEADING`, flat streams, split varint, against class streams with
escape and planes) and `aerodrome_label` (31 buffers, 850 B); over all layers -0,10%. The per-layer
default was fitted well; what the oracle finds is variation inside a layer, not a wrong layer choice.

**What the tile oracle picks** (share of all 4.368 buffers): Hilbert reorder 45,9%, chain 22,5%, none
31,6%; plain `DELTA` 27,7%, `QUAD_CORNER_SHARED_FRAME` 16,7%, `LINE_CONTINUATION_SHARED_FRAME_HEADING`
14,9%, `QUAD_CORNER` 11,3%, `QUAD_CORNER_FRAME` 6,8%, the other predictors under 6% each; class streams
39,8%; split varint 45,5%, byte planes 29,3%, escape 25,3%. At z14 (3.188 buffers) the picture is the
same. No composition dominates: plain `DELTA` is the choice on more than a quarter of the buffers.

**Search under gzip, or on bytes that are not compressed?** Under gzip. A search on raw bytes or
byte-entropy estimates picks worse compositions (raw -9,19% gzip against -11,94%, order-1 -11,15%), see
[failed/raw-search.md](failed/raw-search.md); those flags were removed.

**Reading it.** An oracle that charges nothing for the choice is worth 0,65 points of gzip over a
well-fitted fixed composition per layer, at 252 encodes and a tile-level search per tile. The property
columns are not in the trial, so the reorder choices (chain and Hilbert on 68% of the buffers) are not
priced for what they do to them.

## Pruning the grid

**Question.** The oracle chooses among 252 compositions. Which options of which step does it rarely
pick, and can they go, to search fewer compositions and keep the encoder smaller?

**Command.** `measure --step best --by-zoom` now also prints, per option, how often the alone oracle and
the tile oracle pick it, its share of the chosen bytes, and what the alone oracle loses when every
composition using it is removed, followed by a greedy elimination (always remove the cheapest option, one
option per decision stays). `--exclude <option>,..` takes options out of the grid for both oracles
(`TURN_SCALAR`, `CHAIN`, `CLASS`, `VARINT_SPLIT`, `ESCAPE_INTERLEAVED_PLANES`, any predictor or reorder
name), which confirms a pruned grid on whole tiles. Logs: `research-java/build/pipeline-best.txt`,
`pipeline-best-pruned.txt`, `pipeline-best-pruned2.txt`.

### Pick share and drop cost, full grid (4.368 buffers, gzip)

"alone" and "tile" are the share of buffers whose alone oracle / tile oracle picked the option, "bytes" its
share of the alone oracle's chosen bytes, "drop cost" the alone oracle's bytes (10.054.648) without it.

| decision | option | alone | tile | bytes | drop cost |
|---|---|---|---|---|---|
| reorder | `HILBERT_CENTROID_ROT_MP` | 41,5% | 45,9% | 63,5% | +2,204% |
| reorder | `NONE` | 36,7% | 31,6% | 7,0% | +0,007% |
| reorder | `CHAIN` | 21,8% | 22,5% | 29,5% | +0,295% |
| predictor | `DELTA` | 40,8% | 27,7% | 12,5% | +0,000% (17 B) |
| predictor | `QUAD_CORNER_SHARED_FRAME` | 16,8% | 16,7% | 39,2% | +0,848% |
| predictor | `LINE_CONTINUATION_SHARED_FRAME_HEADING` | 15,0% | 14,9% | 25,6% | +0,516% |
| predictor | `QUAD_CORNER` | 6,0% | 11,3% | 4,1% | +0,000% (0 B) |
| predictor | `QUAD_CORNER_FRAME` | 5,9% | 6,8% | 8,9% | +0,000% (3 B) |
| predictor | `LINE_CONTINUATION` | 3,4% | 5,4% | 1,2% | +0,000% (0 B) |
| predictor | `QUAD_CORNER_SHARED` | 3,2% | 3,9% | 3,8% | +0,029% |
| predictor | `LINE_CONTINUATION_FRAME_HEADING` | 2,4% | 3,7% | 2,1% | +0,008% |
| predictor | `LINE_CONTINUATION_HEADING` | 2,1% | 2,5% | 0,3% | +0,000% (40 B) |
| predictor | `QUAD_CORNER_LINE` | 0,8% | 2,8% | 0,7% | +0,000% (17 B) |
| predictor | `LINE_CONTINUATION_FRAME` | 1,2% | 1,4% | 0,4% | +0,000% (15 B) |
| predictor | `QUAD_CORNER_LINE_FRAME` | 0,7% | 1,4% | 0,5% | +0,000% (32 B) |
| predictor | `LINE_CONTINUATION_SHARED` | 1,1% | 0,6% | 0,1% | +0,001% (59 B) |
| predictor | `TURN_SCALAR` | 0,6% | 0,9% | 0,5% | +0,000% (44 B) |
| segmentation | `FLAT` | 68,9% | 60,2% | 36,4% | +0,096% |
| segmentation | `CLASS` | 31,1% | 39,8% | 63,6% | +1,197% |
| byte terminal | `VARINT_SPLIT` | 57,3% | 45,5% | 31,7% | +0,306% |
| byte terminal | `ESCAPE_INTERLEAVED_PLANES` | 32,3% | 29,3% | 53,4% | +0,160% |
| byte terminal | `ESCAPE` | 10,5% | 25,3% | 14,9% | +0,007% (745 B) |

Each drop cost is for that option alone, with the rest of the grid in place. Two similar options dropped
together can cost more than the sum, which is what the elimination order and the runs below check.

Greedy elimination on the alone oracle (cumulative loss against the 252-composition oracle): ten predictors
(`QUAD_CORNER`, `LINE_CONTINUATION`, `QUAD_CORNER_FRAME`, `LINE_CONTINUATION_FRAME`, `DELTA`,
`QUAD_CORNER_LINE`, `LINE_CONTINUATION_HEADING`, `TURN_SCALAR`, `QUAD_CORNER_LINE_FRAME`,
`LINE_CONTINUATION_SHARED`) go for +0,004% (402 B, 72 compositions left); then `NONE` +0,012%, `ESCAPE`
+0,019%, `QUAD_CORNER_SHARED` +0,051%, `LINE_CONTINUATION_FRAME_HEADING` +0,083%, `FLAT` +0,198%,
`VARINT_SPLIT` +0,440%, `CHAIN` +0,708%, and last `LINE_CONTINUATION_SHARED_FRAME_HEADING` +3,158%.

### Which options are the least chosen, per step

Rule: tile-oracle share under about 2% and a cumulative alone-oracle loss under about 0,05%.

| step | least chosen (tile share) | verdict |
|---|---|---|
| reorder | none; `NONE` 31,6%, `CHAIN` 22,5%, Hilbert 45,9% | keep all three. `NONE` costs only 744 B alone, but the property columns are not in the trial, so what a reorder costs them is unpriced and `NONE` is the safe option |
| predictor | `LINE_CONTINUATION_SHARED` 0,6%, `TURN_SCALAR` 0,9%, `QUAD_CORNER_LINE_FRAME` 1,4%, `LINE_CONTINUATION_FRAME` 1,4% | drop (Run B). Boundary: `LINE_CONTINUATION_HEADING` 2,5%, `QUAD_CORNER_LINE` 2,8% (Run C) |
| segmentation | `FLAT` 60,2% / `CLASS` 39,8% | keep both (a drop costs +0,096% / +1,197%) |
| byte terminal | none under 25% | keep all three. `ESCAPE` costs 745 B alone (+0,007%) but the tile oracle takes it on 25,3% of the buffers; not run without it |

The alone oracle also calls `QUAD_CORNER`, `LINE_CONTINUATION` and `QUAD_CORNER_FRAME` free (0 to 3 B),
because a shared or heading variant matches them on every buffer, yet the tile oracle picks them on 11,3%,
5,4% and 6,8%. Where their picks come from (ties on small buffers, or context shared with neighbouring
buffers) was not measured, and a grid without them was not run. `DELTA` (27,7%) is the identity predictor and
needs no reference state, so it stays as the fallback.

### Confirmed on whole tiles (gzip, 4.368 buffers, 358 tiles)

| run | excluded | compositions | `ORACLE TILE gzip` | vs full grid | vs ref | time |
|---|---|---|---|---|---|---|
| A, full grid | none | 252 | 9.895.841 | | -11,94% | 35:54 |
| B | `TURN_SCALAR`, `LINE_CONTINUATION_SHARED`, `QUAD_CORNER_LINE_FRAME`, `LINE_CONTINUATION_FRAME` | 180 | 9.895.901 | +60 B (+0,0006%) | -11,94% | 23:46 |
| C | B and `QUAD_CORNER_LINE`, `LINE_CONTINUATION_HEADING` | 144 | 9.895.905 | +64 B (+0,0006%) | -11,94% | 17:15 |
| D | `ESCAPE`, `FLAT`, `TURN_SCALAR` | 78 | 9.901.378 | +5.537 B (+0,056%) | -11,89% | 9:50 |
| E | `ESCAPE`, `ESCAPE_INTERLEAVED_PLANES` (split varint only) | 84 | 9.959.398 | +63.557 B (+0,642%) | -11,37% | 6:53 |

`PER LAYER` is 9.960.430 in every run, so the oracle stays 0,65% under the per-layer default. The alone
oracle row is 9.925.196 / 9.925.039 / 9.924.948: it is the per-tile compression of the chosen buffers, not
the alone sum, so it can move either way. The times are wall clock on one machine, run after each other,
and are not a controlled benchmark; the 252-composition run is slower than the 22 minutes of the first full
run. The cost of the tile search scales with the grid, so 144 of 252 compositions is about half the work in
the final run.

Run B removes no layer default. Run C removes the defaults of `place` and `housenumber`
(`QUAD_CORNER_LINE`) and `mountain_peak` (`LINE_CONTINUATION_HEADING`); the oracle still ends at the same
size, but `forLayer` was not refitted without them, so removing those two predictors from the encoder
would need that refit first.

**Run D, the stage options out.** `ESCAPE` (the terminal without byte planes), `FLAT` and `TURN_SCALAR`
excluded: 78 compositions, a third of the grid, in 9:50. The tile oracle is 9.901.378 B, 5.537 B (+0,056%)
above the full grid and -11,89% against the reference, 0,59% under `PER LAYER` (0,65% with the full grid).
The alone oracle is 9.927.252 (+2.056 B). The drop is not free the way the predictors of B and C were, but
it is small: the 0,056% is what the tile oracle loses without flat streams and plain escape, and the
alone-oracle estimate of dropping `FLAT` and `ESCAPE` (+0,096% and +0,007%) was in the same range.
`FLAT` is the default of 12 of the 16 layers (every layer but `landcover`, `landuse`, `poi`, `building`),
so those defaults are out of the grid; `PER LAYER` still uses them, and a pipeline without flat streams
would need `forLayer` refitted on `CLASS` first, which this run does not measure. In the per-layer table
(each buffer compressed alone, `ORACLE TILE` against Run A, in points) the loss is on the line layers:
`aeroway` +0,63, `waterway` +0,57, `boundary` +0,41, `water_name` +0,30, `transportation_name` +0,27,
`transportation` +0,14 (about 3,9 KB of the 5,5 KB, since it is the second largest layer); `building`, `poi`
and `place` do not move, and `aerodrome_label` (-0,88) and `mountain_peak` (-0,30) get smaller, on 31 and
123 buffers.

**Run E, split varint as the only byte terminal.** Stages 6 and 7 reduced to `VARINT_SPLIT` (both escape
terminals excluded, `Transpose.NONE` throughout): 84 compositions in 6:53. The tile oracle is 9.959.398 B,
63.557 B (+0,642%) above the full grid and -11,37% against the reference. That is the whole oracle gain:
`PER LAYER` is 9.960.430 B, so the oracle ends 0,01% under it, against 0,65% with the full grid; the best
a split-only encoder reaches is what the per-layer default (with planes and escape) already delivers. The
alone oracle is 9.975.826 (+50.630 B). The loss is on the layers whose default is a class-stream layout with
byte planes (seven defaults leave the grid: `building`, `housenumber`, `landcover`, `landuse`, `poi`,
`transportation`, `water`), in the per-layer table (each buffer alone, `ORACLE TILE` against Run A, in
points): `housenumber` +1,51 (about 13 KB, the worst: it goes from +3,79% to +5,30% over the baseline,
i.e. split varint is worse than the plain baseline there), `building` +0,21 (about 8 KB), `landcover`
+0,40, `poi` +0,75, `landuse` +0,34. Escape with byte planes carries real bytes on these layers; the
line layers, on split varint already, barely move (`waterway`, `boundary` and `aeroway` are within a
point, and some are smaller).

### Verdict

- **Predictors:** `LINE_CONTINUATION_SHARED`, `TURN_SCALAR`, `QUAD_CORNER_LINE_FRAME` and
`LINE_CONTINUATION_FRAME` are never needed (+60 B of 9,9 MB). `LINE_CONTINUATION_HEADING` and
`QUAD_CORNER_LINE` add 4 B more, but are layer defaults of three layers. That is 4 to 6 of 14 predictors,
and a grid 29 to 43% smaller.
- **Run D (`ESCAPE`, `FLAT`, `TURN_SCALAR` out):** a third of the grid for +0,056% of gzip; the price of
going further than the predictors, paid on the line layers, and it needs `forLayer` refitted on `CLASS`.
- **Run E (split varint only):** loses the whole oracle gain (+0,642%) and puts `housenumber` above the
baseline; the byte planes and escape stay.
- **Reorder, segmentation, terminal:** every option is picked on at least a quarter of the buffers or
carries real bytes; nothing to cut in this measurement.
- **Not run:** a grid without `ESCAPE`, or without `QUAD_CORNER`, `LINE_CONTINUATION` and
`QUAD_CORNER_FRAME`; these are the next candidates, and only a run with `--exclude` can say.
- The encoder, the golden dump and `PipelineRegistry.all()` are unchanged: `bestGrid` is not part of them.
`./gradlew test` passes. The golden-dump diff was not run, since no kept row changed.

### Options outside the grid

The oracle never picks these (they are not in `bestGrid`), they are the other options of each step. Each
is still referenced, so removing one is a separate decision:

| step | option | still used by |
|---|---|---|
| reorder | `HILBERT_START`, `HILBERT_CENTROID`, `MORTON_START`, `MORTON_CENTROID`, `HILBERT_START_ROT`, `HILBERT_START_VC` | S1 rows (`CompositionGrids.s1Orders`), `TileSizeProbe` (the first four), `ReorderMechanismTest` |
| reorder | `HILBERT_CENTROID_ROT` | `StepRows` (frame and S8 rows), `MeasureHarness` (the `BufferSelector` rows of `--step selector`), `ReorderMechanismTest` |
| reorder | `CANON_CHAIN` | `StepRows` (line-shared rows), `TileSizeProbe` |
| reorder | `CLASS_HILBERT_CENTROID_ROT` | `StepRows` (S3 rows), `SegmentationMechanismTest`; left out of `s1Orders` |
| segmentation | `ROLE`, `ROLE_CLASS` | `StepRows` (S3, S6, S7 rows), `StepProbes` (`ROLE`) |
| byte width | `VARINT` | `Composition.IDENTITY`, S0, S6 rows |
| byte width | `MIN_FIXED`, `FORCE_4` | S6, S7, S8 rows, `StreamCodec` (`FORCE_4`), `WidthMechanismTest`, `TransposeMechanismTest` |
| transpose | `PLANE_MAJOR`, `COMPONENT_MAJOR` | S7 rows, `TransposeMechanismTest` |

`HILBERT_CENTROID_ROT_MP` and `CHAIN` are the only reorders in the grid, `NONE` the identity.
