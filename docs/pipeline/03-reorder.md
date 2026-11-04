# 3. S1 Reorder

## What it does
Changes the order of the features in a buffer (and where each ring starts) so that the jump from the end of
one run to the start of the next is short. Feature order carries no meaning in MLT, so nothing is written:
the tile carries the reordered topology streams and property columns, and the decoder emits the vertices in
the order it reads them.

## Options
`encoder/reorder/Reorder.java`, `GeometryReorder.java`. In use:

| option | how |
|---|---|
| `NONE` | identity (`DEFAULT`) |
| `HILBERT_CENTROID_ROT_MP` | features sorted by the Hilbert index of their bounding-box centre; every polygon ring rotated to start at the vertex nearest the previous emitted vertex; members of every MultiPoint sorted along the curve |
| `CHAIN` | greedy chain: from the cursor, take the unused feature whose entry endpoint is nearest, move the cursor to its exit endpoint (`GeometryReorder.FEATURE`) |

## How it works (Hilbert)
1. Locate the features from the topology. A buffer whose features cannot be located (points mixed with
   lines or polygons, topology that does not line up) is returned unchanged.
2. Key per feature: Hilbert index (grid of 2^16 per axis) of the bounding-box centre. Sort by key.
3. `_ROT`: rotate each polygon ring cyclically, winding kept, to start nearest where the previous run ended.
4. `_MP`: in a Point/MultiPoint column, sort the members of each MultiPoint along the curve.
5. Lines are never reversed; the parts of a multi-part feature stay contiguous and in order.
6. Permute the topology streams (and, in the tile, the property columns) with the features.

```
stored order:   F1 F2 F3 F4      mean jump 903,7
Hilbert order:  F3 F1 F4 F2      mean jump 576,7
```

## How the decoder works
No inverse. It reads the reordered topology and decodes in stored order. Round-trip checks compare
geometry order-independently (`GeometryEquivalence`).

## Why
S1 on the geometry buffers (split varint, `DELTA`, vs `DELTA_ZIGZAG_VARINT`): `NONE` -0,81%,
`HILBERT_CENTROID` -3,45%, `HILBERT_CENTROID_ROT` -3,97%, `HILBERT_CENTROID_ROT_MP` -4,05% gzip; Morton
loses to Hilbert. `CHAIN` wins on lines and loses on points.

**Cost on the property side.** The reorder breaks ascending feature ids and runs of equal category values.
Whole-tile MLT: +2,48% gzip, property columns +13,39% (Hilbert). Net over all layers it loses (+413.000 B),
which is why `DEFAULT` has no reorder. `forLayer` reorders every fitted layer; `CHAIN` is used where its
property columns are no larger than Hilbert's net of the geometry gain.

More: [../encoding/good/hilbert-reorder.md](../encoding/good/hilbert-reorder.md),
[../encoding/good/feature-chaining-reorder.md](../encoding/good/feature-chaining-reorder.md),
[../encoding/bad/class-sorted-order.md](../encoding/bad/class-sorted-order.md),
[openzl-pipeline.md#s1-reorder](https://github.com/Turtelll/Thesis/blob/main/research-java/docs/results/openzl-pipeline.md#s1-reorder).
