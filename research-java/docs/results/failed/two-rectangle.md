# Two-rectangle decomposition for L-shaped hexagons

**Status:** analysis only, no encoder written
**Corpus:** previous Munich corpus (`munich.mbtiles`) — see [../reordering.md](../reordering.md)
for the corpus-comparability banner

`BuildingShapeCli` shows `n=6` rings are 78,0% fully right-angled, well clear of the
~1% seen pooled across every `>4`-vertex ring (see [ring-predictors.md](ring-predictors.md)),
so the natural next idea is a shape template: decompose the ring into two axis-aligned
rectangles and predict the vertices that complete them, the way `QUAD_CORNER` predicts a
parallelogram's fourth corner. It does not reach an encoder, for a structural reason worth
recording.

**An L-hexagon has 4 free scalars, not 3.** A parallelogram (`n=4`) has exactly three
independent edge vectors — the fourth vertex is always `p0+p2-p1`, zero residual, no
exceptions. Parametrize a canonical CCW L-hexagon as `(0,0), (a,0), (a,b), (c,b), (c,d),
(0,d)`: **four** free scalars (`a,b,c,d`), so **only one** of the six vertices can ever
be a zero-residual prediction from the rest in general position — there is no second
derivable corner to complete a "second rectangle" the way the name suggests.

**Which vertex is derivable is not fixed — it depends on winding and starting vertex,
which vary per ring in the corpus.** In the canonical parametrization above, the
*closing* vertex is derivable — `p[end-1]` always shares one coordinate with `p[start]`,
since the closing edge is axis-parallel by definition of a rectilinear ring. But that is
not a property specific to L-hexagons or to two-rectangle decomposition at all — it is
`Σdx=0, Σdy=0` on the whole ring, the same closure constraint a general alternating-axis
scheme (one signed scalar per edge, axis implied by alternation, last H/V edge derived
from the sum) generalizes to every even rectilinear ring regardless of vertex count. A
"two-rectangle" predictor that only reaches the closing vertex is not a distinct idea
from that one.

**The other candidate — predicting the reflex (notch) corner specifically — is not
decoder-causal.** The reflex vertex is identifiable by a turn-sign test (`cross(p[i-1],
p[i], p[i+1]) < 0` in a CCW ring), but that test needs `p[i]` itself to evaluate the turn
*at* `i`. A decoder predicting `p[i]` cannot first ask "is `i` the notch?" — the answer
depends on the value being predicted. This is the same one-vertex-lag wall
[context-delta.md](../context-delta.md) hit for a different reason: a rule that needs
vertex `i` to classify vertex `i` cannot be used to predict vertex `i`. The only ways
around it are (a) a fixed template position, which the DOF analysis above shows does not
generalize across the corpus's actual mix of starting vertices and windings, or (b)
signalling the notch position per ring, an `Encoding` with a real cost — and
[right-angle-turn.md](right-angle-turn.md)'s per-vertex signal already lost at a much
smaller per-bit cost than a per-ring position signal would carry.

**Verdict: no encoder written.** The idea reduces either to a general alternating-axis
scheme (more general, worth building on its own merits — see
[../rot90-fixed.md](../rot90-fixed.md)) or to a signalling problem this corpus's other
signalled experiments (`RIGHT_ANGLE_TURN`, `SEGMENT_QUAD_CORNER_TURN`) already show does
not pay. Kept as an analysis note rather than a registered negative result, since there
is no encoder to pin with a mechanism test.
