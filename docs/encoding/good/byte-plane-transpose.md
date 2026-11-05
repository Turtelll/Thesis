# Interleaved byte planes (`INTERLEAVED_PLANES`, pipeline S7)

> **Verdict: Kept.** -6,51% gzip on the tileset against -5,90% for the same fixed-width stream untransposed;
> part of `DEFAULT` and most per-layer compositions.

## Encoding target
all layers; fixed-width streams ([escape width](escape-width.md)), largest effect on housenumber (Point) and
transportation (LineString).

## Idea
In a fixed-width stream the low bytes carry the information and the high bytes are mostly zero. Write all low
bytes first, then all high bytes: the high plane becomes long zero runs and the low plane a homogeneous byte
stream. x and y stay interleaved inside each plane.

## Intention
gzip, Brotli and zstd see bytes, not numbers. In a stream of 2-byte values every second byte is a near-zero
high byte, so the byte statistics are a mixture of two very different sources, and the zeros are too scattered
to form matches. Separating the planes gives the entropy coder two clean distributions and LZ77 long zero runs.
x and y are kept interleaved because splitting them made things worse elsewhere
([x/y-separating transposes](../bad/xy-separating-transposes.md)). The transpose is a pure byte permutation, so
it costs nothing on the wire beyond two header bits.

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

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`, pipeline step S7): **tileset
-6,51%** with `ESCAPE` + `INTERLEAVED_PLANES` against **-5,90%** for `ESCAPE` untransposed; `transportation`
-4,93% vs -4,23%, `housenumber` +3,71% vs +6,70%.

**Decisive case for inclusion:** +0,6 points on the tileset for a byte permutation that costs nothing, and the
two layout alternatives (`PLANE_MAJOR`, `COMPONENT_MAJOR`) are both worse than no transpose. The tile oracle
picks escape + planes on 29,3% of buffers (53,4% of bytes); it is part of `DEFAULT` and most `forLayer`
compositions.

Supporting figures (same metric):

| row | gzip | Brotli 9 | zstd 19 |
|---|---|---|---|
| `ESCAPE`, no transpose | -5,90% | -8,09% | -6,98% |
| **`ESCAPE` + `INTERLEAVED_PLANES`** | **-6,51%** | **-8,79%** | **-7,93%** |
| `ESCAPE` + `PLANE_MAJOR` | -5,72% | -8,17% | -7,21% |
| `ESCAPE` + `COMPONENT_MAJOR` | -5,70% | -8,02% | -7,11% |

- +0,6 gzip / 0,7 Brotli / 0,9 zstd points over untransposed escape; 0,65 / 0,67 / 0,59 over split varint.
  z14 -7,55%.
- Ladder: S7 + planes -0,65 points gzip (`GEOMETRY_BEST` -6,58%).
- Tile oracle: escape + planes on 29,3% of buffers (53,4% of bytes, drop cost +0,160%); run E (split varint
  only): +0,642%, `housenumber` +1,51 points.

Source: `research-java/docs/results/openzl-pipeline.md`, `tile-oracle.md`.
