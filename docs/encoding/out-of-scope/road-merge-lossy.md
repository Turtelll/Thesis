# Road merge, lossy (`LINE_CONTINUATION_ROAD_MERGE`)

## encoding target:
transportation (LineString)

## Idea of the encoding
Road chaining still transmits one join bit per part and keeps every feature boundary. If the boundaries are
dropped entirely, each chain of joined features becomes one feature and one part; linear referencing could
restore where the original features started.

## How the encoding works
- Same `ROAD_CHAIN` pre-pass as road chaining.
- Every chain of joined features becomes one feature, every road one part; encoded as plain
  `LineContinuation`, no bitmap.
- **Lossy:** feature boundaries (and their attributes) are gone. Verified by `MergeEquivalence`: every
  original part must be a vertex-aligned slice of a merged part, used once, no reversal; polygons unchanged.

## How the decoder works
Decodes the merged roads as plain `LineContinuation`. The original features cannot be recovered from the
vertex buffer; they would need linear referencing outside this format.

## results
Current corpus `munich-plantiler` / Chicago, `transportation`, per-tile gzip vs `DELTA_ZIGZAG_VARINT`:
`_ROAD_MERGE` -8,95% / -7,70% (`_ROAD_CHAIN` -8,38% / -7,04%), i.e. a further -0,62% / -0,71% of the row;
20–25% fewer line-feature topology entries (not measured in bytes).

Not admissible: every encoding must round-trip losslessly. Kept in `Baselines.lossy()`, own section, never in
the oracle; removed in the consolidation (git tag `pre-consolidation`).
