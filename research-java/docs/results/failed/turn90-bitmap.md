# 90° edge rotation with a signalled turn direction — `TURN90_BITMAP` + reflex-signal probe

> **Consolidation note.** The Gradle task(s) benchmark named below were removed from the build (the code is at git tag `pre-consolidation`); `research measure` and the other `research` commands replace the rest. The numbers below are unchanged history.

> **Revisited under split varints:** `TURN90_BITMAP_VARINT_SPLIT` is +0,26% whole tileset
> (smallest of gzip/Brotli/zstd) and -0,76% on `building`; stays retired. See
> [../revisit-varint-split.md](../revisit-varint-split.md).

**Status:** failed — unregistered, moved to `encoding/research/failed/Turn90Bitmap.java`
(kept for `Turn90BitmapMechanismTest` and the two probes below)
**Effect (building):** -2,6% mean residual bits all-in / **+3,7% bytes (FastPFOR)**, +3,8% (Varint)
**Effect (corpus):** +1,0% mean residual bits all-in / +2,9% bytes (both codecs)
**Best signal packing found:** flag in residual low bit, still +2,4% building bytes (FastPFOR)
**Corpus:** current baseline (`munich-plantiler.mbtiles`, `data/extractedBaseline`, 4.368
buffers, `set: core -rans`)

Started from scratch; earlier turn work (`failed/right-angle-turn.md`, `turn-scalar*.md`,
`rot90-fixed.md`) was deliberately not reused.

## What it does

Own `Encoding` (a `ClassifiedPredictor` cannot signal). Per polygon ring, every layer;
line/point vertices plain delta:

- v0: plain delta from the previous vertex; v1: plain delta (first edge)
- v2..: `d = v[i-1] - v[i-2]`, prediction `v[i-1] + (-dy, dx)` (bit 0) or `v[i-1] + (dy, -dx)`
  (bit 1); encoder picks the one with fewer zigzag residual bits, tie → bit 0

The rotated edge keeps the previous edge's length, so a rectangle leaves the side-length
difference as residual. Bitmap: one bit per predicted vertex, `Σ max(0, n-2)` per polygon
ring, derived from topology; packed MSB-first behind the codec payload, priced as a second
stream with its own `StreamMetadata` header.

Mechanism: `Turn90BitmapMechanismTest` (rectangle residual = length diff; L-shape reflex
corner flips the bit, all other corners exact; line runs untouched).

## Measured — encoder

`./gradlew benchmark --args="../data/extractedBaseline --layer building --verbose"` (while registered):

| building | mean bits (all-in) | FASTPFOR bytes | VARINT bytes |
|---|---|---|---|
| DELTA_ZIGZAG | 4,78 | 4.285.065 | 4.688.602 |
| QUAD_CORNER | 4,29 (-10,2%) | | |
| QUAD_CORNER_SHARED | 4,21 (-12,0%) | 4.133.861 (-3,5%) | |
| TURN90_BITMAP | 4,65 (-2,6%) | 4.444.873 (+3,7%) | 4.864.717 (+3,8%) |

Corpus default run: +1,0% mean bits; 3 oracle buffer wins (17 bytes), all Varint.

## Cost split

`./gradlew turn90Bitmap --args="../data/extractedBaseline --layer building"`
(`Turn90BitmapCli`, totals match the benchmark byte-for-byte):

| building | bits/value | per predicted vertex (x+y) |
|---|---|---|
| DELTA_ZIGZAG | 4,778 | 8,629 |
| residual only | 4,325 (-9,5%) | 7,255 (-15,9%) |
| flags | 0,330 | 1,000 |
| all-in | 4,655 (-2,6%) | 8,255 (-4,3%) |

1.433.308 predicted vertices; exact prediction (zero residual) 1,7%; flag = 1 share 18,2%.

| building bytes | baseline | residuals | res hdr | bitmap | bmp hdr | total |
|---|---|---|---|---|---|---|
| FASTPFOR | 4.285.065 | 4.261.724 | 2.080 | 179.294 | 1.775 | 4.444.873 (+3,7%) |
| VARINT | 4.688.602 | 4.681.555 | 2.093 | 179.294 | 1.775 | 4.864.717 (+3,8%) |

Corpus (`--layer all`): residual only -2,9% bits, all-in +1,0%; per predicted vertex residual
-8,0%, +flag +2,8%; flag = 1 share 24,1%; +2,9% bytes both codecs.

