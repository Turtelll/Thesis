import maplibregl, { Map as MapLibreMap } from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";

/**
 * Renders the thesis mbtiles source data (served by ../server/server.ts) as an
 * interactive MapLibre GL map, with one on/off checkbox per layer, so the raw
 * geometry the Java pipeline extracts VertexBuffers from can be inspected
 * visually (density, shapes, vertex clustering) before designing predictors.
 */

interface LayerConfig {
  id: string;
  sourceLayer: string;
  label: string;
  color: string;
  type: "fill" | "line" | "circle";
}

const SOURCE_ID = "mbtiles";

const DATASETS = [
  { id: "munich", label: "Munich" },
  { id: "ny", label: "New York" },
];

function currentDataset(): string {
  const id = new URLSearchParams(window.location.search).get("dataset");
  return DATASETS.some((d) => d.id === id) ? id! : DATASETS[0].id;
}

// Suffix for the companion line layer that traces every polygon ring, so the
// individual vertices/edges of a fill layer stay visible where fills overlap.
const OUTLINE_SUFFIX = "-outline";

// Suffix for the companion circle layer marking every vertex of a geometry
// layer, so vertex density/placement can be inspected directly.
const VERTEX_SUFFIX = "-vertices";
const VERTEX_SOURCE_SUFFIX = "-vertices-src";

// Suffixes for the companion layers drawing one arrow per consecutive feature
// pair, from a feature's last vertex to the next feature's first vertex, so the
// order features are laid out in within a tile can be inspected directly.
const ARROW_SUFFIX = "-close-arrows";
const ARROWHEAD_SUFFIX = "-close-arrowheads";
const ARROW_SOURCE_SUFFIX = "-close-arrows-src";

// Suffixes for the companion layers drawing arrows in the order
// GeometryReorder.java's greedy nearest-neighbor chain would visit features,
// so the reordering the Java research pipeline applies can be compared
// visually against the raw tile order drawn by the arrows above.
const REORDER_ARROW_SUFFIX = "-reorder-arrows";
const REORDER_ARROWHEAD_SUFFIX = "-reorder-arrowheads";
const REORDER_ARROW_SOURCE_SUFFIX = "-reorder-arrows-src";

// Arrowhead barb geometry: half-angle against the shaft, fixed barb length
// (in degrees of longitude) so every arrowhead renders the same size.
const ARROWHEAD_ANGLE = Math.PI / 7;
const ARROWHEAD_MAX = 0.00003;

// Palette vertices are colored from, cycling by a hash of the parent
// geometry's id so adjacent geometries' vertices stay visually separable.
const VERTEX_PALETTE = [
  "#e6194b", "#3cb44b", "#4363d8", "#f58231", "#911eb4",
  "#42d4f4", "#f032e6", "#bfef45", "#fabed4", "#469990",
];

function colorForGeom(key: number): string {
  return VERTEX_PALETTE[key % VERTEX_PALETTE.length];
}

// Walks a GeoJSON-style coordinate geometry (from a MapLibre feature) and
// emits one Point feature per vertex, tagged with a per-geometry color key.
function extractVertices(
  geometry: GeoJSON.Geometry,
  geomKey: number,
  out: GeoJSON.Feature[],
): void {
  const emit = (coords: number[]) => {
    out.push({
      type: "Feature",
      properties: { geomKey },
      geometry: { type: "Point", coordinates: coords },
    });
  };
  switch (geometry.type) {
    case "Point":
      emit(geometry.coordinates);
      break;
    case "MultiPoint":
    case "LineString":
      geometry.coordinates.forEach(emit);
      break;
    case "MultiLineString":
    case "Polygon":
      geometry.coordinates.forEach((ring) => ring.forEach(emit));
      break;
    case "MultiPolygon":
      geometry.coordinates.forEach((poly) => poly.forEach((ring) => ring.forEach(emit)));
      break;
  }
}

