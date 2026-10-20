# Delta-of-delta and reset variants

**Status:** regression, unregistered — removed from `research()` after measuring
**Effect:** all six variants larger than baseline, +9,4% to +14,1%
**Corpus:** previous Munich corpus (`munich.mbtiles`) — see [../reordering.md](../reordering.md)
for the corpus-comparability banner

**These encodings are no longer in `research()`** — they were removed after measuring.
The numbers are kept because a negative result with an explained mechanism is worth not
re-deriving; to reproduce them, reimplement the transforms and re-register them.

Six variants over two axes: differencing order (first vs second) and reset strategy
(continuous vs restart at each part/ring boundary). All are lossless over all 8.366
corpus buffers; all are **larger than the baseline**:

```
DELTA_ZIGZAG_FASTPFOR     13.399.028        ref     (baseline: 1st order, continuous)
DELTA_RESET_VARINT        14.661.673      +9,4%
DOD_CONT_FASTPFOR         14.828.938     +10,7%
DOD_RESET_VARINT          14.883.608     +11,1%
DELTA_RESET_FASTPFOR      15.078.047     +12,5%
DOD_CONT_VARINT           15.155.705     +13,1%
DOD_RESET_FASTPFOR        15.282.605     +14,1%
```

**The resets, not the second differencing, are the dominant cost.**
`DELTA_RESET_FASTPFOR` is +12,5% while still being plain first-order — nearly all of
the +14,1% that the full delta-of-delta variant loses. Each run restart stores a
full-magnitude raw coordinate (up to the ~13-bit tile extent) instead of a small
residual, and map layers have many short runs, so the seeds cost more than the boundary
spikes they avoid. Second-order differencing alone (`DOD_CONT_FASTPFOR`, +10,7%) is
*cheaper* than resetting alone; combining them is worst.

Two clean controls fall out of the per-layer table: on `housenumber` (point-only) and on
`POINT` geometry, `DELTA_RESET_*` is byte-identical to the baseline — with no runs to
reset on, the transform degrades exactly to it.

Second differencing is a real cost on its own. Over a 300-buffer sample (357k
positions) the second-order residual is smaller at **60,1%** of positions, yet its mean
zigzag width **rises from 6,18 to 7,14 bits**, because differencing roughly doubles the
magnitude wherever consecutive deltas disagree in sign:

| zigzag width | ≥ 8 bits | ≥ 12 bits |
|---|---|---|
| first-order  | 24,9%    | 6,2%      |
| second-order | 36,4%    | 10,1%     |

Both physical codecs price the tail, not the median — FastPFOR packs a block to the
width of its widest value, and a varint crossing 127 costs another whole byte. Winning
the median while thickening the tail is a net loss: the predicted varint cost,
1,250 → 1,369 bytes/value (+9,5%), matches the measured `DOD_CONT_VARINT`.

Worst on `transportation` and `MULTILINESTRING` — the long polylines expected to benefit
most. Real map vertices are quantized to the tile grid, so successive deltas carry
rounding noise that a second difference amplifies rather than cancels. That the segments
predicted to win lost hardest is the useful part of the result.

## Would a tile ever pick one of these?

No — which is why they were removed. The tables above choose one encoding per corpus or
per layer, but a real tile selects per stream — `StreamMetadata` already carries the
technique per stream, so an encoder may legitimately encode each buffer with whichever
candidate is smallest. See [../selection-oracle.md](../selection-oracle.md) for the
per-buffer selection numbers (this family contributes only 0,14% on top of selecting
between the two baselines) and the general lesson: **structural reset points are not
free.**
