# 1. The input: vertex buffer and topology

## The idea

MLT stores the geometry of a layer in two parts. The topology streams say how the geometry is
structured: the type of every feature, how many polygons, parts and rings it has, and how many vertices
each ring or line part holds. The vertex buffer holds the coordinates themselves, one `x, y` pair per
vertex, in feature order.

The pipeline only re-encodes the vertex buffer. The topology streams stay exactly as stock MLT writes
them, and the decoder reads them before it reads the vertices. That ordering is what the whole pipeline
leans on: anything that can be computed from the topology is known to both sides and never has to be
written.

## What the encoder receives

`research-java/.../extraction/` cuts every `.mbtiles` tile into one buffer per layer, a verbatim copy of
what MLT's own `GeometryEncoder` produces, so the pipeline encodes exactly the buffer MLT would
([mlt-fidelity.md](https://github.com/Turtelll/Thesis/blob/main/research-java/docs/mlt-fidelity.md)).

- The vertex buffer: absolute tile coordinates as `[x0, y0, x1, y1, ...]`. Polygon rings drop their
  closing vertex, since it repeats the first.
- The topology: `geometryTypes`, `numGeometries`, `numParts`, `numRings`.

In the encoder both arrive together as one `BufferContext` (`buffer/Encoding.java`).

## Runs: the structure every stage derives

From the topology alone, `buffer/VertexRuns.java` cuts the buffer into runs: one run per polygon ring
or line part, while points have no runs at all.

```
topology    2 polygons, numParts = [1, 2], numRings = [4, 5, 4]
vertices    a a a a | b b b b b | c c c c
runs        ring 1    ring 2      ring 3 (hole of the second polygon)
```

From the runs, every later stage computes what it needs on both sides of the wire:

- the class of every vertex (first vertex of a ring, fourth corner, continued road vertex, see
  [prediction](04-prediction.md)),
- which stream each residual goes to and how many values every stream holds (see
  [segmentation](07-segmentation.md)),
- where a ring starts and ends, which the frames and shared references need.

If the topology does not cover the buffer exactly, the buffer is treated as one run, and every
structural predictor quietly falls back to plain delta instead of guessing.

## Corpus

```
./gradlew research --args="extract --mbtiles ../data/MVT/<tileset>.mbtiles --out ../data/extracted"
```

All figures in these pages come from `munich-plantiler.mbtiles` (Munich, Planetiler 0.10.2): 358 tiles,
16 layers, 4.368 (tile, layer) buffers, extracted to `data/extractedBaseline`.