// First / last coordinate of a feature's geometry in encoding order, i.e. the
// first vertex of its first part and the last vertex of its last part. Polygon
// rings repeat their first coordinate at the end, so that duplicate closing
// coordinate is skipped to report the actual last distinct vertex.
function endpointsOf(geometry: GeoJSON.Geometry): { first: number[]; last: number[] } | null {
  const parts: number[][][] = [];
  switch (geometry.type) {
    case "Point":
      parts.push([geometry.coordinates]);
      break;
    case "MultiPoint":
    case "LineString":
      parts.push(geometry.coordinates);
      break;
    case "MultiLineString":
    case "Polygon":
      parts.push(...geometry.coordinates);
      break;
    case "MultiPolygon":
      geometry.coordinates.forEach((poly) => parts.push(...poly));
      break;
  }
  const nonEmpty = parts.filter((part) => part.length > 0);
  if (nonEmpty.length === 0) return null;

  const firstPart = nonEmpty[0];
  const lastPart = nonEmpty[nonEmpty.length - 1];
  const first = firstPart[0];
  let last = lastPart[lastPart.length - 1];
  if (lastPart.length > 1) {
    const partFirst = lastPart[0];
    if (last[0] === partFirst[0] && last[1] === partFirst[1]) {
      last = lastPart[lastPart.length - 2];
    }
  }
  return { first, last };
}

// Emits the shaft plus its two arrowhead barbs for one from -> to hop.
function emitArrow(from: number[], to: number[], geomKey: number, out: GeoJSON.Feature[]): void {
  const dx = to[0] - from[0];
  const dy = to[1] - from[1];
  const length = Math.hypot(dx, dy);
  if (length === 0) return;

  out.push({
    type: "Feature",
    properties: { geomKey, head: false },
    geometry: { type: "LineString", coordinates: [from, to] },
  });

  // Two barbs at the tip, each the shaft direction rotated by +/-
  // ARROWHEAD_ANGLE, fixed length so every arrowhead reads the same size.
  const barb = ARROWHEAD_MAX;
  const heading = Math.atan2(dy, dx);
  for (const sign of [1, -1]) {
    const angle = heading + Math.PI + sign * ARROWHEAD_ANGLE;
    out.push({
      type: "Feature",
      properties: { geomKey, head: true },
      geometry: {
        type: "LineString",
        coordinates: [to, [to[0] + Math.cos(angle) * barb, to[1] + Math.sin(angle) * barb]],
      },
    });
  }
}

function refreshCloseArrows(map: MapLibreMap, layer: LayerConfig): void {
  const source = map.getSource(layer.id + ARROW_SOURCE_SUFFIX) as maplibregl.GeoJSONSource | undefined;
  if (!source) return;
  const features = map.querySourceFeatures(SOURCE_ID, { sourceLayer: layer.sourceLayer });

  // querySourceFeatures returns features from every loaded tile at once, so
  // "the next feature" is only meaningful inside a single tile: group by the
  // tile the feature was decoded from, keeping each tile's feature order.
  const byTile = new Map<string, GeoJSON.Feature[]>();
  for (const feature of features) {
    const vtf = (feature as unknown as { _vectorTileFeature?: { _z: number; _x: number; _y: number } })
      ._vectorTileFeature;
    const tileKey = vtf ? `${vtf._z}/${vtf._x}/${vtf._y}` : "unknown";
    const bucket = byTile.get(tileKey);
    if (bucket) bucket.push(feature);
    else byTile.set(tileKey, [feature]);
  }

  const arrows: GeoJSON.Feature[] = [];
  for (const tileFeatures of byTile.values()) {
    const endpoints = tileFeatures.map((f) => endpointsOf(f.geometry));
    for (let i = 0; i + 1 < endpoints.length; i++) {
      const from = endpoints[i];
      const to = endpoints[i + 1];
      if (!from || !to) continue;
      emitArrow(from.last, to.first, i, arrows);
    }
  }
  source.setData({ type: "FeatureCollection", features: arrows });
}

