# Quad corner + line continuation (`QUAD_CORNER_LINE`, `QUAD_CORNER_LINE_FRAME`)

## encoding target:
mixed layers: building, landcover (Polygon) and transportation, transportation_name (LineString) with one predictor

## Idea of the encoding
Corner prediction pays on rings, continuation on lines. One predictor that applies each where it fits is never far
from the best choice on any layer, so a single composition can serve the whole tile.

## How the encoding works
`encoder/retired/QuadCornerLine.java`: per vertex, derived from topology:
- 4th vertex of a 4-vertex ring: `p0 + p2 - p1`;
- 3rd+ vertex of a line run ≥ the length gate: `2·p[i-1] - p[i-2]`;
- run starts and everything else: plain delta.
One class stream per class under `CLASS` segmentation. `QUAD_CORNER_LINE_FRAME` adds the ring frame prior on ring edges.

## How the decoder works
Classify every vertex from the run kinds and lengths; per class recompute the corner, continuation or delta prediction
from decoded vertices and add the residual from that class's stream.

## results
Current corpus `munich-plantiler`, OpenZL pipeline, per tile vs `DELTA_ZIGZAG_VARINT`:
- S2 (`FLAT`): -4,65% gzip / -6,43% Brotli 9 / -6,17% zstd vs `QUAD_CORNER` -4,79%, `LINE_CONTINUATION` -3,83%,
  `DELTA` -3,97%; within 0,3 points of the best on every layer (`transportation` -4,58% vs -4,88%, `building` -5,01%
  vs -5,02%).
- With class streams -5,86%; S8 `GEOMETRY_BEST` -6,58% gzip / -11,72% Brotli / -7,99% zstd; `DEFAULT` -3,63%.
- Was the pipeline's single predictor until the frame and shared predictors; the ladder's S2 is now
  `QUAD_CORNER_SHARED_FRAME`.
- Tile oracle: `QUAD_CORNER_LINE` 2,8% of picks (drop 17 B), `QUAD_CORNER_LINE_FRAME` 1,4% (32 B). **Retired** (header
  codes 3 and 6).
