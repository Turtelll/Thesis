# Signalled right-angle turn (`RIGHT_ANGLE_TURN`)

## encoding target:
building (Polygon), rings with > 4 vertices; also measured on landcover, water, transportation

## Idea of the encoding
Rectilinear outlines turn by 90° at each corner, but the direction (left or right) varies. Predict the next edge as
the previous edge rotated by 90°, pick the better direction per vertex, and spend one bit to tell the decoder which.

## How the encoding works
- Every interior vertex of a > 4-vertex ring: `pred = p[i-1] + rot90(p[i-1] - p[i-2])`, cw or ccw, whichever gives
  the smaller residual.
- The choice is written as 1 bit per such vertex in a side stream (its own `StreamMetadata` header); residuals as
  usual.

## How the decoder works
For each such vertex read the direction bit, compute `p[i-1] + rot90_{cw|ccw}(p[i-1] - p[i-2])`, add the residual.

## results
- Free per-vertex oracle (probe): `building` 8,81 → 7,78 bits.
- **Retired corpus `munich.mbtiles`, FastPFOR:** `building` +1,5%, `transportation` +2,3%, `landcover` +6,2%,
  `water` +8,6%, POLYGON +3,8%. Picked on 1 of 8.366 buffers. The ~1 bit direction saving does not cover the 1 bit
  signal, and the edge length still needs a full residual (only 1,9% of residuals ≤ 1 bit).
- **General-purpose compression (current corpus):** +6,31% gzip / +6,18% Brotli (plain varint); split varints +4,02%
  whole tileset. Related `EVEN_RING_TURN` -0,19%. Kept only for its mechanism test.
- Composition with quad corner: quad corner + signalled turn.
