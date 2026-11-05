# Two-rectangle decomposition of L-shaped hexagons

> **Verdict: Rejected (analysis, no encoder).** Never measured: the idea reduces to two schemes already shown
> not to pay.

## Encoding target
building (Polygon), 6-vertex rings.

## Idea
78,0% of 6-vertex building rings are fully right-angled L-shapes. Decompose each into two axis-aligned
rectangles and predict the vertices that complete them, like quad corner completes a parallelogram.

## Intention
Extend the success of quad corner to the next most common building shape: an L is two rectangles, so after a
few vertices the rest should be determined, just as the fourth corner of a parallelogram is.

## How the encoding works
Analysis only. An L-hexagon `(0,0), (a,0), (a,b), (c,b), (c,d), (0,d)` has 4 free scalars, so only one of six vertices
can be a zero-residual prediction from the others in general position.

## How the decoder works
Not built. The candidate vertices:
- the closing vertex (shares one coordinate with the start): ring closure `Σdx = 0, Σdy = 0`, true for any even
  rectilinear ring, not specific to two rectangles;
- the reflex (notch) corner: identifying it needs a turn-sign test at `i`, which needs `p[i]` itself, so it is not
  decoder-causal without signalling its position.

## Results
**Headline:** *not measured under the headline metric* (no encoder; analysis on the retired corpus
`munich.mbtiles`).

**Decisive case against:** an L-hexagon has four free scalars, not three, so at most one of its six vertices
can be derived from the others, not a whole "second rectangle". Which vertex that is depends on the ring's
winding and start vertex, which vary per ring. The closing vertex is always derivable, but that is the closure
constraint of every rectilinear ring (a general alternating-axis scheme, the prior already measured by
[fixed rot90](../good/rot90-fixed.md)). Predicting the notch corner instead is not decoder-causal: telling
whether vertex `i` is the notch needs `p[i]` itself. The only way around it is a per-ring signal, and the
signalled turn experiments ([right-angle turn](right-angle-turn.md), [quad corner + signalled
turn](quad-corner-turn.md)) already lost at a smaller signalling cost.

Source: `research-java/docs/results/failed/two-rectangle.md`.
