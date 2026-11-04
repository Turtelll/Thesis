# Role streams (`ROLE`, `ROLE_CLASS` segmentation)

## encoding target:
all layers; segmentation stage

## Idea of the encoding
Split residuals by the geometric role of the vertex instead of (or in addition to) the predictor class: the jump to
a new run, the first edge of a run, the body. The three have clearly different distributions, so each should get
its own stream (and, with fixed widths, its own byte width).

## How the encoding works
- `ROLE`: three streams, jump | edge | body.
- `ROLE_CLASS`: jump, edge, then the body split by predictor class.
- Roles follow from the topology, nothing signalled. Header segmentation code 2 / 3.

## How the decoder works
Derive each vertex's role (and class) from the topology, size the streams, read each vertex's residual from its
role stream.

## results
Current corpus `munich-plantiler`, per tile vs `DELTA_ZIGZAG_VARINT` gzip:
- S3: `DELTA` `ROLE` -3,51% vs `FLAT` -3,97%; `QUAD_CORNER_LINE` `ROLE` -4,53% / `ROLE_CLASS` -5,46% vs `CLASS`
  -5,86%.
- Probe: jump stream 38,8% of values > 255 (mean zigzag 582), edge 4,9% (68,5), body 4,3% (61,8): different
  distributions, but the split costs more than it separates.
- S6/S7: `ROLE_CLASS` + `ESCAPE` -5,57%, + planes -6,17% (vs -6,51% with `CLASS`); with `MIN_FIXED` +12,69%.
- Frame on `building`: `ROLE_CLASS` -12,31% vs `CLASS` -12,69%. Discarded; not in the oracle grid.
