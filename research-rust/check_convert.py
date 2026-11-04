"""Checks an .mbtiles written by `mlt convert --vertex-pipeline oracle` against `mlt-oracle compare --write-tiles`.

    python check_convert.py <converted.mbtiles> <tiles-dir> [--subset <source.mbtiles> <out.mbtiles> <n>]

Every tile of the converted archive must equal <tiles-dir>/<z>_<x>_<y>.oracle.mlt byte for byte; the script also sums
the gzip level 6 sizes of the converted tiles (the `compare` figure). With --subset it first writes the first <n>
tiles of the source (the order of `compare --limit-tiles`) to a new archive, to convert instead of the whole tileset.
"""
import os
import sqlite3
import sys
import zlib


def gzip_len(data: bytes) -> int:
    compressor = zlib.compressobj(6, zlib.DEFLATED, 31)
    return len(compressor.compress(data) + compressor.flush())


def subset(source: str, out: str, n: int) -> None:
    if os.path.exists(out):
        os.remove(out)
    src = sqlite3.connect(f"file:{source}?mode=ro", uri=True)
    dst = sqlite3.connect(out)
    # a flat schema, whatever the source's (the source's `tiles` may be a view)
    dst.execute("CREATE TABLE metadata (name TEXT, value TEXT, PRIMARY KEY (name))")
    dst.execute(
        "CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB, "
        "PRIMARY KEY (zoom_level, tile_column, tile_row))"
    )
    dst.executemany("INSERT INTO metadata VALUES (?, ?)", src.execute("SELECT name, value FROM metadata"))
    rows = src.execute(
        "SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles ORDER BY zoom_level, tile_column, tile_row LIMIT ?",
        (n,),
    )
    dst.executemany("INSERT INTO tiles VALUES (?, ?, ?, ?)", rows)
    dst.commit()
    print(f"{out}: {dst.execute('SELECT COUNT(*) FROM tiles').fetchone()[0]} tiles")


def check(converted: str, tiles_dir: str) -> int:
    db = sqlite3.connect(f"file:{converted}?mode=ro", uri=True)
    total = 0
    mismatches = 0
    count = 0
    for z, x, tms, data in db.execute("SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles"):
        y = (1 << z) - 1 - tms
        path = os.path.join(tiles_dir, f"{z}_{x}_{y}.oracle.mlt")
        expected = open(path, "rb").read()
        count += 1
        total += gzip_len(data)
        if data != expected:
            mismatches += 1
            print(f"MISMATCH {z}/{x}/{y}: {len(data)} bytes, expected {len(expected)}")
    print(f"{count} tiles, {mismatches} differ from {tiles_dir}; gzip -6 total {total}")
    return mismatches


def total(converted: str) -> None:
    db = sqlite3.connect(f"file:{converted}?mode=ro", uri=True)
    raw = gz = count = 0
    for (data,) in db.execute("SELECT tile_data FROM tiles"):
        raw += len(data)
        gz += gzip_len(data)
        count += 1
    print(f"{converted}: {count} tiles, raw {raw}, gzip -6 {gz}")


if __name__ == "__main__":
    args = sys.argv[1:]
    if args and args[0] == "--sum":
        total(args[1])
    elif args and args[0] == "--subset":
        subset(args[1], args[2], int(args[3]))
    elif len(args) == 2:
        sys.exit(1 if check(args[0], args[1]) else 0)
    else:
        sys.exit(__doc__)
