# Interleaved byte planes (`INTERLEAVED_PLANES`, pipeline S7)

## encoding target:
all layers; fixed-width streams (escape width), largest effect on housenumber (Point) and transportation (LineString)

## Idea of the encoding
In a fixed-width stream the low bytes carry the information and the high bytes are mostly zero. Write all low bytes
first, then all high bytes: the high plane becomes long zero runs and the low plane a homogeneous byte stream.
x and y stay interleaved inside each plane.

## How the encoding works
`encoder/stream/Transpose.java`: byte `b` of value `i` (of `n` values, x/y interleaved) goes to index
`b·n + i`. Applied to fixed widths only; header byte 0 bits 6–7 = 1.

```
values (2 bytes LE): x0=0x012C y0=0x0005 x1=0x0010 y1=0x0003
NONE:    2C 01 05 00 10 00 03 00
PLANES:  2C 05 10 03 | 01 00 00 00
```

## How the decoder works
Inverse of the same index function: value `i` = OR over `b` of `data[b·n + i] << 8b`.

## results
Current corpus `munich-plantiler`, S7, per tile vs `DELTA_ZIGZAG_VARINT`:

| row | gzip | Brotli 9 | zstd 19 |
|---|---|---|---|
| `ESCAPE`, no transpose | -5,90% | -8,09% | -6,98% |
| **`ESCAPE` + `INTERLEAVED_PLANES`** | **-6,51%** | **-8,79%** | **-7,93%** |
| `ESCAPE` + `PLANE_MAJOR` | -5,72% | -8,17% | -7,21% |
| `ESCAPE` + `COMPONENT_MAJOR` | -5,70% | -8,02% | -7,11% |

- +0,6 gzip / 0,7 Brotli / 0,9 zstd over untransposed escape; 0,65 / 0,67 / 0,59 over split varint. `housenumber`
  +3,71% vs +6,70% without, `transportation` -4,93% vs -4,23%, z14 -7,55%.
- Ladder: S7 + planes -0,65 points gzip (`GEOMETRY_BEST` -6,58%). Part of `DEFAULT` and most `forLayer`
  compositions.
- Tile oracle run E (split varint only): +0,642%, `housenumber` +1,51 points.
