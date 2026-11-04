# Escape byte width (`ESCAPE`, pipeline S6)

## encoding target:
all layers; byte terminal of every stream (needed for the byte-plane transpose)

## Idea of the encoding
87% of residuals fit in one byte, none needs more than two. Give every value exactly one byte and mark the rare
large ones with an escape byte `0xFF`; the large values go into a small side stream. Every value then has the same
width, which is what a byte-plane transpose needs, without paying two bytes for all values.

## How the encoding works
`encoder/stream/StreamCodec.java`, per stream after zigzag:
- One descriptor byte: bits 0–1 main width code (0 = 1 B, 1 = 2 B, 2 = 4 B), bit 2 escape flag, bits 3–4 exception
  width code.
- Escape chosen when `n + exceptions·exceptionWidth < n·width` (else `MIN_FIXED`: the narrowest of 1/2/4 bytes
  holding the stream's maximum).
- With escape: main stream of `n` single bytes, `0xFF` marks an exception; then the exceptions (`value - 255`) at
  their own minimal width, laid out as `INTERLEAVED_PLANES`.

```
values:  5  300  7  1000
main:    05 FF   07 FF
exc:     (300-255)=45, (1000-255)=745 at 2 bytes, planes: 2D E9 | 00 02
```

## How the decoder works
Read the descriptor. Without escape: read `n` values at the main width (laid out by the transpose). With escape:
read `n` main bytes, count the `0xFF` markers → number of exceptions, read them at the exception width, and replace
each marker by `255 + exception` in order. `n` comes from the topology.

## results
Current corpus `munich-plantiler`, S6 (`QUAD_CORNER_LINE`, `CLASS`), per tile vs `DELTA_ZIGZAG_VARINT`:

| row | gzip | Brotli 9 | zstd 19 |
|---|---|---|---|
| `VARINT` | -4,55% | -6,30% | -5,09% |
| `VARINT_SPLIT` | -5,86% | -8,12% | -7,34% |
| **`ESCAPE`** | **-5,90%** | -8,09% | -6,98% |
| `MIN_FIXED` | +12,27% | +5,96% | +4,58% |
| `FORCE_4` | +35,18% | +16,00% | +20,41% |

- Level with split varint alone (-0,03 points in the ladder); its value is that it enables the byte planes (S7:
  -6,51%).
- Probe: 12,76% of 10.717.858 values ≥ 255, none > 65.535.
- Tile oracle: escape + planes on 29,3% of buffers (53,4% of bytes, drop cost +0,160%), plain escape on 25,3%.
