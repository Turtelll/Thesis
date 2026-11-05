# Quad corner + line continuation (`QUAD_CORNER_LINE`, `QUAD_CORNER_LINE_FRAME`)

> **Verdict: Kept, then retired.** -4,65% gzip on the tileset as one predictor for all layers; replaced by
> per-layer predictors, the tile oracle almost never picks it.

## Encoding target
mixed layers: building, landcover (Polygon) and transportation, transportation_name (LineString) with one
predictor.

## Idea
Corner prediction pays on rings, continuation on lines. One predictor that applies each where it fits is never
far from the best choice on any layer, so a single composition can serve the whole tile.

## Intention
Before compositions could be chosen per layer, the pipeline needed one predictor for every buffer. Quad corner
and line continuation touch disjoint vertices (ring corners vs. line interiors), so combining them should give
each layer its best predictor without any choice to signal.

## How the encoding works
`encoder/retired/QuadCornerLine.java`: per vertex, derived from topology:
- 4th vertex of a 4-vertex ring: `p0 + p2 - p1`;
- 3rd+ vertex of a line run ≥ the length gate: `2·p[i-1] - p[i-2]`;
- run starts and everything else: plain delta.
One class stream per class under `CLASS` segmentation. `QUAD_CORNER_LINE_FRAME` adds the ring frame prior on ring edges.

## How the decoder works
Classify every vertex from the run kinds and lengths; per class recompute the corner, continuation or delta prediction
from decoded vertices and add the residual from that class's stream.

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`, pipeline step S2, `FLAT`): **tileset
-4,65%** against `QUAD_CORNER` -4,79%, `LINE_CONTINUATION` -3,83%, `DELTA` -3,97%; within 0,3 points of the
best on every layer (`transportation` -4,58% vs -4,88%, `building` -5,01% vs -5,02%).

**Decisive case — kept, then retired:** it did its job as the single pipeline predictor (-6,58% gzip on the
S8 stack), but once the [per-layer composition](per-layer-composition.md) picks a predictor per layer, a
compromise predictor has no buffer left where it is best: the tile oracle picks `QUAD_CORNER_LINE` on 2,8% of
buffers (drop cost 17 B) and `QUAD_CORNER_LINE_FRAME` on 1,4% (32 B). Both retired (header codes 3 and 6).

Supporting figures (same metric):

- S2 (`FLAT`): -4,65% gzip / -6,43% Brotli 9 / -6,17% zstd.
- With class streams -5,86%; S8 `GEOMETRY_BEST` -6,58% gzip / -11,72% Brotli / -7,99% zstd; `DEFAULT` -3,63%.
- The ladder's S2 is now `QUAD_CORNER_SHARED_FRAME`.

Source: `research-java/docs/results/openzl-pipeline.md`, `tile-oracle.md`.
