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

// Suffix for the companion line layer that traces every polygon ring, so the
// individual vertices/edges of a fill layer stay visible where fills overlap.
const OUTLINE_SUFFIX = "-outline";

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

async function loadCenter(): Promise<{ center: [number, number]; zoom: number }> {
  try {
    const response = await fetch("/metadata.json");
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
  }
}

async function main(): Promise<void> {
  const { center, zoom } = await loadCenter();

  const map = new maplibregl.Map({
    container: "map",
    style: {
      version: 8,
      sources: {
        [SOURCE_ID]: {
          type: "vector",
          tiles: [`${window.location.origin}/tiles/{z}/{x}/{y}.pbf`],
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
    buildLayerToggles(map);
  });
}

main();
