"""Download Overture data for the Munich AOI as GeoJSONSeq, for tiling with tippecanoe.

AOI matches data/MVT/munich-de-rs.mbtiles bounds, so the resulting tileset covers
the same area as the existing OpenMapTiles-derived corpus.

Usage:  python data-analysis/overture_fetch.py [--release 2026-08-19.0]
Output: data/overture/<layer>.geojsonseq   (gitignored)
"""
import argparse
import pathlib
import sys

import duckdb

# bounds of data/MVT/munich-de-rs.mbtiles
XMIN, YMIN, XMAX, YMAX = 11.359, 48.061, 11.723, 48.248

# (theme, type, output layer name). Layer names loosely mirror the
# OpenMapTiles layer they correspond to, to ease side-by-side inspection.
LAYERS = [
    ("buildings", "building", "building"),
    ("transportation", "segment", "transportation"),
    ("base", "water", "water"),
    ("base", "land_cover", "landcover"),
    ("base", "land_use", "landuse"),
    ("places", "place", "place"),
    ("divisions", "division_area", "boundary"),
]

S3 = "s3://overturemaps-us-west-2/release/{release}/theme={theme}/type={type}/*"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--release", default="2026-08-19.0")
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    out = pathlib.Path(args.out) if args.out else \
        pathlib.Path(__file__).resolve().parent.parent / "data" / "overture"
    out.mkdir(parents=True, exist_ok=True)

    con = duckdb.connect()
    con.execute("INSTALL httpfs; LOAD httpfs; INSTALL spatial; LOAD spatial;")
    con.execute("SET s3_region='us-west-2';")

    for theme, type_, layer in LAYERS:
        dst = out / f"{layer}.geojsonseq"
        if dst.exists() and dst.stat().st_size > 0:
            print(f"skip {layer} (exists, {dst.stat().st_size/1e6:.1f} MB)")
            continue
        src = S3.format(release=args.release, theme=theme, type=type_)
        print(f">>> {theme}/{type_} -> {dst.name}", flush=True)
        # bbox struct is Overture's own spatial index; filtering on it prunes
        # row groups, which is far cheaper than a geometry predicate.
        con.execute(f"""
            COPY (
              SELECT * EXCLUDE (bbox, theme, type)
              FROM read_parquet('{src}', hive_partitioning=1)
              WHERE bbox.xmin < {XMAX} AND bbox.xmax > {XMIN}
                AND bbox.ymin < {YMAX} AND bbox.ymax > {YMIN}
            ) TO '{dst.as_posix()}' WITH (FORMAT GDAL, DRIVER 'GeoJSONSeq');
        """)
        print(f"    {dst.stat().st_size/1e6:.1f} MB", flush=True)

    print("\nnext: run data-analysis/overture_tile.sh (needs tippecanoe)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
