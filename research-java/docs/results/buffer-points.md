# Buffer points: MLT bytes spent on points no renderer draws

**Status:** measured, no encoder built — outside `VertexBuffer` scope (not lossless vs MVT)
**Effect:** -24,46% total MLT tile bytes when Point features outside the tile extent are
dropped; almost all of it properties, not geometry
**Corpus:** current baseline (`munich-plantiler.mbtiles`, 358 tiles), `./gradlew research --args="buffer-point"`

## The question

Tiles carry a buffer: line/polygon geometry up to 64 (some layers 128) units past the tile
extent `[0, 4096)`, points up to 1024 (`poi`, `aerodrome_label`, `mountain_peak`) or 4096
(`place`, `water_name`, `park` labels) units, duplicated from the neighbour tile. How much of the baseline is such
cross-tile duplication — counting the same feature stored in two tiles, not vertices shared
by different features — and can any of it be dropped?

## Vertex share (one-off script, not in the repo)

Every vertex is either inside its own tile's extent or in the buffer, so "outside
`[0, extent)`" counts each cross-tile copy once. Over `data/extractedBaseline`:
**575.396 of 5.358.929 vertices (10,74%) are buffer vertices** (z14 11,89%; polygon/line
layers 7-9%; `poi` 56,17%, `place` 89,27%, `water_name` 87,66%). Where the owning
neighbour tile is also in the corpus, 79% of them appear there at the same global
coordinate (same zoom, same layer); ~96% of the rest sit exactly on the buffer edge
(64/128 units out) — clip vertices that exist only because of the buffer.

Line and polygon buffer vertices are needed: the renderer strokes and fills across the
seam, and clipping at the exact edge would add edge vertices anyway.

## Points are never drawn (MapLibre GL JS 4.7.1)

Point geometries have no such use. GL JS skips any point anchor outside the extent at
bucket build time; the neighbour tile draws it:

- `src/symbol/symbol_layout.ts:334` — symbol layers ("We filter out symbols outside our
  tile boundaries (which may be included in vector tile buffers) to prevent
  double-drawing symbols.")
- `src/data/bucket/circle_bucket.ts:166` — circle layers ("Do not include points that are
  outside the tile boundaries."); `HeatmapBucket extends CircleBucket`.

So buffer points are decoded and discarded — pure transfer and decode cost.

## What it measures

`BufferPointCli` re-encodes every tile with `MltConverter` (as `TileSizeCli`: FastPFOR on,
otherwise `ConversionConfig` defaults) twice — as-is, and with buffer points removed:

- a `Point` feature with `x` or `y` outside `[0, extent)` is dropped;
- a `MultiPoint` loses its outside members; the feature is dropped only if none remain;
- LineString/Polygon features are never touched (line labels in `transportation_name` /
  `water_name` stay);
- a layer left empty is dropped.

Per-layer bytes encode each layer as a single-layer tile.

## Measured

```
zoom                  tiles    MLT bytes     filtered    saved  featDrop   ptsDrop
0-4                       5       36.111       36.111    0,00%         0         0
5                         1       17.948       13.795   23,14%        19        19
6                         1       32.322       27.205   15,83%        24        24
7                         1      117.937      101.493   13,94%        95        95
8                         1      163.792      138.976   15,15%       173       173
9                         1      212.092      181.713   14,32%       313       313
10                        4      481.603      342.900   28,80%     1.710     1.710
11                        9      685.846      424.228   38,15%     3.323     3.323
12                       20      985.222      799.330   18,87%     2.004     2.004
13                       63    2.620.300    2.445.893    6,66%     1.538     1.538
14                      252   22.287.456   16.366.809   26,56%   136.717   165.050
all                     358   27.640.629   20.878.453   24,46%   145.916   174.249

layer                   MLT bytes     filtered    saved
poi                     9.725.338    4.405.958   54,70%
place                   1.195.776      220.821   81,53%
housenumber             1.331.708    1.191.230   10,55%
park                      575.841      450.746   21,72%
water_name                207.157       52.712   74,55%
mountain_peak              74.822       43.853   41,39%
aerodrome_label            18.639        8.753   53,04%
transportation_name     2.316.270    2.309.302    0,30%
(all other layers: no Point features, 0,00%)
```

174.249 dropped points are 3,3% of corpus vertices but take 24,46% of the bytes with them:
label features carry heavy property sets (`name:*` translations). `poi` alone is 35% of
corpus bytes and loses more than half.

## Verdict

Not a `VertexBuffer` encoding: whole features (and their properties) disappear, so
`GeometryEquivalence` against the MVT extraction fails by construction. It is a
tiling-policy lever Planetiler already has: `--max-point-buffer=0` (default ∞, present in
0.10.2). It trims points in `FeatureGroup` after the profile's `postProcess`, so label-grid
ranks stay consistent across tiles. A per-layer `setBufferPixels(0)` would not: the label
grid needs buffer ≥ grid size (`FeatureCollector` assert). Edge case: at 0 the filter keeps
x/y = 4096 (`x > max`), which GL JS drops (`>= EXTENT`) — negligible.

Buffer points are needed for raster rendering: MapLibre Native in `MapMode::Tile`
(tileserver-gl) keeps buffer symbols to draw labels overlapping into the tile and to
approximate cross-tile collisions (`symbol_layout.cpp:849`); `MapMode::Static` keeps buffer
circles (`circle_layout.hpp:126`). In `MapMode::Continuous` Native drops both, like GL JS.

Not verified: `circle_bucket.ts:125` still inserts buffer points into the `featureIndex`
(`queryRenderedFeatures`), `querySourceFeatures` returns them; other MLT/MVT clients were
not checked.
