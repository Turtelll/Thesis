# Shared residual references

**Code names:** `QUAD_CORNER_FRAME_RESIDUAL_WINDOW`, `_ALIGNED`, `_RUN`; the same for `QUAD_CORNER_SHARED_FRAME_`,
`LINE_CONTINUATION_FRAME_HEADING_` and `LINE_CONTINUATION_SHARED_FRAME_HEADING_` (12 predictors, codes 14–25)

> **Verdict: Not adopted.** `building` gzip -4,81% against `QUAD_CORNER_SHARED_FRAME` in the same layout (-4,45%
> with the Hilbert reorder), but Brotli +1,28% / zstd +1,02% and no gain on any other layer.

## Encoding target
Every layer; measured on building, landcover, landuse, water (Polygon) and transportation, waterway, poi.

## Idea
Share the residuals instead of the vertices. After prediction (`QUAD_CORNER`, `LINE_CONTINUATION`) and the
frame, every vertex that carries a pair writes `(x, y)`. When that pair already occurred, write a reference to
it. This also catches vertices at different positions that have the same shape, which position references miss.

## Intention
Houses of the same type repeat their corner residuals exactly, and so do road stubs; a reference costs a flag and
a few index bits against two varints. At z14 `building` was expected to be the case where it pays.

## How the encoding works
`encoder/reference/ResidualReferences.java`, over every residual stream (all classes, all geometry types):

1. **`WINDOW`:** one flag per vertex; a hit is a pair among the last 16 distinct pairs of the same stream
   (move-to-front), written as a 4-bit index.
2. **`ALIGNED`:** per run (ring or part) one flag that the run repeats one of the last 4 runs of the same kind
   and length; then one "copy the counterpart" flag per vertex.
3. **`RUN`:** per run one flag that the whole run repeats an earlier one (start vertex excluded, at least 3 hits).
4. **Per-stream enable mask**, chosen greedily by trial encodes: a stream whose pairs do not repeat writes no flags.
5. **Fallback chain per buffer:** the residual candidate, its position-shared base, the plain base; the smallest
   raw payload wins and the header names it (no mode byte).

The `replace` variants take the framed base; the `stack` variants take the position-shared base and share what
the position references left. Payload layout: `research-java/docs/bitstream.md`.

## How the decoder works
Reads the position block (stacked variants), then the mask, the flag and index bit streams and one value count
per stream. For every vertex it skips position-referenced ones, otherwise either takes the referenced pair (and
adds it to the move-to-front list or run history) or reads the next pair from its stream; then it adds the
prediction and inverts the frame as before.

## Results
**Headline** (gzip per tile, `munich-plantiler`, `building`, class segmentation, escape width, interleaved planes):
`QUAD_CORNER_SHARED_FRAME` 3.910.045 B → `_RESIDUAL_WINDOW` 3.721.786 B (**-4,81%**; vs `DELTA_ZIGZAG_VARINT`
+3,41% → -1,57%). With the Hilbert reorder 3.839.840 → 3.668.942 B (-4,45%).

**Decisive case against adoption:**
- The win is gzip only: Brotli +1,28% and zstd +1,02% vs the same base (Hilbert: +1,06% / +0,73%).
- All of it is at z14 (-6,53% vs plain delta against -0,86% for the base); at z13 the references lose 0,2 points.
- `ALIGNED` and `RUN` do nothing (within ±0,1%): 1,33% and 0,24% of pairs hit against 17,53% for `WINDOW`.
- Landcover, landuse, water, transportation, waterway and poi: no gain (0,00% to +0,12% gzip); the flat
  segmentation loses with `WINDOW` (+0,5% to +1,5%).
- On the line base for `building` (not a layer default) `WINDOW` is +0,52% worse than its base.

The rows stay in `residualshared` and the header codes 14–25 stay allocated; grids and `forLayer` are unchanged.
Decode time and the tile oracle were not run on them.

Source: `research-java/docs/results/shared-residuals.md`.
