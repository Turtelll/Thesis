# Burrows–Wheeler and move-to-front before gzip, Brotli and zstd

**Status:** measured, regression — no row beats its own plain predictor; retired
**Corpus:** current baseline (`munich-plantiler.mbtiles`, `data/extractedBaseline`, 358
tiles). Per tile, as in [general-purpose-compression.md](../general-purpose-compression.md):
a tile's buffers concatenated and compressed on their own. Compressors: gzip level 6,
Brotli at the benchmark's default quality (the column is headed `brotli11`), zstd level 19.
Every percentage is against `DELTA_ZIGZAG_VARINT` under the same compressor.
**Runs:** `./gradlew benchmark` with every row added to `research()` (the code for that is
since removed, see Outcome), plus
`--layer building` and `--layer transportation` for the per-layer rows. Raw output in
`data/out/bwt/`.

## Idea

OpenZL puts a Burrows–Wheeler transform and move-to-front coding in front of the general
purpose compressor. Neither has to shrink the stream: BWT sorts symbols by the context that
follows them, MTF turns that locality into small ranks and zero runs, so the entropy coder
sees a stream it can model more cheaply. The question here is whether that helps the
predictors' residual streams, which already go through `VARINT_SPLIT`.

`BwtMtfCodec` is an `IntCodec` wrapping `VARINT_SPLIT` with a stage `MTF`, `BWT` or
`BWT_MTF`, in one of two domains:

- `INT`: the stage runs over the zigzag residuals, then the ranks/permuted values are
  `VARINT_SPLIT`-coded.
- `BYTE`: `VARINT_SPLIT` first, then the stage runs over the bytes (standard bzip2-style
  MTF over 256 symbols).

The BWT is over a unique sentinel; its primary index is one varint at the front. Both stages
preserve the symbol count, so the raw size changes only through the varint width of the MTF
ranks. Eight predictors times six codecs is 48 rows, each round-tripped by the benchmark and
`VertexBufferCodecTest`.

## Result: every stage loses to its own plain row

Full corpus, gzip, change against `DELTA_ZIGZAG_VARINT`. The first column is the predictor
with plain `VARINT_SPLIT`, which is the row each stage has to beat.

| predictor | plain | INT_MTF | INT_BWT | INT_BWT_MTF | BYTE_MTF | BYTE_BWT | BYTE_BWT_MTF |
|---|---|---|---|---|---|---|---|
| `DELTA_ZIGZAG` | -0,87% | +8,74% | +1,68% | +8,41% | +6,00% | +2,01% | +6,30% |
| `QUAD_CORNER` | -3,36% | +4,82% | -0,84% | +5,19% | +2,01% | -0,80% | +3,13% |
| `LINE_CONTINUATION` | -1,20% | +6,78% | +0,78% | +6,74% | +3,94% | +0,81% | +4,66% |
| `QUAD_CORNER_SHARED` | -5,58% | +1,83% | -3,02% | +2,78% | -0,34% | -2,99% | +0,80% |
| `QUAD_CORNER_SHARED` reordered | **-7,27%** | +0,15% | -4,73% | +1,05% | -1,89% | -4,59% | -0,71% |
| `QUAD_CORNER_LINE_SHARED` | -5,39% | +1,63% | -3,38% | +2,49% | -0,57% | -3,34% | +0,50% |
| `LINE_CONTINUATION_SHARED_DICT` | -2,62% | +3,97% | -0,89% | +4,68% | +2,23% | -0,82% | +2,88% |
| `LINE_CONTINUATION_SHARED_MARKED` | -2,15% | +4,58% | -0,36% | +5,32% | +2,76% | -0,29% | +3,44% |

Brotli and zstd rank the codecs the same way. The best stage row under each:
`QUAD_CORNER_SHARED_REORDER_INT_BWT` at -10,73% Brotli / -6,26% zstd, against -13,44% /
-8,61% for the plain row. No cell of any of the three compressors puts a stage row ahead
of its plain predictor. The per-layer runs agree: `building` best stage row -8,97% gzip
against -10,70% plain; `transportation` best `LINE_CONTINUATION_SHARED_DICT_INT_BWT`
-8,96% against -9,49% plain.

## What the numbers say

