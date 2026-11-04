# Bias residual map (`BIAS`, pipeline S4)

## encoding target:
all layers; residual map stage

## Idea of the encoding
Zigzag folds the sign into the low bit. Alternatively shift every residual of a stream by the stream's minimum, so
all values are non-negative without folding; the bias is written once in front of the stream.

## How the encoding works
Per stream: `bias = min(residuals)`, write `bias`, then `r - bias` for each residual (unsigned). Zigzag:
`(n << 1) ^ (n >> 31)`.

## How the decoder works
Read the bias, add it back to every value of the stream.

## results
Current corpus `munich-plantiler`, per tile vs `DELTA_ZIGZAG_VARINT`:

| row | gzip | Brotli 9 | zstd 19 |
|---|---|---|---|
| `ZIGZAG` | -5,86% | -8,12% | -7,34% |
| `BIAS` | +9,51% | +2,54% | +4,91% |

Larger raw (20,4 vs 12,9 million B), worse on every layer (`poi` -8,96% vs -19,55%); with `MIN_FIXED` +23,66%,
with `ESCAPE` +20,66%. Removed from the encoder; zigzag is the only map.
