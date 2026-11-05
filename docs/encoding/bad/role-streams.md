# Role streams (`ROLE`, `ROLE_CLASS` segmentation)

> **Verdict: Rejected.** `ROLE_CLASS` -5,46% gzip on the tileset against -5,86% for plain class streams; the
> extra split costs more than it separates.

## Encoding target
all layers; segmentation stage.

## Idea
Split residuals by the geometric role of the vertex instead of (or in addition to) the predictor class: the
jump to a new run, the first edge of a run, the body. The three have clearly different distributions, so each
should get its own stream (and, with fixed widths, its own byte width).

## Intention
[Class streams](../good/class-streams.md) paid because they separate distributions. The role of a vertex is a
second, predictor-independent axis: jumps are wide (38,8% of values > 255), first edges and bodies narrow. With
fixed byte widths, a stream without jumps could drop to one byte per value.

## How the encoding works
- `ROLE`: three streams, jump | edge | body.
- `ROLE_CLASS`: jump, edge, then the body split by predictor class.
- Roles follow from the topology, nothing signalled. Header segmentation code 2 / 3.

## How the decoder works
Derive each vertex's role (and class) from the topology, size the streams, read each vertex's residual from its
role stream.

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`, pipeline step S3,
`QUAD_CORNER_LINE`): **`ROLE_CLASS` -5,46%** and `ROLE` -4,53% against **`CLASS` -5,86%** (tileset).

**Decisive case against:** the distributions are different, but the first-edge and body streams are nearly the
same (4,9% vs 4,3% of values > 255, mean zigzag 68,5 vs 61,8), so splitting them only cuts one homogeneous
stream in two and gives the compressor less context per stream. It loses at every stage: with escape and
planes (-6,17% vs -6,51%) and with the frame on `building` (-12,31% vs -12,69%). Not in the oracle grid.

Supporting figures (same metric):

- S3: `DELTA` `ROLE` -3,51% vs `FLAT` -3,97%.
- Probe: jump stream 38,8% of values > 255 (mean zigzag 582), edge 4,9% (68,5), body 4,3% (61,8).
- S6/S7: `ROLE_CLASS` + `ESCAPE` -5,57%, + planes -6,17% (vs -6,51% with `CLASS`); with `MIN_FIXED` +12,69%.
- Frame on `building`: `ROLE_CLASS` -12,31% vs `CLASS` -12,69%.

Source: `research-java/docs/results/openzl-pipeline.md`.
