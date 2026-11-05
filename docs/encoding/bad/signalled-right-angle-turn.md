# Signalled right-angle turn

**Code names:** `RIGHT_ANGLE_TURN`

> **Verdict: Rejected.** +5,22% gzip on the tileset (split varints), worse than the baseline on every layer
> tried.

## Encoding target
building (Polygon), rings with > 4 vertices; also measured on landcover, water, transportation.

## Idea
Rectilinear outlines turn by 90° at each corner, but the direction (left or right) varies. Predict the next
edge as the previous edge rotated by 90°, pick the better direction per vertex, and spend one bit to tell the
decoder which.

## Intention
The [fixed 90° turn](../good/fixed-right-angle-turn.md) is wrong at every reflex corner. Signalling the direction makes
the turn right at every right-angle corner, at a cost of one bit per vertex, which should be far less than the
residual it removes.

## How the encoding works
- Every interior vertex of a > 4-vertex ring: `pred = p[i-1] + rot90(p[i-1] - p[i-2])`, cw or ccw, whichever gives
  the smaller residual.
- The choice is written as 1 bit per such vertex in a side stream (its own `StreamMetadata` header); residuals as
  usual.

## How the decoder works
For each such vertex read the direction bit, compute `p[i-1] + rot90_{cw|ccw}(p[i-1] - p[i-2])`, add the residual.

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`, split varints): **tileset +5,22%**
(`RIGHT_ANGLE_TURN_VARINT_SPLIT`, computed from the gzip byte total); over plain Varint +6,31%. Per layer only
the smallest of gzip/Brotli/zstd was reported: `building` +1,27%.

**Decisive case against:** the turn predicts the edge's direction, not its length. The rotated previous edge
has the previous edge's length, and walls rarely have equal lengths, so the residual is still a full-size
number (only 1,9% of residuals ≤ 1 bit), and the direction bit is added on top. Even a free per-vertex
direction oracle only saves ~1 bit per vertex, which the signal costs back. Kept only for its mechanism test.

Supporting figures:

- Plain Varint, same metric: +6,31% gzip / +6,18% Brotli. Related `EVEN_RING_TURN_VARINT_SPLIT` +2,27% gzip
  (computed), smallest of three -0,19%.
- Smallest of gzip/Brotli/zstd, tileset: +4,02%.
- Free per-vertex oracle (probe, mean bits): `building` 8,81 → 7,78.
- *Not under the headline metric* — retired corpus `munich.mbtiles`, FastPFOR: `building` +1,5%,
  `transportation` +2,3%, `landcover` +6,2%, `water` +8,6%, POLYGON +3,8%. Picked on 1 of 8.366 buffers.
- Composition with quad corner: [quad corner + signalled turn](quad-corner-plus-signalled-turn.md).

Source: `research-java/docs/results/failed/right-angle-turn.md`, `general-purpose-compression.md`,
`revisit-varint-split.md`.
