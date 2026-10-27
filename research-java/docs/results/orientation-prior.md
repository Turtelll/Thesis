# Ring frame prior — `QUAD_CORNER_SHARED_FRAME`

> **Consolidation note.** The Gradle task(s) benchmark named below were removed from the build (the code is at git tag `pre-consolidation`); `research measure` and the other `research` commands replace the rest. The numbers below are unchanged history.

> **Partially rebuilt.** The original file was lost (it was never committed). This version holds the
> sections whose numbers are still known: the idea, the headline results from the results
> [README](README.md), the pipeline, workshop and other-layer measurements, decode time and open items.
> Sections marked **TODO** need the Stage 0 probe and the encoder benchmark re-run
> (`./gradlew research --args="orientation-prior ../data/extractedBaseline --layer building"` and
> `./gradlew benchmark`) to be written back with their original numbers.

## The idea

A ring's edges are mostly walls, and the walls of one building sit at a few angles that repeat over
neighbouring buildings and along the length of a street. The plain delta residual of a wall is large and
spread over x and y whenever the wall is not axis-aligned. If the edge is first rotated into the wall's own
frame, a wall becomes (almost) one big component and one near-zero one.

- **Frame of a ring:** the length-weighted mean wall direction modulo 90°, with the direction angles
  quadrupled so that right-angle walls vote together. Quantized to 16 bits, folded to [-45°, 45°).
- **Prior:** the frame of the decoded ring whose centroid is nearest the new ring's start vertex. It counts
  as one wall of `PRIOR_WEIGHT = 50` units and is refined by the ring's own decoded edges, referenced
  vertices included, so the decoder follows the encoder edge by edge.
- **Rotation:** the edge is rotated by -frame with three lifting shears. Each shear is an integer bijection,
  so the rotation is exactly invertible, the stream keeps two values per vertex, and nothing is transmitted.
- **Determinism:** IEEE arithmetic plus `StrictMath`. The wall vote is trig-free (`z^4 / |z|^3`), the shear
  coefficients come from a static table of 65.536 entries.

Implemented by `RingFramePrior` (`encoding/research/`). It is applied to the plain-delta vertices of
polygon rings other than a ring's start, i.e. the vertices a corner predictor leaves to plain delta.
`SharedVertexRef` takes it as the `framed` option (`QUAD_CORNER_SHARED_FRAME`, with and without
`RING_CANONICAL_THEN_FEATURE`); the pipeline and the workshop take it as `QUAD_CORNER_FRAME` /
`QUAD_CORNER_LINE_FRAME` (below). `RingFramePriorMechanismTest`: a street at 0°, 7°, 33°, 44,9°, 45°, 61°,
-20°, 90° and 123,4° round-trips from topology alone, the shear is exactly invertible in every frame, the
framed stream is smaller than plain delta off the axes and not worse on them.

## Probe first (Stage 0): `OrientationPriorCli`

**TODO** — numbers lost. `./gradlew research --args="orientation-prior ../data/extractedBaseline --layer building"`
measures the frame from decoded neighbours against a pre-pass of per-buffer frames, with plain delta as
the reference. The conclusion that was drawn: free decoded-neighbour context beats a pre-pass of buffer
frames, because the index cost of the pre-pass exceeds its accuracy gain.

## Measured: the encoder

**TODO** — the per-row table was lost. From the results [README](README.md), the Wins row: per tile against
`DELTA_ZIGZAG_VARINT` on the current corpus, `QUAD_CORNER_SHARED` -5,58% → -6,47% gzip and -11,98% →
-13,62% Brotli with the frame; composed with the canonical-ring reorder,
`QUAD_CORNER_SHARED_FRAME_RING_CANONICAL_REORDER` is -8,19% gzip / -15,21% Brotli / -9,92% zstd, the best
row of the old encoding set (it was -7,34% / -13,54% / -8,77%); `building` -13,35% gzip / -20,78% Brotli.
Applies to `building`, `landcover`, `landuse` and `water`.

## In the OpenZL-style pipeline (`building`, `HILBERT_CENTROID_ROT_MP`)

