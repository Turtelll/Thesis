# Second-order corner predictors (Stage 0 probe)

> **Verdict: Rejected (probe, no encoder).** Both candidates are worse than plain delta on the quads they were
> meant for; never measured under the headline metric.

## Encoding target
building, landcover, landuse (Polygon), the non-parallelogram 4-vertex rings.

## Idea
Quad corner's `p0 + p2 - p1` is exact only for parallelograms (24–42% of quads are not). For the others a
better guess might exist: continue the third edge, or scale it by the ratio of the edge lengths.

## Intention
The quads quad corner misses are the remaining corner cost. If a second rule were better on exactly those, the
two could be combined (the decoder cannot tell which quads are parallelograms, so the combination would need a
signal or a rule). The probe checks the candidates on the non-parallelogram quads alone first.

## How the encoding works
Probe only, no encoder. Candidates for the 4th vertex of a non-parallelogram quad:
- `B` previous-vertex delta: `p3 - p2`.
- `P0` parallelogram: `p3 - (p0 + p2 - p1)`.
- `H` edge continuation: `p3 - (2·p2 - p1)` (ignores `p0`).
- `D` diagonal ratio: incoming edge scaled by the outgoing/incoming length ratio.
Residual distribution measured (mean, median, p90, p99, p99,9 bits), no bytes.

## How the decoder works
Not built. A decoder would recompute the chosen prediction from `p0..p2` and add the residual.

## Results
**Headline:** *not measured under the headline metric* (probe only, residual bits, retired extraction
`data/extracted`, `building`, 143.447 non-parallelogram quads): parallelogram **1,07 bits** mean, continuation
5,79, diagonal ratio 5,92, plain delta 4,80.

**Decisive case against:** on the very quads where it is not exact, the "wrong" parallelogram is still by far
the best guess (median 1 bit); both new candidates are worse than even plain delta in mean, median and every
tail. Same on `landcover` and `landuse`. No encoder written.

Supporting figures (*bits*):

| predictor | mean | median | p90 | p99 | p99,9 |
|---|---|---|---|---|---|
| `B` delta | 4,80 | 5 | 7 | 8 | 9 |
| `P0` parallelogram | 1,07 | 1 | 2 | 5 | 7 |
| `H` continuation | 5,79 | 6 | 7 | 8 | 9 |
| `D` diagonal ratio | 5,92 | 6 | 8 | 10 | 12 |

Source: `research-java/docs/results/segment-quad-corner.md`.
