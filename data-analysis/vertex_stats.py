"""
vertex_stats — Vertex-Statistik je Layer eines MVT-Tilesets erfassen.

    python vertex_stats.py <tileset> [--force]

<tileset> ist der Dateiname ohne Endung in data/MVT/, z.B. `munich-de-rs`.
Ergebnis wird nach out/<tileset>.vertices.json zwischengespeichert; grosse
Tilesets laufen lange, deshalb wird nur bei fehlendem Cache oder --force neu
gescannt.

Gezaehlt wird je Layer, wie viele Vertices der MLT-VertexBuffer je Feature
enthaelt (Anzahl, Mittelwert, Median, p90, p99, Maximum). Ein "Vertex" ist
hier exakt das, was VertexBufferExtractor.java in den VertexBuffer schreibt:
alle vom Command-Stream erzeugten Koordinaten, aber ohne den Ringschluss-
vertex von ClosePath (der in MVT ohnehin nicht kodiert ist).
"""

from __future__ import annotations

import collections
import json
import os
import sqlite3
import sys
import time

import numpy as np

from mvt_stats import decompress, iter_fields, read_varint

_HERE = os.path.dirname(os.path.abspath(__file__))
MVT_DIR = os.path.join(_HERE, "..", "data", "MVT")
OUT_DIR = os.path.join(_HERE, "out")


def count_vertices(buf: bytes, start: int, end: int) -> int:
    """Zaehlt die vom Command-Stream in buf[start:end] erzeugten Vertices.

    MoveTo/LineTo liefern je `count` Vertices, ClosePath keinen (kein
    Ringschlussvertex im Buffer). Parameter werden ueberschlagen, nicht
    dekodiert.
    """
    i, verts = start, 0
    while i < end:
        cmd, i = read_varint(buf, i)
        cmd_id, count = cmd & 0x7, cmd >> 3
        if cmd_id == 7:  # ClosePath — kein Vertex
            continue
        if cmd_id not in (1, 2):  # MoveTo, LineTo
            raise ValueError(f"Unsupported command id {cmd_id}")
        verts += count
        for _ in range(2 * count):
            _, i = read_varint(buf, i)
    return verts


def scan_tile(buf: bytes, layer_hist: dict[str, collections.Counter],
              skipped_empty: list[int]) -> None:
    for field_no, _, val_start, val_end, _ in iter_fields(buf, 0, len(buf)):
        if field_no != 3:  # tile.layers
            continue
        name = None
        counts = []
        for f2, _, p_start, p_end, _ in iter_fields(buf, val_start, val_end):
            if f2 == 1:  # layer.name
                name = buf[p_start:p_end].decode("utf-8")
            elif f2 == 2:  # layer.features
                verts = 0
                for f3, _, q_start, q_end, _ in iter_fields(buf, p_start, p_end):
                    if f3 == 4:  # feature.geometry
                        verts += count_vertices(buf, q_start, q_end)
                if verts == 0:
                    skipped_empty[0] += 1
                    continue
                counts.append(verts)
        if name is None:
            continue
        hist = layer_hist.setdefault(name, collections.Counter())
        hist.update(counts)


def percentile_from_hist(hist: collections.Counter, q: float) -> float:
    values = sorted(hist)
    total = sum(hist.values())
    target = q / 100 * (total - 1)
    cum = 0
    for v in values:
        cum += hist[v]
        if cum > target:
            return float(v)
    return float(values[-1])


def summarize(hist: collections.Counter) -> dict:
    values = np.array(sorted(hist), dtype=np.int64)
    counts = np.array([hist[v] for v in values], dtype=np.int64)
    total_features = int(counts.sum())
    total_vertices = int((values * counts).sum())
    mean = total_vertices / total_features
    return {
        "features": total_features,
        "vertices": total_vertices,
        "mean": round(mean, 2),
        "median": percentile_from_hist(hist, 50),
        "p90": percentile_from_hist(hist, 90),
        "p99": percentile_from_hist(hist, 99),
        "max": int(values[-1]),
    }


def scan(tileset: str) -> dict:
    path = os.path.join(MVT_DIR, f"{tileset}.mbtiles")
    if not os.path.exists(path):
        raise SystemExit(f"No such tileset: {path}")

    conn = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    layer_hist: dict[str, collections.Counter] = {}
    skipped_empty = [0]
    tiles = failed = 0
    started = time.time()

    for (raw,) in conn.execute("select tile_data from tiles"):
        try:
            buf = decompress(raw)
            scan_tile(buf, layer_hist, skipped_empty)
        except Exception:
            failed += 1
            continue
        tiles += 1
        if tiles % 20000 == 0:
            rate = tiles / (time.time() - started)
            print(f"  {tiles} tiles ({rate:.0f}/s)", flush=True)
    conn.close()

    layers = {name: summarize(hist) for name, hist in layer_hist.items()}
    layers = dict(sorted(layers.items(), key=lambda kv: -kv[1]["vertices"]))

    return {
        "tileset": tileset,
        "tiles": tiles,
        "failed": failed,
        "skipped_empty": skipped_empty[0],
        "layers": layers,
    }


def main() -> None:
    args = [a for a in sys.argv[1:] if a != "--force"]
    force = "--force" in sys.argv[1:]
    if len(args) != 1:
        raise SystemExit(__doc__)
    tileset = args[0]

    os.makedirs(OUT_DIR, exist_ok=True)
    out_path = os.path.join(OUT_DIR, f"{tileset}.vertices.json")
    if os.path.exists(out_path) and not force:
        print(f"{out_path} exists — use --force to rescan")
        return

    started = time.time()
    stats = scan(tileset)
    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump(stats, fh, indent=1)

    total_vertices = sum(l["vertices"] for l in stats["layers"].values())
    print(f"{tileset}: {stats['tiles']} tiles, {stats['failed']} failed, "
          f"{stats['skipped_empty']} skipped empty, {total_vertices} vertices "
          f"[{time.time() - started:.1f}s] -> {out_path}")


if __name__ == "__main__":
    main()
