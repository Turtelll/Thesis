# Dropping the zero quad-corner residual (corner stream probe)

## encoding target:
building (Polygon), 4-vertex rings

## Idea of the encoding
Under class segmentation the predicted 4th corner of every quad has its own stream. If that residual is
zero for rectangles, it is redundant: drop it and mark only the rings where it is not zero.

## How the encoding works
Probe only (`CornerProbe`, removed), sizes only, nothing decodable, on the `building` composition without
shared references (`PIPE_HILBERT_CENTROID_ROT_MP_QUAD_CORNER_FRAME_CLASS_ZIGZAG_ESCAPE`). Three payloads:
- `full`: corner stream kept.
- `dropped`: corner stream removed (upper bound, lossy).
- `flagged`: only nonzero corner residuals kept, plus one bit per corner packed at the end.

## How the decoder works
Not built. A `flagged` decoder would read the per-ring bit; on 0 the corner is exactly
`p0 + p2 - p1`, on 1 it reads the next residual from the corner stream.

## results
Corner residuals (rounding to the integer grid moves the corner):

| corpus | corners | exact (0,0) | off by one | further |
|---|---:|---:|---:|---:|
| Munich (current) | 260.777 | 41,62% | 53,49% | 4,89% |
| Chicago | 623.192 | 36,97% | 61,16% | 1,86% |

Tile-compressed payload vs `full`:

| corpus | row | gzip | Brotli 11 | zstd 19 |
|---|---|---:|---:|---:|
| Munich | `dropped` (bound) | -5,22% | -4,39% | -4,93% |
| Munich | `flagged` | -0,31% | -0,08% | -0,24% |
| Chicago | `dropped` (bound) | -4,58% | -3,89% | -4,32% |
| Chicago | `flagged` | -0,08% | +0,02% | -0,04% |

The flags cost what the zeros did. No encoder. Open: a rectangle-aware corner prediction in the ring's
frame (not tried).
