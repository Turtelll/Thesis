# Split varint

**Code names:** `VARINT_SPLIT`, pipeline S0/S6

> **Verdict: Kept.** -0,87% gzip against plain Varint for the same values, size unchanged before compression;
> the varint terminal of the pipeline.

## Encoding target
all layers (Point, LineString, Polygon); byte terminal of every stream.

## Idea
Most zigzag residuals fit in one varint byte; a few need two or three. In a plain varint stream the rare
continuation bytes keep interrupting the run of small one-byte values. Put the first byte of every value in
one block and all continuation bytes after it: same size, but the first block becomes a clean run of small
symbols that gzip/Brotli/zstd compress better.

## Intention
This changes no value and no size, only the byte order, so any gain is the compressor's. The point is to test
how much of the delivery size is decided by byte layout rather than by prediction, and to give every
predictor a better terminal for free.

## How the encoding works
`encoder/stream/IntCodec.VarintSplit`:
- Unsigned LEB128 varint per value.
- `heads[i]` = low 7 bits of value `i` with the continuation bit set if more bytes follow; `heads` has exactly
  one byte per value.
- All continuation bytes (7 bits + continuation flag) of all values go into `tails`, in value order.
- Output `heads ‖ tails`. In the pipeline the body is prefixed by `varint bodyLength` unless it is the last
  non-empty stream.

```
values:     5     300        7
varint:     05    AC 02      07
split:      05 AC 07 | 02
```

## How the decoder works
The value count `n` is known from the topology. Read `heads[0..n)`; for each head with the continuation bit
set, pull bytes from the tail cursor (starting at offset `n`) until a byte without continuation bit, assembling
7 bits per byte.

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`, on `DELTA_ZIGZAG`): **tileset
-0,87%**, with identical bytes before compression.

**Decisive case for inclusion:** it wins under every compressor and every other byte layout tried loses
(`VARINT_XY` +0,58%, `BYTESPLIT` +3,87%, `RAW` +41,63% gzip); it stacks with the predictors
(`QUAD_CORNER_SHARED_VARINT` -3,07% → `_VARINT_SPLIT` -3,78%). The tile oracle picks it on 45,5% of buffers,
and a grid with only split varint loses the whole oracle gain, so it stays next to
[escape width](escape-byte-width.md).

Supporting figures (same metric):

| layout on `DELTA_ZIGZAG` | gzip | Brotli 9 | zstd 19 |
|---|---|---|---|
| `VARINT` | ref | ref | ref |
| **`VARINT_SPLIT`** | **-0,87%** | **-1,79%** | **-1,73%** |
| `VARINT_XY` | +0,58% | +0,80% | +0,71% |
| `BYTESPLIT` | +3,87% | +0,08% | -0,92% |
| `RAW` | +41,63% | +23,46% | +23,28% |

- With predictors: `QUAD_CORNER_SHARED_VARINT` -3,07% → `_VARINT_SPLIT` -3,78% → `_SEG_VARINT_SPLIT` -5,58%
  gzip / -6,79% Brotli / -6,53% zstd.
- Pipeline S0 anchor: `PIPE_DELTA_FLAT_ZIGZAG_VARINT_SPLIT` -0,81% gzip / -2,53% Brotli 9 / -1,99% zstd
  (header +0,06%). In S6 it is level with `ESCAPE` (-5,86% vs -5,90% gzip) and best under Brotli and zstd
  without planes.
- Tile oracle: picked on 45,5% of buffers, drop cost +0,306%; a split-only grid loses the whole oracle gain.

Source: `research-java/docs/results/general-purpose-compression.md`, `openzl-pipeline.md`, `tile-oracle.md`.
