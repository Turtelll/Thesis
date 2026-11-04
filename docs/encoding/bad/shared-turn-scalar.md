# Shared references + turn scalar (`QUAD_CORNER_SHARED_TURN_SCALAR`, `_ALL`)

## encoding target:
building (Polygon)

## Idea of the encoding
Shared ring references win on 4-vertex buildings next to each other, the turn scalar looked promising on larger
rectilinear rings. They target different rings, so combine them.

## How the encoding works
- 4-vertex rings: rotated and referenced as in shared ring vertices, quad corner
  for the rest.
- Even rings > 4 vertices: turn scalar (`along` + `corr`) past the second vertex, never referenced (`Rings.FOUR`).
  `Rings.ALL`: every ring may reference, the turn rule applies to the unreferenced vertices of larger rings.
- Segmented regions CORNER, CORR, INTERIOR, ALONG, START over `VARINT_SPLIT`; mode byte selects this or plain
  `QUAD_CORNER` per buffer. Selector `SIZE` (fewer bytes), `DEFLATE` (smaller after raw Deflate, default) or `FORCED`.

## How the decoder works
Read the mode byte; decode references and corners as shared quad corner, turn vertices as turn scalar.

## results
Current corpus `munich-plantiler`, `building` only, workshop, per-tile gzip vs `DELTA_ZIGZAG_VARINT`:
`QUAD_CORNER_SHARED` -10,55%, `_TURN_SCALAR` (`FOUR`, `DEFLATE`) -9,49%, `_ALL` -10,40%.

z14 vs `QUAD_CORNER_SHARED` (gzip / Brotli / zstd): `FOUR` `SIZE` +3,38% / +3,66% / +3,58%; `FOUR` `DEFLATE` +1,51% /
+0,48% / +0,52%; `ALL` `DEFLATE` +0,22% / **-1,18%** / **-1,01%**.

References on larger rings are worth more than the turn rule. One layer only, never benchmarked corpus-wide; code
removed.
