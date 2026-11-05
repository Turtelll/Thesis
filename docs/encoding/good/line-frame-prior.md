# Line frame prior

**Code names:** `LINE_CONTINUATION_FRAME`

> **Verdict: Kept inside the heading frame.** `transportation` -6,20% gzip against -5,23% for plain
> continuation; as a standalone predictor retired, it lives on in `LINE_CONTINUATION_FRAME_HEADING`.

## Encoding target
transportation, transportation_name (LineString).

## Idea
Roads parallel or perpendicular to an already decoded road run in the same frame (modulo 90°). The edges that
line continuation leaves as plain deltas (2nd vertex of a part, every vertex of a short part) can be rotated
into the frame of the nearest decoded road, so they become one large and one near-zero component. This is the
[ring frame prior](ring-frame-prior.md) applied to open line runs.

## Intention
Line continuation only predicts from the 3rd vertex of a long part on; `transportation` is fragmented into
many short parts, so a large share of its vertices stay plain deltas. A street grid is locally regular, so the
frame of the nearest decoded road should predict the direction of those edges, as it does for buildings. The
frame is derived from decoded data, so nothing is signalled.

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

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`, `transportation`, pipeline with
`HILBERT_CENTROID_ROT_MP`, class streams, escape, planes): **`transportation` -6,20%** with the frame against
**-5,23%** for `LINE_CONTINUATION`. Tileset (per-layer default): -8,25% → -8,40%.

**Decisive case for inclusion:** about one point on `transportation` for nothing on the wire, on all three
corpora tested, and additive with the reorder (+0,98 points without, +0,97 with) and with the heading frame
(together -6,89%). As a separate predictor (header code 9) it was retired by the tile oracle (1,4% of picks, drop cost 15 B),
because its combination with the heading frame dominates it; it lives on in
[`LINE_CONTINUATION_FRAME_HEADING`](line-heading-frame.md).

Supporting figures (same metric, `transportation`):

| corpus | `LINE_CONTINUATION` gzip / Brotli / zstd | `_FRAME` |
|---|---|---|
| Munich (current) | -5,23% / -9,57% / -6,53% | -6,20% / -10,68% / -7,56% |
| Chicago | -1,33% / -7,57% / -3,35% | -1,50% / -7,97% / -3,64% |
| Overture | -5,63% / -9,71% / -7,17% | -6,33% / -10,43% / -7,93% |

- Stage 0 probe (gzip vs `AXIS`, Munich / Chicago / Overture): `NEAREST_CENTROID` -1,42% / -0,15% / -1,02%
  (ceiling `OWN_EDGE` -5,89% / -4,31% / -5,26%; `NEAREST_EDGE` loses on Chicago +0,25%).
- Decode 3,31x `DELTA_ZIGZAG_FASTPFOR` (vs 1,15x plain continuation).

Source: `research-java/docs/results/line-frame-prior.md`, `tile-oracle.md`.