// Greedy nearest-neighbor chain over feature endpoints, mirroring
// GeometryReorder.greedyChain (research-java): starting from (0,0), repeatedly
// visits the unused feature whose entry point is closest to the cursor in
// Manhattan distance, then advances the cursor to that feature's exit point.
// Ties break on the lower index for reproducibility. No reversal variant and
// no spatial grid (O(n^2) is fine at per-tile feature counts, unlike the
// ~65k-vertex buffers the Java benchmark corpus scans).
function greedyChainOrder(endpoints: ({ first: number[]; last: number[] } | null)[]): number[] {
  const used = new Array(endpoints.length).fill(false);
  const order: number[] = [];
  let cursorX = 0;
  let cursorY = 0;
  for (let step = 0; step < endpoints.length; step++) {
    let best = -1;
    let bestDistance = Infinity;
    for (let i = 0; i < endpoints.length; i++) {
      if (used[i] || !endpoints[i]) continue;
      const first = endpoints[i]!.first;
      const distance = Math.abs(first[0] - cursorX) + Math.abs(first[1] - cursorY);
      if (distance < bestDistance) {
        bestDistance = distance;
        best = i;
      }
    }
    if (best === -1) break;
    used[best] = true;
    order.push(best);
    cursorX = endpoints[best]!.last[0];
    cursorY = endpoints[best]!.last[1];
  }
  return order;
}

function refreshReorderArrows(map: MapLibreMap, layer: LayerConfig): void {
  const source = map.getSource(layer.id + REORDER_ARROW_SOURCE_SUFFIX) as
    | maplibregl.GeoJSONSource
    | undefined;
  if (!source) return;
  const features = map.querySourceFeatures(SOURCE_ID, { sourceLayer: layer.sourceLayer });

  const byTile = new Map<string, GeoJSON.Feature[]>();
  for (const feature of features) {
    const vtf = (feature as unknown as { _vectorTileFeature?: { _z: number; _x: number; _y: number } })
      ._vectorTileFeature;
    const tileKey = vtf ? `${vtf._z}/${vtf._x}/${vtf._y}` : "unknown";
    const bucket = byTile.get(tileKey);
    if (bucket) bucket.push(feature);
    else byTile.set(tileKey, [feature]);
  }

  const arrows: GeoJSON.Feature[] = [];
  for (const tileFeatures of byTile.values()) {
    const endpoints = tileFeatures.map((f) => endpointsOf(f.geometry));
    const order = greedyChainOrder(endpoints);
    for (let i = 0; i + 1 < order.length; i++) {
      const from = endpoints[order[i]];
      const to = endpoints[order[i + 1]];
      if (!from || !to) continue;
      emitArrow(from.last, to.first, i, arrows);
    }
  }
  source.setData({ type: "FeatureCollection", features: arrows });
}

function refreshVertices(map: MapLibreMap, layer: LayerConfig): void {
  const source = map.getSource(layer.id + VERTEX_SOURCE_SUFFIX) as maplibregl.GeoJSONSource | undefined;
  if (!source) return;
  const features = map.querySourceFeatures(SOURCE_ID, { sourceLayer: layer.sourceLayer });
  const vertices: GeoJSON.Feature[] = [];
  features.forEach((feature, index) => {
    const geomKey = typeof feature.id === "number" ? feature.id : index;
    extractVertices(feature.geometry, geomKey, vertices);
  });
  source.setData({ type: "FeatureCollection", features: vertices });
}

// One entry per layer the Java pipeline also extracts (landcover, building,
// water, transportation), styled distinctly so overlaps stay legible.
const LAYERS: LayerConfig[] = [
  { id: "landcover", sourceLayer: "landcover", label: "Landcover", color: "#8fbf7f", type: "fill" },
  { id: "water", sourceLayer: "water", label: "Water", color: "#6ea8d8", type: "fill" },
  { id: "building", sourceLayer: "building", label: "Building", color: "#c98f6b", type: "fill" },
  {
    id: "transportation",
    sourceLayer: "transportation",
    label: "Transportation",
    color: "#3d3d3d",
    type: "line",
  },
];

async function loadCenter(dataset: string): Promise<{ center: [number, number]; zoom: number }> {
  try {
    const response = await fetch(`/metadata.json?dataset=${dataset}`);
    const metadata: Record<string, string> = await response.json();
    const [lon, lat, zoom] = (metadata.center ?? "7.4246,43.7384,14").split(",").map(Number);
    return { center: [lon, lat], zoom };
  } catch {
    return { center: [7.4246, 43.7384], zoom: 14 };
  }
}

