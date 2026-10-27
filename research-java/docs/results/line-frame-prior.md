# Line frame prior — `LINE_CONTINUATION_FRAME`

**Status:** registered in the pipeline, win; `forLayer("transportation")` and `("transportation_name")` take `LINE_CONTINUATION_FRAME_HEADING` (the neighbour frame plus the heading frame, see below)
**Effect:** `transportation`, per tile, gzip vs `DELTA_ZIGZAG_VARINT`: Munich -5,23% → -6,20%, Chicago
-1,33% → -1,50%, Overture -5,63% → -6,33%; Brotli and zstd move the same way on all three
**Corpus:** current baseline (`munich-plantiler.mbtiles`, `data/extractedBaseline`, 354 `transportation`
buffers), `data/extracted-chicago` (319) and `data/extracted-overture` (360)

## The question

[orientation-prior.md](orientation-prior.md) codes a polygon ring's edges in a frame predicted from the
rings the decoder has already decoded (`RingFramePrior`): the rotation that makes walls axis-parallel,
found modulo 90 degrees, applied as an exactly invertible integer rotation (three lifting shears), nothing
transmitted. It said "`transportation`, `poi` and the other non-ring layers are untouched by it", because
`Prediction.FramedPredictor` framed ring runs only. Does it carry over to line parts?

Roads parallel or perpendicular to a decoded road vote for the same frame under the modulo-90 vote, so
the mechanism applies unchanged. Three details did not carry over: the ring's closing edge (a line has
none), the 3-vertex minimum (about half of the `transportation` runs have two vertices), and the centroid
as a locator for a long road.

**Which vertices are framed.** Those `LINE_CONTINUATION` leaves to plain delta: the 2nd vertex of every
line run and every vertex of a run shorter than its gate (`MIN_LINE_RUN_LENGTH = 6`). A run's start keeps
its jump delta and a continued vertex keeps its continuation residual. On Munich that is 447.641 edges
against 398.878 continued vertices: 2 vertices 162.535, 3 vertices 91.676, 4-5 146.110, 6-10 32.371,
11+ 14.949.

## Stage 0 probe: `LineFramePriorCli`

`./gradlew research --args="line-frame-prior ../data/extractedBaseline ../data/extracted-chicago ../data/extracted-overture --layer transportation"`.
Sized stream: `LINE_CONTINUATION`'s layout, interleaved zigzag varints, and the three class streams
(unframed plain delta | framed edges | continuation residuals) concatenated, each gzipped per buffer.
Frame sources, all decodable except `OWN_EDGE`:

| source | what the decoder uses |
|---|---|
| `AXIS` | the tile axes, plain delta (reference) |
| `OWN_EDGE` | the edge's own direction (ceiling only) |
| `PREV_PART` | last edge of the previous line run |
| `NEAREST_CENTROID` | frame of the decoded run whose centroid is nearest the run's start vertex |
| `NEAREST_EDGE` | direction of the decoded edge whose midpoint is nearest the start vertex |
| `NEAREST_EDGE_REFINED` | `NEAREST_EDGE`, as a prior of 50 length units refined by the run's own edges |

Gzip of the whole sized buffer, class-split layout, change vs `AXIS`, extraction order (the Hilbert
reorder the pipeline uses moves each number by at most 0,2 points):

| source | Munich | Chicago | Overture |
|---|---:|---:|---:|
| `OWN_EDGE` (ceiling) | -5,89% | -4,31% | -5,26% |
| `PREV_PART` | -0,78% | -0,07% | -0,63% |
| `NEAREST_CENTROID` | -1,42% | -0,15% | -1,02% |
| `NEAREST_EDGE` | -1,23% | +0,25% | -0,95% |
| `NEAREST_EDGE_REFINED` | -1,44% | +0,05% | -1,28% |

Mean zigzag bits per framed edge on Munich fall 11,13 → 10,18 (`NEAREST_CENTROID`), against 6,87 for the
ceiling. Edges with a zero component rise 6,0% → 12,6% (92% in the ceiling). The frame error is
modest: median 17,2 degrees for `AXIS`, 6,9 for `NEAREST_CENTROID` (15% of the edges within 1 degree, 43% within 5).
The gain is concentrated where the plain residual is largest per vertex: 2-vertex runs 10,68 → 9,29 bits,
11+ vertex runs 11,84 → 11,18.

