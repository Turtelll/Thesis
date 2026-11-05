# Quad corner (`QUAD_CORNER`, formerly `SEGMENT_QUAD_CORNER`)

> **Verdict: Kept.** -4,79% gzip on the tileset (`building` -5,02%) as the only change to the
> predictor; the base every polygon predictor of the pipeline is built on.

## Encoding target
building (Polygon), also every other polygon layer (landcover, landuse, water, park, aeroway); lines and
points pass through as plain delta.

## Idea
Most buildings are a single ring of exactly four vertices, and many of those quads are parallelograms
(rotated rectangles). Once three corners are known, the fourth is almost determined: it is where the
parallelogram closes. So instead of storing the full edge from the third to the fourth corner, store only
how far the real fourth corner is from that "closing" point. For an exact parallelogram the difference is
zero, for the rest it is small.

The encoding also keeps three kinds of residual apart (run starts, ordinary vertices, predicted corners), so
a compressor sees each population on its own.

## Intention
Plain delta treats every vertex alike, so the fourth corner of a rectangle costs as much as any other edge,
although the decoder already holds everything needed to guess it. Buildings are the largest polygon layer and
quads are their dominant shape (on the retired corpus 75,7% of building rings had 4 vertices, 40,7% of those
exact parallelograms), so a corner rule that is exactly right a large part of the time should turn many of
those residuals into `(0, 0)`, the cheapest symbol a compressor can get. Nothing is signalled: whether a
vertex is a corner follows from the ring length the decoder already knows.

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

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`, pipeline step S2, every other stage
fixed): **tileset -4,79%** (plain `DELTA` -3,97%), **`building` -5,02%** (plain `DELTA` -2,54%).

**Decisive case for inclusion:** the corner rule doubles the `building` gain of plain delta at zero signalling
cost, and the win grows once the corner residuals get their own stream (-6,07% tileset with class streams).
Every later polygon predictor ([ring frame prior](quad-corner-frame.md),
[shared ring vertices](shared-ring-vertices.md)) is quad corner plus one more idea, and on the full stack (S8)
plain `QUAD_CORNER` is still the best single composition (-6,78% gzip).

Supporting figures:

- Pipeline, S8 stack: `QUAD_CORNER` -6,78% gzip / -11,96% Brotli / -8,20% zstd.
- *Not under the headline metric* — retired corpus `munich.mbtiles`, FastPFOR bytes vs
  `DELTA_ZIGZAG_FASTPFOR`: `SEGMENT_QUAD_CORNER_FASTPFOR` -6,1% corpus, -12,2% `building`, -8,0% POLYGON;
  interleaved `QUAD_CORNER` only -1,6% corpus / -7,1% `building`. Interleaved, the corner's -9,6% per-value
  bit saving was -0,0% in bytes: under FastPFOR the win needed the separate corner stream.
- Corpus properties (retired corpus): 99,5% of buildings have one ring, 75,7% of rings have 4 vertices,
  40,7% of those are exact parallelograms, 4,2% axis-aligned.

Source: `research-java/docs/results/openzl-pipeline.md`, `segment-quad-corner.md`.
