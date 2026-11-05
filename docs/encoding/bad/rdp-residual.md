# RDP simplification + interpolated residual (`RDP_RESIDUAL`, `RDP_RESIDUAL_SEG`)

> **Verdict: Rejected.** +0,11% gzip on the tileset; worse than the baseline on `transportation`, the layer it
> was built for.

## Encoding target
transportation (LineString / MultiLineString).

## Idea
Simplify each line with Ramer-Douglas-Peucker to a few key vertices. A dropped vertex lies close to the chord
between its two keys, so predict it by interpolating along that chord from its index and store only the
(small) deviation. Lossless, because every dropped vertex still gets its exact residual.

## Intention
Line continuation predicts from the past only; RDP looks ahead. Coding the key vertices first lets every
in-between vertex be predicted from both sides, which should be much closer than an extrapolation, on curves
as well as straight stretches.

## How the encoding works
- Runs with ≥ 3 vertices are simplified with RDP; both endpoints are always keys. Eight `epsilon` candidates per
  buffer, the one with the smallest payload is kept (`epsilon` not stored).
- Key/drop bitmap: 1 bit per interior vertex of every eligible run.
- Key vertices: plain delta. Dropped vertex `i` between keys `a` (A) and `b` (B):
  `pred = A + round((B - A)·(i - a)/(b - a))` (exact integer arithmetic), residual `actual - pred`, zigzag.
- `_SEG`: key and dropped residuals in separate FastPFOR blocks.

## How the decoder works
Read the bitmap; decode all key vertices by delta first (so both bounding keys of every dropped vertex are known);
then for each dropped vertex interpolate between its keys from the indices and add the residual.

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`, split varints): **tileset +0,11%**
(`RDP_RESIDUAL_VARINT_SPLIT`, `_SEG` +0,20%; computed from the gzip byte totals). Per layer only the smallest
of gzip/Brotli/zstd was reported: `transportation` +1,70% / +1,78%.

**Decisive case against:** there is too little to interpolate. 70,4% of `transportation` parts have only 2
vertices and are never eligible, and only 28,9% of the vertices of eligible runs are dropped. On those the
residual does shrink (-24,4% bits), but the key-vertex bitmap costs 1 bit per vertex, which eats the gain. It
won 0 of 354 buffers.

Supporting figures:

- Smallest of gzip/Brotli/zstd, current corpus: tileset -0,61% / `_SEG` -0,54%; -1,5% on `building` and
  `landcover`.
- *Not under the headline metric* — current corpus, `transportation`, FastPFOR bytes vs
  `DELTA_ZIGZAG_FASTPFOR`: interleaved +2,5%, segmented -0,2% (`LINE_CONTINUATION_SEG` -6,8%); mean bits -0,2%.
- On dropped vertices the residual is -24,4% (11,08 → 8,38 bits).
- A 1-scalar (perpendicular) residual is impossible losslessly: index and arc length do not coincide.

Source: `research-java/docs/results/failed/rdp-residual.md`, `revisit-varint-split.md`.
