# 8. From residuals to bytes: zigzag and byte width (S4, S6)

## The idea

After prediction the streams hold signed integers, and most of them are small: 87% of the residuals fit in
one byte, and none needs more than two. Turning them into bytes takes two decisions. First, the sign has to
be folded in so that small negative values stay small. Second, the few large values have to be paid for
without spoiling the many small ones, because a compressor does best on a long run of uniform single bytes.

## Zigzag (S4)

Zigzag interleaves negative and positive values, so the magnitude decides the size, not the sign:

```
zigzag(n) = (n << 1) ^ (n >> 31)

 0 → 0   -1 → 1   1 → 2   -2 → 3   2 → 4   ...   100 → 200   -30 → 59
```

It is the only residual map. The alternative, subtracting each stream's minimum, made the raw streams 58%
larger and was worse on every layer (+9,51% gzip against -5,86% for zigzag), so it was removed.

## Byte width (S6)

### Split varint

A varint writes 7 bits per byte and sets the top bit when more bytes follow, so small values take one byte
and large ones two or more. The split varint writes the same bytes in a different order: first one byte per
value (the low 7 bits and the continuation bit), then all continuation bytes. The first block becomes a
clean run of small symbols, and the rare extra bytes are collected at the end.

```
values           5   300     7
varint           05 | AC 02 | 07
split varint     05 AC 07 | 02
```

A varint stream's length in bytes depends on its values, so it is prefixed with its byte length, unless it is
the last stream of the payload, which simply takes the rest.

### Escape width

Escape writes every value in one byte and moves the large ones out of the way. A value of 255 or more is
replaced by the marker `0xFF` in the main stream, and `value - 255` is stored in a separate exception list
after it. One descriptor byte in front says how wide the main values and the exceptions are:

```
descriptor   bits 0-1  main width (0 = 1 byte, 1 = 2 bytes, 2 = 4 bytes)
             bit  2    escape flag
             bits 3-4  exception width (same codes)

values       5   300   7   9   1000   12
main         05  FF    07  09  FF     0C
exceptions   45, 745 at 2 bytes, as byte planes: 2D E9 | 00 02
```

Here six values plus two 2-byte exceptions take 10 bytes, against 12 at a plain 2-byte width.

The escape is only used when it is smaller than writing every value at the width of the largest one;
otherwise the stream is written at that plain fixed width. No count is written: the number of values
follows from the topology, the number of exceptions from the markers.

Two other widths were measured and lost: the narrowest fixed width holding the stream's maximum
(+12,27% gzip), and four bytes per value (+35,18%). The plain varint is kept as the baseline anchor.

## Decoding

Split varint: read one head byte per value and pull continuation bytes from the tail when the top bit is
set. Escape: read the descriptor and the main bytes, count the `0xFF` markers, read that many exceptions,
and replace each marker by `255 + exception`. Then undo zigzag with `(v >>> 1) ^ -(v & 1)`.

## What it is worth

Measured when the stage was added (gzip, against plain delta + varint): plain varint -4,55%, split varint
-5,86%, escape -5,90%. Escape alone is level with the split varint; it earns its place because it is a fixed
width, which is what the [byte planes](09-transpose.md) need.

Both are kept, and the choice depends on the layer: the tile oracle picks the split varint on 53% of the
layers and escape with byte planes on 47%. A search limited to the split varint loses the whole gain the tile
oracle has over the per-layer default (+0,64%), and `housenumber` would end above the plain baseline.

In the code: `encoder/stream/ResidualMap.java`, `ByteWidth.java`, `StreamCodec.java`, `IntCodec.java`.

Further reading: [split varint](../encoding/good/split-varint.md),
[escape width](../encoding/good/escape-width.md),
[fixed byte widths](../encoding/bad/fixed-byte-width.md),
[S6 results](https://github.com/Turtelll/Thesis/blob/main/research-java/docs/results/openzl-pipeline.md#s6-byte-width).
