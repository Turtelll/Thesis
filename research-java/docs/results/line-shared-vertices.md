# Shared-vertex references for lines — `LINE_CONTINUATION_SHARED_DICT`

**Status:** registered — win on `transportation`, `waterway`, `transportation_name`; decode
time of the `_SEG` row under the 1,5x limit, interleaved FastPFOR over it
**Effect:** `transportation` -8,48% vs `LINE_CONTINUATION_FASTPFOR`, `_SEG` -6,11% vs
`LINE_CONTINUATION_SEG_FASTPFOR` (-12,58% vs `DELTA_ZIGZAG_FASTPFOR`); corpus -2,25% / -1,74%.
Chicago `transportation` -6,54% / -5,33%, Tokyo -4,70% / -2,84%. The recent-parts window
(`LINE_CONTINUATION_SHARED`) gets nothing (-0,12%).
**Corpus:** current baseline (`munich-plantiler.mbtiles`, `data/extractedBaseline`, 4.368
buffers, `--set: core -rans +segmented`), plus `data/extracted-chicago` and
`data/extracted-tokyo`

## How much is shared — probe

`./gradlew sharedVertex --args="../data/extractedBaseline --layer transportation"`
(`SharedVertexCli`, no encoder). A vertex is **shared** if its exact position occurs at two or
more indices of the buffer; **cross-part** if two of those occurrences lie in different parts
(junctions); **within-part** if all lie in one part (loops); **redundant** counts occurrences
after the first — the most a back-reference could replace. Vertex-weighted per zoom.

| z | bufs | vertices | shared | cross-part | within-part | redundant | shared that are part endpoints | parts with an endpoint junction |
|---|---|---|---|---|---|---|---|---|
| 4 | 1 | 1.591 | 10,18% | 8,30% | 1,89% | 5,09% | 42,59% | 68,42% |
| 5 | 1 | 1.184 | 16,13% | 11,91% | 4,22% | 8,11% | 43,98% | 49,35% |
| 6 | 1 | 1.510 | 29,40% | 27,55% | 1,85% | 14,77% | 48,20% | 78,48% |
| 7 | 1 | 4.645 | 25,62% | 24,33% | 1,29% | 12,87% | 49,50% | 80,84% |
| 8 | 1 | 5.514 | 37,67% | 36,02% | 1,65% | 19,15% | 40,20% | 90,50% |
| 9 | 1 | 14.639 | 57,61% | 56,16% | 1,46% | 30,53% | 74,60% | 87,26% |
| 10 | 4 | 19.447 | 50,91% | 50,07% | 0,84% | 26,67% | 77,99% | 89,19% |
| 11 | 9 | 24.694 | 45,55% | 44,76% | 0,79% | 24,07% | 79,47% | 91,37% |
| 12 | 20 | 88.059 | 50,52% | 49,97% | 0,55% | 28,28% | 85,57% | 84,06% |
| 13 | 63 | 186.914 | 36,83% | 36,04% | 0,79% | 20,30% | 81,78% | 70,58% |
| 14 | 252 | 832.526 | 37,28% | 36,83% | 0,45% | 19,85% | 71,32% | 70,85% |
| **all** | 354 | 1.180.723 | **38,74%** | 38,18% | 0,56% | **20,81%** | 74,44% | 72,89% |

- Unlike `building` (z13 0,03% vs z14 23,55%, [shared-vertices.md](shared-vertices.md)),
  sharing is substantial at every zoom from z6 on. z4–z9 are one buffer each — anecdote, not a
  trend.
- The pooled 38,74% reproduces [failed/junction-backref.md](failed/junction-backref.md)'s
  37,3% from the retired `munich.mbtiles` corpus.
- 74% of shared vertices are part endpoints, so references at part starts and ends address most
  of it; interior crossings (the other 26%) are not addressed.

## What it does

`encoding/research/LineSharedVertexRef.java`: `LineContinuation` plus references to positions
that earlier **line parts** hold. Polygon rings and points in the column keep plain residuals and
are never candidates. Per line part, in buffer order:

1. **Part reversal (not transmitted).** If the part's end is a candidate and its start is not,
   the encoder reverses the part; the decoder emits the reversed part, which
   `GeometryEquivalence` accepts. The line counterpart of ring rotation.
2. **Entry flag** at the part start, only when candidates exist: 1 bit; on a hit a bit-packed
   index (`⌈log2 candidates⌉` bits) replaces the residual.
3. **Exit flag** at the part's last vertex: the same, flag plus index. The decoder knows the last
   vertex from the part lengths.

