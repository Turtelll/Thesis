# Per-region rANS tables

**Code names:** `RANSSEG`, `SegmentedRansCodec`

> **Verdict: Rejected.** +9,62% on the corpus (retired corpus, rANS bytes); a wash even with sparse tables.
> Never measured under the headline metric: rANS itself is [out of scope](../out-of-scope/rans-entropy-coder.md).

## Encoding target
building (Polygon) and all segmented layouts under rANS.

## Idea
`QUAD_CORNER_SEG_RANS` codes its three regions (starts, interior, corners) with one shared frequency table,
pricing each at the mixture of all three. Giving each region its own table removes the mixture penalty; in
ideal bucket entropy that is worth 16,76%.

## Intention
An entropy coder is only as good as its model. Starts (large jumps), interior edges and near-zero corners have
very different distributions, so one table per region should price each one much closer to its own entropy.

## How the encoding works
- Same regions as segmented quad corner.
- One rANS frequency table per region; region lengths written into the payload so decoding stays context-free.

## How the decoder works
Read the region lengths and the per-region tables, rANS-decode each region with its own table, then undo the
prediction as for segmented quad corner.

## Results
**Headline:** *not measured under the headline metric* (rANS output, retired corpus `munich.mbtiles`):
**corpus +9,62%**; `building` -5,93%.

**Decisive case against:** the tables cost more than they save on almost every buffer. Two extra 66-byte
tables plus 20 B of headers are ~152 B per buffer, and 5.180 of 8.366 buffers have fewer than 200 vertices; the
crossover is around 1.000–5.000 vertices. With sparse tables on the current corpus it is +0,00%, a per-buffer
decision rather than an improvement. Retired with rANS, which no browser can decode natively.

Supporting figures (*rANS bytes*):

- Retired corpus, `building`: `_RANS` 2.668.361 → `_RANS_SEG` 2.510.165 (-5,93%); with reorder -3,16%,
  `building` best -29,5%.
- Retired corpus by buffer size: 0–50 vertices +163,87%, 50–200 +52,40%, 200–1000 +13,88%, 1000–5000 +1,06%,
  5000+ -4,79%.
- Current corpus with sparse tables: 10.812.307 vs 10.811.936 B (+0,00%); 0–50 bucket still +83,9%, 5000+
  -4,5%.

Source: `research-java/docs/results/rans-entropy-coder.md`, `shared-tables.md`.
