# 9. Byte planes (S7)

## The idea

In a stream of fixed-width values, the bytes of each value mean different things. The low byte carries
almost all the information; the high byte of a small residual is almost always zero. Written value by
value, the two kinds alternate, `low high low high`, and every zero sits between two informative bytes.
Written as planes, all low bytes first and then all high bytes, the high plane becomes a long run of zeros
that costs the compressor almost nothing, and the low plane becomes one homogeneous byte stream.

This only works for fixed-width values, where byte `b` of every value is in the same place. That is why the
[escape width](08-byte-width.md) exists: it is a fixed width that is as small as the split varint.

## The encodings

`encoder/stream/Transpose.java`. Byte `b` of value `i` (of `n` values, `x` and `y` interleaved,
little-endian) is written at

| option | position | status |
|---|---|---|
| `NONE` | `i · width + b` | used with the split varint |
| `INTERLEAVED_PLANES` | `b · n + i` | used with escape |
| `PLANE_MAJOR` | per plane, all `x`, then all `y` | lost |
| `COMPONENT_MAJOR` | `x` as planes, then `y` as planes | lost |

```
values (2 bytes)    x0=0x012C  y0=0x0005  x1=0x0010  y1=0x0003
NONE                2C 01 05 00 10 00 03 00
INTERLEAVED_PLANES  2C 05 10 03 | 01 00 00 00
```

Under escape, the main stream is one byte per value, so planes and no planes give the same bytes there.
The planes act where values are wider: on the exception list of an escape stream, which is always written as
planes, and on streams where the escape does not pay and the values are written at two or four bytes.

Separating `x` from `y` as well (the two layouts that lost) costs about 0,8 points against keeping them
interleaved.

## Decoding

The same position function, read the other way: value `i` is the OR over `b` of the byte at
`position(i, b)`, shifted by `8 · b`.

## What it is worth

Measured when the stage was added (against plain delta + varint): escape without planes -5,90% gzip,
-8,09% Brotli, -6,98% zstd; with interleaved planes -6,51%, -8,79%, -7,93%. A small stage, but a consistent
one under all three compressors.

Further reading: [byte-plane transpose](../encoding/good/byte-plane-transpose.md),
[x/y-separating transposes](../encoding/bad/xy-separating-transposes.md),
[S7 results](https://github.com/Turtelll/Thesis/blob/main/research-java/docs/results/openzl-pipeline.md#s7-byte-plane-transpose).
