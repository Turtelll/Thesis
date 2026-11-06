"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const node_fs_1 = __importDefault(require("node:fs"));
const node_path_1 = __importDefault(require("node:path"));
const node_worker_threads_1 = require("node:worker_threads");
const better_sqlite3_1 = __importDefault(require("better-sqlite3"));
const express_1 = __importDefault(require("express"));
const composition_1 = require("./composition");
/**
 * Serves the thesis mbtiles source data (data/MVT/*.mbtiles) as a plain
 * XYZ vector tile endpoint for MapLibre GL, plus the static viewer page.
 *
 * MBTiles stores `tile_row` in TMS order (origin at the bottom); this flips it
 * to XYZ order (origin at the top) on read, matching what MapLibre/most tile
 * clients expect - the same conversion `MbtilesReader` does on the Java side.
 */
const PORT = Number(process.env.PORT) || 5173;
const MVT_DIR = node_path_1.default.resolve(__dirname, "..", "..", "..", "data", "MVT");
const PUBLIC_DIR = node_path_1.default.resolve(__dirname, "..", "public");
const DATASETS = {
    "bayern": node_path_1.default.join(MVT_DIR, "bayern.mbtiles"),
    "munich-plantiler": node_path_1.default.join(MVT_DIR, "munich-plantiler.mbtiles"),
    "germany-rs": node_path_1.default.join(MVT_DIR, "germany-rs.mbtiles"),
    "munich-overture": node_path_1.default.join(MVT_DIR, "munich-overture.mbtiles"),
    "chicago-25km": node_path_1.default.join(MVT_DIR, "chicago-25km.mbtiles"),
    "tokyo-25km": node_path_1.default.join(MVT_DIR, "tokyo-25km.mbtiles"),
    "sao-paulo-25km": node_path_1.default.join(MVT_DIR, "sao-paulo-25km.mbtiles"),
};
const DEFAULT_DATASET = "munich-plantiler";
// Composition scan results (see composition.ts), one JSON file per dataset.
const CACHE_DIR = node_path_1.default.resolve(__dirname, "..", "cache");
const dbs = Object.fromEntries(Object.entries(DATASETS).map(([id, file]) => [id, new better_sqlite3_1.default(file, {
        readonly: true,
        fileMustExist: true
    }),]));
