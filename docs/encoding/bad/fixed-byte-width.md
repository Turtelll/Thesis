# Fixed byte widths (`MIN_FIXED`, `FORCE_4`)

## encoding target:
all layers; byte terminal

## Idea of the encoding
Give every value of a stream the same number of bytes: the smallest of 1, 2 or 4 that holds the stream's largest
value (`MIN_FIXED`), or always 4 (`FORCE_4`). A fixed width is regular for the compressor and enables byte-plane
transposes.

## How the encoding works
- `MIN_FIXED`: descriptor byte with the width code, then `n` values little-endian at that width.
- `FORCE_4` (test only): every value 4 bytes, no descriptor.

## How the decoder works
Read the descriptor (if any), then `n` values at the width; `n` from topology.

## results
Current corpus `munich-plantiler`, S6/S7 (`QUAD_CORNER_LINE`, `CLASS`), per tile vs `DELTA_ZIGZAG_VARINT`:

| row | gzip | Brotli 9 | zstd 19 |
|---|---|---|---|
| `MIN_FIXED` | +12,27% | +5,96% | +4,58% |
| `FORCE_4` | +35,18% | +16,00% | +20,41% |
| `MIN_FIXED` + planes | -4,64% | -7,87% | -7,50% |
| `FORCE_4` + planes | -3,19% | -7,41% | -7,27% |
| `ESCAPE` + planes (kept) | -6,51% | -8,79% | -7,93% |

Raw 20,7 / 42,9 million B vs 12,9; 10.163 of 11.817 streams need two bytes, so `MIN_FIXED` doubles most of them.
Discarded; not in the oracle grid.
