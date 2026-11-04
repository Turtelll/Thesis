# Turn scalar (`TURN_SCALAR`, Stage 0 probe and encoder)

## encoding target:
building (Polygon), even rings with more than 4 vertices; also landcover, landuse, water

## Idea of the encoding
In a rectilinear ring the next edge is the previous one turned by 90°; only its length is new. So a 90° turn should
cost one value (how far along the rotated direction) instead of a 2D residual. The turn direction comes from the
ring's winding, so it does not have to be signalled.

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

## results
- **Stage 0 probe** (retired corpus): only 12,87% of building turns are exactly 90° and 2,83% have an integer-multiple
  edge; `S2-along` mean 5,15 bits. Killed. Re-run (current corpus, per vertex): even `n > 4` 7,24 bits vs delta 8,21
  (-11,8%), landcover +28%.
- **Encoder** (current corpus, even `n > 4` only, bytes vs `QUAD_CORNER`): `building` +11,8% FastPFOR / +3,2% `_SEG`,
  `landcover` +17,5% / +17,1%, `landuse` +15,7% / +14,6%, `water` +15,8% / +15,7%. `along` costs as much as a delta
  component; `corr` median 0 but p99 6–7 bits.
- **Split varints, per tile:** `TURN_SCALAR_SEG_VARINT_SPLIT` -2,75% whole tileset; `building` -10,09% vs
  `QUAD_CORNER` -10,52%; other polygon layers +9,61% to +12,88%.
- **Pipeline** (`building` composition, per tile vs `DELTA_ZIGZAG_VARINT`): `TURN_SCALAR` -7,09% gzip / -13,06% Brotli
  / -9,38% zstd vs `QUAD_CORNER` -9,20% / -13,61% / -10,41%.
- **Tile oracle:** 0,9% of picks, drop cost 44 B. **Retired** (header code 4).
- Combined with shared references: shared references + turn scalar.
