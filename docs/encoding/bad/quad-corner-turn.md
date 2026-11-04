# Quad corner + signalled turn (`SEGMENT_QUAD_CORNER_TURN`, now `QUAD_CORNER_TURN`)

## encoding target:
building, landcover, water (Polygon)

## Idea of the encoding
Quad corner leaves > 4-vertex rings on plain delta. Route by ring length: 4-vertex rings keep the parallelogram
corner, larger rings get the signalled 90° turn predictor.

## How the encoding works
- 4-vertex rings: 4th vertex `p0 + p2 - p1`.
- > 4-vertex rings, vertices `i >= start + 2`: `p[i-1] + rot90_{cw|ccw}(prevEdge)` with 1 signalled direction bit.
- Four FastPFOR regions: starts | interior | corners | turns, plus the turn-bit side channel.

## How the decoder works
Classify by ring length; corners as quad corner; turn vertices read their direction bit and add the residual to the
rotated-edge prediction.

## results
- **Retired corpus `munich.mbtiles`, FastPFOR:** `building` -11,6% (plain quad corner -12,2%), `landcover` +3,6%
  (-2,8%), `water` +7,5% (-1,2%), POLYGON -4,6% (-8,0%). Worse than plain quad corner on every layer; oracle picks
  it on 74 of 8.366 buffers (84.126 B).
- **Current corpus, split varints, per tile:** -0,26% whole tileset; `building` -8,20% vs `QUAD_CORNER` -10,52%.
  Retired.
