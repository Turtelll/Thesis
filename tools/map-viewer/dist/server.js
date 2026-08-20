"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const node_path_1 = __importDefault(require("node:path"));
const better_sqlite3_1 = __importDefault(require("better-sqlite3"));
const express_1 = __importDefault(require("express"));
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
    munich: node_path_1.default.join(MVT_DIR, "munich.mbtiles"),
    ny: node_path_1.default.join(MVT_DIR, "NY.mbtiles"),
};
const DEFAULT_DATASET = "munich";
const dbs = Object.fromEntries(Object.entries(DATASETS).map(([id, file]) => [
    id,
    new better_sqlite3_1.default(file, { readonly: true, fileMustExist: true }),
]));
function resolveDataset(req) {
    const id = typeof req.query.dataset === "string" ? req.query.dataset : DEFAULT_DATASET;
    return dbs[id];
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
app.use(express_1.default.static(PUBLIC_DIR));
app.listen(PORT, () => {
    console.log(`MVT map viewer running at http://localhost:${PORT}`);
    console.log(`Serving datasets from ${MVT_DIR}: ${Object.keys(DATASETS).join(", ")}`);
});
