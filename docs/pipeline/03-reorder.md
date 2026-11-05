# 3. Reorder (S1)

## The idea

Every later stage codes a vertex relative to the vertices before it, so the first vertex of each ring or
line part pays for the jump from wherever the previous run ended. In the order the tile was generated,
consecutive features are often far apart, and those jumps are the largest values in the buffer.

MLT does not promise to keep feature order, and a renderer does not depend on it. The encoder can
therefore emit the features in a better order: features that are close in space go next to each other,
and each ring starts at the vertex nearest to where the previous run ended. Nothing about the order is
written, because the tile carries the reordered topology streams and property columns, and the decoder
simply decodes the features in the order it reads them.

## The encodings

Three options remain in use (`encoder/reorder/Reorder.java`, `GeometryReorder.java`).

`NONE` keeps the stored order.

`HILBERT_CENTROID_ROT_MP` combines three changes:

1. Features are sorted by the Hilbert-curve index of their bounding-box centre (a 2^16 grid per axis).
   The Hilbert curve visits the tile in small, connected steps, so neighbours on the curve are
   neighbours on the map.
2. `ROT`: every polygon ring is rotated (its winding kept) to start at the vertex nearest the previous
   emitted vertex.
3. `MP`: in a point layer, the points inside each MultiPoint are sorted along the same curve. A
   MultiPoint of scattered points otherwise pays a tile-wide jump between its own members.

`CHAIN` is a greedy chain: starting from the current position, take the unused feature whose first
vertex is nearest, then continue from that feature's last vertex. It follows connected lines (a road
continues where the previous one ended), which suits line layers.

Neither option reverses a line or splits a multi-part feature, and a buffer whose features cannot be
located from the topology (points mixed with lines or polygons) is left unchanged.

```
stored order    F1 F2 F3 F4    mean jump between runs 903,7
Hilbert order   F3 F1 F4 F2    mean jump between runs 576,7
```

## Decoding

There is nothing to undo. The decoder reads the reordered topology, decodes the vertices in stored order
and emits them as they are. The round-trip checks compare geometry independent of feature order and ring
start.

## The cost on the property side

Feature order is not free in a whole tile. The property columns (feature ids, class names, road types)
are stored in the same feature order and compress well in the original order, where ids ascend and
equal values sit in runs. A reorder permutes them too.

- Measured on whole MLT tiles in Java, the Hilbert order grows the property columns by 13,39% and the
  whole tile by 2,48% under gzip, more than it saves in the geometry.
- In the Rust MLT v2 encoder, where the search sees the complete tile, the reorder is worth only 0,42% of
  the tile once the property columns are counted, and the tile oracle chooses a reorder on 28% of the
  layers.
- `poi` is the clearest case: its fixed per-layer default with the Hilbert order is 12,4% larger than
  stock MLT v2, while the tile oracle, which mostly keeps the original order, ends 0,75% above stock.

This is why the reorder is part of the composition and chosen per layer, not applied everywhere.

## What it is worth

On the geometry buffers alone (split varint, plain delta, gzip, against plain delta + varint):

| order | gzip |
|---|---|
| stored order | -0,81% |
| Hilbert of the bounding-box centre | -3,45% |
| plus ring rotation | -3,97% |
| plus sorted MultiPoints | -4,05% |

The Morton curve loses to Hilbert; `CHAIN` wins on line layers and loses on points. In the build-up of
the pipeline the reorder is the largest single step on the geometry buffers, and the property columns
take most of it back.

Further reading: [Hilbert reorder](../encoding/good/hilbert-reorder.md),
[feature chaining](../encoding/good/feature-chaining-reorder.md),
[S1 results](https://github.com/Turtelll/Thesis/blob/main/research-java/docs/results/openzl-pipeline.md#s1-reorder).
