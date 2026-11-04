# 8. S6 Byte width

## What it does
Turns a stream of unsigned values into bytes. 87% of residuals fit in one byte and none needs more than two,
so the question is how to pay for the few large ones.

## Options
`encoder/stream/ByteWidth.java`, `StreamCodec.java`, `IntCodec.java`; header byte 1 bits 0-3.

| option | bytes | status |
|---|---|---|
| `VARINT` (0) | LEB128 varint per value | baseline anchor |
| `VARINT_SPLIT` (1) | first byte of every varint, then all continuation bytes | used |
| `MIN_FIXED` (2) | narrowest of 1/2/4 bytes holding the stream's maximum | lost |
| `ESCAPE` (3) | 1 byte per value, `0xFF` marks an exception stored separately | used |
| `FORCE_4` (4) | 4 bytes per value | test only |

## How it works
**Split varint.** `heads` holds one byte per value (low 7 bits, continuation bit if more follow), `tails` all
continuation bytes in value order; output `heads ‖ tails`. The first block becomes a clean run of small
symbols.

```
values 5 300 7    varint 05 | AC 02 | 07    split 05 AC 07 | 02
```

A varint stream's byte length depends on its values, so it is prefixed by `varint bodyLength` unless it is
the last non-empty stream, which takes the rest of the payload.

**Escape width.** One descriptor byte, then the body:

```
descriptor  bits 0-1  main width (0 = 1 B, 1 = 2 B, 2 = 4 B)
            bit  2    escape flag
            bits 3-4  exception width (same codes)
```

1. Compute the stream's minimal width `w`, the number of values ≥ 255 and the width of the largest
   `value - 255`.
2. If `w > 1` and `n + exceptions · exceptionWidth < n · w`: escape. Main stream of `n` single bytes,
   `0xFF` where the value is ≥ 255; then the exceptions `value - 255` at their width, as byte planes.
3. Otherwise plain `MIN_FIXED` at width `w`.

```
values 5 300 7 1000    main 05 FF 07 FF    exceptions 45, 745 at 2 B as planes: 2D E9 | 00 02
```

No length is written: `n` follows from the topology, the exception count from the `0xFF` markers.

## How the decoder works
Varint: read `n` heads, pull continuation bytes from the tail cursor. Escape: read the descriptor and `n`
main bytes (through the transpose, step 9), count the markers, read that many exceptions, replace each
marker by `255 + exception`.

## Why
History (gzip vs `DELTA_ZIGZAG_VARINT`): `VARINT` -4,55%, `VARINT_SPLIT` -5,86%, `ESCAPE` -5,90%,
`MIN_FIXED` +12,27%, `FORCE_4` +35,18%. Escape alone is level with split varint; it is kept because it is a
fixed width, which the byte-plane transpose needs (step 9). Under Brotli split varint is chosen more often;
the per-layer default uses both.

More: [../encoding/good/split-varint.md](../encoding/good/split-varint.md),
[../encoding/good/escape-width.md](../encoding/good/escape-width.md),
[../encoding/bad/fixed-byte-width.md](../encoding/bad/fixed-byte-width.md),
[openzl-pipeline.md#s6-byte-width](https://github.com/Turtelll/Thesis/blob/main/research-java/docs/results/openzl-pipeline.md#s6-byte-width).
