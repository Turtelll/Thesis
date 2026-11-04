# Burrows–Wheeler and move-to-front before the compressor (`*_INT_*`, `*_BYTE_*`, `BwtMtfCodec`)

## encoding target:
all layers; stage between the residual streams and gzip/Brotli/zstd

## Idea of the encoding
OpenZL puts a BWT and MTF in front of the general-purpose compressor: BWT sorts symbols by their following context, MTF
turns that locality into small ranks and zero runs, so the entropy coder models the stream more cheaply.

## How the encoding works
`BwtMtfCodec` wraps `VARINT_SPLIT` with a stage `MTF`, `BWT` or `BWT_MTF`, in one of two domains:
- `INT`: stage over the zigzag residuals, then `VARINT_SPLIT`.
- `BYTE`: `VARINT_SPLIT` first, then the stage over the bytes (bzip2-style MTF over 256 symbols).
BWT over a unique sentinel, primary index as one varint in front. 8 predictors × 6 codecs = 48 rows.

## How the decoder works
Read the primary index, invert MTF (rank → symbol with the same move-to-front list), invert BWT (LF mapping from the
primary index), then decode `VARINT_SPLIT` (order of the two steps by domain).

## results
Current corpus `munich-plantiler`, gzip per tile vs `DELTA_ZIGZAG_VARINT`:

| predictor | plain | INT_MTF | INT_BWT | INT_BWT_MTF | BYTE_MTF | BYTE_BWT | BYTE_BWT_MTF |
|---|---|---|---|---|---|---|---|
| `DELTA_ZIGZAG` | -0,87% | +8,74% | +1,68% | +8,41% | +6,00% | +2,01% | +6,30% |
| `QUAD_CORNER` | -3,36% | +4,82% | -0,84% | +5,19% | +2,01% | -0,80% | +3,13% |
| `QUAD_CORNER_SHARED` reordered | **-7,27%** | +0,15% | -4,73% | +1,05% | -1,89% | -4,59% | -0,71% |
| `LINE_CONTINUATION_SHARED_DICT` | -2,62% | +3,97% | -0,89% | +4,68% | +2,23% | -0,82% | +2,88% |

- All 48 rows lose to their plain predictor under every compressor. Best stage row
  `QUAD_CORNER_SHARED_REORDER_INT_BWT` -4,73% gzip / -10,73% Brotli / -6,26% zstd vs -7,27% / -13,44% / -8,61%.
- MTF raises byte entropy (+5 to +9 points gzip); BWT destroys the sequential context. Does not replace segmentation
  (interleaved `QUAD_CORNER` + `INT_BWT` +0,38% gzip vs segmented plain -3,36%). Retired.
