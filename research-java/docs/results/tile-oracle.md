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

- **`ORACLE <backend>`**: per buffer, the composition smallest when the buffer is compressed alone. The
  per-buffer oracles of [openzl-pipeline.md](openzl-pipeline.md#oracle-which-combination-compresses-best)
  showed that this over-selects: a tile compresses the concatenation of its buffers.
- **`ORACLE TILE <backend>`**: coordinate descent on the compressed tile (`TileSelector.oracle`). It
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
