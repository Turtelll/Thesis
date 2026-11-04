"""Independent size check for `mlt-oracle compare --write-tiles <dir>`.

Re-computes the gzip (level 6) size of every written tile with Python's zlib, a different
implementation than the flate2 crate that `compare` uses, and sums stock vs oracle.

    python check_tiles.py target-results/tiles-r1 [target-results/run1.csv]

With the CSV of the same run it also checks every per-tile figure against `compare`.
"""

import csv
import gzip
import sys
from pathlib import Path


def gz(data: bytes) -> int:
    return len(gzip.compress(data, compresslevel=6, mtime=0))


def main() -> int:
    directory = Path(sys.argv[1])
    stock = oracle = stock_raw = oracle_raw = 0
    per_tile: dict[tuple[int, int, int], tuple[int, int, int, int]] = {}
    for path in sorted(directory.glob("*.stock.mlt")):
        z, x, y = (int(p) for p in path.name.split(".")[0].split("_"))
        s = path.read_bytes()
        o = (directory / f"{z}_{x}_{y}.oracle.mlt").read_bytes()
        per_tile[(z, x, y)] = (len(s), gz(s), len(o), gz(o))
        stock_raw += len(s)
        oracle_raw += len(o)
        stock += per_tile[(z, x, y)][1]
        oracle += per_tile[(z, x, y)][3]
    print(f"{len(per_tile)} tiles")
    print(f"stock  raw {stock_raw:>10}  gzip {stock:>10}")
    print(f"oracle raw {oracle_raw:>10}  gzip {oracle:>10}")
    print(f"gzip {oracle / stock - 1:+.2%}   raw {oracle_raw / stock_raw - 1:+.2%}")

    if len(sys.argv) > 2:
        mismatches = 0
        with open(sys.argv[2], newline="") as f:
            for row in csv.DictReader(f):
                key = (int(row["z"]), int(row["x"]), int(row["y"]))
                got = per_tile.get(key)
                want = (
                    int(row["stock_raw"]),
                    int(row["stock_gzip"]),
                    int(row["oracle_raw"]),
                    int(row["oracle_gzip"]),
                )
                if got != want:
                    mismatches += 1
                    if mismatches <= 5:
                        print(f"MISMATCH {key}: files {got} csv {want}")
        print(f"per-tile figures vs csv: {mismatches} mismatches")
        return 1 if mismatches else 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
