# Junction back-references

**Status:** regression, unregistered
**Effect:** best variant -2,7% (K=1); K≥2 all net losses
**Corpus:** previous Munich corpus (`munich.mbtiles`) — see [../reordering.md](../reordering.md)
for the corpus-comparability banner

The other structural property of `transportation` is junction sharing: **45%** of parts
share an endpoint with an adjacent part, **37,3%** of vertices exactly repeat another
position in the same buffer, and **18,3%** of part-start residuals against the previous
vertex are already exactly zero. Delta coding cannot capture this, because the repeats are
not adjacent.

The redundancy is real but **could not be addressed cheaply enough to pay for itself.**
Measured as zigzag bit-width sums over samples of the extracted buffers:

- Back-referencing each part start to the best of a window of K recent part endpoints
  shrinks *boundary* bits by up to **-44,1%** (K=32, 28,1% exact hits) — but charging
  ⌈log2(2K+1)⌉ bits per boundary for the reference index makes every K ≥ 2 a **net loss**
  (+2,7% to +3,8%). Only K=1, which needs no index, wins: **-2,7%**.
- A derivable candidate set with an entropy-coded rank (rank entropy 2,40 bits against
  3,00 flat, 46% choose rank 0) nets **-1,4%**.
- A `JUNCTION_REF` transform choosing between {previous vertex, previous part's start} by
  a rule the decoder re-runs was implemented and measured **+1,6%** on `transportation`.

The reason it cannot be rescued by a better rule: a **free, perfect oracle** choosing
between those two references saves only **5,4%** of boundary bits (~1,9% of the buffer),
and the alternative is the better reference at just 24,5% of boundaries. There is not
enough headroom for any decoder-reproducible rule to pay off, so the failure is in the
idea, not in the heuristic.

**The governing constraint, and the reason reordering was not pursued further:** the
redundancy in this layer is abundant but the *addressing* is expensive. Greedy chaining of
parts by shared endpoints is worth **-16,8%** (top-25 buffers) / **-10,2%** (random 40),
but an explicit permutation costs **+32,4%** of baseline — ⌈log2 P⌉ + 1 bits per part, with
P often in the thousands — for a net **+22,2%**. Reordering can only pay if the permutation
is *derived* rather than stored.

> **Superseded on the permutation point.** The premise above — that the new order must be
> transmitted — is wrong: feature order is not a round-trip property, so there is no
> permutation to pay for. See [../reordering.md](../reordering.md), where the same greedy
> chaining measures **-11,8%** corpus-wide. The junction *back-reference* results in this
> section stand; only the reordering verdict is overturned.
