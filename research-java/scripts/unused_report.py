"""Summarises ProGuard -printusage output (scripts/unused.ps1): per class, the members nothing reaches.

    python scripts/unused_report.py ../data/coverage/unused-with-tests.txt [package-prefix ...]

A class line without a trailing colon is unused as a whole; with a colon, the indented member lines follow.
"""
import re
import sys
from pathlib import Path

path = Path(sys.argv[1])
prefixes = sys.argv[2:]
cur = None
data = {}
for line in path.read_text(encoding="utf-8").splitlines():
    if not line.strip():
        continue
    if not line.startswith((" ", "\t")):
        name = line.rstrip(":").replace("org.maplibre.mlt.research.", "")
        cur = name
        data[cur] = {"whole": not line.endswith(":"), "members": []}
    elif cur:
        data[cur]["members"].append(re.sub(r"\s+", " ", line.strip()))
for name in sorted(data):
    if prefixes and not any(name.startswith(p) for p in prefixes):
        continue
    d = data[name]
    print(f"## {name}: {'WHOLE CLASS' if d['whole'] else str(len(d['members'])) + ' members'}")
    if not d["whole"]:
        for m in d["members"][:40]:
            print("   ", m)
