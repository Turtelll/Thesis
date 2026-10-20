# Midpoint reflection

> **Revisited under split varints:** `MIDPOINT_REFLECT_SPLIT` +3,57%, `_SYMMETRIC` +4,94%
> whole tileset (smallest of gzip/Brotli/zstd); worse than the baseline on `landcover`
> (+8% to +11%), its target layer. Stays retired. See
> [../revisit-varint-split.md](../revisit-varint-split.md).

> **Revisited over split varints** ([../revisit-varint-split.md](../revisit-varint-split.md)):
> `MIDPOINT_REFLECT_SPLIT` +3,57% and `_SYMMETRIC` +4,94% per tile (smallest of
> gzip/Brotli/zstd); both lose on every layer but `building`'s SPLIT row at -1,04%. Retired.

**Status:** regression, unregistered
**Effect:** +0,9% to +1,7% corpus; +7,0% to +9,9% landcover (its target layer)
**Corpus:** previous Munich corpus (`munich.mbtiles`) — see [../reordering.md](../reordering.md)
for the corpus-comparability banner

Targeted `landcover`, the layer the quad-corner predictor reaches worst
(`SEGMENT_QUAD_CORNER` is only -2,8% there against -12,2% on `building`). The idea: its
larger, blocky rings are often *centrally symmetric*, so instead of first-order delta
across the whole ring, delta only through the first half and predict each second-half
vertex as the point-reflection of a first-half partner through the midpoint of the ring's
`start → apex` diagonal — `pred = start + apex - partner`, integer-exact, so lossless. A
symmetric ring's residual is then zero; separated into its own FastPFOR region (starts |
interior | reflect) it should pack narrow, the same block-width lever `SEGMENT_PAIR` uses.
Two pairings were measured: `SYMMETRIC` (partner `L - r`, axis reflection) and `SPLIT`
(partner `r - L/2`, central symmetry). Both are lossless over all 8.366 corpus buffers.

Both are **larger than the baseline** on the target layer and everywhere else:

```
                                  corpus    landcover POLYGON   POLYGON
DELTA_ZIGZAG_FASTPFOR               ref          ref             ref
SEGMENT_QUAD_CORNER_FASTPFOR       -6,1%        -2,8%*          -8,0%*
MIDPOINT_REFLECT_SPLIT_FASTPFOR    +0,9%        +7,0%          +1,8%
MIDPOINT_REFLECT_SYM_FASTPFOR      +1,7%        +9,9%          +3,5%
```

The oracle picks a reflect variant on **0** landcover buffers (17+10 across the whole
corpus, ~8 KB), so it is never the best on the layer it was built for.

**Why it fails: the symmetry prior is wrong for this corpus.** Landcover rings are not
symmetric enough for `start + apex - partner` to beat predicting the *immediately
previous* vertex. A first-order delta spans one short edge; the reflection spans the
deviation from a global symmetry the ring does not have, which is *wider*, not narrower.
Pulling those wide residuals into their own block then makes it worse, not better — the
`SEGMENT_PAIR` lever only pays when the separated population is genuinely narrow (near-zero
quad corners), and here it is the widest of the three. Worst on `water` (+23,5%), whose
rings are the least parallelogram-like of all. The transform and its
`MidpointReflectMechanismTest` are kept so the negative result need not be re-derived; it
is removed from `research()`.
