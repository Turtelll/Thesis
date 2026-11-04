# Two-rectangle decomposition of L-shaped hexagons

## encoding target:
building (Polygon), 6-vertex rings

## Idea of the encoding
78,0% of 6-vertex building rings are fully right-angled L-shapes. Decompose each into two axis-aligned rectangles and
predict the vertices that complete them, like quad corner completes a parallelogram.

## How the encoding works
Analysis only. An L-hexagon `(0,0), (a,0), (a,b), (c,b), (c,d), (0,d)` has 4 free scalars, so only one of six vertices
can be a zero-residual prediction from the others in general position.

## How the decoder works
Not built. The candidate vertices:
- the closing vertex (shares one coordinate with the start): ring closure `Σdx = 0, Σdy = 0`, true for any even
  rectilinear ring, not specific to two rectangles;
- the reflex (notch) corner: identifying it needs a turn-sign test at `i`, which needs `p[i]` itself, so it is not
  decoder-causal without signalling its position.

## results
Retired corpus `munich.mbtiles`: no encoder. Reduces either to a general alternating-axis scheme (the rectilinear prior
already measured by fixed rot90) or to a per-ring signal, which the signalled turn experiments
show does not pay.