- **MTF is the damaging half.** It costs +5 to +9 points of gzip on every predictor and in
  both domains. It also lifts the per-tile byte entropy (`order-0`) of
  `QUAD_CORNER_SHARED_REORDER` from 10.834.187 to 11.591.697 bytes in the `INT` domain,
  so it is not the compressor failing to exploit a lower-entropy stream; the stream has
  more entropy. These residuals are already a skewed, stationary distribution around zero,
  which the natural symbol order codes about as well as any adaptive ranking, and MTF
  turns it into a flatter rank distribution. In the `INT` domain it also grows the raw
  payload (`QUAD_CORNER_SHARED_REORDER` 12.815.460 to 13.553.355) because a fresh small
  value can rank above itself.
- **BWT alone is the closest, and still behind.** It is a permutation, so its order-0 is
  unchanged (10.840.457 against 10.834.187) and the raw size grows only by the primary
  index. It still costs 2,5 to 3 points of gzip on the shared rows. The sorted order
  destroys the sequential structure the plain stream has — the previous residual of the
  same buffer is the best context, and sorting by following context separates those — and
  gives LZ77 nothing back at these sizes. This is an interpretation of the measurements,
  not a separately tested mechanism.
- **Domain is secondary.** `INT` and `BYTE` land within about three points of each other
  for the same stage, and neither gets near the plain row. `BYTE_MTF` is the less bad MTF:
  its raw size equals the plain row, where `INT_MTF` grows it.
- **The loss is not a gzip artefact.** On the best predictor the `INT_BWT` row gives back
  2,5 points under gzip, 2,7 under Brotli and 2,4 under zstd.

## Without segmentation

The matrix above mixes two things: the segmented `QUAD_CORNER` regions and BWT. To see
whether BWT can stand in for the regions, the same predictor was run interleaved (one
`(x, y)` residual pair per vertex, no class regions) through
`VertexBufferCodec(PredictedTransform(SegmentQuadCorner, INTERLEAVED), codec)`. Full corpus,
per tile, against `DELTA_ZIGZAG_VARINT`; run output in `data/out/bwt/interleaved-quad-corner.txt`.

| row (interleaved `QUAD_CORNER`) | gzip | Brotli | zstd |
|---|---|---|---|
| `VARINT_SPLIT`, no stage | -1,75% | -7,22% | -3,19% |
| `INT_BWT` | +0,38% | -5,68% | -1,17% |
| `BYTE_BWT` | +0,72% | -4,34% | -0,59% |
| segmented `QUAD_CORNER`, no stage (reference) | -3,36% | -10,02% | -4,80% |
| segmented `QUAD_CORNER_INT_BWT` (reference) | -0,84% | -7,10% | -2,26% |

BWT does not replace segmentation. Against its own plain row, interleaved `INT_BWT`
loses 2,1 points of gzip (1,5 Brotli, 2,0 zstd), a little less than the segmented rows
(2,5 / 2,9 / 2,5): the stage has less structure to destroy, but it still loses. Interleaved
plus BWT ends 3,7 points of gzip behind segmentation with no stage at all, and segmentation
still helps under BWT (segmented `INT_BWT` -0,84% against interleaved +0,38%). The
byte-order-0 of the two layouts is identical under BWT (11.282.346), since BWT permutes the
same values; the gain segmentation adds is in what LZ77 and the entropy coder see in the
plain stream, which BWT does not reproduce.

## Decode time

Not measured. The decode-time probe is for rows that win; no row does, and inverse BWT and
MTF would be client-side code on top of the browser's native decompressor, so a loss on size
is already decisive.

## Outcome

The 48-row matrix above is history. The research encodings (`QuadCornerEncoding`,
`LineContinuationEncoding`, `SharedVertexRef`, `LineSharedVertexRef`) had an optional codec
parameter for it, since removed, so only the six `DELTA_ZIGZAG_*` rows (every stage in both
domains over the baseline transform) stay registered in `Baselines.retired()`, round-tripped
by the tests and not benchmarked. Reproducing the table means re-adding that parameter.
`BwtMtfCodec` stays in `failed/`. The best delivery row is unchanged:
`QUAD_CORNER_SHARED_REORDER` (-7,27% gzip / -13,44% Brotli / -8,61% zstd on this run).
