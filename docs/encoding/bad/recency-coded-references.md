# Recency-coded references (`_RECENT`)

> **Verdict: Rejected.** `QUAD_CORNER_SHARED_RECENT_VARINT` -3,03% gzip against -3,07% with the packed
> indices; no gain.

## Encoding target
building (Polygon, `QUAD_CORNER_SHARED`), transportation (LineString, `LINE_CONTINUATION_SHARED_DICT`).

## Idea
A bit-packed index into a growing dictionary changes value for the same "the road just emitted" as the
dictionary grows. Code the reference as the distance back from the newest candidate instead: the most common
reference is always the same small byte, which a compressor should like.

## Intention
Bit-packed indices are opaque to a byte compressor: the same reference has a different bit pattern depending on
the dictionary size and its position in the bit stream. A byte-aligned, recency-ordered code turns the common
case into one repeated small byte, the kind of symbol gzip prices cheaply.

## How the encoding works
Same shared-vertex references; the candidate index is written as a varint of `newest - candidate` instead of a
`ceil(log2 candidates)`-bit packed index.

## How the decoder works
Read the varint distance and take the candidate that many positions back from the newest one.

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`, interleaved Varint):
**`QUAD_CORNER_SHARED_RECENT_VARINT` -3,03%** against **-3,07%** with packed indices; `_DICT_RECENT` ties
`_DICT`.

**Decisive case against:** the index stream is too small a share of the payload for its byte alignment to
matter; whatever recency coding saves there is within noise of the tileset. The `*_RECENT` rows were removed.

Source: `research-java/docs/results/general-purpose-compression.md`.
