# 4. S2 Prediction

## What it does
Replaces every vertex by its **residual** `p[i] - pred(i)`, where the prediction is computed from vertices
the decoder already has. A good prediction makes residuals small and repetitive.

## How it works
Every predictor is a `ClassifiedPredictor` (`encoder/prediction/mechanism/ClassifiedPredictor.java`):

- `classify(topology)`: a class per vertex, from the topology only (e.g. run start, interior, corner);
- `hasPrediction(class)`: whether the class has a geometric prediction;
- `predictX/predictY(class, i, decoded)`: the prediction, reading only vertices before `i`;
- `regionOrder()`: the order of the class streams (step 6).

A vertex without a geometric prediction is predicted by the **previous vertex of the buffer**, whatever
feature it belongs to: the delta chain is continuous, there are no reset seeds. The residual loop is in
`Segmentation.split`/`join` (`encoder/segmentation/Segmentation.java`).

## Predictors
`encoder/prediction/Prediction.java`; the code is the value in the header.

| code | predictor | prediction |
|---|---|---|
| 0 | `DELTA` | previous vertex, one class |
| 1 | `QUAD_CORNER` | 4th vertex of a 4-vertex run: parallelogram corner `p0 + p2 - p1` |
| 2 | `LINE_CONTINUATION` | 3rd+ vertex of a line run of ≥ 6 vertices: `p[i-1] + (p[i-1] - p[i-2])` |
| 5 | `QUAD_CORNER_FRAME` | `QUAD_CORNER`, ring edges coded in a predicted frame |
| 11 | `LINE_CONTINUATION_FRAME_HEADING` | `LINE_CONTINUATION`, line edges in a predicted frame, continuation residual in the heading frame |
| 7, 8, 13 | `*_SHARED*` | the predictor above plus references, step 5 |

**`QUAD_CORNER`** (`SegmentQuadCorner`). Classes `START` (first vertex of a run), `INTERIOR`, `CORNER`
(4th vertex of a run of exactly 4). An exact parallelogram has corner residual `(0, 0)`.

**`LINE_CONTINUATION`** (`LineContinuation`). Classes `START`, `INTERIOR` (2nd vertex of a run, every vertex
of a short line run, all ring and point vertices), `LINE_INTERIOR` (continued). Gated by geometry type and
run length (`MIN_LINE_RUN_LENGTH = 6`): shorter runs and polygons got worse with continuation.

```
line run p0..p4:  p0-q   p1-p0   p2-(2p1-p0)   p3-(2p2-p1)   p4-(2p3-p2)
class             START  INTER.  LINE          LINE          LINE
```

**Frame** (`FramePrior`, `QUAD_CORNER_FRAME`). Walls of neighbouring buildings share angles. The plain-delta
edges of a ring (not its start, not a predicted corner) are rotated into a frame: the dominant wall
direction (mod 90°) of the decoded ring whose centroid is nearest the new ring's start, refined by the
ring's own decoded edges. A wall at 30° turns `(87, 50)` into `(100, 0)`. The rotation is three integer
lifting shears, an exact bijection, so the stream still holds two values per vertex. Nothing is signalled.

**Heading** (`LINE_CONTINUATION_FRAME_HEADING`). The same frame on the plain edges of line runs, and the
residual of a continued vertex rotated into the direction of the incoming edge `p[i-1] - p[i-2]`:
x becomes the along-track change, y the sideways bend.

## How the decoder works
Derive the classes from the topology, then walk the vertices in order: read the next residual of the
vertex's class stream, undo the frame rotation if the vertex is framed, add the prediction computed from
the vertices already decoded.

## Why
History (S2 on the Hilbert base, split varint, vs `DELTA_ZIGZAG_VARINT`, gzip): `DELTA` -3,97%,
`QUAD_CORNER` -4,79%, `LINE_CONTINUATION` -3,83%. The best predictor follows the geometry (corners for
buildings, continuation for roads, delta for points), hence the per-layer choice (step 2). Today `DEFAULT`'s
predictor is `QUAD_CORNER_SHARED_FRAME` (-8,41% gzip at the S2 stage).

Retired codes, never reused: 3 `QUAD_CORNER_LINE`, 4 `TURN_SCALAR`, 6 `QUAD_CORNER_LINE_FRAME`,
9 `LINE_CONTINUATION_FRAME`, 10 `LINE_CONTINUATION_HEADING`, 12 `LINE_CONTINUATION_SHARED`
(`encoder/retired/`, [tile-oracle.md](https://github.com/Turtelll/Thesis/blob/main/research-java/docs/results/tile-oracle.md)).

More: [../encoding/good/quad-corner.md](../encoding/good/quad-corner.md),
[../encoding/good/line-continuation.md](../encoding/good/line-continuation.md),
[../encoding/good/quad-corner-frame.md](../encoding/good/quad-corner-frame.md),
[../encoding/good/line-frame.md](../encoding/good/line-frame.md),
[../encoding/good/line-heading.md](../encoding/good/line-heading.md); failed predictors in
the *Encoding → bad* section.
