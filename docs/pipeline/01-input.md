# 1. Input: VertexBuffer and topology streams

## What it does
Turns an `.mbtiles` tileset into the unit every later step works on: one `VertexBuffer` per (tile, layer),
plus the topology streams MLT writes in front of it. The extraction is a verbatim copy of what
`maplibre-tile-spec`'s `GeometryEncoder` produces, so the pipeline encodes exactly the buffer MLT would
([mlt-fidelity.md](https://github.com/Turtelll/Thesis/blob/main/research-java/docs/mlt-fidelity.md)).

## How it works
`research-java/.../extraction/` (`MbtilesExtraction`, `VertexBufferExtractor`, `ExtractionWriter`):

- **Vertex buffer** (`<z>_<x>_<y>_<layer>.bin`): interleaved little-endian `int32` `[x0, y0, x1, y1, ...]`,
  absolute tile coordinates, not delta coded. Polygon rings drop their closing vertex (it repeats the first).
- **Topology** (`.json` sidecar): `geometryTypes`, `numGeometries`, `numParts`, `numRings`, `vertexCount`,
  `tileExtent`. A `manifest.json` indexes the run.
- In the encoder both arrive as one `BufferContext` (`buffer/Encoding.java`): the vertices and the four
  topology streams.

## Runs: what every later step derives from the topology
`buffer/VertexRuns.java` cuts the buffer into **runs** (rings, line parts, points) from the topology alone:

- a column with any (Multi)Polygon: `numRings` holds vertices per ring, `numParts` rings per polygon;
- a column without polygons: `numParts` holds vertices per line string;
- points contribute to neither stream, so they have no run boundaries.

If the derived runs do not cover the buffer exactly, the result is one run over the whole buffer, and every
structural mechanism degrades to plain delta instead of guessing. `VertexRunIndex` is the per-vertex view
(which run, position in it).

Since the decoder reads the topology streams before the vertex buffer, the runs, the class of every vertex
(step 4) and the value count of every stream (step 6) are known on both sides without being written.

## Command

```
./gradlew research --args="extract --mbtiles ../data/MVT/<tileset>.mbtiles --out ../data/extracted"
```

Baseline corpus: `data/MVT/munich-plantiler.mbtiles` → `data/extractedBaseline` (358 tiles, 4.368 buffers,
16 layers).
