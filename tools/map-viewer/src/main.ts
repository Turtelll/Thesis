import maplibregl, {Map as MapLibreMap} from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import {buildCompositionDrawer} from "./composition";

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

const DATASETS = [{id: "munich-plantiler", label: "Munich Plantiler"},
    {id: "germany-rs", label: "Germany"},
    {id: "munich-overture", label: "Munich Overture"},
    {id: "bayern", label: "Bayern Plantiler"},
    {id: "chicago-25km", label: "Chicago Plantiler"},
    {id: "tokyo-25km", label: "Tokyo Plantiler"},
    {id: "sao-paulo-25km", label: "São Paulo Plantiler"},];

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
const VERTEX_PALETTE = ["#e6194b", "#3cb44b", "#4363d8", "#f58231", "#911eb4", "#42d4f4", "#f032e6", "#bfef45", "#fabed4", "#469990",];

function colorForGeom(key: number): string {
    return VERTEX_PALETTE[key % VERTEX_PALETTE.length];
}

// Stable color key for a feature: FNV-1a hash of its id, so the same feature
// keeps its color across refreshes and across every tile it is split into.
// Hashed rather than used raw because Planetiler ids encode the OSM element
// type in the last digit (id % 10 would color every way the same). Features
// without an id fall back to hashing their properties.
function geomKeyOf(feature: GeoJSON.Feature): number {
    const s = feature.id !== undefined ? String(feature.id) : JSON.stringify(feature.properties);
    let h = 0x811c9dc5;
    for (let i = 0; i < s.length; i++) {
        h ^= s.charCodeAt(i);
        h = Math.imul(h, 0x01000193);
    }
    return h >>> 0;
}

