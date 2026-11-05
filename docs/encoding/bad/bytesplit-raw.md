# 32-bit words and byte planes (`RAW`, `BYTESPLIT`)

> **Verdict: Rejected.** `BYTESPLIT` +3,87% gzip, `RAW` +41,63% gzip on the tileset; neither ever beats split
> varint under gzip.

## Encoding target
all layers; byte layout of the residual stream.

## Idea
Let the general-purpose compressor do all the work: write every zigzag residual as a fixed 32-bit word
(`RAW`), or transpose those words into byte planes so that the mostly-zero high bytes form long runs
(`BYTESPLIT`, like Parquet's `BYTE_STREAM_SPLIT`).

## Intention
Varint is a variable-length code, so a value's bytes shift position with every long value before it, which may
hide regularities from the compressor. Fixed words keep every byte at a fixed position; byte planes then turn
the empty high bytes into long zero runs that cost almost nothing after compression. This is the approach
column stores such as Parquet use for floats; the question was whether it also beats varint for small integer
residuals.

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

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`, on `DELTA_ZIGZAG`): **`BYTESPLIT`
+3,87%**, **`RAW` +41,63%** (tileset).

**Decisive case against:** four bytes per value is too much to win back under gzip. The zero planes recover
most of `RAW`'s loss under Brotli and zstd, but under gzip `BYTESPLIT` stays +3,87%. It only wins under zstd (best zstd row `QUAD_CORNER_SHARED_SEG_BYTESPLIT`
-6,97%), and the same row is -2,86% under gzip against -5,58% for split varint. The variant that does pay is
one byte per value with an escape, then planes ([escape width](../good/escape-width.md),
[interleaved byte planes](../good/byte-plane-transpose.md)).

Supporting figures (same metric, `DELTA_ZIGZAG`):

| layout | gzip | Brotli 9 | zstd 19 |
|---|---|---|---|
| `BYTESPLIT` | +3,87% | +0,08% | -0,92% |
| `RAW` | +41,63% | +23,46% | +23,28% |

Source: `research-java/docs/results/general-purpose-compression.md`.