function darken(hexColor: string, factor = 0.55): string {
  const value = parseInt(hexColor.slice(1), 16);
  const channels = [(value >> 16) & 0xff, (value >> 8) & 0xff, value & 0xff];
  return `#${channels.map((c) => Math.round(c * factor).toString(16).padStart(2, "0")).join("")}`;
}

function addLayer(map: MapLibreMap, layer: LayerConfig): void {
  if (layer.type === "fill") {
    map.addLayer({
      id: layer.id,
      type: "fill",
      source: SOURCE_ID,
      "source-layer": layer.sourceLayer,
      paint: { "fill-color": layer.color, "fill-opacity": 0.55 },
    });
  } else if (layer.type === "line") {
    map.addLayer({
      id: layer.id,
      type: "line",
      source: SOURCE_ID,
      "source-layer": layer.sourceLayer,
      paint: {
        "line-color": layer.color,
        "line-width": ["interpolate", ["linear"], ["zoom"], 10, 0.5, 16, 2.5],
      },
    });
  } else {
    map.addLayer({
      id: layer.id,
      type: "circle",
      source: SOURCE_ID,
      "source-layer": layer.sourceLayer,
      paint: { "circle-color": layer.color, "circle-radius": 3 },
    });
  }
}

function addVertexLayer(map: MapLibreMap, layer: LayerConfig): void {
  map.addSource(layer.id + VERTEX_SOURCE_SUFFIX, {
    type: "geojson",
    data: { type: "FeatureCollection", features: [] },
  });
  map.addLayer({
    id: layer.id + VERTEX_SUFFIX,
    type: "circle",
    source: layer.id + VERTEX_SOURCE_SUFFIX,
    layout: { visibility: "none" },
    paint: {
      "circle-radius": ["interpolate", ["linear"], ["zoom"], 10, 1.5, 18, 4],
      "circle-color": [
        "match",
        ["%", ["get", "geomKey"], VERTEX_PALETTE.length],
        ...VERTEX_PALETTE.flatMap((color, i) => [i, color]),
        VERTEX_PALETTE[0],
      ] as unknown as maplibregl.ExpressionSpecification,
      "circle-stroke-width": 0.5,
      "circle-stroke-color": "#000000",
    },
  });
}

function addCloseArrowLayer(map: MapLibreMap, layer: LayerConfig): void {
  const colorExpression = [
    "match",
    ["%", ["get", "geomKey"], VERTEX_PALETTE.length],
    ...VERTEX_PALETTE.flatMap((color, i) => [i, color]),
    VERTEX_PALETTE[0],
  ] as unknown as maplibregl.ExpressionSpecification;

  map.addSource(layer.id + ARROW_SOURCE_SUFFIX, {
    type: "geojson",
    data: { type: "FeatureCollection", features: [] },
  });
  map.addLayer({
    id: layer.id + ARROW_SUFFIX,
    type: "line",
    source: layer.id + ARROW_SOURCE_SUFFIX,
    filter: ["!=", ["get", "head"], true],
    layout: { visibility: "none" },
    paint: {
      "line-color": colorExpression,
      "line-width": ["interpolate", ["linear"], ["zoom"], 10, 0.8, 18, 2],
    },
  });
  // The arrowhead is drawn as an explicit filled triangle (see emitArrow)
  // rather than a glyph or sprite, since the style ships no glyph server.
  map.addLayer({
    id: layer.id + ARROWHEAD_SUFFIX,
    type: "fill",
    source: layer.id + ARROW_SOURCE_SUFFIX,
    filter: ["==", ["get", "head"], true],
    layout: { visibility: "none" },
    paint: {
      "fill-color": colorExpression,
    },
  });
}

