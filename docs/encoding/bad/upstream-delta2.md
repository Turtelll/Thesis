# Upstream componentwise delta2

**Code names:** `DELTA2_ZIGZAG`, maplibre-tile-spec #1806

> **Verdict: Rejected.** +9,94% gzip on the tileset, worse on every layer; smaller than plain delta on 0 of
> 673 buffers.

## Encoding target
transportation (LineString), applied to every vertex stream.

## Idea
Upstream MLT added an opt-in second-order delta for vertex streams: predict that the next edge repeats the
previous one, over the whole stream, and race it per stream against plain delta. Port it and measure it on this
corpus.

## Intention
Check an upstream proposal against this thesis's measurements: if delta2 paid on this corpus, it would be a
spec-level alternative to line continuation; if not, the reason should show where its rule fails.

## How the encoding works
Line-for-line port of `encode_componentwise_delta2_vec2s` (`VertexTransform.ZigZagDelta2`):
`delta2[i] = (v[i] - v[i-1]) - (v[i-1] - v[i-2])` per component, zigzag, both predecessors start at 0, continuous
over the whole vertex stream (no reset at part, ring or feature boundaries, no geometry-type gate). Upstream races it
per stream against delta.

## How the decoder works
Integrate twice: `v[i] = delta2[i] + 2·v[i-1] - v[i-2]`, starting from 0, 0.

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`, split varints): **tileset +9,94%**
(`DELTA2_ZIGZAG_VARINT_SPLIT`, computed from the gzip byte total); worse on every layer.

**Decisive case against:** applied over the whole stream, the rule predicts across part boundaries, where the
"previous edge" is the jump between features: the 2nd vertex of a part gets +63,3% (Munich) / +63,6%
(Chicago) bits. Only in line interiors does it win (-6,2% / -0,3%), and gated to exactly those vertices it is
[line continuation](../good/line-continuation.md) (-2,1% bits). The upstream per-stream race would never pick it here
(0 of 673 buffers).

Supporting figures:

- Smallest of gzip/Brotli/zstd, tileset: +8,93%.
- *Not under the headline metric* — current corpus and Chicago, `transportation`, uncompressed bytes vs
  `DELTA_ZIGZAG` with the same codec: Munich +10,5% Varint / +9,8% FastPFOR; Chicago +11,3% / +8,8%. Every zoom
  a regression. Mean residual bits +12,8% / +19,1%.
- By position in part (Munich / Chicago, bits): 2nd vertex +63,3% / +63,6%, part start +4,3% / +6,4%,
  interior -6,2% / -0,3%.

Source: `research-java/docs/results/failed/upstream-delta2.md`, `revisit-varint-split.md`.
