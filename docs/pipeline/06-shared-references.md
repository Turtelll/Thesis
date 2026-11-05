# 6. Shared references (S2)

## The idea

Map geometry repeats itself exactly. Row houses share walls, landcover and landuse polygons share their
borders, and roads meet at junctions. At z14, 23,55% of the `building` vertices sit at a position another
ring also holds; in `transportation`, 38,7% of the vertices sit at a position that occurs more than once,
and 74% of those are the start or end of a line part.

Prediction cannot see these repeats, because the earlier copy is usually far back in the buffer. A
reference can: instead of a residual, the encoder writes "this vertex is the one at that earlier position".
A reference costs a flag bit and a short index, which is far less than a residual, and a vertex that is
referenced still counts as decoded, so guesses and frames after it work as before.

References are added on top of a guess. Each shared predictor is the plain predictor of the
[prediction](04-prediction.md) page plus references: `QUAD_CORNER_SHARED` and `QUAD_CORNER_SHARED_FRAME`
for rings, `LINE_CONTINUATION_SHARED_FRAME_HEADING` for lines.

## The encodings

### Rings: a window of neighbours

Rings refer to the vertices of the last four rings (`encoder/reference/RingReferences.java`). Two
neighbouring rings with the same winding walk their shared wall in opposite directions, which the encoding
uses:

1. Each ring is rotated (its winding kept) to start where its longest shared stretch begins. This is a
   free choice of the encoder and is not written.
2. Entry flag, once per ring: one bit saying whether the ring's first vertex is one of the positions in the
   window. On a hit, an index of `ceil(log2 candidates)` bits replaces the start residual.
3. Follow flag, after every vertex whose position an earlier ring holds: one bit saying whether the next
   vertex is that ring's previous vertex. On a hit, no residual at all.

```
ring A (decoded)    a0 a1 a2 a3
ring B              shares the wall a2-a1, rotated to start at a2: b0=a2 b1=a1 b2 b3
B is written as     entry=1, index of a2 | follow=1 | residual of b2 | corner residual of b3
```

### Lines: a dictionary of earlier positions

Lines refer to every distinct position of the earlier line parts in the buffer, kept in a dictionary in
first-seen order (`encoder/reference/LineReferences.java`). Junctions are at the ends of parts, so only the
ends are checked:

1. At every part start and every part end, a flag bit; on a hit an index of
   `ceil(log2 dictionarySize)` bits replaces the residual.
2. A part whose end is in the dictionary and whose start is not is reversed, so the junction comes first.
   This is not written, and it is the one place where the pipeline changes line direction.

```
part P1   a b c          dictionary {a, b, c}
part P2   d e c          reversed to c e d: entry=1, index of c | residual of e | exit=0, residual of d
```

Rings and points in a line layer keep their plain residuals.

### Fallback

The encoder writes every buffer both with and without references and keeps the smaller one. Without a
gain, the header names the plain predictor (`QUAD_CORNER`, `QUAD_CORNER_FRAME`,
`LINE_CONTINUATION_FRAME_HEADING`). A layer without shared vertices therefore pays nothing, and no mode byte
is needed.

## What the payload carries

References are the one place where the decoder cannot derive everything from the topology: which vertices
are referenced, and so how many residuals each stream holds, is only known while decoding. A block between
the header and the streams carries it:

```
varint flagsLength | flag bits | varint indicesLength | index bits | varint valueCount per stream
```

Flags and indices are bit-packed, most significant bit first.

## Decoding

The decoder reads the flag and index bits and the value counts, then walks the vertices. Where a flag is
due, it reads one; on a hit it emits the referenced position, otherwise it decodes the residual of the
plain predictor. It keeps the window of the last four rings (or the line dictionary) up to date with every
decoded vertex. Rotated rings and reversed parts are emitted as they are.

## What it is worth

- Rings: in the earlier pipeline, adding references to `QUAD_CORNER` saved 2,22 points of gzip on the whole
  corpus. `QUAD_CORNER_SHARED_FRAME` is today the default of `building`, `landcover`, `landuse`, `water` and
  `aeroway`, and the predictor the tile oracle picks most often for polygons.
- Lines: on `transportation` (gzip, against plain delta + varint), references take
  `LINE_CONTINUATION_FRAME_HEADING` from -4,97% to -10,75% in stored order, and from -9,19% to -12,06% with
  the chain order, which places connected parts next to each other.

Further reading: [shared ring vertices](../encoding/good/shared-ring-vertices.md),
[line dictionary](../encoding/good/line-shared-dictionary.md),
[shared vertices](https://github.com/Turtelll/Thesis/blob/main/research-java/docs/results/shared-vertices.md),
[line shared vertices](https://github.com/Turtelll/Thesis/blob/main/research-java/docs/results/line-shared-vertices.md).
