# Quad corner (`QUAD_CORNER`, formerly `SEGMENT_QUAD_CORNER`)

## encoding target:
building (Polygon), also every other polygon layer (landcover, landuse, water, park, aeroway); lines and points pass through as plain delta

## Idea of the encoding
Most buildings are a single ring of exactly four vertices, and many of those quads are
parallelograms (rotated rectangles). Once three corners are known, the fourth is almost
determined: it is where the parallelogram closes. So instead of storing the full edge from the
third to the fourth corner, store only how far the real fourth corner is from the "closing"
corner. For an exact parallelogram that difference is zero, for the rest it is small.

The same encoding keeps three kinds of residual apart (run starts, ordinary vertices, predicted
corners) so a compressor sees each population on its own.

## How the encoding works
Mechanism: `encoder/prediction/mechanism/SegmentQuadCorner.java` (a `ClassifiedPredictor`).

1. Runs (rings, line parts, points) are derived from the topology streams (`VertexRuns`).
2. Every vertex gets one of three classes:
   - `START`: first vertex of a run. Residual = `p[i] - p[i-1]` (delta to the previous vertex, which
     is the last vertex of the previous run, so this is the "jump").
   - `CORNER`: the 4th vertex of a run of exactly 4 vertices. Prediction
     `pred = p[i-3] + p[i-1] - p[i-2]` (= `p0 + p2 - p1`), residual = `p[i] - pred`.
   - `INTERIOR`: everything else. Residual = `p[i] - p[i-1]`.
3. `x` and `y` are predicted independently; `prevX/prevY` advance through every real vertex, so there are
   no reset seeds.
4. Residuals are zigzag-mapped and, under `CLASS` segmentation, written as one stream per class in the
   order `START, INTERIOR, CORNER`. Nothing is signalled: the class of every vertex follows from the
   topology.

Vertex buffer of one quad ring `p0 p1 p2 p3` after the jump from the previous run's last vertex `q`:

```
original:   p0          p1          p2          p3
residual:   p0-q        p1-p0       p2-p1       p3-(p0+p2-p1)
stream:     START       INTERIOR    INTERIOR    CORNER   (≈0 for parallelograms)
```

## How the decoder works
1. Derive the runs and the class array from the topology streams (identical to the encoder).
2. From the stream lengths implied by the class counts, split the payload into the `START`, `INTERIOR` and
   `CORNER` streams and read them with one cursor each.
3. Walk the vertices in order: take the next residual from the stream of the vertex's class, unzigzag it,
   and add it to the prediction — `p[i-1]` for `START`/`INTERIOR`, `p[i-3] + p[i-1] - p[i-2]` for `CORNER`,
   computed from already-decoded vertices.

## results
- **Retired corpus `munich.mbtiles` (FastPFOR):** `SEGMENT_QUAD_CORNER_FASTPFOR` -6,1% corpus, -12,2%
  `building`, -8,0% POLYGON vs `DELTA_ZIGZAG_FASTPFOR`; standalone (interleaved) `QUAD_CORNER` only -1,6%
  corpus / -7,1% building. Interleaved, the corner's -9,6% per-value bit saving was -0,0% in bytes: the
  win needs the separate corner stream.
- Corpus properties (retired corpus): 99,5% of buildings have one ring, 75,7% of rings have 4 vertices,
  40,7% of those are exact parallelograms, 4,2% axis-aligned.
- **Current corpus `munich-plantiler`, OpenZL pipeline (per tile, vs `DELTA_ZIGZAG_VARINT` gzip):** S2
  `QUAD_CORNER` -4,79% gzip (vs `DELTA` -3,97%), `building` -5,02% vs -2,54%; with class streams -6,07%
  gzip. On the S8 stack the best single composition is plain `QUAD_CORNER`: -6,78% gzip / -11,96% Brotli /
  -8,20% zstd.
- Basis of the frame and shared-reference predictors that replaced it as layer default
  (ring frame prior, shared ring vertices).
