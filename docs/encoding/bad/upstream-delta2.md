# Upstream componentwise delta2 (`DELTA2_ZIGZAG`, maplibre-tile-spec #1806)

## encoding target:
transportation (LineString), applied to every vertex stream

## Idea of the encoding
Upstream MLT added an opt-in second-order delta for vertex streams: predict that the next edge repeats the previous
one, over the whole stream, and race it per stream against plain delta. Port it and measure it on this corpus.

## How the encoding works
Line-for-line port of `encode_componentwise_delta2_vec2s` (`VertexTransform.ZigZagDelta2`):
`delta2[i] = (v[i] - v[i-1]) - (v[i-1] - v[i-2])` per component, zigzag, both predecessors start at 0, continuous
over the whole vertex stream (no reset at part, ring or feature boundaries, no geometry-type gate). Upstream races it
per stream against delta.

## How the decoder works
Integrate twice: `v[i] = delta2[i] + 2·v[i-1] - v[i-2]`, starting from 0, 0.

## results
Current corpus `munich-plantiler` and Chicago, `transportation`, vs `DELTA_ZIGZAG` same codec:
- Bytes: Munich +10,5% Varint / +9,8% FastPFOR; Chicago +11,3% / +8,8%. Every zoom a regression; smaller on **0 of
  673** buffers (the upstream race would never pick it). Mean residual bits +12,8% / +19,1%.
- By position in part (Munich / Chicago): 2nd vertex **+63,3% / +63,6%** (the previous "edge" is the jump), part
  start +4,3% / +6,4%, interior -6,2% / -0,3%.
- **Split varints, per tile:** `DELTA2_ZIGZAG_VARINT_SPLIT` +8,93% whole tileset, worse on every layer.
- Gated to line interiors the same predictor is line continuation (-2,1% bits).