The frame is also a predictor of the separate `pipeline/` package: `Prediction.QUAD_CORNER_FRAME` and
`QUAD_CORNER_LINE_FRAME` (the 3-bit header field now holds 7 of 8 values). The frame rotates the
*residual*, not the prediction, so `Segmentation.split/join` ask a `Prediction.FramedEdges.Pass` per vertex
(`framed(i)`, `forward`/`inverse`, `accept(i, vertices)`); the pass derives ring runs from the topology
streams, as `SharedVertexRef` does, and frames the plain-delta vertices of polygon rings except a ring's
start. Same `RingFramePrior`, same integer rotation, no extra stream. The pipeline has no shared-vertex
references, so the frame has to earn its gain on the plain QUAD_CORNER residual alone.
Run: `./gradlew research --args="measure --step frame --layer building --by-zoom --with-old"`; 310 `building` buffers,
every row round-tripped, per tile, change vs `DELTA_ZIGZAG_VARINT`:

| Row (`HILBERT_CENTROID_ROT_MP`, `CLASS`, `ESCAPE`) | gzip | Brotli 11 | zstd 19 |
|---|---:|---:|---:|
| `QUAD_CORNER` (the previous `forLayer("building")`) | -9,20% | -13,61% | -10,41% |
| `QUAD_CORNER_FRAME` | **-12,69%** | **-19,80%** | **-15,36%** |
| `QUAD_CORNER_LINE_FRAME` (same: `building` has only rings) | -12,69% | -19,82% | -15,36% |

The frame is worth 3,5 points gzip, 6,2 Brotli and 5,0 zstd on top of the layer's previous composition,
at z13 -3,15% → -6,46% and at z14 -10,68% → -14,21% (gzip, whole tiles). The other axes, gzip:

- **Segmentation**: `FLAT` -5,43% → -9,89%, `CLASS` -9,20% → -12,69%, `ROLE_CLASS` -8,61% → -12,31%. The
  frame helps under every layout and the class streams stay best (the frame removes the fixed-angle
  structure, not the corner/edge split). It works with every segmentation because it sits in
  `Segmentation.split/join` on the residual values; `SegmentationMechanismTest` round-trips every
  `Prediction` under every `Segmentation`.
- **Reorder**: without any, `CLASS` -8,11% → -11,41%; plain `HILBERT_CENTROID_ROT` -12,46%, `_MP` -12,69%. The
  frame and the Hilbert order are nearly additive (the order helps the frame's neighbour lookup too).
- **Terminal**: `VARINT_SPLIT` -12,45% against `ESCAPE` -12,69%, and `ESCAPE_PLANES` is the same row as the
  grid's own (planes are `DEFAULT`'s); the escape width stays the right one, the rotated residuals still
  fit in a byte.
- Old-pipeline references on the same buffers: `QUAD_CORNER_SHARED_RING_CANONICAL_REORDER` -10,97% gzip /
  -16,05% Brotli / -12,33% zstd, which the pipeline row beats by 1,7 / 3,8 / 3,0 points. The old
  framed row (`building` -13,35% gzip / -20,78% Brotli, above) is still 0,7 / 1,0 points ahead of it, so
  the shared-vertex references the pipeline lacks are worth about that much on top of the frame
  (those two numbers come from different runs and are not re-measured side by side here).

**In the workshop** (`./gradlew research --args="workshop --layer building"`): `QUAD_CORNER_FRAME` and
`QUAD_CORNER_LINE_FRAME` are known rows (the pipeline predictors through its own `FLAT` split, so the
residual pairs are the pipeline's `FLAT` ones; a workshop `Predictor` rule only predicts a position and
cannot rotate a residual, so a new rule cannot be framed from there). Interleaved over Varint, per-tile gzip
vs `DELTA_ZIGZAG_VARINT`, `building`: `QUAD_CORNER` -3,00% → `QUAD_CORNER_FRAME` -7,39% (mean |x| 30,3 → 29,1,
median |x| 9 → 6, |y| 9 → 5); behind `--reorder HILBERT_CENTROID_ROT_MP` -4,27% → -8,85% (median |x| 8 → 5,
|y| 8 → 4). Varint bytes do not move (-0,21% → -0,16%): a residual below 64 is one byte either way, so the
gain is in what gzip makes of smaller, more regular residuals, not in byte count. `QUAD_CORNER_SHARED`
(-10,55% / -11,57% reordered; the references remove vertices, the frame only shrinks residuals) stays
ahead of the frame alone in these rows; the two compose, as `QUAD_CORNER_SHARED_FRAME` does.

