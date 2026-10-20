# Signalling the turn — `RIGHT_ANGLE_TURN_FASTPFOR`

> **Revisited under split varints:** `RIGHT_ANGLE_TURN` +4,02%, `EVEN_RING_TURN` -0,19%,
> `QUAD_CORNER_TURN` -0,26% whole tileset (smallest of gzip/Brotli/zstd, against
> `DELTA_ZIGZAG_VARINT`); none is the smallest row on any layer. Stays retired. See
> [../revisit-varint-split.md](../revisit-varint-split.md).

> **Revisited under split varints** ([../revisit-varint-split.md](../revisit-varint-split.md)):
> `RIGHT_ANGLE_TURN` +4,02%, `EVEN_RING_TURN` -0,19% and `QUAD_CORNER_TURN` -0,26% whole tileset
> (smallest of gzip/Brotli/zstd); the last two are only ahead of the baseline on `building`,
> where `QUAD_CORNER` is -10,52%. Stays failed.

> **Revisited over split varints** ([../revisit-varint-split.md](../revisit-varint-split.md)):
> `RIGHT_ANGLE_TURN` +4,02%, `EVEN_RING_TURN` -0,19% and `QUAD_CORNER_TURN` -0,26% per tile
> (smallest of gzip/Brotli/zstd) against `DELTA_ZIGZAG_VARINT`, and none leads a layer; they
> stay retired.

**Status:** regression, registered (kept for its mechanism test)
**Effect:** +1,5% building, +2,3% transportation, +6,2% landcover, +8,6% water
**Corpus:** previous Munich corpus (`munich.mbtiles`) — see [../reordering.md](../reordering.md)
for the corpus-comparability banner

`RingPriorCli` measured the turn direction as a *free* per-vertex oracle (`R rot-best`, 8,81 →
7,78 bits on `building`, see [ring-predictors.md](ring-predictors.md)).
`RIGHT_ANGLE_TURN_FASTPFOR` is the honest, decodable version of that: for every interior vertex
of a >4-vertex ring it predicts `p[i-1] + rot90(prevEdge)`, picks the turn direction (cw/ccw)
with the smaller residual, and **signals it as one bit per such vertex** in a side stream. It is
the first encoding here that adds a signalled channel rather than only reordering, so it is an
`Encoding` directly (not a `VertexTransform`) and counts a second `StreamMetadata` header for the
bit stream, so `totalBytes()` carries the true signal cost.

Measured end-to-end, it is a regression on every layer — **`building` +1,5%** (3.277.603 vs
3.228.047), `transportation` +2,3%, `landcover` +6,2%, `water` +8,6%; **POLYGON +3,8%** — and
the per-buffer oracle picks it on **1 of 8366 buffers** (8 bytes). This confirms the
`RingPriorCli` inference at the byte level: the ≈1,0-bit direction saving on `building` does not
cover the 1-bit signal, and the edge *length* still carries a full residual (rot90 leaves only
1,9% of residuals ≤1 bit). Kept, with its mechanism test, so the negative result need not be
re-derived — same status as the removed `QUAD_CORNER`, but registered because there is a concrete
encoder to keep.

## Composing turn with quad-corner — `SEGMENT_QUAD_CORNER_TURN_FASTPFOR`

**Status:** regression, registered (kept for its mechanism test)
**Effect:** worse than plain SEGMENT_QUAD_CORNER on every layer

`SEGMENT_QUAD_CORNER` wins on exactly-four-vertex rings but leaves >4-vertex rings on plain delta.
The natural composition routes by ring length: a 4-ring keeps the parallelogram corner, a >4-ring
gets `RIGHT_ANGLE_TURN`'s signalled 90°-turn predictor on its interior vertices (`i >= start+2`),
and the residuals segment into four FastPFOR regions (starts | interior | corners | turns). Unlike
`SEGMENT_QUAD_CORNER` it therefore carries the signalled turn-bit side channel, so it is an
`Encoding` directly.

It is **worse than plain `SEGMENT_QUAD_CORNER` on every layer** — `building` -11,6% (against
-12,2%), `landcover` +3,6% (against -2,8%), `water` +7,5% (against -1,2%); **POLYGON -4,6% against
-8,0%**. The turn path is net-negative wherever it fires (the standalone `RIGHT_ANGLE_TURN` result
above), so adding it to the winning quad-corner path only dilutes the winner: it costs little on
`building` (few >4 rings, so only 0,6pp given back) and flips `landcover`/`water` (68%/67% >4
rings) from a win to a loss. The per-buffer oracle still picks it on **74 of 8366 buffers**
(84.126 bytes) — a small tail of strongly rectilinear buffers — but as a whole-buffer encoding it
never beats plain quad-corner. Registered with its mechanism test so the composition need not be
re-derived.
