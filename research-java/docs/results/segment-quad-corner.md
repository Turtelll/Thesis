# Composing SegmentPair and QuadCorner — `SEGMENT_QUAD_CORNER_FASTPFOR`

**Status:** registered — the single best encoding on the whole corpus (before rANS, see
[rans-entropy-coder.md](rans-entropy-coder.md))
**Effect:** -6,1% corpus / -12,2% building
**Corpus:** previous Munich corpus (`munich.mbtiles`) — see [reordering.md](reordering.md)
for the corpus-comparability banner

The composition of the two transforms below — [SegmentPair](segment-pair.md)'s part-start
grouping and `QUAD_CORNER`'s parallelogram-corner predictor — which the per-geometry tables
show win on *disjoint* populations (SEGMENT_PAIR on LINESTRING, QUAD_CORNER on POLYGON).
Because they touch different residuals, they stack rather than compete.

```
DELTA_ZIGZAG_FASTPFOR            13.399.028   ref
DELTA_ZIGZAG_VARINT              13.818.767  +3,1%
QUAD_CORNER_FASTPFOR             13.181.440  -1,6%
SEGMENT_PAIR_FASTPFOR            12.795.818  -4,5%
SEGMENT_QUAD_CORNER_FASTPFOR     12.579.088  -6,1%  *
```

**What it does.** Three residual populations instead of two, each packed as its own
contiguous FastPFOR region so it is sized to its own width: part starts (delta vs previous
vertex — the wide boundary spikes), interior vertices (delta vs previous vertex — the narrow
population), and fourth corners of four-vertex rings (predicted `p0+p2-p1`). The three classes
are mutually exclusive and both derivable from the topology streams, so nothing is signalled;
`prevX/prevY` stay continuous, so there are still no reset seeds.

**How it stacks.**

| segment | SEGMENT_PAIR | QUAD_CORNER | SEGMENT_QUAD_CORNER |
|---|---|---|---|
| **corpus** | -4,5% | -1,6% | **-6,1%** |
| `building` | -5,2% | -7,1% | **-12,2%** |
| POLYGON | -3,9% | -4,1% | **-8,0%** |
| `transportation` | **-6,5%** | +0,3% | -6,2% |
| LINESTRING | **-6,3%** | +0,3% | -6,1% |

On `building` the two effects are nearly additive (-5,2% and -7,1% → -12,2%). On the line
segments it keeps almost all of SEGMENT_PAIR's win; the small gap (-6,2% vs -6,5% on
`transportation`) is the four-vertex *line* parts being pulled into their own region, a
negligible reshuffle of block boundaries. The oracle confirms it dominates: it is the best
single encoding, and a free per-buffer selector over it plus `SEGMENT_PAIR` and the two
baselines improves on always-using-it by only ~0,4%.

**Concepts and methods it exploits.** The encoding rests on four ideas, each measured:

1. **The layer's geometric prior.** `building` is single-ring quadrilaterals, and the quads
   are *rotated parallelograms*, not grid rectangles:

   | property of the `building` corpus | measured |
   |---|---|
   | polygons with `numParts == 1` (no holes) | **99,5%** |
   | rings storing exactly 4 vertices | **75,7%** |
   | 4-vertex rings that are exact parallelograms (`p1-p0 == p2-p3`) | **40,7%** |
   | 4-vertex rings axis-aligned to the tile grid | 4,2% |

2. **Model-based prediction instead of delta coding.** The baseline predicts each vertex as
   the *previous vertex* (a first-order delta). For the fourth corner of a quad this
   throws away a known constraint: in a parallelogram `p3 = p0 + p2 - p1` exactly, so
   predicting `p0+p2-p1` and storing the *residual* makes it zero for the 40,7% that are
   parallelograms and small for the rest — where a previous-vertex delta would carry a full
   edge length. This is the general **predict + store-residual** scheme (`ideen.md`): the
   decoder recomputes the same prediction from already-decoded vertices and adds the residual
   back, so the transform is symmetric and lossless.

