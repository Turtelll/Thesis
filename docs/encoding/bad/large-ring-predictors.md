# Predictors for rings with more than 4 vertices

**Code names:** edge continuation, rotation, staircase

> **Verdict: Rejected (probe).** No rule beats plain delta on large rings once its choice is paid for; never
> built as an encoding, so never measured under the headline metric.

## Encoding target
landcover, water, landuse, building (Polygon), rings with more than 4 vertices (27% of rings).

## Idea
Quad corner only fires on exactly-four-vertex rings. For larger rings a model might still beat plain delta:
keep the last edge going (collinear runs), turn it by 90° (rectilinear outlines), or alternate the turn
direction like a staircase so it never has to be signalled.

## Intention
27% of rings, and most of the vertices of `landcover` and `water`, get no prediction from quad corner. A
probe over the candidate rules was meant to find one worth building before writing an encoder.

## How the encoding works
Probe only (`RingPriorCli`, `BuildingShapeCli`), zigzag bit widths over interior vertices of > 4-vertex rings:
- `B` baseline `p[i-1]`;
- `C` edge continuation `p[i-1] + (p[i-1] - p[i-2])`;
- `R` right-angle turn `p[i-1] + rot90(p[i-1] - p[i-2])` (fixed ccw, or free per-vertex best);
- staircase: turn opposite to the previous actual turn, read from `p[i-3..i-1]`, nothing signalled;
- parity split: buckets by vertex count (closed rectilinear rings have an even count).

## How the decoder works
Not built (except fixed rot90, see fixed 90° turn). A decoder would recompute each
prediction from decoded predecessors.

## Results
**Headline:** *not measured under the headline metric* (probe only, mean residual bits per interior vertex,
retired corpus `munich.mbtiles`). Best rule on `building`: rot-best **7,78 bits** against **8,81** for
plain delta, but only with a free per-vertex choice.

**Decisive case against:** no single rule wins without a choice: continuation is worse than delta everywhere, and
rotation only wins with a free per-vertex choice. Paying for that choice is fatal: under 1,2% of residuals are ≤ 1 bit on every layer, and a 4-way choice costs
2 bits, so even the free oracle over all rules is net negative. The staircase (no signal) is worse than delta
(9,39 vs 8,81 bits) because turns alternate only 51,7% of the time.

Supporting figures (*retired corpus, mean residual bits*):

| predictor | corpus | building | landcover | water |
|---|---|---|---|---|
| `B` baseline | 10,41 | 8,81 | 11,57 | 10,41 |
| `C` continuation | 11,53 | 10,91 | 12,28 | 10,79 |
| `R` rot-best (free choice) | 10,04 | 7,78 | 11,64 | 10,26 |
| best{B,C,R} free oracle | 8,69 | 6,92 | 9,93 | 8,80 |

- Parity split, `building`: n=6 fixed rot90-ccw 9,13 → 8,22 bits, n=8 8,89 → 8,57; 0,0% of 5- and 7-vertex
  rings are fully right-angled. This opened [fixed rot90](../good/fixed-right-angle-turn.md) (a weak byte win, later
  retired).

Source: `research-java/docs/results/failed/ring-predictors.md`.
