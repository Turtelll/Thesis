# Midpoint reflection (`MIDPOINT_REFLECT_SYMMETRIC`, `_SPLIT`)

> **Verdict: Rejected.** +7,15% (`_SPLIT`) and +8,96% (`_SYMMETRIC`) gzip on the tileset; worse on its own
> target layer.

## Encoding target
landcover (Polygon), also water, landuse.

## Idea
Quad corner reaches `landcover` worst. Its larger blocky rings might be centrally symmetric: delta-code the
first half of the ring and predict each second-half vertex as the reflection of a first-half partner through
the midpoint of the ring's start → apex diagonal. A symmetric ring then has zero residuals in its second half.

## Intention
`landcover` rings are large, so a predictor that covers half of every ring at once would be worth much more
than one corner per quad. Central symmetry is the property that makes this possible without signalling: if it
holds, the first half determines the second.

## How the encoding works
- First half of the ring: plain delta.
- Second half: `pred = start + apex - partner`, integer-exact. Pairings: `SYMMETRIC` (partner `L - r`, axis
  reflection), `SPLIT` (partner `r - L/2`, central symmetry).
- Three FastPFOR regions: starts | interior | reflect.

## How the decoder works
Decode the first half by delta; for each second-half vertex compute `start + apex - partner` from decoded vertices and
add the residual.

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`, segmented split varints): **tileset
`_SPLIT` +7,15%, `_SYMMETRIC` +8,96%** (computed from the gzip byte totals). Per layer only the smallest of
gzip/Brotli/zstd was reported: `landcover` +8,29% / +11,23% (+8% to +11%).

**Decisive case against:** landcover rings are not symmetric. The reflection residual measures the deviation
from global symmetry, which is wider than a single edge, so the predictor is worse than plain delta exactly on
the layer it was built for. The oracle picked it on 0 landcover buffers.

Supporting figures:

- Smallest of gzip/Brotli/zstd, tileset: `_SPLIT` +3,57%, `_SYMMETRIC` +4,94%; every layer but `building`
  loses by 2% to 18%.
- *Not under the headline metric* — retired corpus `munich.mbtiles`, FastPFOR: `SPLIT` +0,9% corpus / +7,0%
  landcover / +1,8% POLYGON; `SYMMETRIC` +1,7% / +9,9% / +3,5%; `water` up to +23,5%.

Source: `research-java/docs/results/failed/midpoint-reflect.md`, `revisit-varint-split.md`.
