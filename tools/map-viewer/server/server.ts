import path from "node:path";
import Database from "better-sqlite3";
import express from "express";

/**
 * Serves the thesis mbtiles source data (data/MVT/luxembourg.mbtiles) as a plain
 * XYZ vector tile endpoint for MapLibre GL, plus the static viewer page.
 *
 * MBTiles stores `tile_row` in TMS order (origin at the bottom); this flips it
 * to XYZ order (origin at the top) on read, matching what MapLibre/most tile
 * clients expect - the same conversion `MbtilesReader` does on the Java side.
 */

const PORT = Number(process.env.PORT) || 5173;
const MBTILES_PATH = path.resolve(__dirname, "..", "..", "..", "data", "MVT", "munich.mbtiles");
const PUBLIC_DIR = path.resolve(__dirname, "..", "public");

const db = new Database(MBTILES_PATH, { readonly: true, fileMustExist: true });

const tileStatement = db.prepare(
  "SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?",
);

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

  const tmsRow = xyzRowToTms(z, y);
  const row = tileStatement.get(z, x, tmsRow) as { tile_data: Buffer } | undefined;
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

app.get("/metadata.json", (_req, res) => {
  const rows = db.prepare("SELECT name, value FROM metadata").all() as {
    name: string;
    value: string;
  }[];
  const metadata = Object.fromEntries(rows.map((r) => [r.name, r.value]));
  res.json(metadata);
});

app.use(express.static(PUBLIC_DIR));

app.listen(PORT, () => {
  console.log(`MVT map viewer running at http://localhost:${PORT}`);
  console.log(`Serving tiles from ${MBTILES_PATH}`);
});
