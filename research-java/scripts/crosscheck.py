"""Cross-checks the per-row tile totals a `./gradlew benchmark` / `pipeline` log prints against a golden dump.

    python scripts/crosscheck.py ../data/golden/pre-consolidation LOG [LOG ...]

A log line counts when it starts with a row name of the manifest followed by integers written with `.` as the
thousands separator: raw, order-0, gzip, brotli11, zstd19 (the order the CLIs print them; `*` marks the best
cell and `change` columns are skipped). Every such line must equal the manifest's payload_bytes, tile_order0,
tile_gzip, tile_brotli11, tile_zstd19. Exit code 1 on any mismatch.
"""
import re
import sys
from pathlib import Path

manifest = {}
for line in Path(sys.argv[1], "manifest.tsv").read_text(encoding="utf-8").splitlines():
    if line.startswith("#") or not line.strip():
        continue
    f = line.split("\t")
    if f[0] == "row":
        manifest[f[1]] = [int(f[4]), int(f[5]), int(f[6]), int(f[7]), int(f[8])]
manifest["PER LAYER"] = manifest["PIPE_PER_LAYER"]

number = re.compile(r"(?<![\w,])\d{1,3}(?:\.\d{3})+(?![\w,])|(?<![\w,.])\d+(?![\w,.%])")
bad = checked = 0
seen = set()
for log in sys.argv[2:]:
    for line in Path(log).read_text(encoding="utf-8", errors="replace").splitlines():
        name = next((n for n in sorted(manifest, key=len, reverse=True) if line.startswith(n + " ") or line.startswith("baseline " + n + " ")), None)
        if not name:
            continue
        rest = line[line.index(name) + len(name):]
        nums = [int(m.group(0).replace(".", "")) for m in number.finditer(rest.replace("*", " "))]
        # a table with the five size columns prints at least 5 integers before the first percent; take the first 5
        if len(nums) < 5:
            continue
        got = nums[:5]
        # the benchmark's change columns hold percentages with a comma, never matched above
        checked += 1
        seen.add(name)
        if got != manifest[name]:
            bad += 1
            print(f"MISMATCH {Path(log).name}: {name}\n  log  {got}\n  dump {manifest[name]}")
print(f"{checked} lines over {len(seen)} rows compared, {bad} mismatches")
sys.exit(1 if bad else 0)