**Addressing** (`LineSharedVertexRef.Addressing`):

- `DICTIONARY` (`_DICT`, the win): every distinct position of every earlier line part, in
  first-seen order, flat index. Append-only — no per-part rebuild.
- `WINDOW`: the distinct positions of the last 4 line parts, newest first — what
  `SharedVertexRef` uses for rings.

**Per-buffer fallback:** a mode byte chooses this or plain `LineContinuation` (same layout and
codec), so a buffer with no junctions pays 1 byte. Payload framing as in `SharedVertexRef`: mode
byte, varint-framed flag bitmap, varint-framed index bits, varint residual count, `_SEG` region
lengths, residual stream. `_SEG` orders its regions starts-last (LINE_INTERIOR, INTERIOR, START).

Registered in `Baselines.core()` (`Layout.INTERLEAVED`, Varint + FastPFOR) and
`Baselines.segmented()` (`Layout.SEGMENTED`, FastPFOR), each for both dictionary tables
(`LineSharedVertexRef.Table`: `ALL_PARTS` = `_DICT`, `MARKED` = `_MARKED`, see below). The window
addressing is gone. Mechanism test: `LineSharedVertexRefMechanismTest`.

**Direction caveat.** Reversal changes line direction, which one-way rendering and
direction-dependent labelling depend on — the same objection [reordering.md](reordering.md)
raises for `feature-rev`. `GeometryEquivalence` accepts it; whether a production encoder may is
a separate decision. Not measured: the encoding without reversal.

## Measured

Bytes, same codec and layout reference: interleaved rows vs `LINE_CONTINUATION_<codec>`,
`_SEG` vs `LINE_CONTINUATION_SEG_FASTPFOR`.

| segment (Munich) | `_DICT_FASTPFOR` | `_DICT_VARINT` | `_DICT_SEG` | `_DICT_SEG` vs `DELTA_ZIGZAG_FASTPFOR` | window `_FASTPFOR` |
|---|---|---|---|---|---|
| **corpus** | **-2,25%** | -1,84% | **-1,74%** | -6,26% | -0,05% |
| `transportation` | **-8,48%** | -7,32% | **-6,11%** | **-12,58%** | -0,12% |
| `waterway` | -6,01% | -5,29% | -6,96% | -9,42% | -3,08% |
| `transportation_name` | -3,61% | -3,10% | -2,62% | -6,25% | -0,60% |
| `boundary` | -0,34% | -0,06% | -1,15% | -1,22% | +0,00% |
| polygon layers | +0,01% to +0,02% | | -0,08% to -0,14% | | |

Interleaved, polygon layers pay only the mode byte. Under `_SEG` they gain slightly instead:
with no line runs the shared stream is plain `LINE_CONTINUATION` in starts-last region order, and
that order alone sometimes beats `LINE_CONTINUATION_SEG`'s START-first order (FastPFOR's
VariableByte tail, see [shared-vertices.md](shared-vertices.md)). Part of every `_SEG` column is
that order effect, not references; the interleaved columns have none of it.

`LINE_CONTINUATION_SHARED_DICT_SEG_FASTPFOR` is the new `transportation` winner (2.681.014
bytes; previous best `LINE_CONTINUATION_SEG_FASTPFOR`, 2.855.563). The per-buffer oracle picks it
for 510 of 4.368 buffers (research oracle -10,9% vs the baseline).

**Across corpora** (`transportation`, `_DICT` without follow):

| corpus | `_DICT_FASTPFOR` vs LC | `_DICT_SEG` vs LC_SEG | `_DICT_SEG` vs `DELTA_ZIGZAG_FASTPFOR` | corpus `_DICT_FASTPFOR` / `_SEG` |
|---|---|---|---|---|
| Munich | -8,48% | -6,11% | -12,58% | -2,25% / -1,74% |
| Chicago | -6,54% | -5,33% | -9,48% | -1,16% / -1,02% |
| Tokyo | -4,70% | -2,84% | -8,67% | -0,99% / -0,69% |

It wins on all three corpora, including Chicago, where `LINE_CONTINUATION` alone is a wash
([line-continuation-by-zoom.md](line-continuation-by-zoom.md)): junctions are a property of a road
network, not of road curvature. `waterway` gains on every corpus (-4,3% to -7,0% `_SEG`).

**By zoom** (Munich `transportation`, `--by-zoom`):

