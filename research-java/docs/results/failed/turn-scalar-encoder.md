# Turn-scalar encoder — `TURN_SCALAR_*`

> **Revisited under split varints:** `TURN_SCALAR_SEG_VARINT_SPLIT` is -10,09% on `building`
> against -10,52% for `QUAD_CORNER`, and +9,61% to +12,88% on `landcover`, `landuse` and
> `water` (smallest of gzip/Brotli/zstd). Stays retired. See
> [../revisit-varint-split.md](../revisit-varint-split.md).

**Status:** regression, unregistered (kept for its mechanism test,
`encoding/research/failed/TurnScalar.java`)
**Effect:** building +11,8% interleaved / +3,2% segmented vs `QUAD_CORNER`; other polygon layers
+15% to +17%
**Corpus:** `data/extractedBaseline` (`munich-plantiler.mbtiles`, current baseline)

The encoder built after the [turn-scalar.md](turn-scalar.md) re-run showed −12% bits per vertex
on `building` even `n>4` rings. Composition, per vertex class (all derived from topology):

- **4-vertex rings:** `QUAD_CORNER`'s parallelogram corner `p0 + p2 - p1` on vertex 4, unchanged.
- **even `n>4` polygon rings, `i - start >= 2`:** `u = rot90(prevEdge)` (CCW outer / CW inner,
  from `numParts`), stored as `along = round(dot(w,u)/|u|)` plus `corr = w - round(along·u/|u|)`
  — 3 values per vertex. Needed a `VertexTransform.valueCount` hook so `VertexBufferCodec`
  knows the stream length on decode.
- **everything else:** plain delta. `_SEG` packs start | interior | corner | along | corr.

A first version also fired on odd `n>4` rings; limiting it to even rings roughly halved the loss
but did not remove it.

```
bytes (even n>4 only)   QUAD_CORNER_FASTPFOR  TURN_SCALAR_FASTPFOR   QUAD_CORNER_SEG  TURN_SCALAR_SEG
building                        4.271.469     4.775.843  +11,8%      3.771.466    3.893.318  +3,2%
landcover                       1.589.214     1.867.394  +17,5%      1.528.130    1.789.525 +17,1%
landuse                           770.015       890.832  +15,7%        741.371      849.730 +14,6%
water                             212.226       245.834  +15,8%        210.030      243.050 +15,7%

all n>4 (first version)
building                        4.271.469     5.018.645  +17,5%      3.771.466    4.028.683  +6,8%
```

**Residual bits mislead here.** The benchmark's table reports −11,3% mean bits vs
`DELTA_ZIGZAG`, but it is a per-value mean and this transform emits 3 values per turn vertex
(11.795.246 values vs 10.717.858). In total bits (count × mean) it is −2,4% vs `DELTA_ZIGZAG`
and ≈+1,2% vs `QUAD_CORNER` (−3,6%) — the turn path costs more than the delta it replaces
corpus-wide.

**Why it fails:**

1. `along` is essentially the edge length, ~5–6 bits — the same cost as a delta component, and
   no layout makes it cheaper.
2. `corr` is median 0 but p99 6–7 bits; FastPFOR prices a block near its max, so the near-zero
   population does not reach the 0 bits the probe counted.
3. Winding buys nothing. `u` and `-u` give the same `corr`, so the direction prior could only
   decide the sign of `along` — but every non-convex ring has −90° (reflex) corners, where
   `along` is negative with `corr = 0`, so `along` must stay signed, and zigzag prices `+v` and
   `−v` at the same width. Reflex corners are therefore free, not the cause of the loss.
4. The prior is building-only (landcover/landuse/water lose at the bit level already), and an
   encoding cannot see the layer name.

**Segmentation cannot rescue it.** Upper bound on `building`: 0,97 bits × 607.090 turn vertices
≈ 74 KB (~2%), and that bound counts zeros as free; the segmented row is currently 121.852 bytes
behind `QUAD_CORNER_SEG`. The per-buffer oracle picks `TURN_SCALAR_SEG_FASTPFOR` on 82 of 4.368
buffers (244.967 bytes), moving the oracle ceiling from −7,0% to −7,1% — not enough to pay for
signalling.

## Re-run in the OpenZL-style pipeline (`Prediction.TURN_SCALAR`)

`TURN_SCALAR` is now a pipeline predictor (`pipeline/Prediction.java`): the same classes, the same
corner and turn rule, with the `along` scalar as a side-value stream of its own after the residual
streams (under every segmentation; the header's predictor field grew to 3 bits). Its residual
pairs equal the old transform's, value for value (`PredictionMechanismTest`). Measured with
`./gradlew pipeline --args="--step turn --layer building --by-zoom --with-old"` on
`data/extractedBaseline` (310 `building` buffers, per tile, change vs `DELTA_ZIGZAG_VARINT` gzip):

```
                                  gzip      brotli11   zstd19
QUAD_CORNER      FLAT           -4,48%     -8,29%     -6,48%
TURN_SCALAR      FLAT           -0,67%     -6,85%     -3,26%
QUAD_CORNER      CLASS          -8,11%    -12,99%     -9,25%
TURN_SCALAR      CLASS          -6,17%    -12,50%     -8,45%
QUAD_CORNER      ROLE_CLASS     -7,62%    -12,40%     -8,77%
TURN_SCALAR      ROLE_CLASS     -5,79%    -12,17%     -8,06%
building composition (Hilbert reorder, CLASS, ESCAPE):
QUAD_CORNER                     -9,20%    -13,61%    -10,41%
TURN_SCALAR                     -7,09%    -13,06%     -9,38%
```

Class streams help `TURN_SCALAR` more than `QUAD_CORNER` (+5,5 points against +3,6 on gzip), but it
still ends 1,8 to 2,1 points (gzip) behind `QUAD_CORNER` on the same terminal, and 0,5 behind under
Brotli. By zoom the gap is z13 +5,75% vs -3,15% and z14 -10,21% vs -10,68% (building composition).
Verdict unchanged: stays out of the per-layer defaults.
