# data-analysis

MVT tileset analysis scripts. Python 3.13, stdlib + `numpy`/`matplotlib`
(no requirements.txt — see installed interpreter).

Input tilesets: `data/MVT/<tileset>.mbtiles` (gitignored). Output caches:
`data-analysis/out/*.json` (gitignored).

## Byte composition — `mvt_stats.py`

```
python mvt_stats.py <tileset> [--force]
```

Geometry bytes per layer and per geometry type vs. total tile size.
Caches to `out/<tileset>.json`; `--force` rescans. Large tilesets take
40-60 min, so don't rerun without `--force`.

Plot: `python plot/plot_composition.py` reads all `out/*.json` and writes
`out/fig_mvt_layer_geom_share.pdf` / `out/fig_mvt_gtype_geom_share.pdf`.

## Vertex distribution — `vertex_stats.py`

```
python vertex_stats.py <tileset> [--force]
```

Vertices-per-feature stats per layer (features, total vertices, mean,
min/q1/median/q3/p90/p99/max), matching MLT's VertexBuffer vertex count
exactly (verified against `research-java`'s `ExtractCli` output — see
`vertex_stats.py`'s docstring for the counting rule). Caches to
`out/<tileset>.vertices.json`; `--force` rescans.

Plot: `python plot/plot_vertex_dist.py <tileset>` reads
`out/<tileset>.vertices.json` and writes `out/fig_vertex_dist_<tileset>.pdf`
— one log-scale box plot per layer, p99 marked separately since Tukey
whiskers alone hide the tail.

## Tilesets

`munich-de-rs`, `munich-maptiler`, `munich-overture`, `NY-maptiler`,
`germany-rs`. Overture source and tiling: `overture/overture_fetch.py` +
`overture/overture_tile.sh`.
