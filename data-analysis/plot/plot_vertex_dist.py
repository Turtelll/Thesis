"""
plot_vertex_dist — Verteilung der Vertices je Feature, je Layer.

    python plot_vertex_dist.py <tileset>

Liest out/<tileset>.vertices.json (erzeugt von vertex_stats.py) und schreibt
  out/fig_vertex_dist_<tileset>.pdf

Boxplot je Layer auf logarithmischer y-Achse: Box = Q1/Median/Q3, Whisker
nach Tukey (1.5*IQR, an min/max gekappt), p99 als Ausreisser-Marker. Zeigt
die Schiefe, die Mittelwert/Median allein verdecken (siehe out/*.vertices.json:
building hat Median 11, aber p99 im Tausenderbereich).
"""

from __future__ import annotations

import json
import os
import sys

import thesisviz as tv

_HERE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.join(_HERE, "..", "out")


def load(tileset: str) -> dict:
    path = os.path.join(OUT_DIR, f"{tileset}.vertices.json")
    if not os.path.exists(path):
        raise SystemExit(f"{path} not found — run vertex_stats.py {tileset} first")
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def bxp_stats(layer: str, s: dict) -> dict:
    """ax.bxp()-Stats aus den in vertex_stats.py gespeicherten Perzentilen.

    Whisker nach Tukey (1.5*IQR), an [min, max] gekappt — keine Rohdaten
    vorhanden, daher keine echten Fliers, stattdessen p99 als Markierung.
    """
    iqr = s["q3"] - s["q1"]
    lo_whisker = max(s["min"], s["q1"] - 1.5 * iqr)
    hi_whisker = min(s["max"], s["q3"] + 1.5 * iqr)
    return {
        "label": layer,
        "med": s["median"],
        "q1": s["q1"],
        "q3": s["q3"],
        "whislo": lo_whisker,
        "whishi": hi_whisker,
        "fliers": [],
    }


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    tileset = sys.argv[1]

    tv.use()
    data = load(tileset)
    layers = data["layers"]  # bereits absteigend nach Vertexzahl sortiert
    names = list(layers)

    fig, ax = tv.figure(width=1.0, ratio=0.7)
    stats = [bxp_stats(name, layers[name]) for name in names]
    bp = ax.bxp(stats, orientation="horizontal", patch_artist=True, widths=0.6)
    for box in bp["boxes"]:
        box.set_facecolor(tv.C.fill)
        box.set_edgecolor(tv.C.ink)
    for part in ("whiskers", "caps", "medians"):
        for line in bp[part]:
            line.set_color(tv.C.ink)

    # p99 je Layer als separate Markierung — die Boxplot-Stats allein zeigen
    # den Tail nicht, den p90/p99 in vertex_stats.py explizit erfassen.
    p99_y = list(range(len(names), 0, -1))
    p99_x = [layers[name]["p99"] for name in names]
    ax.scatter(p99_x, p99_y, marker=tv.MARKERS[0], color=tv.C.rust,
               s=14, zorder=3, label="p99")

    ax.set_xscale("log")
    ax.invert_yaxis()
    ax.set_xlabel("Vertices je Feature (log)")
    ax.grid(axis="y", visible=False)
    ax.grid(axis="x", visible=True, which="both")
    ax.legend(loc="upper right")

    path = tv.save(fig, f"fig_vertex_dist_{tileset}", outdir=OUT_DIR)
    print(f"wrote {path}")


if __name__ == "__main__":
    main()
