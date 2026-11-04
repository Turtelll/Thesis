# Line frame prior (`LINE_CONTINUATION_FRAME`)

## encoding target:
transportation, transportation_name (LineString)

## Idea of the encoding
Roads parallel or perpendicular to an already decoded road run in the same frame (modulo 90°). The edges
that line continuation leaves as plain deltas (2nd vertex of a part, every vertex of a short part) can be
rotated into the frame of the nearest decoded road, so they become one large and one near-zero component.
This is the ring frame prior applied to open line runs.

## How the encoding works
- Same `FramePrior` as for rings: frame of the decoded run whose centroid is nearest the run's start vertex,
  prior weight 50, refined by the run's own decoded edges; `endLine` remembers an open run (two vertices are
  enough).
- **Framed vertices:** those `LINE_CONTINUATION` leaves to plain delta: 2nd vertex of every line run and
  every vertex of a run shorter than `MIN_LINE_RUN_LENGTH = 6`. Run starts keep their jump delta, continued
  vertices their continuation residual.
- Residual rotated by `-frame` with the exact three-shear integer rotation; nothing signalled. Not framed if
  runs are not derivable or there is a single run.

## How the decoder works
Derive the run kinds from the topology, decode in order, look up the nearest decoded run's frame at each run
start, apply the inverse rotation to each framed residual and add it to `p[i-1]`, update the frame with the
decoded edge, store the finished run in the neighbour grid.

## results
Stage 0 probe (gzip vs `AXIS`, Munich / Chicago / Overture): `NEAREST_CENTROID` -1,42% / -0,15% / -1,02%
(ceiling `OWN_EDGE` -5,89% / -4,31% / -5,26%; `NEAREST_EDGE` loses on Chicago +0,25%).

Pipeline (`HILBERT_CENTROID_ROT_MP`, class streams, escape, planes), `transportation`, per tile vs
`DELTA_ZIGZAG_VARINT`:

| corpus | `LINE_CONTINUATION` gzip / Brotli / zstd | `_FRAME` |
|---|---|---|
| Munich (current) | -5,23% / -9,57% / -6,53% | -6,20% / -10,68% / -7,56% |
| Chicago | -1,33% / -7,57% / -3,35% | -1,50% / -7,97% / -3,64% |
| Overture | -5,63% / -9,71% / -7,17% | -6,33% / -10,43% / -7,93% |

- Additive with the reorder (+0,98 without, +0,97 with). Whole-corpus per-layer default -8,25% → -8,40% gzip.
- Decode 3,31x `DELTA_ZIGZAG_FASTPFOR` (vs 1,15x plain continuation).
- As a standalone predictor (code 9) retired by the tile oracle (1,4% of picks, drop cost 15 B); it lives on
  inside line heading's `LINE_CONTINUATION_FRAME_HEADING`.
