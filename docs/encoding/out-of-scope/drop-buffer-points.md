# Dropping buffer points

## encoding target:
poi, place, water_name, park, mountain_peak, aerodrome_label, housenumber (Point)

## Idea of the encoding
Tiles carry a buffer: points up to 1024 or 4096 units outside the tile extent, duplicated from the neighbour tile.
MapLibre GL JS skips every point anchor outside the extent (the neighbour tile draws it), so those points are
decoded and thrown away. Leave them out of the tile.

## How the encoding works
Probe `BufferPointCli`: re-encode every tile with `MltConverter` twice, as is and with buffer points removed:
- a `Point` with x or y outside `[0, extent)` is dropped; a `MultiPoint` loses its outside members (dropped if none
  remain);
- lines and polygons untouched; empty layers dropped.

## How the decoder works
No change; the points are simply not in the tile.

## results
Current corpus `munich-plantiler`:
- 10,74% of vertices are buffer vertices (`poi` 56,17%, `place` 89,27%).
- Dropping buffer points: **-24,46% total MLT bytes** (27.640.629 → 20.878.453 B), mostly properties (`poi` -54,70%,
  `place` -81,53%, `water_name` -74,55%); 174.249 points = 3,3% of vertices.
- Not lossless against the MVT extraction (whole features disappear), so not a `VertexBuffer` encoding; a tiling
  policy (Planetiler `--max-point-buffer=0`). Breaks raster rendering (MapLibre Native `MapMode::Tile`/`Static`).
  No encoder built.
