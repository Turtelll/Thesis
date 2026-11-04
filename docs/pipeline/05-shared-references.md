# 5. S2 Shared references

## What it does
Neighbouring polygons repeat each other's vertices (row houses share walls, landcover polygons share
borders), and roads meet at junctions. Delta coding cannot see repeats that are far back in the buffer. A
shared predictor writes such a vertex as a **reference** to the earlier position instead of a residual.

Predictors: `QUAD_CORNER_SHARED` (7), `QUAD_CORNER_SHARED_FRAME` (8) for rings,
`LINE_CONTINUATION_SHARED_FRAME_HEADING` (13) for lines. Every vertex that is not a reference keeps the
plain predictor's residual (step 4); referenced vertices still count as decoded for predictions and frames.

## How it works: rings
`encoder/reference/RingReferences.java`, window `WINDOW = 4` rings.

1. **Rotation (not written):** each ring is rotated, winding kept, to start where its longest shared stretch
   begins.
2. **Entry flag** once per ring (only if candidates exist): 1 bit "the ring start is one of the window's
   positions"; on a hit a `ceil(log2 candidates)`-bit index replaces the start residual.
3. **Follow flag** after a vertex whose position an earlier ring holds: 1 bit "the next vertex is that ring's
   previous vertex" (same-wound neighbours walk a shared wall in opposite directions). On a hit, no residual.

```
A: a0 a1 a2 a3 (decoded)      B rotated to start at a2:  b0=a2  b1=a1  b2  b3
B written:  entry=1 idx(a2) | follow=1 | residual b2 | corner residual b3
```

## How it works: lines
`encoder/reference/LineReferences.java`.

1. **Dictionary:** every distinct position of earlier line parts, first-seen order, append-only.
2. At every part **start** and **end**: a flag, and on a hit a `ceil(log2 dictionarySize)`-bit index.
3. A part whose end is in the dictionary and whose start is not is **reversed** (not written; changes line
   direction).
4. Rings and points keep plain residuals.

## Fallback
`PipelineEncoding.choose` encodes the buffer both ways and keeps the smaller. Without a gain the buffer is
written under the plain predictor's code (`QUAD_CORNER`, `QUAD_CORNER_FRAME`,
`LINE_CONTINUATION_FRAME_HEADING`), so a layer without shared vertices pays nothing and no mode byte exists.

## Payload
Between the header and the streams:

```
varint flagsLength | flags | varint indicesLength | indices | varint valueCount per stream
```

Flags and indices are bit-packed MSB-first. The value counts are explicit because which vertices are
references (and leave their stream) is only known while decoding.

## How the decoder works
Read the flag and index bits and the value counts, then walk the vertices: where a flag is due, read it; on
a hit emit the referenced position, otherwise decode the residual of the plain predictor. Keep the
window of the last 4 rings (or the line dictionary) up to date with every decoded vertex. Rotated rings and
reversed parts are emitted as they are.

More: [../encoding/good/shared-ring-vertices.md](../encoding/good/shared-ring-vertices.md),
[../encoding/good/line-shared-dictionary.md](../encoding/good/line-shared-dictionary.md),
[line-shared-vertices.md](https://github.com/Turtelll/Thesis/blob/main/research-java/docs/results/line-shared-vertices.md),
[shared-vertices.md](https://github.com/Turtelll/Thesis/blob/main/research-java/docs/results/shared-vertices.md).