// Walks a GeoJSON-style coordinate geometry (from a MapLibre feature) and
// emits one Point feature per vertex, tagged with a per-geometry color key.
function extractVertices(geometry: GeoJSON.Geometry, geomKey: number, out: GeoJSON.Feature[],): void {
    const emit = (coords: number[]) => {
        out.push({
            type: "Feature", properties: {geomKey}, geometry: {type: "Point", coordinates: coords},
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
    return {first, last};
}

// Emits the shaft plus its two arrowhead barbs for one from -> to hop.
function emitArrow(from: number[], to: number[], geomKey: number, out: GeoJSON.Feature[]): void {
    const dx = to[0] - from[0];
    const dy = to[1] - from[1];
    const length = Math.hypot(dx, dy);
    if (length === 0) return;

    out.push({
        type: "Feature", properties: {geomKey, head: false}, geometry: {type: "LineString", coordinates: [from, to]},
    });

    // Two barbs at the tip, each the shaft direction rotated by +/-
    // ARROWHEAD_ANGLE, fixed length so every arrowhead reads the same size.
    const barb = ARROWHEAD_MAX;
    const heading = Math.atan2(dy, dx);
    for (const sign of [1, -1]) {
        const angle = heading + Math.PI + sign * ARROWHEAD_ANGLE;
        out.push({
            type: "Feature", properties: {geomKey, head: true}, geometry: {
                type: "LineString", coordinates: [to, [to[0] + Math.cos(angle) * barb, to[1] + Math.sin(angle) * barb]],
            },
        });
    }
}

function refreshCloseArrows(map: MapLibreMap, layer: LayerConfig): void {
    const source = map.getSource(layer.id + ARROW_SOURCE_SUFFIX) as maplibregl.GeoJSONSource | undefined;
    if (!source) return;
    const features = map.querySourceFeatures(SOURCE_ID, {sourceLayer: layer.sourceLayer});

    // querySourceFeatures returns features from every loaded tile at once, so
    // "the next feature" is only meaningful inside a single tile: group by the
    // tile the feature was decoded from, keeping each tile's feature order.
    const byTile = new Map<string, GeoJSON.Feature[]>();
    for (const feature of features) {
        const vtf = (feature as unknown as {
            _vectorTileFeature?: { _z: number; _x: number; _y: number }
        })._vectorTileFeature;
        const tileKey = vtf ? `${vtf._z}/${vtf._x}/${vtf._y}` : "unknown";
        const bucket = byTile.get(tileKey);
        if (bucket) bucket.push(feature); else byTile.set(tileKey, [feature]);
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
    source.setData({type: "FeatureCollection", features: arrows});
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
    const source = map.getSource(layer.id + REORDER_ARROW_SOURCE_SUFFIX) as | maplibregl.GeoJSONSource | undefined;
    if (!source) return;
    const features = map.querySourceFeatures(SOURCE_ID, {sourceLayer: layer.sourceLayer});

    const byTile = new Map<string, GeoJSON.Feature[]>();
    for (const feature of features) {
        const vtf = (feature as unknown as {
            _vectorTileFeature?: { _z: number; _x: number; _y: number }
        })._vectorTileFeature;
        const tileKey = vtf ? `${vtf._z}/${vtf._x}/${vtf._y}` : "unknown";
        const bucket = byTile.get(tileKey);
        if (bucket) bucket.push(feature); else byTile.set(tileKey, [feature]);
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
    source.setData({type: "FeatureCollection", features: arrows});
}

function refreshVertices(map: MapLibreMap, layer: LayerConfig): void {
    const source = map.getSource(layer.id + VERTEX_SOURCE_SUFFIX) as maplibregl.GeoJSONSource | undefined;
    if (!source) return;
    const features = map.querySourceFeatures(SOURCE_ID, {sourceLayer: layer.sourceLayer});
    const vertices: GeoJSON.Feature[] = [];
    features.forEach((feature) => {
        extractVertices(feature.geometry, geomKeyOf(feature), vertices);
    });
    source.setData({type: "FeatureCollection", features: vertices});
}

// One entry per layer the Java pipeline also extracts (landuse, landcover,
// building, water, transportation), styled distinctly so overlaps stay legible.
// Order is draw order: landuse sits at the bottom, under landcover.
const LAYERS: LayerConfig[] = [{
    id: "landuse", sourceLayer: "landuse", label: "Landuse", color: "#d4b483", type: "fill"
}, {
    id: "landcover", sourceLayer: "landcover", label: "Landcover", color: "#8fbf7f", type: "fill"
}, {id: "water", sourceLayer: "water", label: "Water", color: "#6ea8d8", type: "fill"}, {
    id: "building", sourceLayer: "building", label: "Building", color: "#c98f6b", type: "fill"
}, {
    id: "transportation", sourceLayer: "transportation", label: "Transportation", color: "#3d3d3d", type: "line",
},];

// Point/label layers the Java pipeline does not extract, drawn as circles and
// switched together by one shared toggle (see buildLabelToggle).
const LABEL_LAYERS = [{sourceLayer: "poi", color: "#d6336c"}, {
    sourceLayer: "place", color: "#7048e8"
}, {sourceLayer: "water_name", color: "#1971c2"},];
const LABEL_LAYER_PREFIX = "label-";

function addLabelLayers(map: MapLibreMap, visibility: string): void {
    for (const label of LABEL_LAYERS) {
        map.addLayer({
            id: LABEL_LAYER_PREFIX + label.sourceLayer,
            type: "circle",
            source: SOURCE_ID,
            "source-layer": label.sourceLayer,
            layout: {visibility: visibility as "visible" | "none"},
            paint: {
                "circle-color": label.color,
                "circle-radius": 3,
                "circle-stroke-width": 0.5,
                "circle-stroke-color": "#000000"
            },
        });
    }
}

async function loadCenter(dataset: string): Promise<{ center: [number, number]; zoom: number }> {
    try {
        const response = await fetch(`/metadata.json?dataset=${dataset}`);
        const metadata: Record<string, string> = await response.json();
        const [lon, lat, zoom] = (metadata.center ?? "7.4246,43.7384,14").split(",").map(Number);
        return {center: [lon, lat], zoom};
    } catch {
        return {center: [7.4246, 43.7384], zoom: 14};
    }
}

function darken(hexColor: string, factor = 0.55): string {
    const value = parseInt(hexColor.slice(1), 16);
    const channels = [(value >> 16) & 0xff, (value >> 8) & 0xff, value & 0xff];
    return `#${channels.map((c) => Math.round(c * factor).toString(16).padStart(2, "0")).join("")}`;
}

// fill-color/line-color/circle-color expression reading the per-feature
// geomKey assigned by refreshGeomKeys via setFeatureState, falling back to the
// layer's flat color for features that haven't been keyed yet (e.g. just
// loaded, before the next refreshGeomKeys pass). Only applied while the
// layer's vertices toggle is on, via setGeomKeyColoring.
function geomKeyColorExpression(layer: LayerConfig): maplibregl.ExpressionSpecification {
    return ["case", ["!=", ["feature-state", "geomKey"], null], ["match", ["%", ["feature-state", "geomKey"], VERTEX_PALETTE.length], ...VERTEX_PALETTE.flatMap((color, i) => [i, color]), VERTEX_PALETTE[0],], layer.color,] as unknown as maplibregl.ExpressionSpecification;
}

function colorPaintProperty(layer: LayerConfig): "fill-color" | "line-color" | "circle-color" {
    return layer.type === "fill" ? "fill-color" : layer.type === "line" ? "line-color" : "circle-color";
}

// Switches a layer's color paint property between its flat color and the
// per-feature geomKey expression, so geomKey coloring is only active while
// the "<layer> vertices" checkbox is checked.
function setGeomKeyColoring(map: MapLibreMap, layer: LayerConfig, enabled: boolean): void {
    map.setPaintProperty(layer.id, colorPaintProperty(layer), enabled ? geomKeyColorExpression(layer) : layer.color);
}

function addLayer(map: MapLibreMap, layer: LayerConfig): void {
    if (layer.type === "fill") {
        map.addLayer({
            id: layer.id,
            type: "fill",
            source: SOURCE_ID,
            "source-layer": layer.sourceLayer,
            paint: {"fill-color": layer.color, "fill-opacity": 0.55},
        });
    } else if (layer.type === "line") {
        map.addLayer({
            id: layer.id, type: "line", source: SOURCE_ID, "source-layer": layer.sourceLayer, paint: {
                "line-color": layer.color, "line-width": ["interpolate", ["linear"], ["zoom"], 10, 0.5, 16, 2.5],
            },
        });
    } else {
        map.addLayer({
            id: layer.id,
            type: "circle",
            source: SOURCE_ID,
            "source-layer": layer.sourceLayer,
            paint: {"circle-color": layer.color, "circle-radius": 3},
        });
    }
}

// Assigns each currently-loaded feature of a layer a geomKey via
// setFeatureState, keyed by the same geomKeyOf hash refreshVertices uses for
// its vertex points, so a feature's fill/line/circle color always matches the
// color of its own vertices.
function refreshGeomKeys(map: MapLibreMap, layer: LayerConfig): void {
    const features = map.querySourceFeatures(SOURCE_ID, {sourceLayer: layer.sourceLayer});
    features.forEach((feature) => {
        if (feature.id === undefined) return;
        map.setFeatureState({source: SOURCE_ID, sourceLayer: layer.sourceLayer, id: feature.id}, {geomKey: geomKeyOf(feature)});
    });
}

function addVertexLayer(map: MapLibreMap, layer: LayerConfig): void {
    map.addSource(layer.id + VERTEX_SOURCE_SUFFIX, {
        type: "geojson", data: {type: "FeatureCollection", features: []},
    });
    map.addLayer({
        id: layer.id + VERTEX_SUFFIX,
        type: "circle",
        source: layer.id + VERTEX_SOURCE_SUFFIX,
        layout: {visibility: "none"},
        paint: {
            "circle-radius": ["interpolate", ["linear"], ["zoom"], 10, 1.5, 18, 4],
            "circle-color": ["match", ["%", ["get", "geomKey"], VERTEX_PALETTE.length], ...VERTEX_PALETTE.flatMap((color, i) => [i, color]), VERTEX_PALETTE[0],] as unknown as maplibregl.ExpressionSpecification,
            "circle-stroke-width": 0.5,
            "circle-stroke-color": "#000000",
        },
    });
}

function addCloseArrowLayer(map: MapLibreMap, layer: LayerConfig): void {
    const colorExpression = ["match", ["%", ["get", "geomKey"], VERTEX_PALETTE.length], ...VERTEX_PALETTE.flatMap((color, i) => [i, color]), VERTEX_PALETTE[0],] as unknown as maplibregl.ExpressionSpecification;

    map.addSource(layer.id + ARROW_SOURCE_SUFFIX, {
        type: "geojson", data: {type: "FeatureCollection", features: []},
    });
    map.addLayer({
        id: layer.id + ARROW_SUFFIX,
        type: "line",
        source: layer.id + ARROW_SOURCE_SUFFIX,
        filter: ["!=", ["get", "head"], true],
        layout: {visibility: "none"},
        paint: {
            "line-color": colorExpression, "line-width": ["interpolate", ["linear"], ["zoom"], 10, 0.8, 18, 2],
        },
    });
    // The arrowhead is drawn as an explicit filled triangle (see emitArrow)
    // rather than a glyph or sprite, since the style ships no glyph server.
    map.addLayer({
        id: layer.id + ARROWHEAD_SUFFIX,
        type: "fill",
        source: layer.id + ARROW_SOURCE_SUFFIX,
        filter: ["==", ["get", "head"], true],
        layout: {visibility: "none"},
        paint: {
            "fill-color": colorExpression,
        },
    });
}

function addReorderArrowLayer(map: MapLibreMap, layer: LayerConfig): void {
    const colorExpression = ["match", ["%", ["get", "geomKey"], VERTEX_PALETTE.length], ...VERTEX_PALETTE.flatMap((color, i) => [i, color]), VERTEX_PALETTE[0],] as unknown as maplibregl.ExpressionSpecification;

    map.addSource(layer.id + REORDER_ARROW_SOURCE_SUFFIX, {
        type: "geojson", data: {type: "FeatureCollection", features: []},
    });
    map.addLayer({
        id: layer.id + REORDER_ARROW_SUFFIX,
        type: "line",
        source: layer.id + REORDER_ARROW_SOURCE_SUFFIX,
        filter: ["!=", ["get", "head"], true],
        layout: {visibility: "none"},
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
        layout: {visibility: "none"},
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

// Rebuilds the vector source at a given maxzoom, preserving each base/outline
// layer's current visibility. MapLibre has no API to mutate an existing vector
// source's maxzoom, so locking the zoom level means tearing down and re-adding
// the source (and every layer bound to it) with the new maxzoom - past that
// zoom, MapLibre overzooms the last fetched tile instead of requesting a new one.
function rebuildSource(map: MapLibreMap, dataset: string, maxzoom: number): void {
    const visibility = new Map<string, string>();
    for (const layer of LAYERS) {
        visibility.set(layer.id, map.getLayoutProperty(layer.id, "visibility") ?? "visible");
        if (layer.type === "fill") {
            visibility.set(layer.id + OUTLINE_SUFFIX, map.getLayoutProperty(layer.id + OUTLINE_SUFFIX, "visibility") ?? "visible");
        }
    }
    const labelVisibility = map.getLayoutProperty(LABEL_LAYER_PREFIX + LABEL_LAYERS[0].sourceLayer, "visibility") ?? "visible";

    for (const layer of LAYERS) {
        if (layer.type === "fill") map.removeLayer(layer.id + OUTLINE_SUFFIX);
        map.removeLayer(layer.id);
    }
    for (const label of LABEL_LAYERS) map.removeLayer(LABEL_LAYER_PREFIX + label.sourceLayer);
    map.removeSource(SOURCE_ID);

    map.addSource(SOURCE_ID, {
        type: "vector",
        tiles: [`${window.location.origin}/tiles/{z}/{x}/{y}.pbf?dataset=${dataset}`],
        minzoom: 0,
        maxzoom,
    });

    for (const layer of LAYERS) {
        addLayer(map, layer);
        map.setLayoutProperty(layer.id, "visibility", visibility.get(layer.id)!);
        setGeomKeyColoring(map, layer, map.getLayoutProperty(layer.id + VERTEX_SUFFIX, "visibility") === "visible");
    }
    for (const layer of LAYERS.filter((l) => l.type === "fill")) {
        addOutlineLayer(map, layer);
        map.setLayoutProperty(layer.id + OUTLINE_SUFFIX, "visibility", visibility.get(layer.id + OUTLINE_SUFFIX)!);
    }
    addLabelLayers(map, labelVisibility);
    if (map.getSource(HIGHLIGHT_SOURCE)) raiseHighlightLayers(map);
}

function buildLabelToggle(map: MapLibreMap): void {
    const panel = document.getElementById("layer-panel")!;
    const item = document.createElement("label");
    item.className = "layer-toggle";

    const checkbox = document.createElement("input");
    checkbox.type = "checkbox";
    checkbox.checked = true;
    checkbox.addEventListener("change", () => {
        for (const label of LABEL_LAYERS) {
            map.setLayoutProperty(LABEL_LAYER_PREFIX + label.sourceLayer, "visibility", checkbox.checked ? "visible" : "none");
        }
    });

    item.appendChild(checkbox);
    for (const label of LABEL_LAYERS) {
        const swatch = document.createElement("span");
        swatch.className = "swatch";
        swatch.style.backgroundColor = label.color;
        item.appendChild(swatch);
    }

    const text = document.createElement("span");
    text.textContent = "POI / place / water_name";
    item.appendChild(text);
    panel.appendChild(item);
}

// Toggle that locks the source's maxzoom to the zoom level active when it's
// checked, so zooming in further overzooms the last-fetched tiles instead of
// requesting new ones - useful for seeing how a fixed tile's geometry looks
// magnified rather than replaced by the next zoom level's re-tessellation.
function buildZoomLockToggle(map: MapLibreMap, dataset: string): void {
    const panel = document.getElementById("layer-panel")!;
    const item = document.createElement("label");
    item.className = "layer-toggle";

    const checkbox = document.createElement("input");
    checkbox.type = "checkbox";
    checkbox.checked = false;
    checkbox.addEventListener("change", () => {
        if (checkbox.checked) {
            const lockedZoom = Math.floor(map.getZoom());
            rebuildSource(map, dataset, lockedZoom);
        } else {
            rebuildSource(map, dataset, 14);
        }
    });

    const text = document.createElement("span");
    text.textContent = "Lock zoom level (overzoom)";

    item.append(checkbox, text);
    panel.appendChild(item);
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
            setGeomKeyColoring(map, layer, vertexCheckbox.checked);
            if (vertexCheckbox.checked) {
                refreshGeomKeys(map, layer);
                refreshVertices(map, layer);
            }
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

// Interactive feature inspection. While a layer's "<layer> vertices" checkbox
// is on, that layer's features are hoverable (highlighted outline + pointer
// cursor) and clicking one opens a popup with its part count.
const HIGHLIGHT_SOURCE = "hover-highlight";
const HIGHLIGHT_LAYERS = [HIGHLIGHT_SOURCE + "-fill", HIGHLIGHT_SOURCE + "-line", HIGHLIGHT_SOURCE + "-point"];

function addHighlightLayers(map: MapLibreMap): void {
    map.addSource(HIGHLIGHT_SOURCE, {type: "geojson", data: {type: "FeatureCollection", features: []}});
    map.addLayer({
        id: HIGHLIGHT_LAYERS[0],
        type: "fill",
        source: HIGHLIGHT_SOURCE,
        filter: ["==", ["geometry-type"], "Polygon"],
        paint: {"fill-color": "#ffffff", "fill-opacity": 0.3},
    });
    map.addLayer({
        id: HIGHLIGHT_LAYERS[1],
        type: "line",
        source: HIGHLIGHT_SOURCE,
        // Yellow outlines the whole hovered feature; orange marks its hovered part.
        paint: {
            "line-color": ["case", ["==", ["get", "part"], true], "#ff6b00", "#ffd400"],
            "line-width": ["case", ["==", ["get", "part"], true], 4, 3],
            "line-opacity": 0.95,
        },
    });
    map.addLayer({
        id: HIGHLIGHT_LAYERS[2],
        type: "circle",
        source: HIGHLIGHT_SOURCE,
        filter: ["==", ["geometry-type"], "Point"],
        paint: {
            "circle-radius": 7,
            "circle-color": "#ffd400",
            "circle-opacity": 0.6,
            "circle-stroke-width": 1.5,
            "circle-stroke-color": "#000000"
        },
    });
}

// Keeps the highlight on top after rebuildSource re-adds the base layers.
function raiseHighlightLayers(map: MapLibreMap): void {
    for (const id of HIGHLIGHT_LAYERS) map.moveLayer(id);
}

// Part/ring counts in MLT terms: a part is one member of a Multi* geometry
// (a line of a MultiLineString, a polygon of a MultiPolygon, a point of a
// MultiPoint); a plain single geometry is one part. Rings are counted over all
// polygon parts (outer + holes).
function describeGeometry(geometry: GeoJSON.Geometry): { type: string; parts: number; rings: number | null; vertices: number } {
    const vertices = countVertices(geometry);
    switch (geometry.type) {
        case "Point":
            return {type: geometry.type, parts: 1, rings: null, vertices};
        case "MultiPoint":
            return {type: geometry.type, parts: geometry.coordinates.length, rings: null, vertices};
        case "LineString":
            return {type: geometry.type, parts: 1, rings: null, vertices};
        case "MultiLineString":
            return {type: geometry.type, parts: geometry.coordinates.length, rings: null, vertices};
        case "Polygon":
            return {type: geometry.type, parts: 1, rings: geometry.coordinates.length, vertices};
        case "MultiPolygon":
            return {
                type: geometry.type,
                parts: geometry.coordinates.length,
                rings: geometry.coordinates.reduce((sum, poly) => sum + poly.length, 0),
                vertices,
            };
        default:
            return {type: geometry.type, parts: 0, rings: null, vertices};
    }
}

// Vertices a ring contributes to the MLT VertexBuffer. GeoJSON polygon rings
// repeat their first coordinate at the end, but MVT encodes that closure as
// ClosePath and the VertexBuffer has no vertex for it, so it is not counted.
function ringVertices(ring: number[][]): number {
    if (ring.length > 1) {
        const first = ring[0];
        const last = ring[ring.length - 1];
        if (first[0] === last[0] && first[1] === last[1]) return ring.length - 1;
    }
    return ring.length;
}

// VertexBuffer vertex count of a geometry (all parts, all rings), the same
// counting rule as data-analysis/vertex_stats.py and the composition drawer.
function countVertices(geometry: GeoJSON.Geometry): number {
    switch (geometry.type) {
        case "Point":
            return 1;
        case "MultiPoint":
        case "LineString":
            return geometry.coordinates.length;
        case "MultiLineString":
            return geometry.coordinates.reduce((sum, line) => sum + line.length, 0);
        case "Polygon":
            return geometry.coordinates.reduce((sum, ring) => sum + ringVertices(ring), 0);
        case "MultiPolygon":
            return geometry.coordinates.reduce((sum, poly) => sum + poly.reduce((s, ring) => s + ringVertices(ring), 0), 0);
        default:
            return 0;
    }
}

// Splits a geometry into its MLT parts, each as a single-part geometry: the
// members of a Multi* geometry, or the geometry itself when it is not one.
function partsOf(geometry: GeoJSON.Geometry): GeoJSON.Geometry[] {
    switch (geometry.type) {
        case "MultiPoint":
            return geometry.coordinates.map((coordinates): GeoJSON.Geometry => ({type: "Point", coordinates}));
        case "MultiLineString":
            return geometry.coordinates.map((coordinates): GeoJSON.Geometry => ({type: "LineString", coordinates}));
        case "MultiPolygon":
            return geometry.coordinates.map((coordinates): GeoJSON.Geometry => ({type: "Polygon", coordinates}));
        default:
            return [geometry];
    }
}

// Screen-space (pixel) distance from p to the segment a-b.
function segmentDistance(p: maplibregl.Point, a: maplibregl.Point, b: maplibregl.Point): number {
    const dx = b.x - a.x;
    const dy = b.y - a.y;
    const lengthSquared = dx * dx + dy * dy;
    const t = lengthSquared === 0 ? 0 : Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.y - a.y) * dy) / lengthSquared));
    return Math.hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy));
}

function pathDistance(path: maplibregl.Point[], p: maplibregl.Point): number {
    if (path.length === 1) return Math.hypot(p.x - path[0].x, p.y - path[0].y);
    let best = Infinity;
    for (let i = 0; i + 1 < path.length; i++) best = Math.min(best, segmentDistance(p, path[i], path[i + 1]));
    return best;
}

// Even-odd point-in-ring test in screen space.
function ringContains(ring: maplibregl.Point[], p: maplibregl.Point): boolean {
    let inside = false;
    for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
        const a = ring[i];
        const b = ring[j];
        if ((a.y > p.y) !== (b.y > p.y) && p.x < ((b.x - a.x) * (p.y - a.y)) / (b.y - a.y) + a.x) inside = !inside;
    }
    return inside;
}

// How far (in pixels) the cursor is from a single-part geometry: 0 when it is
// inside a polygon (outside its holes), otherwise the distance to the nearest
// edge/point. Used to pick which part of a Multi* feature is being hovered.
function partDistance(map: MapLibreMap, part: GeoJSON.Geometry, p: maplibregl.Point): number {
    const project = (c: number[]) => map.project(c as [number, number]);
    switch (part.type) {
        case "Point":
            return pathDistance([project(part.coordinates)], p);
        case "LineString":
            return pathDistance(part.coordinates.map(project), p);
        case "Polygon": {
            const rings = part.coordinates.map((ring) => ring.map(project));
            if (rings.length > 0 && ringContains(rings[0], p) && !rings.slice(1).some((hole) => ringContains(hole, p))) return 0;
            return Math.min(...rings.map((ring) => pathDistance(ring, p)));
        }
        default:
            return Infinity;
    }
}

interface HoveredPart {
    index: number;
    count: number;
    geometry: GeoJSON.Geometry;
    vertices: number;
}

// The part of the feature under the cursor. Single-part features skip the
// projection work: their only part is the feature itself.
function hoveredPart(map: MapLibreMap, geometry: GeoJSON.Geometry, p: maplibregl.Point): HoveredPart {
    const parts = partsOf(geometry);
    let index = 0;
    if (parts.length > 1) {
        let best = Infinity;
        parts.forEach((part, i) => {
            const distance = partDistance(map, part, p);
            if (distance < best) {
                best = distance;
                index = i;
            }
        });
    }
    return {index, count: parts.length, geometry: parts[index] ?? geometry, vertices: countVertices(parts[index] ?? geometry)};
}

// Hover tooltip: the vertices of the hovered part and, for multi-part
// features, of the whole feature as well.
function buildHoverContent(layer: LayerConfig, feature: maplibregl.MapGeoJSONFeature, part: HoveredPart, featureVertices: number): HTMLElement {
    const root = document.createElement("div");
    root.className = "feature-popup";

    const title = document.createElement("strong");
    // class == subclass is common (e.g. farmland / farmland): show it once.
    const type = [...new Set(typeRows(feature).map(([, value]) => value))].join(" / ");
    title.textContent = type ? `${layer.label}: ${type}` : layer.label;
    root.appendChild(title);

    const geometry = [`Geometry: ${feature.geometry.type}`];
    const lines = geometry.concat(part.count > 1 ? [`Part ${part.index + 1} of ${part.count}: ${part.vertices.toLocaleString("en-US")} vertices`, `Feature: ${featureVertices.toLocaleString("en-US")} vertices`] : [`Vertices: ${featureVertices.toLocaleString("en-US")}`]);
    for (const text of lines) {
        const line = document.createElement("div");
        line.textContent = text;
        root.appendChild(line);
    }
    return root;
}

// Type tags of a feature, coarse to fine: Overture tilesets tag `subtype` and
// `class`, OpenMapTiles-style tilesets (Planetiler) `class` and `subclass`
// (e.g. road class "minor"/"path", landcover class "wood"/"grass"). Layers or
// tilesets without them (e.g. Planetiler buildings) simply yield no rows.
const TYPE_PROPERTIES: [string, string][] = [["Subtype", "subtype"], ["Class", "class"], ["Subclass", "subclass"]];

function typeRows(feature: maplibregl.MapGeoJSONFeature): [string, string][] {
    const rows: [string, string][] = [];
    for (const [label, key] of TYPE_PROPERTIES) {
        const value = feature.properties?.[key];
        if (value !== undefined && value !== null && value !== "") rows.push([label, String(value)]);
    }
    return rows;
}

function buildPopupContent(layer: LayerConfig, feature: maplibregl.MapGeoJSONFeature): HTMLElement {
    const info = describeGeometry(feature.geometry);
    const root = document.createElement("div");
    root.className = "feature-popup";

    const title = document.createElement("strong");
    title.textContent = layer.label;
    root.appendChild(title);

    const rows: [string, string][] = [...typeRows(feature), ["Geometry", info.type], ["Parts", String(info.parts)], ["Vertices", info.vertices.toLocaleString("en-US")]];
    if (info.rings !== null) rows.push(["Rings", String(info.rings)]);
    if (feature.id !== undefined) rows.push(["Feature id", String(feature.id)]);
    for (const [key, value] of rows) {
        const line = document.createElement("div");
        line.textContent = `${key}: ${value}`;
        root.appendChild(line);
    }
    return root;
}

function setupFeatureInteraction(map: MapLibreMap): void {
    addHighlightLayers(map);
    const highlight = map.getSource(HIGHLIGHT_SOURCE) as maplibregl.GeoJSONSource;
    const popup = new maplibregl.Popup({closeButton: true, maxWidth: "260px"});
    // Non-interactive tooltip following the cursor (pointer-events: none in index.html).
    const tooltip = new maplibregl.Popup({closeButton: false, closeOnClick: false, maxWidth: "240px", offset: 14, className: "hover-tooltip"});
    let hoveredKey: string | null = null;

    // Layers currently inspectable: base layer shown and its vertices toggle on.
    // Listed topmost-first so the first query hit is the feature drawn on top.
    const activeLayers = (): LayerConfig[] => LAYERS.filter((layer) => map.getLayoutProperty(layer.id, "visibility") !== "none" && map.getLayoutProperty(layer.id + VERTEX_SUFFIX, "visibility") === "visible").reverse();

    const featureAt = (point: maplibregl.Point): { layer: LayerConfig; feature: maplibregl.MapGeoJSONFeature } | null => {
        const active = activeLayers();
        if (active.length === 0) return null;
        // Small pixel tolerance so thin lines and point layers are easy to hit.
        const box: [maplibregl.PointLike, maplibregl.PointLike] = [[point.x - 4, point.y - 4], [point.x + 4, point.y + 4]];
        const hits = map.queryRenderedFeatures(box, {layers: active.map((l) => l.id)});
        if (hits.length === 0) return null;
        const layer = active.find((l) => l.id === hits[0].layer.id)!;
        return {layer, feature: hits[0]};
    };

    const clearHover = () => {
        if (hoveredKey === null) return;
        hoveredKey = null;
        highlight.setData({type: "FeatureCollection", features: []});
        tooltip.remove();
        map.getCanvas().style.cursor = "";
    };

    map.on("mousemove", (e) => {
        const hit = featureAt(e.point);
        if (!hit) return clearHover();
        const part = hoveredPart(map, hit.feature.geometry, e.point);
        const key = `${hit.layer.id}:${hit.feature.id ?? geomKeyOf(hit.feature)}:${part.index}`;
        map.getCanvas().style.cursor = "pointer";
        tooltip.setLngLat(e.lngLat);
        if (key === hoveredKey) return;
        hoveredKey = key;
        // The whole feature is highlighted; for multi-part features the hovered
        // part is drawn on top in a second color, so the vertex count in the
        // tooltip visibly belongs to it.
        const features: GeoJSON.Feature[] = [{type: "Feature", properties: {part: false}, geometry: hit.feature.geometry}];
        if (part.count > 1) features.push({type: "Feature", properties: {part: true}, geometry: part.geometry});
        highlight.setData({type: "FeatureCollection", features});
        tooltip.setDOMContent(buildHoverContent(hit.layer, hit.feature, part, countVertices(hit.feature.geometry)));
        if (!tooltip.isOpen()) tooltip.addTo(map);
    });
    map.on("mouseout", clearHover);

    map.on("click", (e) => {
        const hit = featureAt(e.point);
        if (!hit) return;
        popup.setLngLat(e.lngLat).setDOMContent(buildPopupContent(hit.layer, hit.feature)).addTo(map);
    });
}

async function main(): Promise<void> {
    const dataset = currentDataset();
    const {center, zoom} = await loadCenter(dataset);

    const map = new maplibregl.Map({
        container: "map", style: {
            version: 8, sources: {
                [SOURCE_ID]: {
                    type: "vector",
                    tiles: [`${window.location.origin}/tiles/{z}/{x}/{y}.pbf?dataset=${dataset}`],
                    minzoom: 0,
                    maxzoom: 14,
                },
            }, layers: [{id: "background", type: "background", paint: {"background-color": "#eef2f5"}}],
        }, center, zoom,
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
        addLabelLayers(map, "visible");
        for (const layer of LAYERS) {
            addVertexLayer(map, layer);
            addCloseArrowLayer(map, layer);
            addReorderArrowLayer(map, layer);
        }
        buildDatasetSelect(dataset);
        buildCompositionDrawer(map, dataset, DATASETS.find((d) => d.id === dataset)!.label, LAYERS.map((l) => ({
            sourceLayer: l.sourceLayer, label: l.label, color: l.color
        })));
        buildTileBoundaryToggle(map);
        buildZoomLockToggle(map, dataset);
        buildLayerToggles(map);
        buildLabelToggle(map);
        setupFeatureInteraction(map);

        const refreshVisibleVertexLayers = () => {
            for (const layer of LAYERS) {
                if (map.getLayoutProperty(layer.id + VERTEX_SUFFIX, "visibility") === "visible") {
                    refreshGeomKeys(map, layer);
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
        map.on("sourcedata", (e) => {
            if (e.sourceId === SOURCE_ID && e.isSourceLoaded) refreshVisibleVertexLayers();
        });
    });
}

main();
