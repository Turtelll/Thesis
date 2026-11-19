# Ring frame prior

**Code names:** `QUAD_CORNER_PREVFRAME_FIRST_ONLY` (code 23), `QUAD_CORNER_SHARED_PREVFRAME_FIRST_ONLY` (code 25)

> **Verdict: Kept.** `building` -12,38% gzip against -9,20% for plain quad corner; part of the layer
> default of every large polygon layer.

## Encoding target
building, landcover, landuse, water, aeroway (Polygon, `QUAD_CORNER_SHARED_PREVFRAME_FIRST_ONLY`); place
(`QUAD_CORNER_PREVFRAME_FIRST_ONLY`).

## Idea
A building's walls sit at a few angles, and neighbouring buildings along a street share those angles. A wall
that is not parallel to the tile axes has a large delta in both x and y. Rotate the edge into the wall's own
frame first, and the wall becomes one large component and one near-zero one. After the Hilbert reorder the
ring decoded just before is usually a neighbour, so its frame is the guess for the next ring's first wall;
that wall's own direction is the frame of the rest of the ring. Nothing is transmitted.

## Intention
Quad corner only helps the one predicted corner; the other edges of a ring stay plain deltas, and a wall at
30° costs two full-size numbers. Rotating into a predicted frame turns one of the two numbers into almost
zero for every wall that follows it. One vote per ring (its first edge) instead of a neighbour search and a
vote per edge keeps most of that gain at a fraction of the decode time.

## How the encoding works
Mechanism: `research-rust/mlt-pipeline/src/frame.rs` (`FramePrior`), bit for bit with Java
`encoder/prediction/mechanism/FramePrior.java`.

- **Frame:** a wall direction modulo 90° as an integer index, 4096 frames over 90°.
- **First edge** of a ring (vertex 1): rotated into the frame of the previous ring (the previous run of at
  least three vertices, so a hole follows its outer ring). The first ring of a buffer uses the tile axes.
- **Later edges:** rotated into the frame of the ring's own first edge; that frame is also what the next
  ring starts from. A first edge of length zero keeps the previous ring's frame.
- **Which vertices:** the plain-delta vertices of polygon rings except the ring start (what the corner
  predictor leaves to delta). Starts and predicted corners are unchanged.
- **Rotation:** residual `(dx, dy)` rotated with three lifting shears; each shear is an integer bijection,
  so the rotation is exactly invertible and the stream keeps two values per vertex. Shear coefficients from
  Q16 tables, directions from an integer `atan` table: no `atan2`, `sin` or `cos` in the decoder.
- Code 25 = the same frame on top of shared ring vertices.

```
wall at 30°:   plain delta (87, 50)   →  framed (100, 0)
```

## How the decoder works
1. Decode the vertices in order, keeping one remembered frame.
2. At a ring start, take the remembered frame.
3. For each framed vertex: read the residual, apply the inverse three-shear rotation with the current frame,
   add to the guess. After the first edge, set the frame from its direction and remember it.
4. Unframed vertices (starts, corners, references) decode as in quad corner / shared ring vertices.

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`, `building`, 310 buffers,
`HILBERT_CENTROID_ROT_MP`, `CLASS`, escape, byte planes): **-12,38%** with the frame against **-9,20%** for
`QUAD_CORNER` alone.

| `building` | gzip | Brotli | zstd |
|---|---:|---:|---:|
| `QUAD_CORNER` | -9,20% | -13,60% | -10,41% |
| `QUAD_CORNER_PREVFRAME_FIRST_ONLY` | **-12,38%** | **-19,47%** | **-15,03%** |

**Decisive case for inclusion:** it keeps 91% (Munich) and 82% (Tokyo) of what the earlier nearest-ring
frame gained on `building` over `QUAD_CORNER`, for 38 to 40% of its whole-tile decode overhead.

Supporting figures (whole MLT v2 tiles, Munich, the five ring layers of the per-layer default switched):

- Code 23 against `QUAD_CORNER`: 112.250 bytes smaller, decode 3,04 to 3,11 x stock against 2,45 to 2,48.
- Code 25 against `QUAD_CORNER_SHARED`: 110.174 bytes smaller, decode 3,83 to 3,87 x stock against 3,20
  to 3,21.
- Chicago (axis-aligned grid): every frame loses to plain `QUAD_CORNER`, so the selector takes no frame there.

Source: `research-java/docs/results/previous-frame.md`, `frame-decode-time.md`.