function addReorderArrowLayer(map: MapLibreMap, layer: LayerConfig): void {
  const colorExpression = [
    "match",
    ["%", ["get", "geomKey"], VERTEX_PALETTE.length],
    ...VERTEX_PALETTE.flatMap((color, i) => [i, color]),
    VERTEX_PALETTE[0],
  ] as unknown as maplibregl.ExpressionSpecification;

  map.addSource(layer.id + REORDER_ARROW_SOURCE_SUFFIX, {
    type: "geojson",
    data: { type: "FeatureCollection", features: [] },
  });
  map.addLayer({
    id: layer.id + REORDER_ARROW_SUFFIX,
    type: "line",
    source: layer.id + REORDER_ARROW_SOURCE_SUFFIX,
    filter: ["!=", ["get", "head"], true],
    layout: { visibility: "none" },
    paint: {
      "line-color": colorExpression,
      "line-width": ["interpolate", ["linear"], ["zoom"], 10, 0.8, 18, 2],
      "line-dasharray": [2, 1],
    },
  });
  map.addLayer({
    id: layer.id + REORDER_ARROWHEAD_SUFFIX,
    type: "fill",
    source: layer.id + REORDER_ARROW_SOURCE_SUFFIX,
    filter: ["==", ["get", "head"], true],
    layout: { visibility: "none" },
    paint: {
      "fill-color": colorExpression,
    },
  });
}

function addOutlineLayer(map: MapLibreMap, layer: LayerConfig): void {
  map.addLayer({
    id: layer.id + OUTLINE_SUFFIX,
    type: "line",
    source: SOURCE_ID,
    "source-layer": layer.sourceLayer,
    filter: ["==", ["geometry-type"], "Polygon"],
    paint: {
      "line-color": darken(layer.color),
      "line-width": ["interpolate", ["linear"], ["zoom"], 8, 0.4, 14, 1, 18, 2],
      "line-opacity": 0.9,
    },
  });
}

function buildDatasetSelect(dataset: string): void {
  const panel = document.getElementById("layer-panel")!;
  const select = document.createElement("select");
  select.className = "dataset-select";
  for (const d of DATASETS) {
    const option = document.createElement("option");
    option.value = d.id;
    option.textContent = d.label;
    option.selected = d.id === dataset;
    select.appendChild(option);
  }
  select.addEventListener("change", () => {
    const url = new URL(window.location.href);
    url.searchParams.set("dataset", select.value);
    window.location.href = url.toString();
  });
  panel.appendChild(select);
}

// MapLibre's built-in debug overlay, drawing each loaded tile's border and its
// z/x/y label. Useful next to the feature-order arrows, which chain features
// within a tile and never across tile boundaries.
function buildTileBoundaryToggle(map: MapLibreMap): void {
  const panel = document.getElementById("layer-panel")!;
  const item = document.createElement("label");
  item.className = "layer-toggle";

  const checkbox = document.createElement("input");
  checkbox.type = "checkbox";
  checkbox.checked = false;
  checkbox.addEventListener("change", () => {
    map.showTileBoundaries = checkbox.checked;
  });

  const text = document.createElement("span");
  text.textContent = "Tile boundaries";

  item.append(checkbox, text);
  panel.appendChild(item);
}

