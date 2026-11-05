# Shared references + turn scalar (`QUAD_CORNER_SHARED_TURN_SCALAR`, `_ALL`)

> **Verdict: Rejected.** `building` -9,49% gzip against -10,55% for shared references alone; adding the turn
> scalar makes it worse.

## Encoding target
building (Polygon).

## Idea
Shared ring references win on 4-vertex buildings next to each other, the turn scalar looked promising on
larger rectilinear rings. They target different rings, so combine them.

## Intention
[Shared ring vertices](../good/shared-ring-vertices.md) and the [turn scalar](turn-scalar.md) seemed to act on
different rings, so their gains should add. `_ALL` applies the turn scalar to every eligible ring, the other
variant only where it is the smaller choice.

## How the encoding works
- 4-vertex rings: rotated and referenced as in shared ring vertices, quad corner
  for the rest.
- Even rings > 4 vertices: turn scalar (`along` + `corr`) past the second vertex, never referenced (`Rings.FOUR`).
  `Rings.ALL`: every ring may reference, the turn rule applies to the unreferenced vertices of larger rings.
- Segmented regions CORNER, CORR, INTERIOR, ALONG, START over `VARINT_SPLIT`; mode byte selects this or plain
  `QUAD_CORNER` per buffer. Selector `SIZE` (fewer bytes), `DEFLATE` (smaller after raw Deflate, default) or `FORCED`.

## How the decoder works
Read the mode byte; decode references and corners as shared quad corner, turn vertices as turn scalar.

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`, `building` only, workshop):
**`_TURN_SCALAR` -9,49%**, `_ALL` -10,40%, against **`QUAD_CORNER_SHARED` -10,55%**. No tileset figure: never
benchmarked corpus-wide.

**Decisive case against:** the two do not simply add: on larger rings the shared references are worth more than
the turn rule, so giving those rings to the turn scalar loses part of the reference gain. Only under Brotli and zstd
does `_ALL` win on z14 (-1,18% / -1,01%); under gzip it loses everywhere. Code removed.

Supporting figures (z14 vs `QUAD_CORNER_SHARED`, gzip / Brotli / zstd): `FOUR` `SIZE` +3,38% / +3,66% /
+3,58%; `FOUR` `DEFLATE` +1,51% / +0,48% / +0,52%; `ALL` `DEFLATE` +0,22% / -1,18% / -1,01%.

Source: `research-java/docs/results/shared-turn-scalar.md`.
