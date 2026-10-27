# Predictors for >4-vertex rings

> **Consolidation note.** The Gradle task(s) ringPrior, buildingShape named below were removed from the build (the code is at git tag `pre-consolidation`); `research measure` and the other `research` commands replace the rest. The numbers below are unchanged history.

**Status:** measured, no encoder written (baseline delta already near-optimal on the
pooled population); parity resolution opened WP2/WP3 — see [rot90-fixed.md](../rot90-fixed.md)
**Corpus:** previous Munich corpus (`munich.mbtiles`) — see [../reordering.md](../reordering.md)
for the corpus-comparability banner

## Pooled >4-vertex rings

`SEGMENT_QUAD_CORNER`'s parallelogram predictor fires only on *exactly*-four-vertex rings.
Rings with more vertices — **27% of all rings** (163k), predominant in `landcover` (68%),
`water` (67%), `landuse` (57%) — get plain first-order delta. The question was whether any
model-based predictor beats that delta on their *interior* vertices (those with two
predecessors, so an edge is defined). `RingPriorCli` measures it directly — it encodes
nothing, it just sums the zigzag bit width of each candidate residual over every >4-vertex
ring in the pure-polygon columns (75% of these rings, zero linestring contamination):

```
./gradlew ringPrior --args="../data/extracted"
```

Three predictors, all integer-exact (lossless), against the baseline `B: pred = p[i-1]`:

- **C — edge continuation** (constant velocity, for collinear runs): `pred = p[i-1] + (p[i-1]-p[i-2])`.
- **R — right-angle turn** (rectilinear): `pred = p[i-1] + rot90(p[i-1]-p[i-2])`, both turn
  directions and a per-vertex best-of.

Mean residual bits per interior vertex (lower is better), corpus-wide over 1,35M vertices:

| predictor | corpus | building | landcover | water |
|---|---|---|---|---|
| **B baseline** | **10,41** | 8,81 | 11,57 | 10,41 |
| C continuation | 11,53 | 10,91 | 12,28 | 10,79 |
| R rot-best (free turn choice) | 10,04 | 7,78 | 11,64 | 10,26 |
| best{B,C,R} (free per-vertex oracle) | 8,69 | 6,92 | 9,93 | 8,80 |

**No predictor beats B on any layer that matters.** C is *wider* than B almost everywhere —
the same reason uniform delta-of-delta lost (see [delta-of-delta.md](delta-of-delta.md)): on
quantized map vertices the second difference amplifies rounding noise. R edges B only on
`building`/`water` and loses on `landcover`/`landuse`. The separable near-zero population
that made `QUAD_CORNER` win (40,7% exact parallelograms) **does not exist here**: across
every layer the share of residuals that are zero or ≤1 bit is under 1,2% (building's best
case, 3,2%). Larger rings are neither rectilinear nor collinear enough for a fixed model to
zero out an edge.

**The oracle settles it.** Even a *free, perfect* per-vertex choice among all four predictors
drops the mean only ~1,7 bits/vertex (10,41 → 8,69). Signalling a 4-way per-vertex choice
costs 2 bits/vertex, so the oracle is **net negative on every layer** (corpus −0,3, building
−0,1, landcover −0,4 bits/vertex) before the block-width separation lever is even applied —
and that lever cannot help a population with ~0% near-zero residuals. So there is no encoder
to write: a first-order delta over a short ring edge is already near-optimal, and the two
priors that could have beaten it (rectilinearity, collinearity) do not hold on this corpus.
`RingPriorCli` is kept so the measurement need not be re-derived.

**The rectilinear-staircase refinement, and why it also fails.** `building` is CCW-wound and
mostly right-angled, so a natural idea is a *derived* turn direction instead of a signalled one:
force each turn to be the 90° rotation *opposite* to the previous actual turn (an alternating
`staircase`), reading the previous turn's sign from `p[i-3..i-1]` so nothing is signalled. It
measures **9,39 bits on building — worse than the baseline (8,81) and worse than a fixed
rot90-ccw (8,71).** Two direct measurements say why:

| property of `building` >4-ring interior turns (446k) | measured |
|---|---|
| turn is ~90° (80°–100°) | **82,2%** |
| turn *alternates* sign from the previous turn | **51,7%** |

