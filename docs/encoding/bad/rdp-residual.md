# RDP simplification + interpolated residual (`RDP_RESIDUAL`, `RDP_RESIDUAL_SEG`)

## encoding target:
transportation (LineString / MultiLineString)

## Idea of the encoding
Simplify each line with Ramer-Douglas-Peucker to a few key vertices. A dropped vertex lies close to the chord
between its two keys, so predict it by interpolating along that chord from its index and store only the (small)
deviation. Lossless, because every dropped vertex still gets its exact residual.

## How the encoding works
- Runs with ≥ 3 vertices are simplified with RDP; both endpoints are always keys. Eight `epsilon` candidates per
  buffer, the one with the smallest payload is kept (`epsilon` not stored).
- Key/drop bitmap: 1 bit per interior vertex of every eligible run.
- Key vertices: plain delta. Dropped vertex `i` between keys `a` (A) and `b` (B):
  `pred = A + round((B - A)·(i - a)/(b - a))` (exact integer arithmetic), residual `actual - pred`, zigzag.
- `_SEG`: key and dropped residuals in separate FastPFOR blocks.

## How the decoder works
Read the bitmap; decode all key vertices by delta first (so both bounding keys of every dropped vertex are known);
then for each dropped vertex interpolate between its keys from the indices and add the residual.

## results
Current corpus `munich-plantiler`, `transportation`, vs `DELTA_ZIGZAG_FASTPFOR`:
- Interleaved +2,5%, segmented -0,2% (`LINE_CONTINUATION_SEG` -6,8%); 0 of 354 buffers won. Mean bits -0,2%.
- On dropped vertices the residual is -24,4% (11,08 → 8,38 bits), but only 28,9% of eligible-run vertices are
  dropped, 70,4% of parts have 2 vertices and are never eligible, and the bitmap costs 1 bit per vertex.
- A 1-scalar (perpendicular) residual is impossible losslessly: index and arc length do not coincide.
- **Split varints, per tile:** -0,61% / `_SEG` -0,54% whole tileset; +1,70% / +1,78% on `transportation`. Retired.
