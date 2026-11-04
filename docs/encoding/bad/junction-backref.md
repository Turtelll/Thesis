# Junction back-references (`JUNCTION_REF`)

## encoding target:
transportation (LineString)

## Idea of the encoding
45% of road parts share an endpoint with an adjacent part, 37,3% of vertices repeat another position. Instead of a
delta, code a part start as a reference to one of the K most recent part endpoints.

## How the encoding works
Variants measured (zigzag bit-width sums over samples):
- Window of K recent part endpoints, index `ceil(log2(2K+1))` bits per boundary.
- Derivable candidate set with an entropy-coded rank.
- `JUNCTION_REF` transform: choose between {previous vertex, previous part's start} by a rule the decoder re-runs, no
  signal.

## How the decoder works
At each part start read the index (or apply the rule) and take the referenced endpoint as prediction, then add the
residual.

## results
Retired corpus `munich.mbtiles`:
- Window: boundary bits up to -44,1% (K=32, 28,1% exact hits), but every K ≥ 2 a net loss (+2,7% to +3,8%); K=1 (no
  index) -2,7%.
- Ranked candidates -1,4%; `JUNCTION_REF` rule **+1,6%** on `transportation`.
- A free perfect oracle between the two references saves only 5,4% of boundary bits (~1,9% of the buffer).
- Redundancy abundant, addressing expensive. Solved later by the flagged dictionary
  (shared line vertices via dictionary): a miss costs 1 bit, ends are addressed too, parts
  are reversed, the candidate set is the whole buffer. The "a permutation must be transmitted" verdict on reordering
  was overturned (nearest-endpoint feature chaining).
