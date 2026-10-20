# Edge continuation gated by geometry type — `LINE_CONTINUATION`

**Status:** registered — win
**Effect:** -0,7% corpus mean residual bits (-2,1% on `transportation`) / -0,1% to -0,2%
corpus bytes (FastPFOR) / -6,8% transportation bytes as the `_SEG` add-on
**Corpus:** current baseline (`munich-plantiler.mbtiles`, `data/extractedBaseline`, 358
tiles, 4.368 buffers, `--set: core -rans`)

## What it does

A `ClassifiedPredictor` (`encoding/research/LineContinuation.java`) that predicts each
vertex from the two vertices before it, but only inside LineString/MultiLineString runs
of at least 4 vertices; everywhere else it is plain delta. Packed like
`SegmentPair`/`SegmentQuadCorner`, three classes:

1. **part start** — first vertex of each run. Plain delta.
2. **line interior** — 3rd+ vertex of a LineString/MultiLineString run that has at least
   4 vertices. Predicted as `pred = p[i-1] + (p[i-1] - p[i-2])` — the point continuation
   would reach if the incoming edge kept going straight. Residual = actual - predicted,
   zigzag-encoded per component, same as every other predictor here.
3. **interior** — the 2nd vertex of any run (no `p[i-2]` inside the run yet), every
   vertex of a line run shorter than 4 vertices, and every polygon-ring and point
   vertex. Plain delta.

The length gate (`MIN_LINE_RUN_LENGTH = 4`) excludes 2- and 3-vertex runs, which
measured no gain or a regression (below) — a 2-vertex run has no interior vertex at
all, and a 3-vertex run's one candidate interior vertex still regressed +1,13% mean
bits corpus-wide.

The class of every vertex is derived from the topology streams (`geometryTypes`,
`numGeometries`, `numParts`, `numRings`) on both encode and decode, identically —
nothing is signalled, and `prevX`/`prevY` still advance continuously through the actual
vertices regardless of class, so there are no reset seeds.

**Locating a run's geometry type.** `PartBoundaries.of` gives run boundaries but not
which run belongs to which feature or geometry type — and `VertexBufferExtractor` puts
LineString vertex counts in `numRings`, interleaved with polygon rings, whenever the
column contains any polygon at all (`docs/mlt-fidelity.md`). `LineContinuation` walks
`geometryTypes` feature by feature, consuming `numGeometries` entries for Multi* types
and `numParts`/`numRings` entries for polygons exactly as `GeometryReorder.featureGroups`
does, to label each run LINE or not before classifying its vertices. If a column's
streams don't line up with the derived runs (points in the column, a malformed stream),
classification falls back to marking only part starts — the same honest-degrade
`PartBoundaries` uses when it can't derive runs at all.

Registered under both `Layout.INTERLEAVED` (core) and `Layout.SEGMENTED` (`_SEG`
add-on) via `PredictedTransform`, so no transform/codec code was written for this
encoding beyond the classifier itself.

## Measured (current baseline corpus, `--set: core -rans`)

Core interleaved, `--layer transportation`:

```
--- residual bits ---
transform                 values  mean bits   vs DELTA_ZIGZAG
DELTA_ZIGZAG           2.361.446       6,39               ref
QUAD_CORNER            2.361.446       6,40             +0,2%
LINE_CONTINUATION      2.361.446       6,25             -2,1%

--- by layer ---
transportation  354 buffers  3.066.704 baseline bytes
  winner: LINE_CONTINUATION_FASTPFOR   3.063.536   -0,1%
```

Corpus-wide, core interleaved:

```
--- residual bits ---
transform                 values  mean bits   vs DELTA_ZIGZAG
DELTA_ZIGZAG          10.717.858       5,82               ref
QUAD_CORNER           10.717.858       5,61             -3,5%
LINE_CONTINUATION     10.717.858       5,78             -0,7%
```