## Follow-up: signal the reflex corners instead of a free direction bit

Question: can the direction be derived implicitly — winding from the topology, plus the
hypothesis "after a turn against the winding (reflex), the next two turns are with it
(convex)" — or at least signalled more cheaply, e.g. interleaved where the reflex corner is?

`./gradlew reflexSignal --args="../data/extractedBaseline --layer building|all"`
(`ReflexSignalCli`, encodes nothing). Flag = geometric turn at `v[i-1]` is reflex relative
to the spec winding (cross product; collinear counts as convex), same predicted-vertex set
and prediction as above but with the direction taken from the flag.

### Winding is derivable

| ring | building per spec | corpus per spec |
|---|---|---|
| outer (area +) | 366.599 / 366.599 | 451.918 / 451.918 |
| hole (area −) | 2.770 / 2.770 | 6.720 / 6.720 |

100% compliance, so convex vs reflex needs no signal — only *where* the reflex corners are.

### The "two convex after reflex" rule does not hold

P(reflex) by context (previous turns in the same ring, decode order):

| context | building flags | building P(reflex) | corpus P(reflex) | building rectilinear¹ P(reflex) |
|---|---|---|---|---|
| previous turn reflex | 274.482 | 22,46% | 32,10% | 13,73% |
| turn two back reflex | 188.106 | 32,29% | 34,71% | 12,73% |
| previous two convex | 256.513 | 52,32% | 43,53% | 54,86% |
| < 2 previous turns, convex | 714.207 | 6,80% | 8,74% | 3,65% |

¹ every cyclic corner within 10° of 90°: 298.676 of 369.369 building rings, but only
777.048 of 1.433.308 flags.

- The rule is violated in 26,5% of the flags it would force on building (13,3% on
  rectilinear rings, 33,1% corpus) — it cannot replace flags losslessly without an escape.
- The data shows roughly the opposite dependency: after two convex turns a reflex is
  ~50/50 — consistent with `#convex − #reflex = 4` in a rectilinear ring.
- Reflex share 21,29% building (order-0 0,747 bit/flag → 1.070.639 bits); conditioning on
  the two previous turns gives 893.738 bits ≈ **112 KB** (computed from the table above, no
  coder built) vs a residual-stream saving of ≈ **25 KB** (FastPFOR). Corpus: 230 KB vs
  no residual-stream saving at all.

Convex turns until the next reflex (building): 0 = 61.636, 1 = 60.745, 2 = 57.580,
3 = 26.696, 4 = 7.884, 5+ = 2.278, no further reflex = 88.318.

### Packings on the same residuals (bytes incl. stream headers)

| packing | building FASTPFOR | building VARINT | corpus FASTPFOR | corpus VARINT |
|---|---|---|---|---|
| DELTA_ZIGZAG | 4.285.065 | 4.688.602 | 12.435.468 | 13.294.612 |
| residuals + separate bitmap | +3,6% | +3,7% | +2,8% | +3,0% |
| flag in x residual's low bit | +2,4% | +1,2% | +2,3% | +2,0% |
| reflex gaps interleaved² | +11,6% | +13,6% | +7,6% | +8,5% |

² per ring: a gap value before the first predicted vertex and after each reflex (convex
turns until the next reflex; the remaining count means none) — +14,8% more values on
building.

## Reading

- **The signal costs more than the prediction saves.** Building residual-stream saving is
  ~21–25 KB; the raw bitmap is 179 KB, and even a context-conditioned entropy bound is
  ~112 KB.
- **Residual bit savings barely reach bytes.** -9,5% residual bits → ~-0,5% residual bytes
  on both codecs: the saved bits mostly don't cross a FastPFOR block width or a varint
  7-bit boundary (not decomposed further).
- **Interleaving a small symbol into the residual stream is priced at the stream's width**:
  each gap costs a full FastPFOR block width or ≥ 1 varint byte, so gap interleaving is the
  worst packing despite fewer symbols than flags.
- **Length-keeping costs**: only 1,7% of predicted vertices are exact — the rotated edge
  keeps the previous edge's length, while buildings are rectangles, not squares.
- Line/point residuals equal `DELTA_ZIGZAG`, so the corpus regression beyond building comes
  from non-building polygon rings (per-layer split not measured).
- Winding is free (100% spec compliance) — reusable for any later winding-relative
  predictor without a signal.
