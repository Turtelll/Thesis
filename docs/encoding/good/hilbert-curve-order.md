# Hilbert curve order with ring rotation

**Code names:** `HILBERT_CENTROID_ROT_MP`, pipeline S1

> **Verdict: Kept, per layer.** -4,05% gzip on the tileset against -0,81% without reorder (geometry only);
> not in `DEFAULT`, because the reordered property columns cost more than the geometry saves on the whole tile.

## Encoding target
all layers with derivable runs; largest effect on poi (Point), transportation (LineString), building,
landcover (Polygon).

## Idea
Sort the features of a buffer along a space-filling curve, so consecutive features are spatially close and the
jump from one run to the next is short. Additionally rotate each polygon ring to start at the vertex nearest
to where the previous run ended, and sort the members of each MultiPoint along the curve. Feature order and
ring start carry no information, so nothing is transmitted.

## Intention
The jump from the end of one feature to the start of the next is the largest residual in almost every buffer
(mean 903,7 units without reorder), and the tiler's feature order is not chosen to keep it small. A
space-filling curve keeps neighbours in 2D close in the 1D order, which shortens every jump at once without
any per-feature decision. The MLT spec allows features to be sorted, so the decoder needs no change. The risk is the other columns of the tile: properties are stored in the same order, and their runs and ascending
ids break when features are sorted by position.

## How the encoding works
`encoder/reorder/Reorder.java` (pre-pass):
- Key per feature: Hilbert index of the bounding-box centre (`_CENTROID`) or of the first vertex (`_START`).
  Morton as alternative curve.
- `_ROT`: each polygon ring cyclically rotated (winding kept) to start at the vertex nearest the previous run's
  end.
- `_MP`: the members of every MultiPoint sorted along the curve (verification compares them as a set); also lets
  single-feature buffers through the reorder.
- The topology streams and property columns are permuted with the features. Not in the header: the tile carries
  the reordered topology.

## How the decoder works
No change: it reads the reordered topology and decodes the vertices in stored order (the decoder is told which
reorder to assume via `Composition.reorder`, but nothing is undone).

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`, pipeline step S1, split varint,
`DELTA`, geometry only): **tileset -4,05%** against **-0,81%** without reorder; **`poi` -19,60%**.

**Decisive case for inclusion — and against making it the default:** it is the best reorder on the geometry
(Hilbert beats Morton, ring rotation adds 0,5 points), and the tile oracle picks it on 45,9% of buffers, with
the highest drop cost of any option (+2,204%). But it also reorders the property columns: on the whole MLT
tile it costs +2,48% gzip (+413.000 B, +2,4% of the tile), so `DEFAULT` has no reorder and only the fitted
[per-layer compositions](per-layer-composition.md) use it, layer by layer, where the geometry gain outweighs
the property cost.

Supporting figures (same metric, geometry only):

| row | gzip | Brotli 9 | zstd 19 | mean jump |
|---|---|---|---|---|
| `NONE` | -0,81% | -2,53% | -1,99% | 903,7 |
| `HILBERT_START` | -3,53% | -5,18% | -4,79% | 594,6 |
| `HILBERT_CENTROID` | -3,45% | -5,23% | -4,72% | 592,0 |
| `MORTON_START` / `_CENTROID` | -3,23% / -3,15% | -4,87% / -4,88% | -4,49% / -4,42% | 621,0 / 616,5 |
| `HILBERT_CENTROID_ROT` | -3,97% | -5,71% | -5,22% | 575,6 |
| `HILBERT_START_VC` (vertex-class sort) | -3,54% | -5,15% | -4,81% | 594,3 |
| **`HILBERT_CENTROID_ROT_MP`** | **-4,05%** | **-5,85%** | **-5,30%** | 576,7 |

- Ring rotation +0,5 points; `_MP` +0,08 points.
- Property cost (whole-tile MLT, a different metric): +2,48% gzip, props-only +13,39%; feature ids stop
  ascending (96% → 50% on `transportation`) and category runs break. S8 `GEOMETRY_BEST` -6,58% vs `DEFAULT`
  -3,63% gzip (geometry only).
- Reorder of every fitted `forLayer` composition except the `CHAIN` layers (`transportation`,
  `transportation_name`, `waterway`, `water`, `aeroway`, see [feature chaining](feature-chaining.md)).

Source: `research-java/docs/results/openzl-pipeline.md`, `reordering.md`, `tile-oracle.md`.