POLYGON, POINT and MULTIPOINT buffers are **byte-identical** to the baseline —
`building` stays on `QUAD_CORNER_FASTPFOR`, `housenumber`/`poi` stay on
`DELTA_ZIGZAG_FASTPFOR` — confirming the gate touches only LineString/MultiLineString
interiors, not polygon rings or points.

`--segmented` add-on, `--layer transportation`:

```
--- by layer ---
transportation  354 buffers  3.066.704 baseline bytes
  winner: LINE_CONTINUATION_SEG_FASTPFOR   2.858.848   -6,8%
```

This beats `PART_START_SEG_FASTPFOR` (previously the best transportation/LINESTRING
encoding at -6,5%, see [segment-pair.md](segment-pair.md)) and lifts the per-buffer
oracle ceiling on this layer from -0,4% (core only) to **-7,0%** (segmented).

`--reorder feature-rev` add-on, `--layer transportation`:
`LINE_CONTINUATION_FASTPFOR_REORDER_REV` -5,2% transportation — composes cleanly with
reordering, as `SegmentPair` does.

## Corpus characterization behind the gates

The geometry-type gate: continuation vs. plain delta on run-interior vertices (part
starts excluded — always plain delta regardless of predictor; length gate not yet
applied here), by geometry type, all layers, all zooms:

```
gtype          segs    delta     cont     gain
POLYGON     2.985.531   9,712   10,789  +11,09%
LINESTRING  1.064.818  11,773   11,352   -3,58%

global delta             10,2539   ref
global continuation      10,9368   +6,66%
gated by geometry type   10,1431   -1,08%
```

Continuation is a straight-line predictor: it wins wherever consecutive edges tend to
point the same direction (roads, waterways) and loses wherever they don't (building
outlines, which are mostly right angles). `geometryTypes` is the gate that separates
those two populations, and it is already in `BufferContext` — MLT ships it in the tile
regardless of which encoding reads it.

The length gate: within `transportation`, gain by run length (vertex count of the
LineString part, `transportation` only, all zooms):

```
part len       segs   delta    cont    gain
2             45.875  11,482  11,482  +0,00%
3             58.708  11,295  11,423  +1,13%
4-5           98.057  11,451  11,277  -1,52%
6-10         159.922  11,569  11,087  -4,17%
11-20        132.534  11,772  10,975  -6,77%
21-50         68.814  11,694  10,727  -8,27%
51+            8.255   9,797   9,116  -6,95%
```

A 2-vertex part has no interior vertex to predict at all (the gain is a no-op, not a
measurement of the predictor). A 3-vertex part's one candidate interior vertex still
regresses — the baseline it would improve on is already too short for the two prior
edges to constrain the next one usefully. Gain grows monotonically from 4-5 vertices
through the 21-50 bucket, then eases slightly for 51+ (mostly simplified overview-zoom
lines long enough to contain real curvature). `MIN_LINE_RUN_LENGTH = 4` in
`LineContinuation.java` draws the line at the first bucket that measured a net win.

Within `transportation`, per road `class` (length gate not applied here either; MVT tag,
not part of the extraction — read directly from `data/MVT/munich-plantiler.mbtiles` via
`data-analysis/transportation_stats.py`):

```
class        segs      delta    cont    gain
path       379.516    10,981  10,909  -0,66%
minor      151.863    12,217  11,808  -3,35%
service    144.105    11,125  11,211  +0,78%
secondary   44.752    11,247  10,592  -5,82%
track       32.748    13,394  12,547  -6,32%
rail        29.558    13,397  12,370  -7,66%
motorway    27.427    11,028   9,926  -9,99%
tertiary    22.333    12,066  11,298  -6,37%
primary     16.875    10,839  10,171  -6,16%
trunk       12.530    10,967  10,000  -8,81%
```

Continuation wins on 9 of 10 classes; `path`+`service`+`minor` are 87% of transportation
vertex mass. See [segment-pair.md](segment-pair.md) for the same layer's part-length
distribution (70,4% of parts are exactly 2 vertices).
