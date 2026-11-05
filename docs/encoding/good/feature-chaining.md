# Nearest-endpoint feature chaining

**Code names:** `--reorder feature` / `feature-rev`, pipeline `CHAIN`

> **Verdict: Kept, on line layers.** `transportation` -5,48% gzip against -3,82% for the Hilbert order; the
> reorder of the `transportation` default.

## Encoding target
transportation (LineString), boundary, building and other polygon layers; point layers unchanged.

## Idea
Every time a new feature starts, the delta cursor pays a jump from where the previous feature ended. The order
of features in a tile carries no information (the MLT spec allows sorting for compression), so put the
features in an order where each starts close to where the previous one ended. Nothing has to be transmitted,
the decoder just reads features in the order they are stored.

## Intention
A space-filling curve ([Hilbert reorder](hilbert-curve-order.md)) sorts features by where they *are*, but a line
jump goes from where the previous feature *ends* to where the next one *starts*. For long road segments those
are far apart, so a greedy walk from endpoint to nearest endpoint should shorten line jumps more than any
curve; where segments meet exactly, the walk finds a start that is a zero jump. For points, which have no
"end", the curve should stay better.

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

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`, pipeline step S1, geometry only):
**`transportation` -5,48%** with `CHAIN` against **-3,82%** for Hilbert; **tileset -2,42%** against Hilbert
-3,53% (chaining loses on points: `poi` +3,29% vs -19,60%).

**Decisive case for inclusion:** on lines it beats the Hilbert order, also on top of the line references
(`transportation` -12,06% vs -11,59% Hilbert vs -10,75% none), and on the joint tile (geometry + properties,
ids regenerated) the `transportation` default with `CHAIN` is -5,14% gzip against the previous default. So
`forLayer` uses it on `transportation`, `transportation_name`, `waterway`, `water` and `aeroway`, where its
property columns are no larger than Hilbert's net of the geometry gain. It is not the tileset-wide order
because it loses on point layers.

Supporting figures:

- `CANON_CHAIN` -2,55% tileset (same metric). Tile oracle: `CHAIN` on 22,5% of buffers, drop cost +0,295%.
- Older encoding set, current corpus: `QUAD_CORNER_SHARED_REORDER` -7,27% gzip (computed from the gzip byte
  total) vs `QUAD_CORNER_SHARED` -5,58%; smallest of gzip/Brotli/zstd -9,39% vs -7,86%.
- *Not under the headline metric* — retired corpus `munich.mbtiles`, FastPFOR bytes: `DELTA_ZIGZAG` -10,2%
  (reorder) / -11,8% (+rev); `SEGMENT_QUAD_CORNER` -15,8% with rev. `transportation` -17,6%, `boundary`
  -22,3%, `building` -13,5%. Overlap with part-start grouping 2,09 points. NY corpus: -11,2%
  (`DELTA_ZIGZAG_FASTPFOR`, rev), `SEGMENT_QUAD_CORNER` -14,5%, z14 -10,4%.
- Caveats: property columns grow with any reorder (ids kept: +20–23% on `transportation`); `-rev` destroys
  line direction and flips polygon winding.

Source: `research-java/docs/results/openzl-pipeline.md`, `reordering.md`, `line-shared-vertices.md`,
`tile-oracle.md`, `revisit-varint-split.md`.
