# Zoom-dependent turn radius / constant-curvature predictor (Stage 0 probe)

## encoding target:
transportation (LineString), line-run interior vertices

## Idea of the encoding
If roads are smoother (larger turn radius) at higher zooms, a predictor could branch on zoom. A related candidate:
extrapolate the incoming edge rotated by the previously measured turn (constant curvature), instead of straight
continuation.

## How the encoding works
Probe only (`TurnRadiusCli`), interior vertices of line runs ≥ 4 vertices, bucketed by zoom:
- turn angle `atan2(|cross|, dot)`; Menger radius `|e1|·|e2|·|e1+e2| / (2·|cross|)`, raw and normalized by the incoming
  edge;
- residual bits of `B` plain delta, `C` continuation `2·p[i-1] - p[i-2]`, `A` constant curvature (incoming edge rotated
  by the previous turn).

## How the decoder works
Not built. `A` would need the previous turn angle from the last three decoded vertices; a zoom branch would need z in
`BufferContext` (not carried today).

## results
- **Current corpus `munich-plantiler`:** median turn angle flat (13,2°–14,4°), normalized median radius flat (4,35–5,17)
  across zoom buckets. Premise refuted.
- **Overture:** median angle falls 19,1° → 9,7° and normalized radius rises 3,39 → 6,81 from ≤ z11 to z14. Premise
  holds. Corpus-dependent (tiler generalization), not a property of the layer.
- Residual bits, z14 (Munich / Overture): `B` 5,85 / 6,06, `C` 5,52 / 5,52, `A` 5,48 / 5,49; median ties `C`, no tail
  improvement. Not promoted to an encoder.
