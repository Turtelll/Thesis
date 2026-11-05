# Dropping the zero quad-corner residual

**Code names:** corner stream probe

> **Verdict: Rejected (probe, no encoder).** Flagging the zero corners saves only -0,31% gzip on `building`;
> the flags cost what the zeros did.

## Encoding target
building (Polygon), 4-vertex rings.

## Idea
Under class segmentation the predicted 4th corner of every quad has its own stream. If that residual is zero
for rectangles, it is redundant: drop it and mark only the rings where it is not zero.

## Intention
A stream of zeros still costs bytes after compression. If most corners were exact, one bit per corner (or
less) would replace a full residual for each of them, and only the non-exact ones would be coded.

## How the encoding works
Probe only (`CornerProbe`, removed), sizes only, nothing decodable, on the `building` composition without
shared references (`PIPE_HILBERT_CENTROID_ROT_MP_QUAD_CORNER_FRAME_CLASS_ZIGZAG_ESCAPE`). Three payloads:
- `full`: corner stream kept.
- `dropped`: corner stream removed (upper bound, lossy).
- `flagged`: only nonzero corner residuals kept, plus one bit per corner packed at the end.

## How the decoder works
Not built. A `flagged` decoder would read the per-ring bit; on 0 the corner is exactly
`p0 + p2 - p1`, on 1 it reads the next residual from the corner stream.

## Results
**Headline:** *not measured against `DELTA_ZIGZAG_VARINT`.* The probe compares `building` payloads with and
without the corner stream (gzip per tile, `munich-plantiler`): **`flagged` -0,31%** against the full corner
stream; even the lossy upper bound (`dropped`, every corner removed) is only -5,22%.

**Decisive case against:** the residual is not zero for most corners. Building coordinates are rounded to the
integer grid, so the parallelogram corner is off by the rounding of its four vertices: only 41,62% of corners
are exact, 53,49% are off by one. A flag per corner then carries about as much information as the residual it
replaces, so the real encoding (`flagged`) saves almost nothing. No encoder was built.

Supporting figures:

| corpus | corners | exact (0,0) | off by one | further |
|---|---:|---:|---:|---:|
| Munich (current) | 260.777 | 41,62% | 53,49% | 4,89% |
| Chicago | 623.192 | 36,97% | 61,16% | 1,86% |

Tile-compressed `building` payload vs `full` (with corner stream):

| corpus | row | gzip | Brotli 11 | zstd 19 |
|---|---|---:|---:|---:|
| Munich | `dropped` (bound) | -5,22% | -4,39% | -4,93% |
| Munich | `flagged` | -0,31% | -0,08% | -0,24% |
| Chicago | `dropped` (bound) | -4,58% | -3,89% | -4,32% |
| Chicago | `flagged` | -0,08% | +0,02% | -0,04% |

Open: a rectangle-aware corner prediction in the ring's frame (not tried).

Source: `research-java/docs/results/corner-stream.md`.
