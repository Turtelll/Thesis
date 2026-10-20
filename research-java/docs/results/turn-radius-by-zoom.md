# Stage 0: does `transportation` curvature vary with zoom?

**Status:** measured, no encoder built — corpus-dependent, inconclusive
**Effect:** flat on the current baseline corpus; a real trend on the Overture corpus
**Corpus:** current baseline (`munich-plantiler.mbtiles`, `data/extractedBaseline`) and
`data/extracted-overture`, `--layer transportation`, `./gradlew turnRadius`

## The question

Asked: does the `transportation` layer have a larger average turn radius at higher
zooms than at lower zooms, in a way an encoding could exploit on low zooms? A cached
measurement (`data-analysis/transportation_stats.py` on `munich-plantiler`) already
showed median turn angle flat and non-monotone across zoom (11,9°-20,7°), while delta
magnitude — not curvature — scales with zoom (5,50 to 11,58 mean bits). That was one
Python run, median-only, in raw tile units, using sign+magnitude bit widths
(`abs(v).bit_length() + 1`) rather than this project's zigzag convention
(`32 - numberOfLeadingZeros`) — not enough on its own to settle the question.
`TurnRadiusCli` re-measures on the Java extraction with tail percentiles and a proper
curvature/radius metric, per `docs/evaluation-protocol.md` Stage 0: probe the
distribution before writing an `Encoding`.

## What it measures

Over line-run interior vertices (index `start+2 .. end-1` of each
LineString/MultiLineString run of at least 4 vertices — the same population
`LINE_CONTINUATION` predicts), bucketed by zoom (`<=11`, `12`, `13`, `14` — thinner
buckets are single-digit tile counts and unreadable as anything but anecdote):

- **Turn angle**, `atan2(|cross|, dot)` between incoming and outgoing edge, mean/median/p90/p99.
- **Curvature via Menger radius**, `radius = |e1|*|e2|*|e1+e2| / (2*|cross|)`, both raw
  (tile units, which scale with zoom because coordinates do) and **normalized by the
  incoming edge length** — the normalized figure is what actually answers "is the
  geometry smoother", since raw radius conflates that with "the tile just has bigger
  numbers at this zoom".
- **Residual bits** for three predictors on the same vertices: B (plain delta), C
  (continuation, `2*p[i-1]-p[i-2]`, what `LINE_CONTINUATION` does), and A
  (constant-curvature: extrapolate the incoming edge rotated by the previous measured
  turn). A is the one candidate nothing in this repo had tried — prior turn work
  (`RightAngleTurn`, `Rot90Fixed`, `EvenRingTurn`, turn-scalar) assumed a fixed 90°, and
  `CONTEXT_DELTA` used curvature only to switch predictors, never to extrapolate.
- **Part-length composition**, since `docs/results/line-continuation.md` shows
  continuation's gain tracks run length, and that is the more likely confound behind any
  per-zoom difference.

## Measured

Baseline corpus (`munich-plantiler.mbtiles`, 354 buffers):

```
--- turn angle (degrees) ---
zoom       vertices     mean   median      p90      p99
12           31.304    21,58    13,67    50,49    94,32
13           72.874    22,28    14,17    51,85    94,26
14          358.515    21,61    13,19    52,48    94,11
<=11         37.797    21,88    14,36    45,00   163,63

--- curvature: turn radius (tile units) ---
zoom         turned straight%    medianR       p90R  medianR/e     p90R/e
12           31.304       0,1%      177,3     1546,2       5,02      22,04
13           72.874       0,0%      182,9     2210,7       4,95      24,88
14          358.515       0,1%      200,4     3325,9       5,17      32,92
<=11         37.797       3,0%      103,8      916,3       4,35      20,07
```

**Median turn angle is flat and non-monotone (13,2°-14,4°) across every bucket, and
normalized median radius is flat (4,35-5,17)** — a larger turn radius at higher zoom
would show as a smaller angle and a larger normalized radius; neither happens. This
independently confirms the Python cache's flat result, despite a different decoder,
different bit-width convention, and a proper curvature metric instead of angle alone.
**The premise is refuted on this corpus.**

Overture corpus (`data/extracted-overture`, 360 buffers) — measured as a generalization
check, per the standing rule that a Munich-only result may be a producer quirk:

