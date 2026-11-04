# Quad corner + fixed 90° turn (`SEGMENT_QUAD_CORNER_ROT90`, now `QUAD_CORNER_ROT90_SEG`)

## encoding target:
building (Polygon)

## Idea of the encoding
Quad corner handles 4-vertex rings, fixed rot90 handles even rectilinear rings with more vertices. Since
they cover different rings, combining them should add up, like part-start grouping and quad corner did.

## How the encoding works
- `n = 4` rings: 4th vertex predicted `p0 + p2 - p1` (quad corner).
- Even `n > 4` rings: interior vertices predicted `p[i-1] + rot90ccw(p[i-1] - p[i-2])`.
- Starts / interior / corner / turn residuals in separate regions; classes from topology, nothing
  signalled.

## How the decoder works
Derive classes from the ring lengths, read each region with its own cursor, recompute the class's
prediction from decoded vertices and add the residual.

## results
- **Retired corpus `munich.mbtiles`, `building`, FastPFOR:** -12,2%, statistically identical to plain quad
  corner (2.834.236 vs 2.834.672 B). With `--reorder feature-rev` *worse*: -20,0% vs -21,5%.
- Why: both target the same layer's rings, and `n=4` is 76,4% of building rings vs 15,5% for `n=6/8`.
  The oracle picked it on 29 of 311 buffers (23.264 B).
- **Current corpus, segmented split varints:** -9,30% on `building` vs -10,52% for `QUAD_CORNER`. Retired.
