# Zoom-dependent turn radius / constant-curvature predictor (Stage 0 probe)

> **Verdict: Rejected (probe, no encoder).** The premise is false on this corpus, and constant curvature only
> ties straight continuation; never measured under the headline metric.

## Encoding target
transportation (LineString), line-run interior vertices.

## Idea
If roads are smoother (larger turn radius) at higher zooms, a predictor could branch on zoom. A related
candidate: extrapolate the incoming edge rotated by the previously measured turn (constant curvature), instead
of straight continuation.

## Intention
The zoom is known to the decoder, so a zoom-dependent predictor costs nothing to signal. Constant curvature
targets the bends that straight continuation gets wrong: on a curve, the last turn is a better guess for the
next one than no turn.

## How the encoding works
Probe only (`TurnRadiusCli`), interior vertices of line runs ≥ 4 vertices, bucketed by zoom:
- turn angle `atan2(|cross|, dot)`; Menger radius `|e1|·|e2|·|e1+e2| / (2·|cross|)`, raw and normalized by the incoming
  edge;
- residual bits of `B` plain delta, `C` continuation `2·p[i-1] - p[i-2]`, `A` constant curvature (incoming edge rotated
  by the previous turn).

## How the decoder works
Not built. `A` would need the previous turn angle from the last three decoded vertices; a zoom branch would need z in
`BufferContext` (not carried today).

## Results
**Headline:** *not measured under the headline metric* (probe only, turn statistics and residual bits).
z14 Munich residual bits: constant curvature (`A`) **5,48**, straight continuation (`C`) **5,52**, delta (`B`)
5,85.

**Decisive case against:** on `munich-plantiler` turn angle and radius do not change with zoom (median angle
13,2°–14,4°, normalized radius 4,35–5,17 across zoom buckets), so there is nothing for a zoom branch to use. On
Overture the premise holds, which makes it a property of the tiler's generalization, not of the layer.
Constant curvature ties continuation on the median and does not improve the tail, so it was not promoted to an
encoder.

Supporting figures (*probe*):

- Overture: median angle falls 19,1° → 9,7° and normalized radius rises 3,39 → 6,81 from ≤ z11 to z14.
- Residual bits, z14 (Munich / Overture): `B` 5,85 / 6,06, `C` 5,52 / 5,52, `A` 5,48 / 5,49.

Source: `research-java/docs/results/turn-radius-by-zoom.md`.