```
--- turn angle (degrees) ---
zoom       vertices     mean   median      p90      p99
12           15.436    20,25    13,25    46,68    93,37
13           51.859    18,63    11,45    44,31    92,17
14          135.136    17,17     9,67    42,02    91,51
<=11          6.051    27,82    19,07    65,70   108,43

--- curvature: turn radius (tile units) ---
zoom         turned straight%    medianR       p90R  medianR/e     p90R/e
12           15.436       0,2%      109,8     1438,5       5,05      26,63
13           51.859       0,2%      171,8     2573,5       5,77      34,36
14          135.136       0,2%      310,3     5709,2       6,81      49,66
<=11          6.051       0,2%       48,5      508,8       3,39      16,17
```

**Here the premise holds**: median turn angle falls monotonically (19,1° → 13,3° →
11,5° → 9,7°) and normalized median radius rises monotonically (3,39 → 5,05 → 5,77 →
6,81) from `<=11` through z14. This is the opposite of the baseline corpus's flat
result, on the same layer, over a comparable zoom range.

**Verdict: corpus-dependent, not a property of `transportation` geometry in general.**
Whether turn radius grows with zoom depends on the tiler/generalization pipeline, not
on the layer. A predictor that assumes one direction risks winning on one producer's
output and losing on another's — exactly the kind of dataset-quirk risk
`docs/results/reordering.md` was written to rule out for its own result.

Residual bits track the same split. Baseline corpus, z14:

```
zoom         values     pred     mean   median      p90    p99.9
14          717.564        B     5,85        6        8       11
            717.564        C     5,52        6        8       11
            717.564        A     5,48        6        8       11
```

Overture corpus, z14:

```
zoom         values     pred     mean   median      p90    p99.9
14          270.806        B     6,06        6        8       12
            270.806        C     5,52        6        8       12
            270.806        A     5,49        6        8       12
```

On both corpora, **A (constant-curvature) narrows the mean past C without widening
p99.9** — a small, consistent move, but median ties C on most buckets rather than
beating it outright, and the effect is roughly the same size on the flat corpus and the
curved one. That is not the zoom-dependent signal the premise predicted; it reads as
"continuation-family predictors have a small, corpus-independent edge over plain
delta on smooth line interiors," which `LINE_CONTINUATION` already captures. Per the
protocol, a median-only (here: tied) move with no tail improvement does not earn a new
`ClassifiedPredictor` — rANS is retired, so there is no codec left to price a
median-only win.

Part-length composition, baseline corpus:

```
zoom          n=2      n=3     4-10    11-50      51+       runs
12          58,3%    16,7%    22,0%     3,0%     0,0%     26.184
13          51,0%    16,6%    28,1%     4,3%     0,0%     49.414
14          53,6%    14,5%    26,6%     5,3%     0,0%    212.828
<=11        50,2%    16,1%    26,3%     7,0%     0,4%     15.853
```

Composition is nearly flat across zoom on this corpus too, unlike the Munich-vs-NY
composition flip `ideen.md` records (71,7% vs 37,9% two-vertex parts) — that finding
was about *cross-dataset* corpus composition (overview-tile world masks vs a
production-zoom corpus), not a *within-dataset* zoom gradient, and does not contradict
the flat-curvature result here.

## Why this matters for future work

- **Do not build a zoom-branching predictor from this premise alone.** It holds on one
  corpus and not the other; an encoding tuned to either would misfire on the other.
- `BufferContext` carries no z/x/y (`ExtractionReader.readContext` drops them), so a
  literal zoom-branching predictor would need `BufferContext` extended or per-buffer
  construction in the CLI — extra plumbing for a signal that isn't reliable across
  producers anyway.
- If curvature-magnitude prediction is revisited, condition on **measured curvature
  itself** (derivable identically on encode/decode from the vertices already read),
  never on zoom — the corpus split here shows zoom is a poor proxy for it.
- The one candidate this probe surfaced (constant-curvature predictor A) is a small,
  corpus-independent improvement over continuation, not a zoom effect. It did not clear
  the Stage 0 bar (no median or tail win over C) and was not promoted to an `Encoding`.