Decode time (`decodeTime --layer building --encoding PIPE_..._QUAD_CORNER_FRAME_CLASS_ZIGZAG_ESCAPE`,
310 buffers, end to end): 490 ms, 5,86x `DELTA_ZIGZAG_FASTPFOR` (worst bucket 7,38x, 5000+ vertices,
112 ns/value), against 127 ms (1,52x) for the same row with `QUAD_CORNER`. `forLayer("building")` takes the
frame: the 1,5x budget does not apply to this target
([general-purpose-compression.md](general-purpose-compression.md)), and the whole-corpus per-layer default
goes to -7,36% gzip / -13,55% Brotli / -9,16% zstd at the time (today's whole-corpus default, with the shared-reference
predictors: -11,36% / -17,18% / -13,03%) ([openzl-pipeline.md](openzl-pipeline.md#per-layer-default)).

Other layers (`./gradlew research --args="measure --step framelayers --layer <name>"`, each layer's own terminal,
plain → framed, gzip / Brotli / zstd): `landcover` -4,70% → -5,15% / -7,38% → -7,92% / -5,64% → -6,21%,
`landuse` -4,70% → -5,72% / -8,41% → -9,63% / -6,16% → -7,29%, `aeroway` -2,14% → -2,21% / -16,47% →
-17,84% / -10,96% → -11,13% (58 buffers): these three now use the frame. `water` -2,41% → -2,24% gzip
(Brotli -7,85% → -7,92%, zstd -4,26% → -4,15%), `boundary` and `park` tie, so they stay plain. The frame is
worth far less outside `building` (likely because those layers' polygons are less rectilinear; not
measured), and `poi` and the other non-ring layers are untouched by it. `transportation` was untouched too,
until the same prior was applied to line parts: [line-frame-prior.md](line-frame-prior.md)
(`LINE_CONTINUATION_FRAME`, -5,23% → -6,20% gzip on Munich). Whole-corpus
per-layer default: -7,45% gzip / -13,66% Brotli / -9,29% zstd (at the time; today -11,36% / -17,18% / -13,03%). Decode on those layers is cheaper than on
`building`: `landcover` 2,41x FastPFOR (plain 0,56x), `landuse` 1,55x (plain 0,46x).

## Decode time (old encoding set)

`./gradlew research --args="decode-time ../data/extractedBaseline --encoding QUAD_CORNER_SHARED_FRAME --layer building"`
(end to end, 310 `building` buffers): 5,1x `DELTA_ZIGZAG_FASTPFOR` (487 ms against 95 ms; 7,0x in the 5000+
vertex bucket, 115 ns/value), against 1,37x for `QUAD_CORNER_SHARED`. The 1,5x decode budget does not
apply to the general-purpose-compression target ([general-purpose-compression.md](general-purpose-compression.md)):
this is about 1,6 ms for a whole `building` buffer of a tile, but it is the price of the row.
First version was 17x: the neighbour search used a boxed `HashMap` and `StrictMath.hypot`, the wall
vote three trig calls. What is left is mostly `StrictMath.atan2` once per coded edge (the quantized
frame) and once per ring; a hand-written atan with fixed arithmetic would remove it and is not built.

## Not done / next

- **Property side**: nothing changes there, the prior reads only geometry.
- **Other predictors**: the frame hook applies to any `ClassifiedPredictor`'s plain-delta ring edges.
  `QUAD_CORNER_LINE_SHARED` and the 6- and 8-vertex ring specifics (rot90) are not combined with it.
- **Tuning**: `PRIOR_WEIGHT` (50) and the cell size (128) were set from the probe's reasoning, not swept,
  in the old encoding set or under the pipeline. The nearest *right-angled* ring, a second ring as a
  tie-breaker, and a prior blended from the k nearest were not tried (the probe's rect-only nearest was no
  better).
- **Plain-mode fallback** has no frame: a buffer where the shared stream loses to plain QUAD_CORNER
  also loses the prior.
- **Other corpora**: Munich only. A city whose buildings sit on the tile's axes would seed 0° and gain
  little; a city of mixed street angles could gain more. Neither is measured (`data/extracted-chicago`
  was not run).
- **Pipeline**: shared-vertex references are not in the pipeline; a faster `atan` would cut the decode
  cost of the framed rows.
