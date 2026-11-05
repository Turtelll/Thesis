# Part-start grouping (`PART_START_SEG`, formerly `SEGMENT_PAIR`)

> **Verdict: Kept under FastPFOR, then retired.** Under the headline metric -1,41% gzip on the tileset, never
> ahead of line continuation; its idea lives on in the [class streams](class-streams.md).

## Encoding target
transportation (LineString), boundary, also polygon layers; point-only layers unchanged.

## Idea
`transportation` is not long polylines but a fragmented road graph: 70,4% of its parts have exactly two
vertices. The delta from the end of one part to the start of the next (the "jump") is much wider than the
deltas inside a part, and there are many of them. A bit-packing codec (FastPFOR) sizes a block by its widest
value, so the wide jumps inflate the narrow interior deltas. Putting all jumps in one region and all interior
deltas in another lets each be packed at its own width.

## Intention
The residuals stay exactly those of plain delta; only their order changes, and the split point follows from
the topology. So any gain is pure layout, with no prediction risk and no signalling. It was aimed at FastPFOR,
whose block-width pricing punishes mixed magnitudes the most.

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

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`, segmented split varints): **tileset
-1,41%** (`PART_START_SEG_VARINT_SPLIT`, computed from the gzip byte total). Per layer only the smallest of
gzip/Brotli/zstd was reported: `transportation` -2,07% against -3,08% for `LINE_CONTINUATION`.

**Decisive case — why it was retired:** it was the first encoding to beat the baseline under FastPFOR (-4,5%),
but under gzip it is never ahead of line continuation on any layer: the same on the polygon layers, behind on
`transportation` and `waterway`. Over plain Varint it is byte-identical to the baseline before compression. The
mechanism survived: the pipeline's class streams separate the run starts from every other class, and that
pays under gzip (+1,21 points).

Supporting figures:

- *Not under the headline metric* — retired corpus `munich.mbtiles`, FastPFOR bytes vs
  `DELTA_ZIGZAG_FASTPFOR`: -4,5% corpus; `transportation` -6,5%, `boundary` -6,1%, `building` -5,2%,
  `landcover` -2,6%, `water` -1,1%, LINESTRING -6,3%, POLYGON -3,9%, points byte-identical.
- Mechanism (retired corpus): boundary deltas are 30,2% of deltas but 35,5% of zigzag bits, 13,10 vs 10,30
  mean bits; a block-width simulation predicted -20,2% packed bits.
- Over plain Varint it is byte-identical to `DELTA_ZIGZAG_VARINT` (a byte-aligned code prices each value
  independently), so not registered there.
- Smallest of gzip/Brotli/zstd, tileset: -3,65%.

Source: `research-java/docs/results/segment-pair.md`, `revisit-varint-split.md`.
