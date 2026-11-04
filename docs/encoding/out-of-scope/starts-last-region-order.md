# Starts-last region order and 128-value FastPFOR blocks

## encoding target:
all segmented layouts over FastPFOR, measured on building / landcover / landuse / water (Polygon) and transportation (LineString)

## Idea of the encoding
FastPFOR packs full 256-value blocks and writes the last < 256 values of a stream as VariableByte (≥ 8 bits
each). With the regions in the order START, INTERIOR, CORNER, the near-zero corner residuals land in that
expensive tail. Putting the wide start residuals last puts the cheap-to-pack values into full blocks.
Smaller blocks (128) halve the tail and straddle fewer region boundaries.

## How the encoding works
- Same residuals and classes as the segmented quad corner / shared encodings.
- The regions are concatenated in the order CORNER, INTERIOR, START (lines: LINE_INTERIOR, INTERIOR, START)
  before FastPFOR; the order is fixed, not signalled.
- Variant: `IntCodec.FASTPFOR128` (`FastPFOR128` + `VariableByte`, mlt-core framing), the block size the
  next MLT version adopts.

## How the decoder works
FastPFOR-decode the stream, then split it into regions in the same fixed order using the region lengths
(derived from topology or, for shared encodings, read from the payload), and decode as usual.

## results
Current corpus `munich-plantiler`, bytes vs old `QUAD_CORNER_SHARED_SEG_FASTPFOR` (11.339.751):

| region order | 256 block | 128 block |
|---|---|---|
| START, INTERIOR, CORNER (old) | ref | -0,34% |
| **CORNER, INTERIOR, START** | **-0,27%** | **-0,45%** |
| INTERIOR, CORNER, START | -0,20% | -0,41% |
| START, CORNER, INTERIOR | -0,05% | -0,37% |
| per-buffer best order (oracle) | -0,43% | -0,59% |

- Per layer at 256: `water` -2,06%, `building` -0,44%, `landcover` +0,14%.
- 128 blocks help segmented rows (-0,18% to -0,32%), not interleaved ones (+0,04% to +0,05%); `building`
  +0,18%.
- Separate regions for reference misses: -0,09% to -0,21%, worse than the order alone.
- FastPFOR itself is retired as a delivery target (see FastPFOR as delivery terminal), so this applies to the
  FastPFOR-era rows only.
