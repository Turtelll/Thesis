"""Checks an .mbtiles written by `mlt convert --vertex-pipeline oracle` against `mlt-oracle compare --write-tiles`.

    python check_convert.py <converted.mbtiles> <tiles-dir> [--subset <source.mbtiles> <out.mbtiles> <n>]
    python check_convert.py --sum <converted.mbtiles>
    python check_convert.py --same <a.mbtiles> <b.mbtiles>

Every tile of the converted archive must equal <tiles-dir>/<z>_<x>_<y>.oracle.mlt byte for byte; the script also sums
the gzip level 6 sizes of the converted tiles (the `compare` figure). With --subset it first writes the first <n>
tiles of the source (the order of `compare --limit-tiles`) to a new archive, to convert instead of the whole tileset.

Archives written with `mlt convert --gzip|--brotli|--zstd` hold compressed tiles and a `compression` metadata row
(`gzip`, `br`, `zstd`); every command here decompresses them first, so the sizes stay the MLT tile's own. `--sum`
prints the stored (compressed) bytes beside them. `--same` checks that two archives hold the same tiles after
decompression (for example the same conversion with and without a compression flag). Brotli needs `pip install
brotli`, zstd `pip install zstandard`.
"""
import os
import sqlite3
import sys
import zlib


def gzip_len(data: bytes) -> int:
    compressor = zlib.compressobj(6, zlib.DEFLATED, 31)
    return len(compressor.compress(data) + compressor.flush())


def tile_compression(db: sqlite3.Connection) -> str | None:
    """The `compression` metadata row of a converted archive, `None` for plain tiles."""
    row = db.execute("SELECT value FROM metadata WHERE name = 'compression'").fetchone()
    return row[0] if row else None


def decompress(data: bytes, compression: str | None) -> bytes:
    if compression is None:
        return data
    if compression == "gzip":
        return zlib.decompress(data, 31)
    if compression == "br":
        import brotli

        return brotli.decompress(data)
    if compression == "zstd":
        import zstandard

        # the tile's frame carries no content size
        return zstandard.ZstdDecompressor().decompressobj().decompress(data)
    sys.exit(f"unknown tile compression {compression!r}")


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
    compression = tile_compression(db)
    total = 0
    mismatches = 0
    count = 0
    for z, x, tms, data in db.execute("SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles"):
        data = decompress(data, compression)
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
    compression = tile_compression(db)
    raw = gz = stored = count = 0
    for (data,) in db.execute("SELECT tile_data FROM tiles"):
        stored += len(data)
        data = decompress(data, compression)
        raw += len(data)
        gz += gzip_len(data)
        count += 1
    stored_note = f", stored {stored} ({compression})" if compression else ""
    print(f"{converted}: {count} tiles, raw {raw}, gzip -6 {gz}{stored_note}")


def same(a: str, b: str) -> int:
    """Whether two archives hold the same tiles once decompressed."""
    dbs = [sqlite3.connect(f"file:{path}?mode=ro", uri=True) for path in (a, b)]
    query = "SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles ORDER BY 1, 2, 3"
    tiles = []
    for db in dbs:
        compression = tile_compression(db)
        tiles.append({(z, x, y): decompress(d, compression) for z, x, y, d in db.execute(query)})
    differ = [key for key in tiles[0].keys() | tiles[1].keys() if tiles[0].get(key) != tiles[1].get(key)]
    for key in sorted(differ)[:10]:
        print(f"DIFFERS {key}")
    print(f"{a} vs {b}: {len(tiles[0])} and {len(tiles[1])} tiles, {len(differ)} differ")
    return len(differ)


if __name__ == "__main__":
    args = sys.argv[1:]
    if args and args[0] == "--sum":
        total(args[1])
    elif args and args[0] == "--same":
        sys.exit(1 if same(args[1], args[2]) else 0)
    elif args and args[0] == "--subset":
        subset(args[1], args[2], int(args[3]))
    elif len(args) == 2:
        sys.exit(1 if check(args[0], args[1]) else 0)
    else:
        sys.exit(__doc__)