function buildLayerToggles(map: MapLibreMap): void {
  const panel = document.getElementById("layer-panel")!;
  for (const layer of LAYERS) {
    const item = document.createElement("label");
    item.className = "layer-toggle";

    const swatch = document.createElement("span");
    swatch.className = "swatch";
    swatch.style.backgroundColor = layer.color;

    const checkbox = document.createElement("input");
    checkbox.type = "checkbox";
    checkbox.checked = true;
    checkbox.addEventListener("change", () => {
      const visibility = checkbox.checked ? "visible" : "none";
      map.setLayoutProperty(layer.id, "visibility", visibility);
      if (layer.type === "fill") {
        map.setLayoutProperty(layer.id + OUTLINE_SUFFIX, "visibility", visibility);
      }
    });

    const text = document.createElement("span");
    text.textContent = layer.label;

    item.append(checkbox, swatch, text);
    panel.appendChild(item);

    const vertexItem = document.createElement("label");
    vertexItem.className = "layer-toggle vertex-toggle";

    const vertexCheckbox = document.createElement("input");
    vertexCheckbox.type = "checkbox";
    vertexCheckbox.checked = false;
    vertexCheckbox.addEventListener("change", () => {
      const visibility = vertexCheckbox.checked ? "visible" : "none";
      map.setLayoutProperty(layer.id + VERTEX_SUFFIX, "visibility", visibility);
      if (vertexCheckbox.checked) refreshVertices(map, layer);
    });

    const vertexText = document.createElement("span");
    vertexText.textContent = `${layer.label} vertices`;

    vertexItem.append(vertexCheckbox, vertexText);
    panel.appendChild(vertexItem);

    const arrowItem = document.createElement("label");
    arrowItem.className = "layer-toggle vertex-toggle";

    const arrowCheckbox = document.createElement("input");
    arrowCheckbox.type = "checkbox";
    arrowCheckbox.checked = false;
    arrowCheckbox.addEventListener("change", () => {
      const visibility = arrowCheckbox.checked ? "visible" : "none";
      map.setLayoutProperty(layer.id + ARROW_SUFFIX, "visibility", visibility);
      map.setLayoutProperty(layer.id + ARROWHEAD_SUFFIX, "visibility", visibility);
      if (arrowCheckbox.checked) refreshCloseArrows(map, layer);
    });

    const arrowText = document.createElement("span");
    arrowText.textContent = `${layer.label} feature order`;

    arrowItem.append(arrowCheckbox, arrowText);
    panel.appendChild(arrowItem);

    const reorderItem = document.createElement("label");
    reorderItem.className = "layer-toggle vertex-toggle";

    const reorderCheckbox = document.createElement("input");
    reorderCheckbox.type = "checkbox";
    reorderCheckbox.checked = false;
    reorderCheckbox.addEventListener("change", () => {
      const visibility = reorderCheckbox.checked ? "visible" : "none";
      map.setLayoutProperty(layer.id + REORDER_ARROW_SUFFIX, "visibility", visibility);
      map.setLayoutProperty(layer.id + REORDER_ARROWHEAD_SUFFIX, "visibility", visibility);
      if (reorderCheckbox.checked) refreshReorderArrows(map, layer);
    });

    const reorderText = document.createElement("span");
    reorderText.textContent = `${layer.label} reordered feature order`;

    reorderItem.append(reorderCheckbox, reorderText);
    panel.appendChild(reorderItem);
  }
}

async function main(): Promise<void> {
  const dataset = currentDataset();
  const { center, zoom } = await loadCenter(dataset);

  const map = new maplibregl.Map({
    container: "map",
    style: {
      version: 8,
      sources: {
        [SOURCE_ID]: {
          type: "vector",
          tiles: [`${window.location.origin}/tiles/{z}/{x}/{y}.pbf?dataset=${dataset}`],
          minzoom: 0,
          maxzoom: 14,
        },
      },
      layers: [{ id: "background", type: "background", paint: { "background-color": "#eef2f5" } }],
    },
    center,
    zoom,
  });

  map.addControl(new maplibregl.NavigationControl(), "top-right");

  map.on("load", () => {
    for (const layer of LAYERS) {
      addLayer(map, layer);
    }
    // Outlines go on top of all fills so polygon borders stay visible where
    // layers overlap (e.g. buildings on top of landcover).
    for (const layer of LAYERS.filter((l) => l.type === "fill")) {
      addOutlineLayer(map, layer);
    }
    for (const layer of LAYERS) {
      addVertexLayer(map, layer);
      addCloseArrowLayer(map, layer);
      addReorderArrowLayer(map, layer);
    }
    buildDatasetSelect(dataset);
    buildTileBoundaryToggle(map);
    buildLayerToggles(map);

    const refreshVisibleVertexLayers = () => {
      for (const layer of LAYERS) {
        if (map.getLayoutProperty(layer.id + VERTEX_SUFFIX, "visibility") === "visible") {
          refreshVertices(map, layer);
        }
        if (map.getLayoutProperty(layer.id + ARROW_SUFFIX, "visibility") === "visible") {
          refreshCloseArrows(map, layer);
        }
        if (map.getLayoutProperty(layer.id + REORDER_ARROW_SUFFIX, "visibility") === "visible") {
          refreshReorderArrows(map, layer);
        }
      }
    };
    map.on("moveend", refreshVisibleVertexLayers);
    map.on("zoomend", refreshVisibleVertexLayers);
  });
}

main();
