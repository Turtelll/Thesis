# Dropping buffer points

> **Verdict: Out of scope (lossy, a tiling policy).** -24,46% of total MLT bytes, but it removes features from
> the tile; not a vertex-buffer encoding, so not measured under the headline metric.

## Encoding target
poi, place, water_name, park, mountain_peak, aerodrome_label, housenumber (Point).

## Idea
Tiles carry a buffer: points up to 1024 or 4096 units outside the tile extent, duplicated from the neighbour
tile. MapLibre GL JS skips every point anchor outside the extent (the neighbour tile draws it), so those points
are decoded and thrown away. Leave them out of the tile.

## Intention
Measure how much of the tile is data a client never uses. If it is large, it puts the vertex-buffer savings of
this thesis in proportion and points the tiler at a cheaper lever.

## How the encoding works
Probe `BufferPointCli`: re-encode every tile with `MltConverter` twice, as is and with buffer points removed:
- a `Point` with x or y outside `[0, extent)` is dropped; a `MultiPoint` loses its outside members (dropped if none
  remain);
- lines and polygons untouched; empty layers dropped.

## How the decoder works
No change; the points are simply not in the tile.

## Results
**Headline:** *not measured under the headline metric* (whole-tile MLT bytes, `munich-plantiler`):
**-24,46% total MLT bytes** (27.640.629 → 20.878.453 B).

**Decisive case — why it is out of scope:** it is not lossless against the MVT extraction: whole features
disappear, and every encoding in this thesis must round-trip. It is a tiling decision (Planetiler
`--max-point-buffer=0`), not an encoding, and it breaks raster rendering (MapLibre Native
`MapMode::Tile`/`Static`). Most of the saving is property data, not
vertices. No encoder built.

Supporting figures (*whole-tile MLT bytes*):

- 10,74% of vertices are buffer vertices (`poi` 56,17%, `place` 89,27%).
- Saving mostly properties (`poi` -54,70%, `place` -81,53%, `water_name` -74,55%); 174.249 points = 3,3% of
  vertices.

Source: `research-java/docs/results/buffer-points.md`.
