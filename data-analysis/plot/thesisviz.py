"""
thesisviz — Design-Sprache fuer Abbildungen der Bachelorarbeit.

    import thesisviz as tv
    fig, ax = tv.figure(width=1.0, ratio=0.62)
    ax.plot(x, y, color=tv.C.teal, label="SegmentPair")
    tv.save(fig, "fig_segmentpair_ratio")

Alle Farben sind in CIE L*a*b* konstruiert:
  * streng aufsteigende Helligkeit  -> im Graustufendruck unterscheidbar
  * Mindestabstand dE > 19 unter Deuteranopie/Protanopie
Ab fuenf Serien traegt Farbe die Unterscheidung nicht mehr allein:
dann zusaetzlich MARKERS / HATCHES verwenden.
"""

from __future__ import annotations

import os
from dataclasses import dataclass

import matplotlib as mpl
import numpy as np
import matplotlib.pyplot as plt
from matplotlib.colors import LinearSegmentedColormap, ListedColormap

_HERE = os.path.dirname(os.path.abspath(__file__))

# --------------------------------------------------------------------------
# Satzspiegel — EINMAL an die eigene Vorlage anpassen:
#   \the\textwidth in der Arbeit ausgeben und den Wert in mm eintragen.
# --------------------------------------------------------------------------
TEXTWIDTH_MM = 150.0
MM_PER_IN = 25.4


@dataclass(frozen=True)
class Palette:
    # kategorial, nach L* aufsteigend
    ink: str = "#013556"      # L* 21 — Referenz / Baseline (MVT, unkomprimiert)
    teal: str = "#006875"     # L* 40 — Hauptverfahren (MLT / SegmentPair)
    violet: str = "#9265AD"   # L* 50 — Variante
    rust: str = "#DD6449"     # L* 57 — Kontrast / Verschlechterung
    amber: str = "#EEAD1F"    # L* 75 — Hervorhebung, Annotation

    # Neutrale
    text: str = "#2B2B2B"
    muted: str = "#6E6E6E"
    rule: str = "#D6D6D6"
    fill: str = "#EDEDED"
    paper: str = "#FFFFFF"

    def cycle(self, n: int) -> list[str]:
        return [self.ink, self.teal, self.violet, self.rust, self.amber][:n]


C = Palette()

# Sequenziell (Bitbreiten, Quantisierungsstufen, Heatmaps) — einheitiger Farbton
SEQ = ["#E5EFF9", "#ABC0D3", "#7193AF", "#33698C", "#00416A"]
CMAP_SEQ = LinearSegmentedColormap.from_list("thesis_seq", ["#F4F8FC", C.ink])
CMAP_SEQ_D = ListedColormap(SEQ, name="thesis_seq_d")

# Divergierend (Delta gegenueber Baseline: Einsparung <-> Verschlechterung)
DIV = ["#00343F", "#00697A", "#5FA3AC", "#F2F1EE", "#E4B7A7", "#CC6F58", "#A83A2E"]
CMAP_DIV = LinearSegmentedColormap.from_list("thesis_div", DIV)

for _cm in (CMAP_SEQ, CMAP_SEQ_D, CMAP_DIV):
    mpl.colormaps.register(_cm, force=True)

# Formkodierung, wenn Farbe nicht ausreicht
MARKERS = ["o", "s", "^", "D", "v"]
HATCHES = ["", "///", "...", "\\\\\\", "xxx"]

# Semantische Rollen — im Text konsistent mitbenennen
ROLE = {
    "baseline": C.ink,
    "proposed": C.teal,
    "variant": C.violet,
    "regression": C.rust,
    "highlight": C.amber,
}


def use(pgf: bool = False) -> None:
    """Style aktivieren. pgf=True -> Satz durch LaTeX, identische Schrift wie im Text."""
    plt.style.use(os.path.join(_HERE, "thesis.mplstyle"))
    if pgf:
        mpl.use("pgf")
        mpl.rcParams.update({
            "pgf.texsystem": "lualatex",
            "pgf.rcfonts": False,
            "pgf.preamble": r"\usepackage{fontspec}\usepackage{unicode-math}",
        })


def figure(width: float = 1.0, ratio: float = 0.618, **kwargs):
    """Figur in Bruchteilen der Textbreite. ratio = Hoehe/Breite."""
    w = TEXTWIDTH_MM * width / MM_PER_IN
    fig, ax = plt.subplots(figsize=(w, w * ratio), **kwargs)
    for a in np.atleast_1d(ax).ravel() if hasattr(ax, "ravel") else [ax]:
        a.set_axisbelow(True)
        a.xaxis.set_zorder(0.5)
        a.yaxis.set_zorder(0.5)
    return fig, ax


def size(width: float = 1.0, ratio: float = 0.618) -> tuple[float, float]:
    w = TEXTWIDTH_MM * width / MM_PER_IN
    return (w, w * ratio)


def save(fig, name: str, outdir: str = "figures") -> str:
    """Als PDF ablegen — vektoriell, Schriften eingebettet."""
    os.makedirs(outdir, exist_ok=True)
    path = os.path.join(outdir, f"{name}.pdf")
    fig.savefig(path)
    return path


def bar_labels(ax, fmt: str = "{:.0f}", pad: int = 2, **kwargs) -> None:
    """Werte direkt an die Balken — ersetzt in der Regel die y-Achse."""
    for cont in ax.containers:
        ax.bar_label(cont, fmt=fmt.format, padding=pad, fontsize=7,
                     color=C.text, **kwargs)


def annotate(ax, text: str, xy, xytext, color: str | None = None) -> None:
    """Sparsame Annotation im Amber-Ton, ohne Kasten."""
    ax.annotate(text, xy=xy, xytext=xytext, fontsize=8,
                color=color or C.text,
                arrowprops=dict(arrowstyle="-", lw=0.6, color=C.muted,
                                shrinkA=0, shrinkB=2))


def despine_all(ax) -> None:
    for s in ax.spines.values():
        s.set_visible(False)
