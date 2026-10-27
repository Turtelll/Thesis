"""Reads a golden dump's manifest.tsv and prints, for the named rows, the per-tile size under each compressor.

Two reference conventions are printed because the results files mix them:
  * benchmark convention (`./gradlew benchmark`, README): every column against the smaller baseline's
    **gzip** size (DELTA_ZIGZAG_VARINT, gzip), so a Brotli or zstd row can show more than a gzip row;
  * own-compressor convention: each column against DELTA_ZIGZAG_VARINT under the same compressor.

    python scripts/golden_summary.py ../data/golden/pre-consolidation ROW [ROW ...] [--layers transportation,building]
"""
import sys
from pathlib import Path

args = sys.argv[1:]
layers = []
if "--layers" in args:
    i = args.index("--layers")
    layers = args[i + 1].split(",")
    del args[i : i + 2]
folder, names = Path(args[0]), args[1:]

rows, layer_rows = {}, {}
for line in (folder / "manifest.tsv").read_text(encoding="utf-8").splitlines():
    if line.startswith("#") or not line.strip():
        continue
    f = line.split("\t")
    if f[0] == "row":
        rows[f[1]] = dict(buffers=int(f[3]), payload=int(f[4]), gzip=int(f[6]), brotli=int(f[7]), zstd=int(f[8]),
                          iso_gzip=int(f[9]))
    elif f[0] == "layer":
        layer_rows[(f[1], f[2])] = dict(buffers=int(f[3]), payload=int(f[4]), iso_gzip=int(f[9]),
                                        iso_brotli=int(f[10]), iso_zstd=int(f[11]))

ref = rows["DELTA_ZIGZAG_VARINT"]
print(f"reference DELTA_ZIGZAG_VARINT: gzip {ref['gzip']:,} brotli {ref['brotli']:,} zstd {ref['zstd']:,} bytes (sum of per-tile sizes)")
print("| row | gzip B | gzip | brotli (bench) | zstd (bench) | brotli (own) | zstd (own) |")
print("|---|---:|---:|---:|---:|---:|---:|")
for n in names:
    r = rows[n]
    bench = lambda k: 100.0 * (r[k] - ref["gzip"]) / ref["gzip"]
    own = lambda k: 100.0 * (r[k] - ref[k]) / ref[k]
    print(f"| {n} | {r['gzip']:,} | {bench('gzip'):+.2f}% | {bench('brotli'):+.2f}% | {bench('zstd'):+.2f}% | {own('brotli'):+.2f}% | {own('zstd'):+.2f}% |")
for layer in layers:
    base = layer_rows[("DELTA_ZIGZAG_VARINT", layer)]["iso_gzip"]
    print(f"\n{layer}: isolated per-buffer gzip, reference DELTA_ZIGZAG_VARINT {base:,}")
    for n in names:
        x = layer_rows.get((n, layer))
        if x:
            print(f"  {n}: {x['iso_gzip']:,} ({100.0 * (x['iso_gzip'] - base) / base:+.2f}%)")
