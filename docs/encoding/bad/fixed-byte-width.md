# Fixed byte widths

**Code names:** `MIN_FIXED`, `FORCE_4`

> **Verdict: Rejected.** `MIN_FIXED` +12,27% gzip, `FORCE_4` +35,18% on the tileset; even with byte planes
> both stay behind the escape width.

## Encoding target
all layers; byte terminal.

## Idea
Give every value of a stream the same number of bytes: the smallest of 1, 2 or 4 that holds the stream's
largest value (`MIN_FIXED`), or always 4 (`FORCE_4`). A fixed width is regular for the compressor and enables
byte-plane transposes.

## Intention
The byte-plane transpose needs fixed-width values. The simplest fixed width is the one the stream's maximum
needs; the hope was that the planes would compress the wasted high bytes away.

## How the encoding works
- `MIN_FIXED`: descriptor byte with the width code, then `n` values little-endian at that width.
- `FORCE_4` (test only): every value 4 bytes, no descriptor.

## How the decoder works
Read the descriptor (if any), then `n` values at the width; `n` from topology.

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`, pipeline S6/S7, `QUAD_CORNER_LINE`,
`CLASS`): **`MIN_FIXED` +12,27%**, **`FORCE_4` +35,18%**; with planes -4,64% / -3,19% against **-6,51%** for
escape + planes.

**Decisive case against:** the width is set by the largest value, and almost every stream has one value that
needs two bytes (10.163 of 11.817 streams), so `MIN_FIXED` doubles the size of most streams before
compression (raw 20,7 million B vs 12,9). The planes win part of it back but not all. The escape width gives
the same fixed width at one byte per value plus a few exceptions. Not in the oracle grid.

Supporting figures (same metric):

| row | gzip | Brotli 9 | zstd 19 |
|---|---|---|---|
| `MIN_FIXED` | +12,27% | +5,96% | +4,58% |
| `FORCE_4` | +35,18% | +16,00% | +20,41% |
| `MIN_FIXED` + planes | -4,64% | -7,87% | -7,50% |
| `FORCE_4` + planes | -3,19% | -7,41% | -7,27% |
| `ESCAPE` + planes (kept) | -6,51% | -8,79% | -7,93% |

- Raw 20,7 / 42,9 million B vs 12,9.

Source: `research-java/docs/results/openzl-pipeline.md`.