The rectilinearity is real, but the walk is **not a zigzag**: a concave outline turns in *runs*
of same-direction corners with occasional reversals at notches, so the alternation is a coin
flip and forcing it adds error rather than removing it. And even with the direction right, the
rotation predicts an edge's *direction*, not its *length* — adjacent walls differ in length, so
`p[i-1] + rot90(prevEdge)` still carries a full residual edge (fixed rot90-ccw reaches only
1,9% ≤1bit). A rectilinear prior cannot be converted into a near-zero residual here, because
neither the turn sign nor the edge length is derivable.

## Resolving >4-vertex rings by vertex count and parity — opens WP2/WP3

The pooled `>4`-vertex conclusion above averages across two populations that behave
differently: **a closed rectilinear ring must have an even vertex count**, so pooling
odd-length rings in with even ones dilutes any rectilinearity-driven predictor. Two
tools now resolve this directly: `RingPriorCli` buckets `>8`-vertex rings by parity
(`>8even`/`>8odd`) and tracks **per-component** zero counts (`%zero-comp`), since the
paired `%<=1bit` metric ANDs x and y and structurally cannot see a predictor that zeroes
exactly one axis; `BuildingShapeCli` (`./gradlew buildingShape --args="../data/extracted"`)
reports the same per-vertex-count resolution for ring shape rather than residual cost.

**`BuildingShapeCli` on `building`** (289.554 rings, pure-polygon columns):

```
                            4         5         6         7         8        >8       all
turns ~90deg            98,3%     54,9%     91,8%     57,3%     90,0%     78,5%     92,4%
fully right-angled      96,5%      0,0%     78,0%      0,0%     71,4%     40,4%     87,7%
grid-aligned (<5deg)    24,6%     17,7%     24,2%     21,6%     25,7%     29,0%     24,8%
```

The parity law is exact in this corpus: **0,0% of 5- and 7-vertex rings are fully
right-angled**, against 78,0% (n=6) and 71,4% (n=8). This is the number the pooled `>4`
measurement could not see. But `grid-aligned` — how far the ring's own frame sits off the
*tile* axes — stays ~25% regardless of bucket: buildings are rectilinear in their own
frame, almost never in the tile's. That distinction is exactly what separates WP2
(frame-relative, needs no grid alignment) from WP3/fixed-direction rot90 (implicitly
assumes a frame, loses when the ring is rotated relative to it).

**`RingPriorCli` on `building`, parity-split** (446.179 interior vertices):

```
predictor               5        6        7        8    >8even   >8odd
B baseline            9,56     9,13     9,54     8,89     8,35     9,03
R rot90-ccw (fixed)    9,43     8,22     9,43     8,57     8,75     9,61
R rot-best (oracle)    9,24     7,46     8,77     7,66     7,64     8,58
rot-best %zero-comp    4,03%    9,93%    5,50%    9,94%   12,35%    6,95%
```

**n=6 is the strongest signal in the whole table.** Fixed, unsignalled `rot90-ccw`
already beats baseline by **-0,91 bit** (9,13 → 8,22) — much better than the pooled
`>4` figure (8,81 → 8,71, -0,10 bit) suggested, because pooling mixed it with the
odd buckets where rot90 cannot fire usefully. The oracle (`rot-best`, free per-vertex
turn choice) reaches **-1,67 bit** with 9,93% of components exactly zero, against
5,80% for baseline delta. n=8 shows the same pattern, weaker (fixed -0,32 bit, oracle
-1,23 bit). Odd buckets (5, 7, `>8odd`) show a much smaller or reversed effect, exactly
as the parity law predicts.

**What this changes.** The existing verdict — [right-angle-turn.md](right-angle-turn.md)'s
*signalled* per-vertex turn choice loses because the 1-bit signal costs more than the ~1-bit
direction saving — still stands; nothing here contradicts it. What is new is that the
**fixed**, unsignalled direction (no signal, `VertexTransform` not `Encoding`) was
previously measured only pooled, where its win looked negligible (-0,10 bit). Resolved
to n=6/n=8, the same fixed rule is worth -0,91 / -0,32 bit for free. Whether that
survives FastPFOR's block-maximum pricing (the same mechanism that sank
[context-delta.md](../context-delta.md) and [delta-of-delta.md](delta-of-delta.md)) is
measured in bytes in [rot90-fixed.md](../rot90-fixed.md).
