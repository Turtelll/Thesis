# Fixed 90° turn on even rings

**Code names:** `ROT90_FIXED`

> **Verdict: Kept under FastPFOR, then retired.** Under the headline metric +1,45% gzip on the tileset; on
> `building` far behind quad corner.

## Encoding target
building (Polygon), rings with an even vertex count > 4.

## Idea
Larger building outlines are often rectilinear: every corner is a right angle. If the ring turns the same way
at every corner (given by its winding), the next edge is the previous edge rotated by 90°. Predict each vertex
that way and store only the deviation. The turn direction is fixed by the winding, so nothing has to be
signalled.

## Intention
[Quad corner](quad-corner.md) only covers four-vertex rings; this was the attempt to give the larger
rectilinear outlines a predictor too. Restricting it to even vertex counts was the cheap filter: a closed ring
with only right angles must have an even count (no 5- or 7-vertex ring in the data was fully right-angled).

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

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`, segmented split varints): **tileset
+1,45%** (`ROT90_FIXED_SEG_VARINT_SPLIT`, computed from the gzip byte total). Per layer only the smallest of
gzip/Brotli/zstd was reported: `building` -3,10% against -10,52% for `QUAD_CORNER`.

**Decisive case — why it was retired:** the fixed direction is only right when the ring really turns the same
way at every corner; at every reflex corner it is wrong by a full edge. Under FastPFOR its -5,0% on `building`
came from the block-width separation alone (over Varint it was +3,3%), and under gzip it leads no layer, loses
to the baseline on four of six layers, and does not combine with quad corner
([quad corner + fixed 90° turn](../bad/quad-corner-plus-fixed-turn.md)).

Supporting figures:

- *Not under the headline metric* — retired corpus `munich.mbtiles`, `building`: `ROT90_FIXED_FASTPFOR`
  -5,0% vs `DELTA_ZIGZAG_FASTPFOR`; over Varint +3,3%.
- Smallest of gzip/Brotli/zstd, current corpus: tileset -0,95%, `building` -3,10%.

Source: `research-java/docs/results/rot90-fixed.md`, `revisit-varint-split.md`.
