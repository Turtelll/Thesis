# Fixed rot90 on even rings — `ROT90_FIXED_FASTPFOR`

> **Revisited under split varints:** `ROT90_FIXED_SEG_VARINT_SPLIT` is -3,10% on `building`
> against -10,52% for `QUAD_CORNER`, -0,95% whole tileset (smallest of gzip/Brotli/zstd);
> `QUAD_CORNER_ROT90_SEG_VARINT_SPLIT` -9,30% on `building`. Both retired. See
> [revisit-varint-split.md](revisit-varint-split.md).

> **Revisited under segmented split varints** ([revisit-varint-split.md](revisit-varint-split.md)):
> `ROT90_FIXED_SEG_VARINT_SPLIT` is -3,10% on `building` against -10,52% for `QUAD_CORNER` and
> -0,95% whole tileset; its composition with quad-corner is -9,30% on `building`. Neither leads
> a layer; retired.

**Status:** registered — weak win
**Effect:** -5,0% on `building`
**Corpus:** previous Munich corpus (`munich.mbtiles`) — see [reordering.md](reordering.md)
for the corpus-comparability banner

Measures whether the parity-resolved bit-width win in
[failed/ring-predictors.md](failed/ring-predictors.md) survives FastPFOR. **It does.**
`Rot90Fixed` predicts every interior vertex of an even-length, `>4`-vertex ring as
`p[i-1] + rot90ccw(prevEdge)` — direction fixed by winding, nothing signalled — and packs
starts / interior / turn residuals into three regions, same lever as
[segment-quad-corner.md](segment-quad-corner.md).

```
DELTA_ZIGZAG_FASTPFOR     3.228.047        ref
ROT90_FIXED_FASTPFOR      3.065.822      -5,0%  *
ROT90_FIXED_VARINT        3.334.186      +3,3%
```

The Varint column confirms the win is FastPFOR-specific, as with every other
region-separation encoding here: a byte-aligned codec prices each value independently,
so reordering into regions cannot change the total on its own — the gain comes entirely
from block-width separation.

**It does not compose with `SEGMENT_QUAD_CORNER`.** `SEGMENT_QUAD_CORNER_ROT90` routes
`n=4` rings to the parallelogram corner and even `n>4` rings to `Rot90Fixed`'s predictor,
following the same disjoint-population pattern that made `SEGMENT_QUAD_CORNER` itself a
win over its two parts. Measured:

```
SEGMENT_QUAD_CORNER_FASTPFOR              2.834.672     -12,2%  *
SEGMENT_QUAD_CORNER_ROT90_FASTPFOR        2.834.236     -12,2%  (statistically identical)
SEGMENT_QUAD_CORNER_FASTPFOR_REORDER_REV  2.534.515     -21,5%  *
SEGMENT_QUAD_CORNER_ROT90_FASTPFOR_REORDER_REV  2.581.746 -20,0%  (worse than plain, with reorder)
```

Unlike `SEGMENT_QUAD_CORNER` composing `SegmentPair` and `QuadCorner` — which win on
*disjoint* geometry types (LINESTRING vs POLYGON) and are close to additive — quad-corner
and rot90-fixed both target the same layer's *same* rings, and quad-corner already claims
the highest-value population: `n=4` is 76,4% of building rings (`BuildingShapeCli`), while
`n=6`/`n=8` together are only 15,5%. The oracle confirms the marginal contribution is
small: with reordering, `SEGMENT_QUAD_CORNER_ROT90_FASTPFOR_REORDER_REV` is picked on 29
of 311 buffers for 23.264 bytes, against 636.322 bytes plain quad-corner-reorder already
captures — and the composition is *worse* than plain quad-corner once reordering is in
play, likely because folding the turn region into the buffer changes which vertices group
with which under `GeometryReorder`'s greedy chaining, a second-order interaction not
present in the unreordered case where the two are equal. **`ROT90_FIXED` stays registered
standalone**, since it is a real, if modest, unconditional win; `SEGMENT_QUAD_CORNER_ROT90`
is kept as a measured composition that does not pay for its added decoder complexity.

**A separate alternating-axis scheme (one signed scalar per edge instead of a 2D turn
residual) was considered and not built.** The original idea was that ring closure
(`Σdx=0, Σdy=0`) lets the last two edges of an even rectilinear ring be *derived* rather
than stored — but closure is a tautology of any closed polygon, true regardless of shape,
so it supplies no information on its own. `Rot90Fixed` above and a 2-scalar alternating
scheme both actually rest on the same real assumption — this ring is rectilinear — and
both pay a residual, not a correctness failure, when a ring is not. Since `Rot90Fixed`
already measures that assumption end-to-end (a real but modest win, does not compose
with quad-corner), a second predictor testing the same prior in a different residual
shape was judged not to add enough to justify building and verifying separately. If a
future pass wants the comparison, the open design question is whether a 1-scalar-per-edge
residual (rather than a 2D turn residual with one component structurally near-zero)
packs differently under FastPFOR — untested here; see
[failed/turn-scalar.md](failed/turn-scalar.md) for the probe that answered this directly.