**Gate passed** (the best decodable source lowers Munich gzip and regresses nowhere): `NEAREST_CENTROID`,
which is `RingFramePrior`'s own rule on open runs, is the best source that is not worse than `AXIS` on any of
the three corpora (`NEAREST_EDGE` and `NEAREST_EDGE_REFINED` lose on Chicago, `PREV_PART` is safe but gains half as much). The encoder therefore reuses `RingFramePrior`
(centroid neighbour, refined by the run's own decoded edges) and needs no edge-midpoint index.
Chicago is the corpus where it comes close to a tie: its streets sit on the tile's axes, plain delta
already has a zero component on 12,7% of the framed edges, and the frame error of `AXIS` there (median
1,85 degrees) is smaller than that of any predicted frame (2,2 to 2,6).

## Encoder

- `RingFramePrior.endLine(vertices, start, end)`: remembers an open run (two vertices are enough, no closing
  edge) in the same neighbour grid as the rings. The ring path is unchanged (`endRing` shares the code).
- `Prediction.LINE_CONTINUATION_FRAME`: `FramedPredictor` over `LineContinuation`, now with a run filter
  (rings, lines); `QUAD_CORNER_FRAME` and `QUAD_CORNER_LINE_FRAME` frame rings only, as before. Appended last
  in the enum so the header ordinals of the other predictors do not move. Nothing is signalled; the run
  kinds come from the topology streams, so a buffer whose runs are not derivable (or a single run) is not framed.
- Tests: `LineFrameMechanismTest` (a street grid at 0, 7, 33, 44,9, 45, 61, -20, 90 and 123,4 degrees of 2-,
  3- and 9-vertex parts round-trips from the emitted topology alone under every segmentation, with and
  without the Hilbert reorder; framed is smaller than plain under gzip off the axes and not worse on the axes
  without noise; one run is not framed) and `RingFramePriorMechanismTest.aTwoVertexLineSetsTheFrameOfTheRunsAfterIt`.
  `SegmentationMechanismTest` and the header test cover the new `Prediction` value already.

## Measured: the pipeline, `--step framelines`

`./gradlew research --args="measure ../data/<corpus> --step framelines --layer transportation --by-zoom"`: every row
round-tripped, per tile, gzip / Brotli 11 / zstd 19 against `DELTA_ZIGZAG_VARINT`. `HILBERT_CENTROID_ROT_MP`,
class streams, escape width, byte planes is `forLayer("transportation")`'s terminal:

| corpus | row | gzip | Brotli 11 | zstd 19 |
|---|---|---:|---:|---:|
| Munich | `LINE_CONTINUATION` | -5,23% | -9,57% | -6,53% |
| Munich | `LINE_CONTINUATION_FRAME` | **-6,20%** | **-10,68%** | **-7,56%** |
| Chicago | `LINE_CONTINUATION` | -1,33% | -7,57% | -3,35% |
| Chicago | `LINE_CONTINUATION_FRAME` | **-1,50%** | **-7,97%** | **-3,64%** |
| Overture | `LINE_CONTINUATION` | -5,63% | -9,71% | -7,17% |
| Overture | `LINE_CONTINUATION_FRAME` | **-6,33%** | **-10,43%** | **-7,93%** |

The frame is worth 0,97 / 1,11 / 1,03 points on Munich, 0,17 / 0,40 / 0,29 on Chicago and 0,70 / 0,72 /
0,76 on Overture (gzip / Brotli / zstd). It wins under all three compressors on all three corpora, as the probe
predicted: small, and smallest where the street grid is aligned with the tile.

Other axes, Munich gzip:

- **Segmentation**: `FLAT` -3,45% → -4,31%, `CLASS` -5,23% → -6,20%. The frame helps under both; the class
  streams stay ahead on the escape terminal. On the split-varint terminal (`transportation_name`'s) `FLAT` and
  `CLASS` are even (-6,11% / -6,09%).
- **Reorder**: without one, `CLASS` -3,28% → -4,26% (+0,98); with `HILBERT_CENTROID_ROT_MP`, +0,97. The frame and
  the order are additive here too.
- **Terminal**: on `VARINT_SPLIT` without planes the frame is worth +1,2 points (`FLAT` and `CLASS`, reorder) and the two
  terminals end within 0,1 points of each other (`FRAME_CLASS` escape/planes -6,20%, `FRAME_FLAT` split varint
  -6,11%); the layer keeps its escape/planes terminal.
- **Zoom** (whole tiles, gzip, `CLASS` escape/planes, Hilbert): z10 -2,43% → -3,04%, z11 -3,29% → -3,99%,
  z12 -10,31% → -11,26%, z13 -6,62% → -7,60%, z14 -4,56% → -5,57%. Low zooms (z4, z5) lose against the plain
  baseline either way (+2,3% / +1,8% with the frame): they are a handful of tiles of simplified lines.
- **Other layers** (same step, all layers, gzip, each on its own terminal; Munich / Chicago): `transportation_name`
  (`HILBERT`, `CLASS`, split varint) -4,49% → -5,26% / -1,07% → -1,53%, so it is also a candidate (not switched, Brotli
  and zstd not measured for it). `waterway` is not in any of the three corpora, so its row measured nothing. Layers
  without line parts (`building`, `landuse`, `landcover`, `housenumber`, `poi`, `water`) are unchanged to within 0,02
  points, as the rows do not frame rings.

**Decode time** (`decodeTime --layer transportation`, 354 buffers, end to end): 206 ms, 3,31x `DELTA_ZIGZAG_FASTPFOR`
(worst bucket 5,82x, 5000+ vertices, 94 ns/value), against 63 ms (1,15x) for the same row with plain
`LINE_CONTINUATION`. The 1,5x budget does not apply to this target
([general-purpose-compression.md](general-purpose-compression.md)); it is about 0,4 ms for the
`transportation` buffer of a tile. The cost is `StrictMath.atan2` once per framed edge and a square root per
line edge for the vote; a hand-written atan would cut it (the same open item as `building`).

## Decision

`forLayer("transportation")` took `LINE_CONTINUATION_FRAME`: it wins under gzip, Brotli and zstd on Munich and
loses nowhere on Chicago or Overture. Whole-corpus per-layer default (`--step layer --by-zoom`, Munich, geometry
buffers, per tile): -8,25% → -8,40% gzip, -14,37% → -14,57% Brotli, -10,09% → -10,32% zstd; the `transportation`
layer itself -5,23% → -6,20% gzip. (Both whole-corpus pairs are from this change's own before/after runs; the
figures in [openzl-pipeline.md](openzl-pipeline.md#per-layer-default) were measured earlier.) The heading frame
below then superseded it.

## The heading frame: `LINE_CONTINUATION_HEADING`, `LINE_CONTINUATION_FRAME_HEADING`

The neighbour frame only touches the plain-delta edges. The continued vertices (the interior of a run of 6 or
more) are predicted as `p[i-1] + (p[i-1]-p[i-2])` and their residual is the bend and speed change, still in tile
axes. The heading predictors rotate that residual into the direction of the incoming edge `p[i-1]-p[i-2]`: x is
the along-track change (the step got longer or shorter), y the sideways one (the road bent). The decoder already
has both vertices, so nothing is signalled.

- **Rotation**: `RingFramePrior.forwardHeading/inverseHeading(ex, ey, dx, dy)`: the direction is `atan2` (with
  `StrictMath`), split into whole quarter turns (exact `(x, y) → (y, -x)`) and a remainder in [-45°, 45°)
  quantised to 16 bits, rotated by the same three-shear integer rotation as the neighbour frame. A bijection on
  the integers for every edge; a zero edge leaves the residual as it is.
- **Predictors**: `LINE_CONTINUATION_HEADING` rotates the continued vertices only; `LINE_CONTINUATION_FRAME_HEADING`
  also codes the plain edges in the neighbour frame (disjoint vertices, so no interaction). `FramedPredictor` takes
  a `heading` flag; `FramePass.accept` remembers the last accepted edge, `framed(i)` is true for a continued vertex
  two or more past its run's start.
- **Tests**: `RingFramePriorMechanismTest` (`theHeadingRotationIsExactlyInvertibleForEveryEdge`: edges at every 5°
  from -180° to 180°, five lengths and the zero edge; `theHeadingRotationPutsTheEdgeItselfOnThePositiveXAxis`) and
  `LineFrameMechanismTest` (`theHeadingFrameCostsLessOnWindingRoadsThanPlainContinuation`, and
  `theHeadingPredictorsDecodeFromTheTopologyAloneToTheEmittedBuffer`: both predictors, every segmentation, with and
  without the Hilbert reorder, roads heading at 0°, 33°, 90°, 123,4° and -170° plus a grid, decoded from the emitted
  topology).

Measured (`--step framelines`, `HILBERT_CENTROID_ROT_MP`, class streams, escape width, planes, per tile, change
vs `DELTA_ZIGZAG_VARINT`; round-tripped):

| corpus | `LINE_CONTINUATION` | `_FRAME` | `_HEADING` | `_FRAME_HEADING` |
|---|---:|---:|---:|---:|
| Munich gzip | -5,23% | -6,20% | -5,92% | **-6,89%** |
| Munich Brotli 11 | -9,57% | -10,68% | -10,32% | **-11,55%** |
| Munich zstd 19 | -6,53% | -7,56% | -7,20% | **-8,25%** |
| Chicago gzip | -1,33% | -1,50% | -1,60% | **-1,78%** |
| Chicago Brotli 11 | -7,57% | -7,97% | -7,89% | **-8,29%** |
| Chicago zstd 19 | -3,35% | -3,64% | -3,61% | **-3,91%** |
| Overture gzip | -5,63% | -6,33% | -6,68% | **-7,47%** |
| Overture Brotli 11 | -9,71% | -10,43% | -10,87% | **-11,76%** |
| Overture zstd 19 | -7,17% | -7,93% | -8,22% | **-9,07%** |

The two levers add (Munich gzip: 0,97 + 0,69 = 1,66 points; measured 1,66). On Chicago, whose grid is aligned with
the tile, the heading alone beats the neighbour frame (-1,60% vs -1,50%): it does not depend on neighbours.
`_FRAME_HEADING` is best on every corpus under every compressor; in the Munich, Chicago and Overture runs it is
also best in both segmentations (`FLAT`, `CLASS`) and on both terminals (escape/planes, split varint). By zoom (Munich, gzip, `CLASS`, escape/planes, Hilbert) it
turns z5 into a tie (-0,31% against +1,82% with the frame) and adds 0,5 to 1,2 points on z6 to z14 over the frame;
z4 is still above the baseline (+1,17%).

Other layers: `transportation_name` (`HILBERT`, `CLASS`, split varint, gzip, per tile) Chicago -1,07% → -1,53%
(frame) → -1,84% (`_FRAME_HEADING`); Munich -4,49% → -6,76% in the full per-layer run below. Overture has no
`transportation_name`. `waterway` is in none of the corpora (the layer step's tables have no column for it), so
its `--layer waterway` rows are not evidence and it is left on `LINE_CONTINUATION`.

**Decode time** (`decodeTime`, `transportation`, 354 buffers, end to end, pipeline composition names): 239 ms,
4,92x `DELTA_ZIGZAG_FASTPFOR` (worst bucket 8,89x, 5000+ vertices, 106 ns/value), against 197 ms (4,06x) for
`LINE_CONTINUATION_FRAME` and 53 ms (1,10x) for plain `LINE_CONTINUATION` in the same run (the machine was slower
in this run than in the 3,31x measurement above). The heading's own cost is one `StrictMath.atan2` per continued
vertex, about 0,1 ms per `transportation` buffer (42 ms over 354 buffers). The 1,5x budget does not apply to this target
([general-purpose-compression.md](general-purpose-compression.md)); a hand-written atan or a table over the
quantised direction would cut it.

**Decision.** `forLayer("transportation")` and `forLayer("transportation_name")` take `LINE_CONTINUATION_FRAME_HEADING`.
Whole-corpus per-layer default (`--step layer --by-zoom`, Munich, per tile), `LINE_CONTINUATION_FRAME` on
`transportation` only → both layers on `_FRAME_HEADING`: -8,40% → -8,64% gzip, -14,57% → -14,82% Brotli,
-10,32% → -10,54% zstd; the `transportation` layer by itself -6,20% → -6,89% and `transportation_name` -4,49% →
-6,76%. (These before/after pairs are history of each decision; the whole-corpus per-layer default today, with
the later shared-reference predictors, is -11,36% gzip / -17,18% Brotli / -13,03% zstd.)

A trap to remember when changing a layer's predictor: `transportation` alone on `_FRAME_HEADING` made the
whole-corpus number *worse* (-8,40% → -8,17% gzip) while the layer got better (-6,20% → -6,89%), although the raw
and order-0 sizes both fell. The tile's gzip sees `transportation` and `transportation_name` carry the same road
geometry; changing one without the other (here `transportation_name` still on plain continuation) cost
more than the layer gained. They are switched together. Judge a change on the layer set, not on the layer.

## Not done / next

- **Cheaper heading.** The direction is recomputed with `atan2` for every continued vertex; the sign and slope
  ratio of the edge would pick a table entry without it, at a possible loss of a few quantisation steps.
- **Noise on short axis-aligned edges.** Rounding noise makes a neighbour's frame a few degrees off the axes,
  which costs a little where the streets are exactly aligned: a synthetic grid on the axes with +-1 unit of noise
  is 1,2% larger framed than plain under gzip (`LineFrameMechanismTest` keeps the noiseless case), and Chicago is
  the near-tie above. Snapping frames within a small angle of 0 to 0, or a minimum edge length for a vote, were
  not tried.
- **Tuning**: `PRIOR_WEIGHT` and the cell size are the ring prior's, not swept for lines. Edge-midpoint
  neighbours (`NEAREST_EDGE`) lost to the centroid on the probe and were not built.
- **Other layers**: `transportation_name` (above) and any line layer of a corpus that has it (`waterway`).
- **Old encoding set**: not combined with `LINE_CONTINUATION_ROAD_CHAIN` or `_SHARED_DICT`; the pipeline has no
  road chaining.
