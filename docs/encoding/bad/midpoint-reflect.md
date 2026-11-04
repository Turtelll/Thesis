# Midpoint reflection (`MIDPOINT_REFLECT_SYMMETRIC`, `_SPLIT`)

## encoding target:
landcover (Polygon), also water, landuse

## Idea of the encoding
Quad corner reaches `landcover` worst. Its larger blocky rings might be centrally symmetric: delta-code the first
half of the ring and predict each second-half vertex as the reflection of a first-half partner through the midpoint
of the ring's start → apex diagonal. A symmetric ring then has zero residuals in its second half.

## How the encoding works
- First half of the ring: plain delta.
- Second half: `pred = start + apex - partner`, integer-exact. Pairings: `SYMMETRIC` (partner `L - r`, axis
  reflection), `SPLIT` (partner `r - L/2`, central symmetry).
- Three FastPFOR regions: starts | interior | reflect.

## How the decoder works
Decode the first half by delta; for each second-half vertex compute `start + apex - partner` from decoded vertices and
add the residual.

## results
- **Retired corpus `munich.mbtiles`, FastPFOR:** `SPLIT` +0,9% corpus / +7,0% landcover / +1,8% POLYGON; `SYMMETRIC`
  +1,7% / +9,9% / +3,5%; `water` up to +23,5%. Picked on 0 landcover buffers by the oracle.
- **Current corpus, split varints, per tile:** `SPLIT` +3,57%, `SYMMETRIC` +4,94% whole tileset; landcover +8% to +11%.
- Landcover rings are not symmetric; the reflection residual spans a global-symmetry deviation wider than one edge.
