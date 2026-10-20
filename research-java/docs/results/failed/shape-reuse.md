# Shape-reuse feasibility scan (WP5-E5.1)

**Status:** killed, below the 20% gate
**Corpus:** previous Munich corpus (`munich.mbtiles`) — see [../reordering.md](../reordering.md)
for the corpus-comparability banner

`BuildingShapeCli`'s corpus-wide shape index already showed 32,4% of building rings share
a translation-invariant shape with some other ring, and 88-92% of those repeats are
cross-tile — the "redundancy abundant, addressing expensive" wall the junction/reordering
experiments hit (see [junction-backref.md](junction-backref.md)). That measurement doesn't
say whether a codec with only a small *back-window* (not a corpus-wide dictionary) could
still exploit it, since a corpus-wide index can find a match arbitrarily far away in the
buffer. `BuildingShapeCli` was extended with a k-window scan: for k ∈ {1, 4, 16}, does this
ring's canonical shape signature match one of the k most-recently-seen ring signatures in
extraction (buffer) order, exact translation and also allowing 90°-rotation multiples.

```
                       4        5        6        7        8      all
k=1 window hit      0,66%    0,16%    0,04%    0,04%    0,07%    0,52%
  ...+rot90          0,97%    0,16%    0,04%    0,04%    0,07%    0,76%
k=4 window hit      1,79%    0,19%    0,09%    0,04%    0,11%    1,39%
  ...+rot90          2,63%    0,19%    0,09%    0,04%    0,13%    2,03%
k=16 window hit     3,36%    0,23%    0,20%    0,04%    0,20%    2,61%
  ...+rot90          4,87%    0,23%    0,20%    0,08%    0,22%    3,76%
```

**Killed against the plan's own gate (≥20% same-tile hit rate).** Even at k=16 with
rotation, the best bucket (n=4) reaches only 4,87%, and the pooled rate is 3,76% — an
order of magnitude below the threshold, and this is generous to the idea, since real
addressing would also need to encode *which* of the k candidates matched
(⌈log2(k+1)⌉ bits, charged nowhere in this scan). Confirms directly what the corpus-wide
cross-tile share implied: shape repetition in `building` is real but not locally
clustered in buffer order, so a small-window codec cannot reach the redundancy a
corpus-wide dictionary could — and a corpus-wide dictionary is the addressing-cost wall
already measured and rejected for `transportation`'s junctions. **E5.2–E5.5 not
implemented**, per the plan's stop rule.
