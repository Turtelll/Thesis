# Nearest-endpoint feature chaining (`--reorder feature` / `feature-rev`, pipeline `CHAIN`)

## encoding target:
transportation (LineString), boundary, building and other polygon layers; point layers unchanged

## Idea of the encoding
Every time a new feature starts, the delta cursor pays a jump from where the previous feature ended. The
order of features in a tile carries no information (the MLT spec allows sorting for compression), so put
the features in an order where each starts close to where the previous one ended. Nothing has to be
transmitted, the decoder just reads features in the order they are stored.

## How the encoding works
Pre-pass `GeometryReorder` (pipeline `Reorder.CHAIN`):
- Start from the current cursor; repeatedly take the unused feature whose entry endpoint is nearest the
  cursor (Manhattan distance, what the two componentwise delta cursors pay), emit it, move the cursor to its
  exit endpoint.
- `feature-rev`: also emit a feature backwards when its far endpoint is nearer (reverses lines and ring
  winding).
- A multi-part feature's parts stay contiguous and in order. A buffer whose runs are not derivable is left
  untouched.
- The topology streams and property columns are permuted with the features.

```
stored:    F1(end near F7) F2 F3 ...      jumps: 900, 1200, ...
chained:   F1 F7 F4 F2 ...                jumps:  10,   40, ...
```

## How the decoder works
No change. The decoder reads the reordered topology streams and emits the vertices in the stored order;
`GeometryEquivalence` checks the multiset of parts is unchanged (parts compared up to reversal).

## results
- **Retired corpus `munich.mbtiles`, FastPFOR:** `DELTA_ZIGZAG` -10,2% (reorder) / **-11,8%** (+rev);
  `SEGMENT_QUAD_CORNER` -15,8% with rev. `transportation` -17,6%, `boundary` -22,3%, `building` -13,5%.
  Overlap with part-start grouping 2,09 points.
- **NY corpus:** -11,2% (`DELTA_ZIGZAG_FASTPFOR`, rev), `SEGMENT_QUAD_CORNER` -14,5%, z14 -10,4%.
- **Current corpus, general-purpose compression:** `QUAD_CORNER_SHARED_REORDER` -9,39% smallest-of-three
  (Brotli) vs -7,86% without.
- **OpenZL pipeline S1** (current corpus, per tile vs `DELTA_ZIGZAG_VARINT` gzip): `CHAIN` -2,42%,
  `CANON_CHAIN` -2,55% vs Hilbert -3,53% overall; chaining wins on lines (`transportation` -5,48% vs -3,82%)
  but loses on points (`poi` +3,29% vs -19,60%).
- `transportation` with line references: `CHAIN` -12,06% vs Hilbert -11,59% vs none -10,75%; with ids
  regenerated the joint tile (geometry + properties) is -5,14% gzip vs previous default, so
  `forLayer("transportation")` uses `CHAIN`; also `transportation_name`, `waterway`, `water`, `aeroway` (where the
  chain's property columns are no larger than Hilbert's net of the geometry gain).
- Tile oracle: `CHAIN` picked on 22,5% of buffers, drop cost +0,295%.
- **Caveats:** property columns grow with any reorder (ids kept: +20–23% on `transportation`); `-rev`
  destroys line direction and flips polygon winding.
