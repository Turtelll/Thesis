"""
mvt_stats — Byte-Zusammensetzung eines MVT-Tilesets erfassen.

    python mvt_stats.py <tileset> [--force]

<tileset> ist der Dateiname ohne Endung in data/MVT/, z.B. `munich-de-rs`.
Ergebnis wird nach out/<tileset>.json zwischengespeichert; grosse Tilesets
laufen 40-60 min, deshalb wird nur bei fehlendem Cache oder --force neu
gescannt.

Gezaehlt werden Geometriebytes (protobuf feature.geometry, Feld 4) je Layer
und je Geometrietyp, bezogen auf die Gesamtgroesse der entpackten Tiles.
Der Rest sind Tags/Keys/Values/Metadaten.
"""

from __future__ import annotations

import collections
import gzip
import json
import os
import sqlite3
import sys
import time
import zlib

_HERE = os.path.dirname(os.path.abspath(__file__))
MVT_DIR = os.path.join(_HERE, "..", "data", "MVT")
OUT_DIR = os.path.join(_HERE, "out")


def read_varint(buf: bytes, i: int) -> tuple[int, int]:
    result = shift = 0
    while True:
        byte = buf[i]
        i += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, i
        shift += 7


def iter_fields(buf: bytes, start: int, end: int):
    """Laeuft die protobuf-Felder in buf[start:end] ab.

    Liefert (field_no, key_start, val_start, val_end, field_end) — damit sind
    sowohl die Nutzlast als auch die Gesamtgroesse des Feldes adressierbar.
    """
    i = start
    while i < end:
        key_start = i
        key, i = read_varint(buf, i)
        field_no, wire_type = key >> 3, key & 7
        if wire_type == 2:  # length-delimited
            length, i = read_varint(buf, i)
            val_start, val_end = i, i + length
            i = val_end
        elif wire_type == 0:  # varint
            val_start = i
            _, i = read_varint(buf, i)
            val_end = i
        elif wire_type == 5:  # 32-bit
            val_start, i = i, i + 4
            val_end = i
        elif wire_type == 1:  # 64-bit
            val_start, i = i, i + 8
            val_end = i
        else:
            raise ValueError(f"Unsupported wire type {wire_type}")
        yield field_no, key_start, val_start, val_end, i


def decompress(raw: bytes) -> bytes:
    """Gzip/zlib erkennen und auspacken, sonst unveraendert — wie MbtilesSource."""
    if raw[:2] == b"\x1f\x8b":
        return gzip.decompress(raw)
    if raw[:1] == b"\x78":
        return zlib.decompress(raw)
    return raw


def scan_tile(buf: bytes, layer_bytes: collections.Counter,
              gtype_bytes: collections.Counter) -> None:
    for field_no, _, val_start, val_end, _ in iter_fields(buf, 0, len(buf)):
        if field_no != 3:  # tile.layers
            continue
        name = None
        geom_total = 0
        per_type: collections.Counter = collections.Counter()
        for f2, _, p_start, p_end, _ in iter_fields(buf, val_start, val_end):
            if f2 == 1:  # layer.name
                name = buf[p_start:p_end].decode("utf-8")
            elif f2 == 2:  # layer.features
                gtype = 0
                geom = 0
                for f3, k3, q_start, _, f3_end in iter_fields(buf, p_start, p_end):
                    if f3 == 3:  # feature.type
                        gtype, _ = read_varint(buf, q_start)
                    elif f3 == 4:  # feature.geometry
                        geom += f3_end - k3
                geom_total += geom
                per_type[gtype] += geom
        layer_bytes[name] += geom_total
        gtype_bytes.update(per_type)


def scan(tileset: str) -> dict:
    path = os.path.join(MVT_DIR, f"{tileset}.mbtiles")
    if not os.path.exists(path):
        raise SystemExit(f"No such tileset: {path}")

    conn = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    layer_bytes: collections.Counter = collections.Counter()
    gtype_bytes: collections.Counter = collections.Counter()
    total = tiles = failed = 0
    started = time.time()

    for (raw,) in conn.execute("select tile_data from tiles"):
        try:
            buf = decompress(raw)
            scan_tile(buf, layer_bytes, gtype_bytes)
        except Exception:
            failed += 1
            continue
        total += len(buf)
        tiles += 1
        if tiles % 20000 == 0:
            rate = tiles / (time.time() - started)
            print(f"  {tiles} tiles ({rate:.0f}/s)", flush=True)
    conn.close()

    return {
        "tileset": tileset,
        "tiles": tiles,
        "failed": failed,
        "total_bytes": total,
        "layer_geom_bytes": dict(layer_bytes.most_common()),
        "gtype_geom_bytes": {str(k): v for k, v in sorted(gtype_bytes.items())},
    }


def main() -> None:
    args = [a for a in sys.argv[1:] if a != "--force"]
    force = "--force" in sys.argv[1:]
    if len(args) != 1:
        raise SystemExit(__doc__)
    tileset = args[0]

    os.makedirs(OUT_DIR, exist_ok=True)
    out_path = os.path.join(OUT_DIR, f"{tileset}.json")
    if os.path.exists(out_path) and not force:
        print(f"{out_path} exists — use --force to rescan")
        return

    started = time.time()
    stats = scan(tileset)
    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump(stats, fh, indent=1)

    geom = sum(stats["gtype_geom_bytes"].values())
    total = stats["total_bytes"]
    print(f"{tileset}: {stats['tiles']} tiles, {stats['failed']} failed, "
          f"{total} B total, geometry {100 * geom / total:.1f}% "
          f"[{time.time() - started:.1f}s] -> {out_path}")


if __name__ == "__main__":
    main()
