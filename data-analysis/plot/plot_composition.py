"""
plot_composition — Abbildungen zur Byte-Zusammensetzung der MVT-Tilesets.

    python plot_composition.py

Liest alle vorhandenen out/*.json (erzeugt von mvt_stats.py) und schreibt:
  out/fig_mvt_layer_geom_share.pdf   Geometriebytes je Layer
  out/fig_mvt_gtype_geom_share.pdf   Geometriebytes je Geometrietyp

Bezugsgroesse sind die Geometriebytes des Tilesets (protobuf feature.geometry,
Feld 4) — Tags/Keys/Values bleiben ausser Betracht. Die Anteile summieren sich
je Tileset auf 100%.
"""

from __future__ import annotations

import glob
import json
import os

import numpy as np

import thesisviz as tv

_HERE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.join(_HERE, "..", "out")

# Layer unterhalb dieses mittleren Anteils sind als Balken nicht mehr sichtbar
MIN_LAYER_SHARE = 0.5
GTYPE_NAMES = {0: "Unknown", 1: "Point", 2: "Line", 3: "Polygon"}


def load() -> list[dict]:
    stats = []
    paths = set(glob.glob(os.path.join(OUT_DIR, "*.json")))
    paths -= set(glob.glob(os.path.join(OUT_DIR, "*.vertices.json")))
    for path in sorted(paths):
        with open(path, encoding="utf-8") as fh:
            stats.append(json.load(fh))
    if not stats:
        raise SystemExit("No out/*.json — run mvt_stats.py <tileset> first")
    return stats


def grouped_bars(categories: list[str], series: dict[str, list[float]],
                 axis_label: str, name: str, width: float = 1.0,
                 ratio: float = 0.5, horizontal: bool = False) -> str:
    """Gruppierte Balken. horizontal=True -> Kategorien waagerecht lesbar."""
    fig, ax = tv.figure(width=width, ratio=ratio)
    n = len(series)
    x = np.arange(len(categories))
    # Balken stossen buendig aneinander. Eine schmale Luecke waere bei
    # Bildschirmaufloesung subpixelig und wuerde mal 1, mal 2 px breit gerendert.
    bar_w = 0.8 / n

    for i, (label, values) in enumerate(series.items()):
        offset = (i - (n - 1) / 2) * bar_w
        if horizontal:
            # erste Serie oben: y-Achse wird unten invertiert
            ax.barh(x + offset, values, bar_w, label=label)
        else:
            ax.bar(x + offset, values, bar_w, label=label)

    if horizontal:
        ax.set_yticks(x)
        ax.set_yticklabels(categories)
        ax.invert_yaxis()
        ax.set_xlabel(axis_label)
        # Gitter der Vorlage liegt auf y — bei liegenden Balken gehoert es auf x
        ax.grid(axis="y", visible=False)
        ax.grid(axis="x", visible=True)
        ax.set_xlim(right=ax.get_xlim()[1] * 1.05)
        ax.legend(ncol=1, loc="lower right")
    else:
        ax.set_xticks(x)
        ax.set_xticklabels(categories)
        ax.set_ylabel(axis_label)
        # Kopfraum, damit die Legende den hoechsten Balken nicht ueberdeckt
        ax.set_ylim(top=ax.get_ylim()[1] * 1.15)
        ax.legend(ncol=min(n, 4), loc="upper right")

    path = tv.save(fig, name, outdir=OUT_DIR)
    print(f"wrote {path}")
    return path


def main() -> None:
    tv.use()
    stats = load()
    names = [s["tileset"] for s in stats]

    # --- Fig 1: Geometriebytes je Layer -----------------------------------
    shares = [
        {layer: 100 * b / sum(s["layer_geom_bytes"].values())
         for layer, b in s["layer_geom_bytes"].items()}
        for s in stats
    ]
    layers = sorted({layer for sh in shares for layer in sh})
    layers.sort(key=lambda l: -np.mean([sh.get(l, 0.0) for sh in shares]))
    dropped = [l for l in layers
               if np.mean([sh.get(l, 0.0) for sh in shares]) < MIN_LAYER_SHARE]
    layers = [l for l in layers if l not in dropped]
    if dropped:
        print(f"dropped (<{MIN_LAYER_SHARE}% mean): {', '.join(dropped)}")

    grouped_bars(
        layers,
        {name: [sh.get(l, 0.0) for l in layers] for name, sh in zip(names, shares)},
        "Anteil an den Geometriebytes [%]",
        "fig_mvt_layer_geom_share",
        ratio=0.85,
        horizontal=True,
    )

    # --- Fig 2: Geometriebytes je Geometrietyp ----------------------------
    gshares = [
        {int(k): 100 * v / sum(s["gtype_geom_bytes"].values())
         for k, v in s["gtype_geom_bytes"].items()}
        for s in stats
    ]
    gtypes = sorted({g for gs in gshares for g in gs if any(x.get(g, 0.0) > 0 for x in gshares)})

    grouped_bars(
        [GTYPE_NAMES.get(g, str(g)) for g in gtypes],
        {name: [gs.get(g, 0.0) for g in gtypes] for name, gs in zip(names, gshares)},
        "Anteil an den Geometriebytes [%]",
        "fig_mvt_gtype_geom_share",
        ratio=0.5,
    )


if __name__ == "__main__":
    main()
