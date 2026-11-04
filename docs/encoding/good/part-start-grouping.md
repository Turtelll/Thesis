# Part-start grouping (`PART_START_SEG`, formerly `SEGMENT_PAIR`)

## encoding target:
transportation (LineString), boundary, also polygon layers; point-only layers unchanged

## Idea of the encoding
`transportation` is not long polylines but a fragmented road graph: 70,4% of its parts have exactly two
vertices. The delta from the end of one part to the start of the next (the "jump") is much wider than
the deltas inside a part, and there are many of them. A bit-packing codec (FastPFOR) sizes a block by its
widest value, so the wide jumps inflate the narrow interior deltas. Putting all jumps in one region and
all interior deltas in another lets each be packed at its own width.

## How the encoding works
- Same residuals as the baseline: componentwise delta `p[i] - p[i-1]`, zigzag.
- Only their order changes: first every part-start residual, then every within-part residual.
- The split point is derived from the topology streams; nothing is signalled, no vertex is reordered,
  no reset seeds.

```
original order:   S0 a a a S1 b S2 c c ...   (S = part start)
written order:    S0 S1 S2 ... | a a a b c c ...
```

## How the decoder works
1. Derive the runs from the topology; count the part starts → length of the start region.
2. Read the start region and the interior region with separate cursors.
3. Walk the vertices: a part start takes the next start residual, every other vertex the next interior
   residual; add it to `p[i-1]`.

## results
- **Retired corpus `munich.mbtiles` (FastPFOR):** -4,5% corpus vs `DELTA_ZIGZAG_FASTPFOR`;
  `transportation` -6,5%, `boundary` -6,1%, `building` -5,2%, `landcover` -2,6%, `water` -1,1%,
  LINESTRING -6,3%, POLYGON -3,9%, points byte-identical. The first encoding to beat the baseline.
- Mechanism (retired corpus): boundary deltas are 30,2% of deltas but 35,5% of zigzag bits, 13,10 vs 10,30
  mean bits; a block-width simulation predicted -20,2% packed bits.
- Over plain Varint it is byte-identical to `DELTA_ZIGZAG_VARINT` (a byte-aligned code prices each value
  independently), so not registered there.
- **Current corpus, split varints, per tile:** `PART_START_SEG_VARINT_SPLIT` -3,65% (smallest of
  gzip/Brotli/zstd), never ahead of `LINE_CONTINUATION`. **Retired.** The class-stream segmentation of
  the pipeline carries the idea on (class streams).
