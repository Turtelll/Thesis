# Shared-vertex references — `QUAD_CORNER_SHARED`

**Status:** registered — win
**Effect:** -2,25% corpus bytes vs `QUAD_CORNER_FASTPFOR` (-7,71% `landcover`, -3,22%
`building`); as the `_SEG` add-on -2,66% corpus vs `QUAD_CORNER_SEG_FASTPFOR`
(-8,97% vs `QUAD_CORNER_FASTPFOR`, -9,06% vs `DELTA_ZIGZAG_FASTPFOR`; -9,22% with
128-value FastPFOR blocks); -5,2% corpus mean bits (all-in) vs 5,61 → 5,51 for
`QUAD_CORNER`
**Corpus:** current baseline (`munich-plantiler.mbtiles`, `data/extractedBaseline`, 358
tiles, 4.368 buffers, `--set: core -rans`)

## What it does

Adjacent polygons repeat each other's vertices exactly: row houses share walls,
landcover/landuse polygons share borders. Delta coding cannot use this because the
repeat is not adjacent in the buffer. `encoding/research/SharedVertexRef.java` is
`QUAD_CORNER` (interleaved or segmented) plus two kinds of reference, both addressed
without transmitting anything the decoder could not derive itself:

1. **Ring rotation (not transmitted).** Every polygon ring is rotated — a cyclic shift, so
   winding is kept — to start at the vertex where its longest shared stretch begins: a
   position one of the last `WINDOW = 4` rings holds, whose predecessor is not shared.
   The decoder emits the rotated rings; `GeometryEquivalence` accepts ring rotation.
2. **Entry flag, once per ring.** 1 bit: is the ring's start one of the last 4 rings'
   distinct positions? If yes, a bit-packed index (`⌈log2 candidates⌉` bits, mean 4,67)
   replaces its residual.
3. **Follow flag.** After a vertex whose exact position an earlier ring holds (the decoder
   checks this itself by hash lookup), 1 bit: is this vertex that ring's *previous* vertex?
   Two same-wound neighbours walk their shared wall in opposite directions, so the reverse
   walk is the one that repeats. If yes, no residual.