| z | `_DICT_FASTPFOR` vs LC | `_DICT_SEG` vs LC_SEG | `_DICT_SEG` vs `DELTA_ZIGZAG_FASTPFOR` |
|---|---|---|---|
| 4 | -2,27% | -5,05% | -8,74% |
| 5 | -1,56% | -1,63% | -7,06% |
| 6 | -7,79% | -5,28% | -12,16% |
| 7 | -4,37% | -2,28% | -10,81% |
| 8 | -5,24% | -3,25% | -10,09% |
| 9 | -7,01% | -3,29% | -11,11% |
| 10 | -8,97% | -6,19% | -12,84% |
| 11 | -9,92% | -7,72% | -14,17% |
| 12 | **-12,95%** | **-9,67%** | **-16,86%** |
| 13 | -9,52% | -7,16% | -14,23% |
| 14 | -7,78% | -5,53% | -11,75% |

The gain follows the probe's sharing profile: largest at z10–z13, where sharing and the endpoint
share both peak. Chicago peaks at z12 too (-12,97% / -10,86%); Tokyo is flatter (-3,9% to -5,5%
on z10–z14).

## Residual bits understate it

All-in mean bits (residual widths plus flag and index bits, over two values per vertex), Munich
`transportation`: `DELTA_ZIGZAG` 6,39, `LINE_CONTINUATION` 6,25 (-2,1%), `_DICT` **6,19
(-3,0%)**. That is 1 point of mean bits for 8,5 points of bytes; on z9 the bits even rise
(+10,2% vs `DELTA_ZIGZAG`) while bytes fall 7,0%. The bits table charges a residual its own bit
width, and no codec here does: Varint spends at least 8 bits per value, FastPFOR a block's widest
value. Part-start residuals are the wide outliers in their blocks, and the references move them
into a tightly bit-packed side stream, which is the same effect
[shared-vertices.md](shared-vertices.md) "Entry decision: always reference" notes. Judge this
encoding by bytes.

## Follow flags — measured, off

Built first with the building encoding's follow flag, generalised to lines: after a vertex whose
predecessor sits at a position an earlier part holds, 1 bit says "this vertex is that source's
neighbour", plus 1 direction bit (previous/next, since two roads sharing a stretch can walk it
either way). Now the `_FOLLOW` control (`new LineSharedVertexRef(addressing, true, layout,
codec)`, not registered). Dictionary rows, `transportation`:

| corpus | `_FASTPFOR` with follow | without | `_SEG` with follow | without |
|---|---|---|---|---|
| Munich | -7,69% | **-8,48%** | -5,27% | **-6,11%** |
| Chicago | -5,87% | **-6,54%** | -4,63% | **-5,33%** |
| Tokyo | -3,94% | **-4,70%** | -2,08% | **-2,84%** |

Follow loses everywhere. Every referenced part start makes the part's second vertex
follow-eligible, and that vertex almost never continues along the other road, so most follow
flags are misses. Unlike between buildings, shared stretches of two roads are rare — 0,56% of
vertices repeat within a part, and the probe found 74% of shared vertices at endpoints.
Unverified: the hit rate itself was not counted. With follow off the decoder also drops its
per-vertex position table (see Decode time).

## Window vs dictionary

The 4-part window wins nothing (-0,12% `transportation` FastPFOR) where the dictionary wins
-8,48%. The window works for buildings because 46,7% of shared building positions come from the
immediately previous polygon. That a road's junction partner is rarely among the last 4 parts is
the obvious explanation, and consistent with
[failed/junction-backref.md](failed/junction-backref.md)'s K ≤ 32 windows failing, but source
recency was not measured here. The dictionary pays a wider flat index — `⌈log2 n⌉` bits for `n`
distinct earlier positions, growing through the buffer — and still wins, since a part-start
residual against the previous vertex (a jump across the tile) costs more in either codec. Index
widths were not tallied. Why this works where junction back-references failed: the flag makes a
miss cost 1 bit instead of an index, part ends are addressed too, reversal moves end-only
junctions to the start, and the candidate set is the whole buffer instead of a window.

## Decode time

`./gradlew decodeTime --args="../data/extractedBaseline --encoding LINE_CONTINUATION_FASTPFOR,
LINE_CONTINUATION_SEG_FASTPFOR,LINE_CONTINUATION_SHARED_DICT_FASTPFOR,
LINE_CONTINUATION_SHARED_DICT_SEG_FASTPFOR,LINE_CONTINUATION_SHARED_DICT_VARINT --layer
transportation --reps 5 --warmup 2"`, end-to-end `Encoding.decode`, x same-codec baseline, two
runs ([decode-time.md](decode-time.md); absolute times moved ~2x between runs with machine load,
so only within-run ratios compare):

