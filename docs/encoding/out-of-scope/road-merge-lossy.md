# Road merge, lossy (`LINE_CONTINUATION_ROAD_MERGE`)

> **Verdict: Out of scope (lossy).** `transportation` -8,95% gzip, 0,57 points below road chaining, but it
> drops feature boundaries and their attributes; every encoding must round-trip losslessly.

## Encoding target
transportation (LineString).

## Idea
Road chaining still transmits one join bit per part and keeps every feature boundary. If the boundaries are
dropped entirely, each chain of joined features becomes one feature and one part; linear referencing could
restore where the original features started.

## Intention
Measure the upper bound of road chaining: what the vertex buffer and the topology would save if feature
boundaries inside a road did not have to be kept.

## How the encoding works
- Same `ROAD_CHAIN` pre-pass as road chaining.
- Every chain of joined features becomes one feature, every road one part; encoded as plain
  `LineContinuation`, no bitmap.
- **Lossy:** feature boundaries (and their attributes) are gone. Verified by `MergeEquivalence`: every
  original part must be a vertex-aligned slice of a merged part, used once, no reversal; polygons unchanged.

## How the decoder works
Decodes the merged roads as plain `LineContinuation`. The original features cannot be recovered from the
vertex buffer; they would need linear referencing outside this format.

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`, `transportation`):
**`_ROAD_MERGE` -8,95%** against **`_ROAD_CHAIN` -8,38%**; tileset -2,80% vs -2,60%.

**Decisive case — why it is out of scope:** it is not lossless. The original features and their attributes
cannot be recovered from the vertex buffer; that would need linear referencing outside this format. The thesis
admits only lossless encodings, so the row lived in `Baselines.lossy()`, never in the oracle, and was removed
in the consolidation (git tag `pre-consolidation`). Even as a bound it is small: a further 15.883 B in the
vertex buffer over road chaining.

Supporting figures:

- Chicago `transportation`: `_ROAD_MERGE` -7,70% vs `_ROAD_CHAIN` -7,04% (same metric), i.e. a further
  -0,62% / -0,71% of the row (Munich / Chicago).
- 20–25% fewer line-feature topology entries (not measured in bytes).

Source: `research-java/docs/results/road-chain.md`, `research-java/docs/consolidation-inventory.md`.
