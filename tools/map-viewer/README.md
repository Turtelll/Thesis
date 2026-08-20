# MVT Map Viewer

A small TypeScript + [MapLibre GL JS](https://maplibre.org/maplibre-gl-js/docs/)
app for visually inspecting the thesis source data
(`data/MVT/munich.mbtiles`, `data/MVT/NY.mbtiles`): a Node/Express server
streams tiles straight out of the selected mbtiles file (converting MBTiles'
TMS row order to XYZ on the fly), and the browser client renders `landcover`,
`water`, `building` and `transportation` as an interactive map with one on/off
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
  static files are served from `public/`. `dataset` is `munich` (default) or
  `ny`.
- `src/main.ts` — client: builds the MapLibre GL map, adds one styled layer per
  target layer (fill for landcover/water/building, line for transportation),
  and renders a toggle panel that calls `map.setLayoutProperty(id, "visibility", ...)`,
  plus a dataset dropdown that reloads the page with `?dataset=`.
- `public/index.html` — page shell + toggle panel container/styles.
