# 9. S7 Transpose

## What it does
Reorders the bytes of a fixed-width stream before the backend. In a fixed-width stream the low bytes carry
the information and the high bytes are mostly zero; writing all low bytes first, then all high bytes, turns
the high plane into long zero runs and the low plane into a homogeneous byte stream.

## Options
`encoder/stream/Transpose.java`; header byte 0 bits 6-7. One index function drives encoder and decoder:
byte `b` of value `i` (of `n` values, x/y interleaved, `width` bytes little-endian) goes to

| option | index | status |
|---|---|---|
| `NONE` (0) | `i·width + b` | used |
| `INTERLEAVED_PLANES` (1) | `b·n + i` | used |
| `PLANE_MAJOR` (2) | per plane all x, then all y | lost |
| `COMPONENT_MAJOR` (3) | x as planes, then y as planes | lost |

```
values (2 B LE): x0=0x012C y0=0x0005 x1=0x0010 y1=0x0003
NONE:    2C 01 05 00 10 00 03 00
PLANES:  2C 05 10 03 | 01 00 00 00
```

## How it works
- Only for fixed widths: `PipelineEncoding` rejects a transpose with a varint terminal.
- Under `ESCAPE` the main stream has width 1, so its planes equal `NONE`; the transpose acts on streams that
  fall back to 2 or 4 bytes. The exceptions of an escape stream are always written as `INTERLEAVED_PLANES`.
- A side stream (one value per vertex, none live) is not x/y pairs, so the x/y-separating layouts fall back
  to `INTERLEAVED_PLANES` there.

## How the decoder works
Same index function: value `i` = OR over `b` of `data[index(i, b)] << 8b`.

## Why
History (gzip / Brotli 9 / zstd 19 vs `DELTA_ZIGZAG_VARINT`): `ESCAPE` without transpose -5,90% / -8,09% /
-6,98%, with `INTERLEAVED_PLANES` -6,51% / -8,79% / -7,93%. The x/y-separating layouts lose about 0,8
points against the interleaved planes.

More: [../encoding/good/byte-plane-transpose.md](../encoding/good/byte-plane-transpose.md),
[../encoding/bad/xy-separating-transposes.md](../encoding/bad/xy-separating-transposes.md),
[openzl-pipeline.md#s7-byte-plane-transpose](https://github.com/Turtelll/Thesis/blob/main/research-java/docs/results/openzl-pipeline.md#s7-byte-plane-transpose).
