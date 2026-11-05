# Quad corner + signalled turn

**Code names:** `QUAD_CORNER_TURN`, formerly `SEGMENT_QUAD_CORNER_TURN`

> **Verdict: Rejected.** +2,70% gzip on the tileset, where plain quad corner is -3,36%; worse than quad corner
> on every layer.

## Encoding target
building, landcover, water (Polygon).

## Idea
Quad corner leaves > 4-vertex rings on plain delta. Route by ring length: 4-vertex rings keep the
parallelogram corner, larger rings get the signalled 90° turn predictor.

## Intention
Same as [quad corner + fixed 90° turn](quad-corner-plus-fixed-turn.md), but with the turn direction signalled per
vertex ([signalled right-angle turn](signalled-right-angle-turn.md)), so the turn predictor is never wrong about the
direction and can also cover rings that are not uniformly convex.

## How the encoding works
- 4-vertex rings: 4th vertex `p0 + p2 - p1`.
- > 4-vertex rings, vertices `i >= start + 2`: `p[i-1] + rot90_{cw|ccw}(prevEdge)` with 1 signalled direction bit.
- Four FastPFOR regions: starts | interior | corners | turns, plus the turn-bit side channel.

## How the decoder works
Classify by ring length; corners as quad corner; turn vertices read their direction bit and add the residual to the
rotated-edge prediction.

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`, split varints): **tileset +2,70%**
(`QUAD_CORNER_TURN_VARINT_SPLIT`, computed from the gzip byte total) against **-3,36%** for `QUAD_CORNER`.
Per layer only the smallest of gzip/Brotli/zstd was reported: `building` -8,20% vs -10,52%.

**Decisive case against:** the direction bit costs about what it saves, and the edge length still needs a full
residual, so the turn predictor is worse than plain delta on the large rings. On `landcover` and `water` that turns
quad corner's gain into a loss (+5,55% / +8,50%, smallest of three). It is worse than plain quad corner on
every layer.

Supporting figures:

- Smallest of gzip/Brotli/zstd, current corpus: tileset -0,26%; `landcover` +5,55%, `water` +8,50%.
- *Not under the headline metric* — retired corpus `munich.mbtiles`, FastPFOR: `building` -11,6% (plain quad
  corner -12,2%), `landcover` +3,6% (-2,8%), `water` +7,5% (-1,2%), POLYGON -4,6% (-8,0%); oracle picks it on
  74 of 8.366 buffers (84.126 B).

Source: `research-java/docs/results/failed/right-angle-turn.md`, `revisit-varint-split.md`.
