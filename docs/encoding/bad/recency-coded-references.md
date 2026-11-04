# Recency-coded references (`_RECENT`)

## encoding target:
building (Polygon, `QUAD_CORNER_SHARED`), transportation (LineString, `LINE_CONTINUATION_SHARED_DICT`)

## Idea of the encoding
A bit-packed index into a growing dictionary changes value for the same "the road just emitted" as the dictionary
grows. Code the reference as the distance back from the newest candidate instead: the most common reference is
always the same small byte, which a compressor should like.

## How the encoding works
Same shared-vertex references; the candidate index is written as a varint of `newest - candidate` instead of a
`ceil(log2 candidates)`-bit packed index.

## How the decoder works
Read the varint distance and take the candidate that many positions back from the newest one.

## results
Current corpus `munich-plantiler`, per tile gzip vs `DELTA_ZIGZAG_VARINT`: `QUAD_CORNER_SHARED_RECENT_VARINT`
-3,03% vs -3,07% packed; `_DICT_RECENT` ties `_DICT`. The index stream is too small a share of the payload for
its byte alignment to matter. Removed (the `*_RECENT` rows are gone).
