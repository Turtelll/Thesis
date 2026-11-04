# 32-bit words and byte planes (`RAW`, `BYTESPLIT`)

## encoding target:
all layers; byte layout of the residual stream

## Idea of the encoding
Let the general-purpose compressor do all the work: write every zigzag residual as a fixed 32-bit word (`RAW`),
or transpose those words into byte planes so that the mostly-zero high bytes form long runs (`BYTESPLIT`, like
Parquet's `BYTE_STREAM_SPLIT`).

## How the encoding works
- `RAW`: every value as 4 little-endian bytes, `x0 y0 x1 y1 ...`.
- `BYTESPLIT` (`IntCodec.ByteSplit`): the same 4-byte words transposed into four planes: all byte 0, then all
  byte 1, then byte 2, then byte 3.

```
values 5, 300:   RAW       05 00 00 00 | 2C 01 00 00
                 BYTESPLIT 05 2C | 00 01 | 00 00 | 00 00
```

## How the decoder works
Value count from topology; `RAW` reads 4 bytes per value; `BYTESPLIT` reads byte `b` of value `i` at
`b·n + i` and reassembles the word.

## results
Current corpus `munich-plantiler`, `DELTA_ZIGZAG`, per tile vs `VARINT`:

| layout | gzip | Brotli 9 | zstd 19 |
|---|---|---|---|
| `BYTESPLIT` | +3,87% | +0,08% | -0,92% |
| `RAW` | +41,63% | +23,46% | +23,28% |

`QUAD_CORNER_SHARED_SEG_BYTESPLIT` is the best zstd row (-6,97%) but -2,86% under gzip vs -5,58% for split
varint. Never beats `VARINT_SPLIT` under gzip. The pipeline's escape width + byte planes is the variant that
pays (escape byte width, interleaved byte planes).
