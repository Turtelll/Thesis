import fs from "node:fs";
import path from "node:path";
import {Worker} from "node:worker_threads";
import Database from "better-sqlite3";
import express from "express";
import {type Aggregates, availableZooms, CACHE_VERSION, type CacheFile, deserialize, summarize} from "./composition";
import type {WorkerInput} from "./compositionWorker";

/**
 * Serves the thesis mbtiles source data (data/MVT/*.mbtiles) as a plain
 * XYZ vector tile endpoint for MapLibre GL, plus the static viewer page.
 *
 * MBTiles stores `tile_row` in TMS order (origin at the bottom); this flips it
 * to XYZ order (origin at the top) on read, matching what MapLibre/most tile
 * clients expect - the same conversion `MbtilesReader` does on the Java side.
 */

const PORT = Number(process.env.PORT) || 5173;
const MVT_DIR = path.resolve(__dirname, "..", "..", "..", "data", "MVT");
const PUBLIC_DIR = path.resolve(__dirname, "..", "public");

const DATASETS: Record<string, string> = {
    "bayern": path.join(MVT_DIR, "bayern.mbtiles"),
    "munich-plantiler": path.join(MVT_DIR, "munich-plantiler.mbtiles"),
    "germany-rs": path.join(MVT_DIR, "germany-rs.mbtiles"),
    "munich-overture": path.join(MVT_DIR, "munich-overture.mbtiles"),
    "chicago-25km": path.join(MVT_DIR, "chicago-25km.mbtiles"),
    "tokyo-25km": path.join(MVT_DIR, "tokyo-25km.mbtiles"),
    "sao-paulo-25km": path.join(MVT_DIR, "sao-paulo-25km.mbtiles"),
};
const DEFAULT_DATASET = "munich-plantiler";
// Composition scan results (see composition.ts), one JSON file per dataset.
const CACHE_DIR = path.resolve(__dirname, "..", "cache");

const dbs = Object.fromEntries(Object.entries(DATASETS).map(([id, file]) => [id, new Database(file, {
    readonly: true,
    fileMustExist: true
}),]),);

function datasetId(req: express.Request): string {
    return typeof req.query.dataset === "string" ? req.query.dataset : DEFAULT_DATASET;
}

function resolveDataset(req: express.Request): Database.Database | undefined {
    return dbs[datasetId(req)];
}

function xyzRowToTms(z: number, xyzRow: number): number {
    return (1 << z) - 1 - xyzRow;
}

function isGzip(buffer: Buffer): boolean {
    return buffer.length >= 2 && buffer[0] === 0x1f && buffer[1] === 0x8b;
}

const app = express();

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
        .get(z, x, tmsRow) as { tile_data: Buffer } | undefined;
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
    const rows = db.prepare("SELECT name, value FROM metadata").all() as {
        name: string; value: string;
    }[];
    const metadata = Object.fromEntries(rows.map((r) => [r.name, r.value]));
    res.json(metadata);
});

// --- layer composition (see composition.ts) ----------------------------------

interface ScanState {
    done: number;
    total: number;
}

const scans = new Map<string, ScanState>();
const scanErrors = new Map<string, string>();
const loaded = new Map<string, Aggregates>();

function isDataset(id: string): boolean {
    return Object.prototype.hasOwnProperty.call(DATASETS, id);
}

function cacheFileOf(id: string): string {
    return path.join(CACHE_DIR, `${id}.composition.json`);
}

/** Aggregates from memory or the cache file, or null if missing/stale/unreadable. */
function loadAggregates(id: string): Aggregates | null {
    const inMemory = loaded.get(id);
    if (inMemory) return inMemory;
    const file = cacheFileOf(id);
    if (!fs.existsSync(file)) return null;
    try {
        const cache = JSON.parse(fs.readFileSync(file, "utf-8")) as CacheFile;
        const source = fs.statSync(DATASETS[id]);
        if (cache.version !== CACHE_VERSION || cache.sourceSize !== source.size || cache.sourceMtimeMs !== source.mtimeMs) {
            return null;
        }
        const aggregates = deserialize(cache);
        loaded.set(id, aggregates);
        return aggregates;
    } catch {
        return null;
    }
}

function startScan(id: string): ScanState {
    const state: ScanState = {done: 0, total: 0};
    scans.set(id, state);

    const input: WorkerInput = {tileset: id, mbtilesFile: DATASETS[id], cacheFile: cacheFileOf(id)};
    const worker = new Worker(path.join(__dirname, "compositionWorker.js"), {workerData: input});
    let finished = false;
    const finish = (error?: string) => {
        if (finished) return;
        finished = true;
        scans.delete(id);
        if (error) scanErrors.set(id, error);
    };
    worker.on("message", (message: { type: string; done?: number; total?: number; message?: string }) => {
        if (message.type === "progress") {
            state.done = message.done ?? 0;
            state.total = message.total ?? 0;
        } else if (message.type === "done") {
            finish();
        } else if (message.type === "error") {
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
        res.json({status: "ready", zooms: availableZooms(aggregates), summary: summarize(aggregates, id, zoom)});
        return;
    }
    const error = scanErrors.get(id);
    if (error) {
        res.json({status: "error", message: error});
        return;
    }
    const scan = scans.get(id) ?? startScan(id);
    res.json({status: "scanning", done: scan.done, total: scan.total});
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
        fs.rmSync(cacheFileOf(id), {force: true});
        scan = startScan(id);
    }
    res.json({status: "scanning", done: scan.done, total: scan.total});
});

app.use(express.static(PUBLIC_DIR));

app.listen(PORT, () => {
    console.log(`MVT map viewer running at http://localhost:${PORT}`);
    console.log(`Serving datasets from ${MVT_DIR}: ${Object.keys(DATASETS).join(", ")}`);
});