Everything not referenced keeps its `QUAD_CORNER` residual; prediction runs over the actual
(decoded or referenced) vertices, so references do not disturb other residuals. Payload:
mode byte, varint-framed flag bitmap, varint-framed index bits, varint residual count,
(`_SEG` only: first two region lengths — the decoder cannot count them, since which
vertices are referenced is known only while decoding), then the residual stream. `_SEG`
orders its regions CORNER, INTERIOR, START — see [Region order and FastPFOR block
size](#region-order-and-fastpfor-block-size).

**Per-buffer fallback.** The encoder also encodes plain `QUAD_CORNER` (same layout) and
keeps the smaller payload behind the mode byte, so a buffer with nothing shared pays 1
byte. Point/line layers therefore show ≤ +1 byte per buffer.

**Simplification against the probes:** window and "earlier" sets count polygon *rings*,
not polygons — a hole sharing a vertex with its own outer ring also counts. Nearly all
buildings have one ring.

Registered in `Baselines.core()` (`Layout.INTERLEAVED`, Varint + FastPFOR + FastPFOR128)
and `Baselines.segmented()` (`Layout.SEGMENTED`, FastPFOR + FastPFOR128). Mechanism test:
`SharedVertexRefMechanismTest`.

## Measured (current baseline corpus)

Bytes; core rows vs `QUAD_CORNER` with the same codec, `_SEG` row vs both
`QUAD_CORNER_FASTPFOR` and `QUAD_CORNER_SEG_FASTPFOR`:

| layer | SHARED_FASTPFOR | SHARED_VARINT | SHARED_SEG vs QC | SHARED_SEG vs QC_SEG |
|---|---|---|---|---|
| **corpus** | **-2,25%** | -2,44% | **-8,97%** | **-2,66%** |
| `building` | -3,22% | -3,62% | -15,48% | -4,27% |
| `landcover` | -7,71% | -8,04% | -11,04% | -7,48% |
| `landuse` | -2,71% | -2,86% | -6,55% | -2,94% |
| `water` | -0,37% | -0,28% | -3,32% | -2,31% |
| `transportation` | +0,01% | +0,00% | -6,67% | -0,17% |

The `_SEG` columns include the region-order change below; before it they read -8,72% /
-2,39% corpus. `QC_SEG` keeps START, INTERIOR, CORNER, so part of `water`'s and
`transportation`'s `_SEG` gain is the order, not references.

By zoom for the layers that share:

| segment | SHARED_FASTPFOR vs QC_FASTPFOR | SHARED_SEG vs QC_SEG |
|---|---|---|
| `building` z14 | -3,99% | -5,38% |
| `building` z13 | +0,01% | -0,01% |
| `landcover` z14 | -9,66% | -9,32% |
| `landcover` z13 | -8,46% | -8,31% |
| `landuse` z14 | -3,77% | -4,31% |
| `landuse` z13 | -4,00% | -4,11% |

Residual-bits table (`--- residual bits ---`), **all-in**: residual bit widths plus one
bit per flag plus index bits, over two values per vertex — a referenced vertex costs only
its flag/index. Always the shared stream, never the codec-dependent fallback, so layers
with nothing shared pay their flag bits here although the byte tables do not:

```
transform                      values  mean bits   vs DELTA_ZIGZAG
DELTA_ZIGZAG               10.717.858       5,82               ref
QUAD_CORNER                10.717.858       5,61             -3,5%
QUAD_CORNER_SHARED         10.717.858       5,51             -5,2%
QUAD_CORNER_SHARED_SEG     10.717.858       5,51             -5,2%
```

**It composes with segmentation almost additively.** Expected overlap: segmentation's
win on buildings is isolating ring-start residuals (the START region), and entry
references delete exactly those. Measured: on `building` z14 the references gain *more*
on top of segmentation (-4,85% before the region-order change, -5,38% after) than alone
(-3,99%). Unverified explanation: removing
large starts narrows the START region's FastPFOR blocks rather than disappearing into
interior blocks.

`building` z13 gets nothing: it shares 0,03% of vertices (Planetiler apparently merges
touching buildings at z13 — not checked against its config).

## Probes behind the design (scratch, not in the repo)

**How much is shared** (`building`, exact position repeated in a *different* polygon of
the same buffer):

| z | vertices | shared | polygons with ≥1 shared vertex |
|---|---|---|---|
| 13 | 452.765 | 0,03% | 0,54% |
| 14 | 1.719.281 | 23,55% | 46,14% |

12,06% of z14 vertices repeat an *earlier* polygon's position (the addressable part).

**Shape of the sharing** (`building` z14):

- Run length of consecutive shared vertices (vertex-weighted, rotation allowed): 1: 14,4% /
  **2: 51,3%** / 3: 8,5% / 4: 16,2% / 5+: 9,5%. Most shared walls are a single edge, so
  the entry — not the follow — carries most of the value.
- Consecutive shared pairs: 61,5% walk the source ring backwards, 16,1% forwards, 22,5%
  not adjacent in the source (probably T-junctions; not verified). Only the reverse walk
  is flagged.
- Source recency: 46,7% of shared positions come from the immediately previous polygon,
  32,7% from 9+ back.
- `--reorder feature` changes none of this: at z14, 341k polygons sit in 8,5k features,
  mostly MultiPolygons whose parts must stay in order.

**Ideal-bits ceiling** (flags priced at binary entropy, flat index), `building` z14, vs
`QUAD_CORNER` interleaved residual bits (14,87M, 8,65/vertex):

| variant | best W | net |
|---|---|---|
| no rotation, entry flag on every eligible vertex | 1 | -2,14% |
| rotation, entry flag on every eligible vertex | 4 | -4,43% |
| **rotation, entry flag only at ring start** (built) | **4** | **-4,48%** |
| rotation alone, no references | — | -0,59% |

Rotation is what makes it pay: it moves the entry to the ring start, where one flag per
ring suffices. W = "all earlier polygons" loses (-3,69%) to index cost. The built encoder
reaches -3,99% bytes on the same segment with a plain 1-bit flag bitmap instead of
entropy-coded flags.

## Entry decision: always reference

First built with "reference only if the index is narrower than the vertex's own residual
bits". That rejected 16.761 of 70.005 in-window ring starts (24%) — ones with small
residuals, often because the rotated ring starts right beside where the previous ring
ended. But no codec here charges a residual its own bit width: Varint spends ≥ 8 bits per
value, FastPFOR a block's widest value. Dropping the condition (`referenced = index >= 0`):
-0,15% corpus (FastPFOR), -0,19% (Varint), -0,23% (`_SEG`), no segment worse beyond
+0,01%. All figures above are after this change.

## Previous-ring-only entries — measured, not kept

Idea: 46,7% of shared positions come from the immediately previous polygon, so drop the
4-ring window and address only the previous ring — a narrower index (an offset into one
ring) and, optionally, no entry flag at all. `SharedVertexRef.Entry`:

- `PREV_FLAG` (`_PREV`): window 1, entry flag + offset into the previous ring.
- `PREV_OFFSET` (`_PREVOFF`): window 1, no entry flag; the offset has one extra value (the
  ring length) meaning "no match", so a hit costs `⌈log2(n+1)⌉` bits and a miss the same.

Follow flags and rotation are unchanged (rotation uses the same window). Payload bytes vs
the registered 4-ring window, same layout and codec (`--segmented` run):

| row | `_SHARED` | `_PREV` | `_PREVOFF` |
|---|---|---|---|
| interleaved FastPFOR | 12.120.751 | +0,19% | +0,89% |
| `_SEG` FastPFOR | 11.285.111 | +0,17% | +0,99% |
| interleaved Varint | 12.940.767 | +0,17% | +0,86% |

All-in mean bits: 5,51 (`_SHARED`) / 5,52 (`_PREV`) / 5,61 (`_PREVOFF`, back to plain
`QUAD_CORNER`'s 5,61).

- **The window earns its index bits.** `_PREV`'s narrower offset does not pay for the
  references to rings 2-4 back it gives up. It is still smallest on 98 buffers (oracle),
  too few to matter.
- **The entry flag is the cheap part.** Most ring starts with candidates miss — at z13
  almost nothing is shared, and at z14 54% of buildings share nothing. Without the flag
  every miss pays a full offset (~3 bits for a building, more for landcover/landuse rings)
  instead of 1 bit, which outweighs the 1 bit saved per hit. `_PREVOFF` wins 1 buffer.

Code removed after measuring; `SharedVertexRef` has only the 4-ring window.

## Region order and FastPFOR block size

The codec is `Composition(FastPFOR, VariableByte)` with `FastPFOR.BLOCK_SIZE = 256`
(JavaFastPFOR 0.3.10): the stream's last < 256 values are VariableByte, ≥ 8 bits each. The
original order START, INTERIOR, CORNER put the near-zero corner residuals (~1-2 bits packed)
in that tail — the worst region to put there. All six orders, plus 128-value blocks
(`IntCodec.FASTPFOR128`: `FastPFOR128` + `VariableByte`, same framing as mlt-core; the block
size the next MLT version adopts). Bytes vs the old `QUAD_CORNER_SHARED_SEG_FASTPFOR`
(11.339.751):

| region order | 256 block | 128 block |
|---|---|---|
| START, INTERIOR, CORNER (old) | ref | -0,34% |
| **CORNER, INTERIOR, START** (now registered) | **-0,27%** | **-0,45%** |
| INTERIOR, CORNER, START | -0,20% | -0,41% |
| START, CORNER, INTERIOR | -0,05% | -0,37% |
| INTERIOR, START, CORNER | -0,07% | -0,33% |
| CORNER, START, INTERIOR | -0,03% | -0,32% |
| per-buffer best order (oracle, signallable free in the mode byte) | -0,43% | -0,59% |

- **Starts last wins** — the two orders ending in START are the top two at both block
  sizes, as the tail argument predicts. The order is derived, not signalled.
- **Per layer, CORNER, INTERIOR, START at 256:** `water` -2,06%, `building` -0,44%,
  `transportation_name` -0,44%, `landuse` -0,37%, `transportation` -0,18%, but `landcover`
  **+0,14%** (the per-buffer oracle recovers it: -0,42%). Not built: the per-buffer choice
  (6 encodes per buffer, ~+0,16%).
- **128 blocks help segmented rows, not interleaved ones.** At the same order:
  `_SHARED_SEG` -0,18%, `QUAD_CORNER_SEG` -0,32%, `PART_START_SEG` -0,23%,
  `LINE_CONTINUATION_SEG` -0,22%; every interleaved FastPFOR row +0,04% to +0,05%
  (`QUAD_CORNER_SHARED_FASTPFOR128` +0,04%). So segmentation is worth more at 128:
  `_SHARED_SEG` vs `_SHARED` -6,63% → -6,97%. Fewer blocks straddle a region boundary and
  the VariableByte tail halves. The order effect shrinks with the tail (-0,27% at 256,
  -0,11% on top of 128). `_SHARED_SEG_FASTPFOR128` is -2,52% vs `QUAD_CORNER_SEG` at 128
  (unregistered row, measured in a temporary run).
- **128 by layer** (`_SHARED_SEG_FASTPFOR128` vs `_SHARED_SEG_FASTPFOR`, both new order):
  `water` -0,72%, `landuse` -0,69%, `landcover` -0,60%, `transportation` -0,32%, but
  `building` **+0,18%**.
- **Separate regions for misses — no gain.** Entry misses (ring start with candidates, no
  match) and follow misses (follow flag 0) as their own regions, 4 orders: -0,09% to -0,21%
  vs old, all worse than CORNER, INTERIOR, START alone (-0,27%).
- Buffers < 128 vertices (< 256 values) are all VariableByte at 256 blocks; segmentation
  cannot touch them. They are 1.917 of 4.368 buffers but only 1,9% of `_SHARED_SEG` bytes.
- The plain fallback keeps `SegmentQuadCorner`'s START, INTERIOR, CORNER order (not
  re-measured with the new one).

## Decode time

`./gradlew research --args="decode-time ../data/extractedBaseline --encoding QUAD_CORNER_SEG_FASTPFOR,
QUAD_CORNER_SHARED_SEG_FASTPFOR,QUAD_CORNER_SHARED_FASTPFOR,QUAD_CORNER_SHARED_VARINT --reps 5
--warmup 2"`, full corpus, end-to-end `Encoding.decode`, x same-codec baseline (method:
[decode-time.md](decode-time.md)):

| row | first decoder | allocation-free `State` | worst bucket (5000+), after |
|---|---|---|---|
| `QUAD_CORNER_SEG_FASTPFOR` (no references) | 0,91x | 1,06x | 1,26x |
| `QUAD_CORNER_SHARED_SEG_FASTPFOR` | 2,35x | **1,27x** | 2,93x |
| `QUAD_CORNER_SHARED_FASTPFOR` | 2,02x | 1,38x | 3,01x |
| `QUAD_CORNER_SHARED_VARINT` | 4,80x | 1,65x | 2,23x |

The first decoder's slowdown was mostly implementation: a `HashMap<Vertex, int[]>` insert
(plus an `int[]` allocation) per ring vertex, and a `LinkedHashSet` + `ArrayList` of the
window's positions built at every ring start, even when its entry flag was 0. `State` now
uses primitive open-addressing position tables and builds the candidate list only when an
entry flag is 1. The payload is byte-identical (benchmark `--segmented` CSV unchanged).

What remains is the idea's own cost: one position lookup per ring vertex, which grows with
buffer size — hence the 5000+ bucket stays at ~2,3x of the same encoding without references
(25,8 vs 11,1 ns/value). The marginal step the references add, `QUAD_CORNER_SHARED_SEG_FASTPFOR`
vs `QUAD_CORNER_SEG_FASTPFOR`, is 1,20x end-to-end for -2,66% bytes. Absolute milliseconds
moved ~45% between the two runs for the *baseline* too (machine load), so only the
within-run ratios are comparable.

## Side findings

- **`--reorder feature-rev` flips polygon winding.** `GeometryReorder.FEATURE_REV` reverses
  whole features, polygon rings included, and `GeometryEquivalence` accepts ring reversal.
  MVT uses winding to tell outer rings from holes, so the polygon rows of
  [reordering.md](reordering.md)'s `+reversal` column are not winding-safe. Not fixed here.
- **`ReorderedEncoding` ignored the inner encoding's `emitted()`.** An inner encoding that
  itself rewrites the buffer (as this one rotates rings) would fail round-trip under
  `--reorder`. Fixed: `emitted` = `inner.emitted(reorder.apply(ctx))`,
  `reordersGeometries` = either.
- **The residual-bits table only understood `VertexBufferCodec`/`SegmentedEncoding`.**
  Its sources are now measure functions (`ctx → {bitSum, count}`); existing rows print
  unchanged.

## Not measured / open

- Entropy-coded flags (the probe's ceiling suggests ~0,5M → ~0,35M flag bits on
  `building` z14); forward-walk follow (16,1% of pairs); T-junction vertices.
- Distance-ranked instead of flat index.
- `--reorder` byte numbers for these rows.
- Line layers: `transportation` junctions (see [failed/junction-backref.md](failed/junction-backref.md))
  are untouched — references only apply to polygon rings.
