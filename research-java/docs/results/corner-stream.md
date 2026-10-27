# The predicted corner of a four-vertex ring is not redundant

**Status:** measured, no encoder built; the one-off probe (`CornerProbe`, `cornerProbe` task) was removed afterwards

A four-vertex ring has three edges inside it plus the jump to its start; the closing edge is not coded (the
extractor drops the closing vertex). The fourth vertex is predicted as the parallelogram corner
`p0 + p2 - p1` and its residual is written in a stream of its own under `CLASS` segmentation. Question: if
that residual is zero for rectangles, can it be dropped?

## Probe

A one-off probe, sizes only, nothing decodable (`CornerProbe` / `CornerProbeCli`, since removed). The `building` composition without its shared
references (`PIPE_HILBERT_CENTROID_ROT_MP_QUAD_CORNER_FRAME_CLASS_ZIGZAG_ESCAPE`), buffers of the layer
concatenated per tile and compressed per tile, as the benchmark does. Three payloads: `full`; `dropped`, the
corner stream removed (an upper bound: the corners that are not exact would be lost); `flagged`, only the
nonzero corner residuals kept plus one bit per corner packed at the end.

## Result

The residual is **not** zero for most corners: building coordinates are rounded to the integer grid, so the
parallelogram corner is off by the rounding of its four vertices.

| corpus | predicted corners | exact (0,0) | off by one | further off |
|---|---:|---:|---:|---:|
| Munich | 260.777 | 41,62% | 53,49% | 4,89% |
| Chicago | 623.192 | 36,97% | 61,16% | 1,86% |

Tile-compressed payload against `full`:

| corpus | row | gzip | Brotli 11 | zstd 19 |
|---|---|---:|---:|---:|
| Munich | `dropped` (bound) | -5,22% | -4,39% | -4,93% |
| Munich | `flagged` | -0,31% | -0,08% | -0,24% |
| Chicago | `dropped` (bound) | -4,58% | -3,89% | -4,32% |
| Chicago | `flagged` | -0,08% | +0,02% | -0,04% |

The corner stream is 4 to 5% of the `building` payload, but almost all of it is the one-unit rounding noise,
not redundancy. Dropping the exact corners behind a flag bit per ring saves 0,0 to 0,3% (and loses under
Brotli on Chicago): the flags cost what the zeros did. **No encoder.**

## Open

The 4 to 5% is where a better corner predictor would pay: `p0 + p2 - p1` carries the rounding of three
decoded vertices plus the vertex's own, and a rectangle-aware prediction in the ring's frame (the fourth
vertex shares one coordinate with each of two decoded ones) carries fewer. Not tried.