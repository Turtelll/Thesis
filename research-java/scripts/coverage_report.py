"""Phase 1 inventory: per class and per method, what the figure commands reach (figures.xml) and what
every command reaches (all.xml, which adds the probes no current results file cites).

    python scripts/coverage_report.py ../data/coverage > ../data/coverage/report.md

Classes are listed with instruction coverage under both runs; methods are listed when no figure command
ever enters them. Only the first run needs to finish: nothing here re-runs a command.
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 else "../data/coverage")


def load(name):
    classes = {}
    for pkg in ET.parse(root / name).getroot().iter("package"):
        for cls in pkg.findall("class"):
            cname = cls.get("name").replace("/", ".")
            methods = {}
            for m in cls.findall("method"):
                key = (m.get("name"), m.get("desc"))
                ins = next((c for c in m.findall("counter") if c.get("type") == "INSTRUCTION"), None)
                missed = int(ins.get("missed")) if ins is not None else 0
                covered = int(ins.get("covered")) if ins is not None else 0
                methods[key] = (missed, covered, m.get("line"))
            ins = next((c for c in cls.findall("counter") if c.get("type") == "INSTRUCTION"), None)
            classes[cname] = {
                "missed": int(ins.get("missed")) if ins is not None else 0,
                "covered": int(ins.get("covered")) if ins is not None else 0,
                "methods": methods,
            }
    return classes


fig = load("figures.xml")
alls = load("all.xml")


def pct(c):
    t = c["missed"] + c["covered"]
    return 100.0 * c["covered"] / t if t else 100.0


def outer(n):
    return n.split("$")[0]


# roll nested classes into their outer class
def rollup(d):
    out = {}
    for n, c in d.items():
        o = out.setdefault(outer(n), {"missed": 0, "covered": 0, "methods": {}})
        o["missed"] += c["missed"]
        o["covered"] += c["covered"]
        for k, v in c["methods"].items():
            o["methods"][(n,) + k] = v
    return out


fig_r, all_r = rollup(fig), rollup(alls)
print("| class | instr. | figures % | all commands % |")
print("|---|---:|---:|---:|")
for n in sorted(all_r):
    a = all_r[n]
    f = fig_r.get(n, {"missed": a["missed"] + a["covered"], "covered": 0})
    print(f"| `{n.replace('org.maplibre.mlt.research.', '')}` | {a['missed'] + a['covered']} | {pct(f):.0f} | {pct(a):.0f} |")

print()
print("## Methods no figure command enters")
for n in sorted(all_r):
    f = fig_r.get(n)
    rows = []
    for key, (missed, covered, line) in all_r[n]["methods"].items():
        fm = f["methods"].get(key) if f else None
        in_fig = fm is not None and fm[1] > 0
        in_all = covered > 0
        if not in_fig:
            rows.append((int(line or 0), key[1], key[2], missed + covered, "probe-only" if in_all else "NEVER"))
    if rows:
        print(f"\n### `{n.replace('org.maplibre.mlt.research.', '')}`")
        for line, name, desc, size, why in sorted(rows):
            if name in ("<clinit>",):
                continue
            print(f"- L{line} `{name}` ({size} instr.) {why}")