3. **Residual-population separation for a bit-packed codec.** FastPFOR packs each block to the
   **bit width of its widest value**, so mixing a narrow population with a wide one taxes the
   narrow one up to the wide width. Three populations here have distinct widths — part-start
   deltas (wide boundary spikes), interior deltas (narrow), and corner residuals (near-zero) —
   so the transform emits each as its own contiguous region, letting FastPFOR size each
   independently. This is the same block-width lever `SEGMENT_PAIR` found; the corner
   predictor's -9,6% per-value zigzag-bit saving only becomes a real byte win *because* the
   corners are separated (interleaved, it measured -0,0%: one zero value in a block of wide
   deltas changes nothing).

4. **Derived, not signalled, structure — and no reset seeds.** Which vertex is a part start
   or a fourth corner is derivable from the topology streams (`numParts`/`numRings`), so both
   encoder and decoder compute the same partition and *nothing is written to the tile* to
   describe it — no permutation, no flags. And prediction stays continuous across the buffer
   (independent x/y cursors advancing through every vertex): the transform only changes *which*
   predictor and *which* block a residual lands in, never restarting a run, so it pays none of
   the ~10% seed cost the [delta-of-delta](failed/delta-of-delta.md) experiments incurred.

**What was left on the table.** Exact-shape repetition (32,4% of building rings share a
translation-invariant shape) was **not** exploited: 90% of repeated shapes span multiple
tiles, which a single-tile codec cannot address — the "redundancy abundant, addressing
expensive" wall the junction/reordering experiments hit. The non-parallelogram quads were
also untouched at the time; see the Stage 0 worked example below for the measured answer.

The `SegmentPair` component is still registered — it was the best on LINESTRING until
[line-continuation.md](line-continuation.md) superseded it there; the standalone
`QUAD_CORNER` was measured (-7,1% building / -1,6% corpus) and **removed once the
composition strictly dominated it**.

## Stage 0 worked example — second-order corner predictors, killed before an encoder existed

Validates the evaluation protocol (`docs/evaluation-protocol.md`) end to end on the one
population `SEGMENT_QUAD_CORNER` leaves untouched: the ~24-42% (current corpus,
layer-dependent) of four-vertex rings that are not exact parallelograms, where `p0+p2-p1` is
not exact. `QuadCornerResidualCli` (`./gradlew quadCornerResidual --args="../data/extracted
--layer building"`) measures three candidate predictors against that population's residual
**distribution** — median and tail, no bytes, no `Encoding` written — per Stage 0 of the
protocol:

```
building layer, 143.447 non-parallelogram 4-vertex rings, 286.894 residual values
predictor                            mean   median      p90      p99    p99.9
B  previous-vertex delta             4,80        5        7        8        9
P0 parallelogram (p0+p2-p1)          1,07        1        2        5        7
H  edge-continuation (2p2-p1)        5,79        6        7        8        9
D  diagonal-ratio (2nd order)        5,92        6        8       10       12
```

**Result: both new candidates are dead on arrival, and the existing predictor already wins.**
`P0` — the *parallelogram* predictor, applied to the very rings it isn't exact for — still beats
plain delta by a wide margin (median 5 → 1) because deviation from a parallelogram tends to be
small even when not zero. Neither `H` (continuation, ignoring `p0`) nor `D` (the genuinely
second-order idea: scale the incoming edge by the outgoing/incoming length ratio) improves on `B`
in median *or* tail — not a "narrow median, wide tail" case that a rANS cell might rescue, just
uniformly worse. Same pattern on `landcover` and `landuse` (checked as a reproducibility control,
not cherry-picked). **No `Encoding` was written; Stage 1's layout × codec matrix was never run,
because Stage 0 already answers the question.** This is what the protocol is for: the corner
predictor's own history above (-9,6% per-value bits, -0,0% bytes interleaved) shows a good idea
can look dead under the wrong test; this shows the converse — a probe can correctly kill an idea
*before* paying for an encoder, a round-trip test, and a benchmark column.