function datasetId(req) {
    return typeof req.query.dataset === "string" ? req.query.dataset : DEFAULT_DATASET;
}
function resolveDataset(req) {
    return dbs[datasetId(req)];
}
function xyzRowToTms(z, xyzRow) {
    return (1 << z) - 1 - xyzRow;
}
function isGzip(buffer) {
    return buffer.length >= 2 && buffer[0] === 0x1f && buffer[1] === 0x8b;
}
const app = (0, express_1.default)();
app.get("/tiles/:z/:x/:y.pbf", (req, res) => {
    const z = Number(req.params.z);
    const x = Number(req.params.x);
    const y = Number(req.params.y);
    if (![z, x, y].every(Number.isInteger)) {
        res.status(400).send("Invalid tile coordinates");
        return;
    }
    const db = resolveDataset(req);
    if (!db) {
        res.status(400).send("Unknown dataset");
        return;
    }
    const tmsRow = xyzRowToTms(z, y);
    const row = db
        .prepare("SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?")
        .get(z, x, tmsRow);
    if (!row) {
        res.status(204).end();
        return;
    }
    const data = row.tile_data;
    res.setHeader("Content-Type", "application/x-protobuf");
    if (isGzip(data)) {
        // MapLibre decodes MVT itself; it doesn't expect a gzip-wrapped body unless
        // Content-Encoding says so, so pass it through as an actual HTTP encoding.
        res.setHeader("Content-Encoding", "gzip");
    }
    res.send(data);
});
app.get("/metadata.json", (req, res) => {
    const db = resolveDataset(req);
    if (!db) {
        res.status(400).send("Unknown dataset");
        return;
    }
    const rows = db.prepare("SELECT name, value FROM metadata").all();
    const metadata = Object.fromEntries(rows.map((r) => [r.name, r.value]));
    res.json(metadata);
});
const scans = new Map();
const scanErrors = new Map();
const loaded = new Map();
function isDataset(id) {
    return Object.prototype.hasOwnProperty.call(DATASETS, id);
}
function cacheFileOf(id) {
    return node_path_1.default.join(CACHE_DIR, `${id}.composition.json`);
}
/** Aggregates from memory or the cache file, or null if missing/stale/unreadable. */
function loadAggregates(id) {
    const inMemory = loaded.get(id);
    if (inMemory)
        return inMemory;
    const file = cacheFileOf(id);
    if (!node_fs_1.default.existsSync(file))
        return null;
    try {
        const cache = JSON.parse(node_fs_1.default.readFileSync(file, "utf-8"));
        const source = node_fs_1.default.statSync(DATASETS[id]);
        if (cache.version !== composition_1.CACHE_VERSION || cache.sourceSize !== source.size || cache.sourceMtimeMs !== source.mtimeMs) {
            return null;
        }
        const aggregates = (0, composition_1.deserialize)(cache);
        loaded.set(id, aggregates);
        return aggregates;
    }
    catch {
        return null;
    }
}
function startScan(id) {
    const state = { done: 0, total: 0 };
    scans.set(id, state);
    const input = { tileset: id, mbtilesFile: DATASETS[id], cacheFile: cacheFileOf(id) };
    const worker = new node_worker_threads_1.Worker(node_path_1.default.join(__dirname, "compositionWorker.js"), { workerData: input });
    let finished = false;
    const finish = (error) => {
        if (finished)
            return;
        finished = true;
        scans.delete(id);
        if (error)
            scanErrors.set(id, error);
    };
    worker.on("message", (message) => {
        if (message.type === "progress") {
            state.done = message.done ?? 0;
            state.total = message.total ?? 0;
        }
        else if (message.type === "done") {
            finish();
        }
        else if (message.type === "error") {
            finish(message.message ?? "scan failed");
        }
    });
    worker.on("error", (error) => finish(error.message));
    worker.on("exit", (code) => finish(code === 0 ? "scan worker exited without a result" : `scan worker exited with code ${code}`));
    console.log(`Composition scan started: ${id}`);
    return state;
}
app.get("/composition.json", (req, res) => {
    const id = datasetId(req);
    if (!isDataset(id)) {
        res.status(400).send("Unknown dataset");
        return;
    }
    const zoomParam = typeof req.query.zoom === "string" ? req.query.zoom : "all";
    const zoom = zoomParam === "all" ? "all" : Number(zoomParam);
    if (zoom !== "all" && !Number.isInteger(zoom)) {
        res.status(400).send("Invalid zoom");
        return;
    }
    const aggregates = loadAggregates(id);
    if (aggregates) {
        res.json({ status: "ready", zooms: (0, composition_1.availableZooms)(aggregates), summary: (0, composition_1.summarize)(aggregates, id, zoom) });
        return;
    }
    const error = scanErrors.get(id);
    if (error) {
        res.json({ status: "error", message: error });
        return;
    }
    const scan = scans.get(id) ?? startScan(id);
    res.json({ status: "scanning", done: scan.done, total: scan.total });
});
app.post("/composition/rescan", (req, res) => {
    const id = datasetId(req);
    if (!isDataset(id)) {
        res.status(400).send("Unknown dataset");
        return;
    }
    let scan = scans.get(id);
    if (!scan) {
        loaded.delete(id);
        scanErrors.delete(id);
        node_fs_1.default.rmSync(cacheFileOf(id), { force: true });
        scan = startScan(id);
    }
    res.json({ status: "scanning", done: scan.done, total: scan.total });
});
app.use(express_1.default.static(PUBLIC_DIR));
app.listen(PORT, () => {
    console.log(`MVT map viewer running at http://localhost:${PORT}`);
    console.log(`Serving datasets from ${MVT_DIR}: ${Object.keys(DATASETS).join(", ")}`);
});
