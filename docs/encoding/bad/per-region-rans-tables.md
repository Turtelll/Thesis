# Per-region rANS tables (`RANSSEG`, `SegmentedRansCodec`)

## encoding target:
building (Polygon) and all segmented layouts under rANS

## Idea of the encoding
`QUAD_CORNER_SEG_RANS` codes its three regions (starts, interior, corners) with one shared frequency table, pricing
each at the mixture of all three. Giving each region its own table removes the mixture penalty; in ideal bucket
entropy that is worth 16,76%.

## How the encoding works
- Same regions as segmented quad corner.
- One rANS frequency table per region; region lengths written into the payload so decoding stays context-free.

## How the decoder works
Read the region lengths and the per-region tables, rANS-decode each region with its own table, then undo the
prediction as for segmented quad corner.

## results
Retired corpus `munich.mbtiles`:
- `building`: `_RANS` 2.668.361 → `_RANS_SEG` 2.510.165 (-5,93%); with reorder -3,16%, `building` best -29,5%.
- **Corpus: +9,62% loss.** Fixed ~152 B per buffer (two extra 66 B tables + 20 B headers). By size: 0–50 vertices
  +163,87%, 50–200 +52,40%, 200–1000 +13,88%, 1000–5000 +1,06%, 5000+ -4,79%. Crossover ~1.000–5.000 vertices;
  5.180 of 8.366 buffers have < 200 vertices.
- Current corpus with sparse tables: 10.812.307 vs 10.811.936 B (+0,00%, a wash); 0–50 bucket still +83,9%,
  5000+ -4,5%. A per-buffer decision, not a universal improvement. Retired with rANS.