| row | first decoder | without follow state, run 1 / run 2 | 5000+ bucket, run 1 / run 2 |
|---|---|---|---|
| `LINE_CONTINUATION_SEG_FASTPFOR` (no references) | 1,14x | 1,03x / 1,07x | 0,97x / 1,16x |
| `LINE_CONTINUATION_SHARED_DICT_SEG_FASTPFOR` | 1,90x | **1,44x / 1,47x** | 1,88x / 2,01x |
| `LINE_CONTINUATION_SHARED_DICT_FASTPFOR` | 1,92x | 1,57x / 1,86x (over) | 2,05x / 2,63x |
| `LINE_CONTINUATION_SHARED_DICT_VARINT` | 2,79x | 1,73x / 1,48x | 1,53x / 1,59x |

"First decoder" is a single earlier run, before the change below.

The first decoder still maintained the follow state — a position table and part bounds per
vertex — although follow was off; skipping it (payload byte-identical) brought `_SEG` under the
limit. What remains is the dictionary itself: one position-table lookup and insert per line
vertex, so the cost grows with buffer size. The marginal step over `LINE_CONTINUATION_SEG` is
1,37x–1,41x end-to-end for -6,11% bytes.

## Marked table — `_MARKED`, the fast-decode variant

`new LineSharedVertexRef(Table.MARKED, layout, codec)`, registered beside `_DICT` (Varint +
FastPFOR interleaved, FastPFOR `_SEG`). Same predictor, reversal, entry/exit flags and framing;
only what enters the dictionary changes. The encoder sees the whole buffer, so it knows which
positions recur: after every entry or exit that was **not** referenced it writes a **remember
bit**, 1 if the position is a line part end again later in the buffer, and the position is
appended to the dictionary at once (so a closed line's exit can reference its own entry). Interior
vertices never enter. Every entry is used at least once, which narrows the index, and the decoder
only appends to an array — no position table, no per-vertex dedup lookup. The idea behind it: an
explicit table of repeated positions at the buffer start, minus the cost of transmitting the
positions twice (the first occurrence stays an inline residual). Mechanism tests:
`markedConnectorRemembersOnlyRecurringEnds`, `markedTableMissesAJunctionOntoAnInterior`.

Bytes vs `LINE_CONTINUATION` with the same codec and layout, Munich (`--segmented`):

| segment | `_DICT_FASTPFOR` | `_MARKED_FASTPFOR` | `_DICT_VARINT` | `_MARKED_VARINT` | `_DICT_SEG` | `_MARKED_SEG` |
|---|---|---|---|---|---|---|
| **corpus** | **-2,25%** | -1,45% | **-1,84%** | -1,12% | **-1,74%** | -1,08% |
| `transportation` | **-8,48%** | -5,61% | **-7,32%** | -4,59% | **-6,11%** | -3,72% |
| `waterway` | **-6,01%** | -5,17% | **-5,29%** | -4,59% | **-6,96%** | -6,22% |
| `transportation_name` | **-3,61%** | -1,53% | **-3,10%** | -1,13% | **-2,62%** | -0,97% |
| `boundary` | **-0,34%** | +0,33% | **-0,06%** | +0,33% | **-1,15%** | -0,51% |

`_MARKED` loses to `_DICT` on every line layer and codec: `transportation` +3,13% (FastPFOR),
+2,55% `_SEG` (2.749.340 vs 2.681.014 bytes). All-in mean bits, corpus: 5,79 vs 5,76. The
narrower index does not pay for what the table gives up. Two costs, not separated: the remember
bit on every unreferenced part end, and T-junctions onto an earlier road's interior, which `_DICT`
references from the first branch on and `_MARKED` only from the second (the interior occurrence
cannot be marked; the first branch's own end can). Which dominates is unverified.

Decode time, `--layer transportation --reps 5 --warmup 2`, end-to-end vs `DELTA_ZIGZAG_FASTPFOR`,
two runs back to back (within-run ratios only):

| row | corpus, run 1 / run 2 | 5000+ bucket, run 1 / run 2 |
|---|---|---|
| `LINE_CONTINUATION_SEG_FASTPFOR` (no references) | 1,21x / 1,00x | 1,42x / 1,10x |
| `LINE_CONTINUATION_SHARED_DICT_SEG_FASTPFOR` | 1,85x / 1,59x (over) | 2,69x / 2,23x |
| `LINE_CONTINUATION_SHARED_MARKED_SEG_FASTPFOR` | **1,15x / 1,24x** | 1,54x / 1,61x |
| `LINE_CONTINUATION_SHARED_DICT_FASTPFOR` | 1,71x / 1,76x (over) | 2,53x / 2,52x |
| `LINE_CONTINUATION_SHARED_MARKED_FASTPFOR` | **1,37x / 1,15x** | 1,86x / 1,45x |

Dropping the position table removes most of the reference overhead: `_MARKED_SEG` decodes within
~0,2x of plain `LINE_CONTINUATION_SEG`, `_DICT_SEG` 0,6x–0,85x above it (and over the 1,5x limit
in both runs here, under it in the earlier runs above — machine load). So the trade on
`transportation` is -3,72% instead of -6,11% bytes for roughly a third of `_DICT`'s decode
overhead. `_DICT` stays the bytes winner; `_MARKED` is the choice if decode time is binding.

## In the pipeline — `LINE_CONTINUATION_SHARED[_FRAME_HEADING]`, and the reorder under it

`Prediction.LINE_CONTINUATION_SHARED` and `LINE_CONTINUATION_SHARED_FRAME_HEADING` (`_DICT` table) run
`LineSharedVertexRef`'s references inside `PipelineEncoding`; a buffer the references do not shrink is
written under the plain predictor id. Rows: `./gradlew pipeline --args="--step linesshared --layer
transportation --by-zoom"`; mechanism test `pipeline/LineSharedMechanismTest`.

Munich `transportation`, geometry buffers alone, per-tile gzip vs `DELTA_ZIGZAG_VARINT` (class streams,
escape width, byte planes):

| reorder | `LINE_CONTINUATION_FRAME_HEADING` | `…_SHARED_FRAME_HEADING` |
|---|---|---|
| none | -4,97% | -10,75% |
| `HILBERT_CENTROID_ROT_MP` | -6,89% | -11,59% |
| `CHAIN` | -9,19% | -12,06% |

The chain beats Hilbert on lines, and by less once the references take the junction starts it would
have shortened. The reorder also permutes the property columns, so the geometry figure alone is not
the choice. `./gradlew pipelineTileSize --args="../data/MVT/munich-plantiler.mbtiles --joint
transportation [--ids sequential]"` compresses the layer's pipeline geometry payload and its
property-only tile together, once per tile (a stand-in for the tile: the MLT writer cannot take the
pipeline's geometry bytes; the single pass is 0,1% to 1% larger than the two compressed alone).
Change vs the previous default (Hilbert, no references), gzip / brotli9 / zstd19:

| composition | ids kept | ids regenerated |
|---|---|---|
| no reorder, no references | -2,64% / -3,36% / -2,71% | +0,62% / -0,16% / +0,74% |
| no reorder + references | **-7,54% / -8,01% / -7,43%** | -4,83% / -5,46% / -4,52% |
| Hilbert + references | -4,02% / -4,54% / -3,88% | -4,42% / -4,83% / -4,19% |
| `CHAIN` + references | -4,83% / -5,12% / -4,65% | **-5,14% / -5,55% / -4,86%** |

With ids kept, any reorder costs more in the property columns than it saves (+20% to +23% there); with
ids regenerated (MLT does not preserve feature order) `CHAIN` leads no reorder by 0,3 to 0,4 points in
all three backends. `PipelineRegistry.forLayer("transportation")` is therefore `CHAIN` +
`LINE_CONTINUATION_SHARED_FRAME_HEADING` (geometry alone -12,06% gzip, -15,87% brotli, -13,09% zstd).
`PipelineTileSizeCli` now derives the real chain permutation (`GeometryReorder.featureOrder`) instead
of leaving `CHAIN` as a no-op. Munich only; the `--ids sequential` property figures regenerate ids as
0, 1, 2, .. in feature order.

## Not measured / open

- The encoding without reversal (no direction change).
- Distance- or recency-ranked dictionary index instead of flat.
- `_MARKED`: split its loss into remember-bit cost vs lost interior junctions; a remember bit on
  interior vertices too (one bit per line vertex, likely too expensive at ~18% density).
- Interior crossings (26% of shared vertices): would need a per-vertex flag.
- Combining with ring references in one encoding (`QUAD_CORNER_LINE` predictor plus
  `SharedVertexRef` rings plus these line references) for mixed columns.
- `--reorder` interaction: greedy chaining ([reordering.md](reordering.md)) already turns many
  junction starts into zero deltas, so the two will overlap.
