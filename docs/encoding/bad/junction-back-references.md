# Junction back-references

**Code names:** `JUNCTION_REF`

> **Verdict: Rejected.** +1,6% on `transportation` (retired corpus, FastPFOR bytes); never measured under the
> headline metric. The idea was solved later by the flagged dictionary.

## Encoding target
transportation (LineString).

## Idea
45% of road parts share an endpoint with an adjacent part, 37,3% of vertices repeat another position. Instead
of a delta, code a part start as a reference to one of the K most recent part endpoints.

## Intention
The part-start jump is the most expensive residual of a road buffer, and at a junction its target is a
position the decoder has already seen. A short index into the last K endpoints should be much cheaper than the
jump.

## How the encoding works
Variants measured (zigzag bit-width sums over samples):
- Window of K recent part endpoints, index `ceil(log2(2K+1))` bits per boundary.
- Derivable candidate set with an entropy-coded rank.
- `JUNCTION_REF` transform: choose between {previous vertex, previous part's start} by a rule the decoder re-runs, no
  signal.

## How the decoder works
At each part start read the index (or apply the rule) and take the referenced endpoint as prediction, then add the
residual.

## Results
**Headline:** *not measured under the headline metric* (retired corpus `munich.mbtiles`, FastPFOR era, no code
left to re-run). `JUNCTION_REF` **+1,6%** on `transportation`.

**Decisive case against:** the redundancy is real, the addressing is too expensive. Every window size K ≥ 2 is
a net loss (+2,7% to +3,8%), because every part start pays for the index, also where there is no hit. Even a
free perfect oracle between the two reference schemes saves only 5,4% of boundary bits (~1,9% of the buffer).
The [flagged dictionary](../good/shared-line-vertices.md) fixed exactly these points (a miss costs 1 bit,
ends are addressed too, parts are reversed, the candidate set is the whole buffer) and is part of the
`transportation` default.

Supporting figures (*retired corpus, FastPFOR bytes / bits*):

- Window: boundary bits up to -44,1% (K=32, 28,1% exact hits), but every K ≥ 2 a net loss (+2,7% to +3,8%);
  K=1 (no index) -2,7%.
- Ranked candidates -1,4%; `JUNCTION_REF` rule +1,6% on `transportation`.
- The "a permutation must be transmitted" verdict on reordering was overturned
  ([nearest-endpoint feature chaining](../good/feature-chaining.md)).

Source: `research-java/docs/results/failed/junction-backref.md`.
