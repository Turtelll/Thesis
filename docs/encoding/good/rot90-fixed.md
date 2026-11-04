# Fixed 90° turn on even rings (`ROT90_FIXED`)

## encoding target:
building (Polygon), rings with an even vertex count > 4

## Idea of the encoding
Larger building outlines are often rectilinear: every corner is a right angle. If the ring turns the same
way at every corner (given by its winding), the next edge is the previous edge rotated by 90°. Predict
each vertex that way and store only the deviation. The turn direction is fixed by the winding, so nothing
has to be signalled.

## How the encoding works
- Applies to rings with even `n > 4`. Every interior vertex is predicted as
  `pred = p[i-1] + rot90ccw(p[i-1] - p[i-2])` (direction fixed by winding), residual `p[i] - pred`.
- Starts and other vertices: plain delta.
- Starts / interior / turn residuals are packed into three separate regions (same lever as quad corner).

## How the decoder works
1. Derive runs, ring lengths and classes from the topology.
2. Read the three regions with separate cursors.
3. Turn vertex: compute `p[i-1] + rot90ccw(p[i-1] - p[i-2])` from decoded vertices and add the residual;
   other vertices: `p[i-1] + r`.

## results
- **Retired corpus `munich.mbtiles`, `building`:** `ROT90_FIXED_FASTPFOR` -5,0% vs `DELTA_ZIGZAG_FASTPFOR`;
  over Varint +3,3% (the gain is FastPFOR block-width separation only). Weak win.
- **Current corpus, segmented split varints, per tile:** -3,10% on `building` vs -10,52% for
  `QUAD_CORNER`; -0,95% whole tileset. Leads no layer. **Retired.**
- Does not compose with quad corner: see quad corner + fixed 90° turn.
