# Quad corner + fixed 90° turn

**Code names:** `QUAD_CORNER_ROT90_SEG`, formerly `SEGMENT_QUAD_CORNER_ROT90`

> **Verdict: Rejected.** -0,49% gzip on the tileset against -3,36% for plain quad corner in the same layout;
> the added turn predictor makes quad corner worse.

## Encoding target
building (Polygon).

## Idea
Quad corner handles 4-vertex rings, fixed rot90 handles even rectilinear rings with more vertices. Since they
cover different rings, combining them should add up, like part-start grouping and quad corner did.

## Intention
The two predictors touch disjoint rings (n = 4 vs. even n > 4), so their gains should simply add, with no
signalling, since ring length is known from the topology.

## How the encoding works
- `n = 4` rings: 4th vertex predicted `p0 + p2 - p1` (quad corner).
- Even `n > 4` rings: interior vertices predicted `p[i-1] + rot90ccw(p[i-1] - p[i-2])`.
- Starts / interior / corner / turn residuals in separate regions; classes from topology, nothing
  signalled.

## How the decoder works
Derive classes from the ring lengths, read each region with its own cursor, recompute the class's
prediction from decoded vertices and add the residual.

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`, segmented split varints): **tileset
-0,49%** (`QUAD_CORNER_ROT90_SEG_VARINT_SPLIT`, computed from the gzip byte total) against **-3,36%** for
`QUAD_CORNER`. Per layer only the smallest of gzip/Brotli/zstd was reported: `building` -9,30% vs -10,52%.

**Decisive case against:** there is little left to add. 76,4% of building rings have 4 vertices and only
15,5% have 6 or 8, and on those the fixed turn is wrong at every reflex corner, so the combination is
statistically identical to plain quad corner under FastPFOR and worse than it under gzip, on the tileset and on
`building`.

Supporting figures:

- Smallest of gzip/Brotli/zstd, current corpus: tileset -3,13%; `building` -9,30% vs -10,52% for
  `QUAD_CORNER`.
- *Not under the headline metric* — retired corpus `munich.mbtiles`, `building`, FastPFOR: -12,2%,
  statistically identical to plain quad corner (2.834.236 vs 2.834.672 B). With `--reorder feature-rev`
  *worse*: -20,0% vs -21,5%. The oracle picked it on 29 of 311 buffers (23.264 B).

Source: `research-java/docs/results/rot90-fixed.md`, `revisit-varint-split.md`.
