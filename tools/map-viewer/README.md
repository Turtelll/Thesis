# MVT Map Viewer

A small TypeScript + [MapLibre GL JS](https://maplibre.org/maplibre-gl-js/docs/)
app for visually inspecting the thesis source data
(the `data/MVT/*.mbtiles` tilesets listed in `server/server.ts`, default
`munich-plantiler`): a Node/Express server
streams tiles straight out of the selected mbtiles file (converting MBTiles'
TMS row order to XYZ on the fly), and the browser client renders `landuse`,
`landcover`, `water`, `building` and `transportation` as an interactive map with one on/off
checkbox per layer, plus a dropdown to switch between datasets.

No tile pre-processing or Java build is needed — this reads the same mbtiles
file the `research-java` pipeline extracts from.

## Setup

```
cd tools/map-viewer
npm install
```

## Run

```
npm start
```

This builds the client bundle (esbuild) and server (tsc), then starts the
server. Open the printed URL (default `http://localhost:5173`) in a browser.

For iterative development, rebuild the client after editing `src/main.ts`:

```
npm run build:client
```

(the server auto-serves whatever is currently in `public/`; restart
`npm start` after editing `server/server.ts`).

## Layout

- `server/server.ts` — Express server: `/tiles/:z/:x/:y.pbf?dataset=<id>` reads
  directly from the selected mbtiles (SQLite) file and serves the raw tile
  bytes; `/metadata.json?dataset=<id>` exposes the mbtiles'
  `center`/`bounds`/`minzoom`/`maxzoom` so the client can set the initial view;
  static files are served from `public/`. `dataset` is one of the ids in
  `DATASETS` (default `munich-plantiler`).
  It also serves the layer composition statistics (see below):
  `GET /composition.json?dataset=<id>&zoom=all|<z>` returns
  `{status: "ready", zooms, summary}`, `{status: "scanning", done, total}` or
  `{status: "error", message}`, and `POST /composition/rescan?dataset=<id>`
  discards the cache and scans again.
- `server/composition.ts` — TypeScript port of the scanners in
  `data-analysis/mvt_stats.py` (geometry bytes per layer / geometry type vs.
  decompressed tile size) and `data-analysis/vertex_stats.py` (vertices per
  feature, counted as MLT's VertexBuffer does), aggregated per zoom, layer and
  geometry type. The counting rules are the Python ones, so the numbers match
  the `data-analysis/out/*.json` caches (checked for `chicago-25km` and
  `tokyo-25km`); the one difference is that a tile that fails to decode
  contributes nothing instead of its partial counts.
- `server/compositionWorker.ts` — runs one scan in a `worker_threads` worker
  (better-sqlite3 is synchronous, so scanning on the main thread would block tile
  serving), reports progress, and writes `cache/<dataset>.composition.json`
  (gitignored). The cache is reused as long as the mbtiles file's size and mtime
  are unchanged. A scan starts the first time the drawer is opened for a
  dataset; the small tilesets take seconds, `bayern` about a minute and
  `germany-rs` (3.3 GB) a long time.
- `src/composition.ts` — the "Layer composition" drawer (button in the layer
  panel): summary cards (tiles, features, vertices, vertices per feature,
  geometry share of tile bytes, dominant geometry type), stacked bars of the
  geometry type share by bytes / vertices / features, bars of each layer's
  share of geometry bytes, and a sortable per-layer table (dominant type,
  features, vertices, geometry bytes and shares, bytes per vertex, mean / median /
  p90 / p99 / max vertices per feature, and a log-scale box plot). The zoom
  selector limits the numbers to one zoom level; "Follow map zoom" tracks the
  map. Layers the research pipeline extracts are marked with their map color.
- `src/main.ts` — client: builds the MapLibre GL map, adds one styled layer per
  target layer (fill for landuse/landcover/water/building, line for transportation),
  and renders a toggle panel that calls `map.setLayoutProperty(id, "visibility", ...)`,
  plus a dataset dropdown that reloads the page with `?dataset=`.
  While a layer's "`<layer>` vertices" checkbox is on, its features are
  hoverable (whole feature highlighted, hovered part of a multi-part feature
  outlined in orange, pointer cursor) with a tooltip showing the feature's type
  and the vertex count of the hovered part and of the whole feature. Clicking one
  opens a popup with its type (`subtype` / `class` / `subclass` tags, e.g. road
  class, landcover class; Planetiler buildings carry none), geometry type, part
  count (members of a Multi* geometry; a single geometry is one part), vertex
  count, plus the ring count for polygons. Counts are per tile, since MVT
  splits features at tile borders; polygon closing vertices are not counted
  (as in the MLT VertexBuffer).
- `public/index.html` — page shell + toggle panel and composition drawer
  containers/styles.
