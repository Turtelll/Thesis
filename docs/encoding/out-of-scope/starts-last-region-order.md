# Starts-last region order and 128-value FastPFOR blocks

> **Verdict: Out of scope (FastPFOR only).** -0,27% (256 blocks) / -0,45% (128 blocks) FastPFOR bytes; a
> FastPFOR packing effect, so not measured under the headline metric.

## Encoding target
all segmented layouts over FastPFOR, measured on building / landcover / landuse / water (Polygon) and
transportation (LineString).

## Idea
FastPFOR packs full 256-value blocks and writes the last < 256 values of a stream as VariableByte (≥ 8 bits
each). With the regions in the order START, INTERIOR, CORNER, the near-zero corner residuals land in that
expensive tail. Putting the wide start residuals last puts the cheap-to-pack values into full blocks. Smaller
blocks (128) halve the tail and straddle fewer region boundaries.

## Intention
A free fix (a fixed order, nothing signalled) for a FastPFOR artefact: the cheapest values should be packed,
not left in the VariableByte tail. The 128-block variant tests the block size the next MLT version adopts.

## How the encoding works
- Same residuals and classes as the segmented quad corner / shared encodings.
- The regions are concatenated in the order CORNER, INTERIOR, START (lines: LINE_INTERIOR, INTERIOR, START)
  before FastPFOR; the order is fixed, not signalled.
- Variant: `IntCodec.FASTPFOR128` (`FastPFOR128` + `VariableByte`, mlt-core framing), the block size the
  next MLT version adopts.

## How the decoder works
FastPFOR-decode the stream, then split it into regions in the same fixed order using the region lengths
(derived from topology or, for shared encodings, read from the payload), and decode as usual.

## Results
**Headline:** *not measured under the headline metric* (current corpus, FastPFOR bytes vs
`QUAD_CORNER_SHARED_SEG_FASTPFOR`, 11.339.751 B): **CORNER, INTERIOR, START -0,27%** at 256 blocks, **-0,45%**
at 128 blocks.

**Decisive case — why it is out of scope:** the effect exists only inside FastPFOR's block packing. FastPFOR is
retired as a delivery target: once a browser-native compressor runs, Varint beats FastPFOR by 6,7–7,4% per
tile, and a byte-aligned code has no block tail to optimise. The result applies to the FastPFOR-era rows only.

Supporting figures (*FastPFOR bytes vs the old order at 256*):

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

Source: `research-java/docs/results/shared-vertices.md`, `general-purpose-compression.md`.
