# Shared ring vertices

**Code names:** `QUAD_CORNER_SHARED`

> **Verdict: Kept.** -5,58% gzip on the tileset against -3,36% for quad corner alone in the same layout;
> the references are part of the polygon layer default.

## Encoding target
building, landcover, landuse, water (Polygon).

## Idea
Neighbouring polygons repeat each other's vertices exactly: row houses share walls, landcover and landuse
polygons share borders. Delta coding cannot see this because the repeat is far away in the buffer. Instead of
coding such a vertex again, point back at the earlier ring that already holds it. Rings are rotated so the
shared stretch comes first, which means one "is this a repeat?" question per ring is enough to find most
repeats; then the shared wall is walked backwards along the neighbour (two same-wound neighbours walk a shared
wall in opposite directions).

## Intention
A vertex the decoder already has should cost almost nothing, yet plain delta pays a full residual for it, and
for a ring start even a long jump. At z14, 23,55% of `building` vertices sit at a position another ring also
has. A short index into the last few rings plus one follow bit per shared vertex should be far cheaper than
those residuals. Rotating the ring start onto the shared stretch is free (ring rotation does not change the
geometry) and puts the repeats where one flag can catch them.

## How the encoding works
`QUAD_CORNER` plus references (`encoder/reference/RingReferences.java`, window `WINDOW = 4` rings):

1. **Ring rotation (not transmitted).** Each ring is cyclically shifted (winding kept) to start where its
   longest shared stretch begins: a position one of the last 4 rings holds whose predecessor is not shared.
2. **Entry flag, once per ring** (only if candidates exist): 1 bit "the ring start is one of the window's
   distinct positions". On a hit a `ceil(log2 candidates)`-bit index replaces the start residual (mean 4,67
   bits). Always referenced on a hit (an earlier "only if narrower" rule was 0,15–0,23% worse).
3. **Follow flag:** after a vertex whose exact position an earlier ring holds, 1 bit "this vertex is that
   ring's previous vertex". On a hit no residual.
4. Every other vertex keeps its `QUAD_CORNER` residual, predicted from the actual (decoded or referenced)
   vertices.
5. **Per-buffer fallback:** if the references do not make the buffer smaller, it is written as plain
   `QUAD_CORNER` (pipeline: plain predictor id in the header, no mode byte).

Pipeline payload: header, then the shared-reference block — `varint flagsLength`, flag bits,
`varint indicesLength`, index bits (MSB-first `BitWriter`), one `varint valueCount` per stream (the
decoder cannot derive them, references remove residuals) — then the residual streams.

```
ring B shares wall with ring A (decoded earlier):
A: a0 a1 a2 a3          B rotated to start at a2:  b0=a2  b1=a1  b2  b3
B written:  entry=1 idx(a2) | follow=1 (a1) | residual b2 | corner residual b3
```

## How the decoder works
1. Read the header; if it names a shared predictor, read the flag and index bit streams and the per-stream
   value counts, then the residual streams.
2. Keep a primitive position table of the last 4 rings' vertices.
3. Per ring start with candidates: read the entry flag; on 1 read the index and emit the candidate
   position, else decode a normal residual.
4. After any vertex whose position the table holds: read the follow flag; on 1 emit that source ring's
   previous vertex, no residual read.
5. All other vertices: `QUAD_CORNER` prediction + next residual of its class stream.
6. The rotated ring is emitted as is (ring rotation is geometry-equivalent).

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`, segmented split varints):
**tileset -5,58%** (`QUAD_CORNER_SHARED_SEG_VARINT_SPLIT`) against **-3,36%** for `QUAD_CORNER` in the same
layout. A per-layer gzip figure was not reported for this layout.

**Decisive case for inclusion:** the references add 2,2 points on the tileset, and the same step is worth
249.754 B gzip (2,22 points) in the pipeline. The per-buffer fallback (plain quad corner when the references
do not pay) means a buffer never gets larger. Its frame variant `QUAD_CORNER_SHARED_FRAME` is the layer
default of `building`, `landcover`, `landuse` and `water` ([ring frame prior](ring-frame-prior.md)).

Supporting figures:

- Same layout, gzip / Brotli / zstd: `QUAD_CORNER_SHARED_SEG_VARINT_SPLIT` -5,58% / -6,79% / -6,53%.
- OpenZL pipeline: old pipeline `QUAD_CORNER` → `QUAD_CORNER_SHARED` worth 220.167 B Brotli (1,96 points) /
  249.754 B gzip (2,22 points).
- *Not under the headline metric* — current corpus, FastPFOR era, bytes vs the same codec without
  references:

| layer | `SHARED_FASTPFOR` vs `QC_FASTPFOR` | `SHARED_VARINT` | `SHARED_SEG` vs `QC_SEG` |
|---|---|---|---|
| corpus | -2,25% | -2,44% | -2,66% |
| building | -3,22% | -3,62% | -4,27% |
| landcover | -7,71% | -8,04% | -7,48% |
| landuse | -2,71% | -2,86% | -2,94% |
| water | -0,37% | -0,28% | -2,31% |

- `_SEG` vs `QUAD_CORNER_FASTPFOR` -8,97% corpus, -15,48% building. All-in mean bits 5,61 → 5,51 (-5,2% vs
  `DELTA_ZIGZAG`).
- Sharing: building z14 23,55% of vertices shared (12,06% addressable), z13 0,03%. Shared runs: 51,3% length
  2; 61,5% of shared pairs walk the source backwards.
- Decode (allocation-free state): `_SHARED_SEG_FASTPFOR` 1,27x, worst bucket 2,93x; marginal step over
  `QUAD_CORNER_SEG` 1,20x.

Source: `research-java/docs/results/shared-vertices.md`, `general-purpose-compression.md`,
`openzl-pipeline.md`; the -3,36% is computed from the gzip byte totals in `revisit-varint-split.md`.
