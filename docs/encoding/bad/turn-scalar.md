# Turn scalar (`TURN_SCALAR`, Stage 0 probe and encoder)

> **Verdict: Rejected.** `building` -7,09% gzip in the pipeline against -9,20% for quad corner; +0,30% on the
> tileset (split varints). Retired by the tile oracle.

## Encoding target
building (Polygon), even rings with more than 4 vertices; also landcover, landuse, water.

## Idea
In a rectilinear ring the next edge is the previous one turned by 90°; only its length is new. So a 90° turn
should cost one value (how far along the rotated direction) instead of a 2D residual. The turn direction comes
from the ring's winding, so it does not have to be signalled.

## Intention
The [signalled turn](right-angle-turn.md) failed because the edge length still cost a full 2D residual. Coding
the turn as one scalar along the rotated direction (plus a small correction across it) should halve the cost of
every right-angle vertex in the larger building outlines that quad corner does not reach.

## How the encoding works
- 4-vertex rings: quad corner `p0 + p2 - p1`.
- Even `n > 4` rings, vertices with `i - start >= 2`: `u = rot90(prevEdge)` (CCW for outer, CW for inner rings, from
  `numParts`); stored `along = round(dot(w, u) / |u|)` and `corr = w - round(along · u / |u|)` → 3 values per vertex
  (`w` the actual edge).
- Everything else plain delta. `_SEG` regions: start | interior | corner | along | corr. Pipeline: `along` as a side
  stream after the residual streams (always `INTERLEAVED_PLANES`). Code in `encoder/retired/TurnScalar.java`.

## How the decoder works
For a turn vertex compute `u` from the decoded previous edge and the winding, read `along` and `corr`, reconstruct
`w = round(along · u / |u|) + corr`, `p[i] = p[i-1] + w`. A `valueCount` hook tells the decoder the stream length.

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`): pipeline `building` composition
**`TURN_SCALAR` -7,09%** against **`QUAD_CORNER` -9,20%**; old encoding set, segmented split varints,
**tileset +0,30%** (`TURN_SCALAR_SEG_VARINT_SPLIT`, computed from the gzip byte total).

**Decisive case against:** the `along` value costs as much as a delta component, because edge lengths vary;
the correction across has median 0 but a tail of 6–7 bits (p99). So a turn vertex is not cheaper than a delta,
and on non-rectilinear polygon layers it is much worse (+9,61% to +12,88%, smallest of three). In the pipeline
the tile oracle picked it on 0,9% of buffers, drop cost 44 B; retired (header code 4).

Supporting figures:

- Smallest of gzip/Brotli/zstd, split varints: tileset -2,75%; `building` -10,09% vs `QUAD_CORNER` -10,52%.
- Pipeline `building`: `TURN_SCALAR` -7,09% gzip / -13,06% Brotli / -9,38% zstd vs `QUAD_CORNER` -9,20% /
  -13,61% / -10,41%.
- *Not under the headline metric* — Stage 0 probe (retired corpus): only 12,87% of building turns are exactly
  90° and 2,83% have an integer-multiple edge; `S2-along` mean 5,15 bits. Re-run (current corpus, per vertex):
  even `n > 4` 7,24 bits vs delta 8,21 (-11,8%), landcover +28%.
- *Not under the headline metric* — encoder (current corpus, even `n > 4` only, bytes vs `QUAD_CORNER`):
  `building` +11,8% FastPFOR / +3,2% `_SEG`, `landcover` +17,5% / +17,1%, `landuse` +15,7% / +14,6%, `water`
  +15,8% / +15,7%.
- Combined with shared references: [shared references + turn scalar](shared-turn-scalar.md).

Source: `research-java/docs/results/failed/turn-scalar.md`, `failed/turn-scalar-encoder.md`,
`revisit-varint-split.md`, `tile-oracle.md`.
