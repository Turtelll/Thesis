# Retired encodings revisited under segmented VARINT_SPLIT

> **Consolidation note.** The Gradle task(s) benchmark named below were removed from the build (the code is at git tag `pre-consolidation`); `research measure` and the other `research` commands replace the rest. The numbers below are unchanged history.

**Status:** measured — one row kept and flagged, the other fourteen retired again; the flagged
idea, combined with feature chaining, is the smallest tileset row (-9,50%, see the follow-up)
**Effect:** only `QUAD_CORNER_SHARED_RING_CANONICAL` is the smallest row on any layer
(`building`, -13,62% against -13,36% for the current best row there). On the whole tileset no
revisited row beats `QUAD_CORNER_SHARED_REORDER` (-9,39%).
**Corpus:** current baseline (`munich-plantiler.mbtiles`, `data/extractedBaseline`, 358 tiles,
4.368 buffers). Every figure is **per tile**: a tile's buffers concatenated and compressed on
their own, summed over tiles, as in [general-purpose-compression.md](general-purpose-compression.md).
gzip level 6, zstd level 19, Brotli at the benchmark's default quality (the column is headed
`brotli11`; the older result files used 9, so absolute Brotli bytes are not comparable to them).
**Runs:** `./gradlew benchmark --args="--verbose --csv build/revisit/all.csv"`, then
`--layer <name>` for `building`, `transportation`, `landcover`, `landuse`, `water`, `waterway`,
`housenumber`, `poi`. Raw output in `research-java/build/revisit/` (not committed).

## Why

[general-purpose-compression.md](general-purpose-compression.md) retired FastPFOR and moved
every idea onto segmented split varints under gzip, Brotli and zstd. The rows retired before
that — or only measured over FastPFOR, plain `VARINT`, or interleaved — had never been run in
that configuration. The three FastPFOR casualties had only been re-run interleaved.

## Metric

Each row's **smallest compressed tileset size**: the minimum of its gzip, Brotli and zstd
per-tile totals. Brotli was the smallest for every row, so the min column is the Brotli
column. Percentages are against `DELTA_ZIGZAG_VARINT` under the same metric (10.734.783 bytes).
A revisited row is **kept and flagged** when it is the smallest row on at least one layer
(any other row in that layer's run, current research rows included); otherwise it is retired.

## Rows

Each revisited predictor ran as `PredictedTransform(predictor, SEGMENTED)` or its own
`Encoding` over `IntCodec.VARINT_SPLIT`. `RdpResidual*` gained an `IntCodec` constructor
parameter (default FastPFOR, names and mechanism tests unchanged); the epsilon ladder is
priced with that codec's bytes.

Whole tileset, smallest of gzip / Brotli / zstd, best first:

| row | raw | gzip | brotli11 | zstd19 | smallest | vs `DELTA_ZIGZAG_VARINT` |
|---|---|---|---|---|---|---|
| `QUAD_CORNER_SHARED_REORDER` (current best) | 12.815.460 | 10.420.477 | 9.726.840 | 10.269.454 | 9.726.840 | -9,39% |
| **`QUAD_CORNER_SHARED_RING_CANONICAL`** | 12.950.571 | 10.600.022 | 9.877.448 | 10.450.351 | 9.877.448 | **-7,99%** |
| `QUAD_CORNER_SHARED` (current) | 12.943.740 | 10.610.302 | 9.890.835 | 10.469.602 | 9.890.835 | -7,86% |
| `QUAD_CORNER` (current) | 13.265.283 | 10.860.056 | 10.111.002 | 10.697.529 | 10.111.002 | -5,81% |
| `PART_START_SEG_VARINT_SPLIT` | 13.270.873 | 11.078.731 | 10.343.098 | 10.939.335 | 10.343.098 | -3,65% |
| `QUAD_CORNER_ROT90_SEG_VARINT_SPLIT` | 13.493.282 | 11.182.051 | 10.398.280 | 11.017.913 | 10.398.280 | -3,13% |
| `TURN_SCALAR_SEG_VARINT_SPLIT` | 14.346.654 | 11.270.359 | 10.439.079 | 11.033.005 | 10.439.079 | -2,75% |
| `ROT90_FIXED_SEG_VARINT_SPLIT` | 13.498.872 | 11.400.059 | 10.632.596 | 11.257.359 | 10.632.596 | -0,95% |
| `RDP_RESIDUAL_VARINT_SPLIT` | 13.365.066 | 11.250.036 | 10.669.672 | 11.110.169 | 10.669.672 | -0,61% |
| `RDP_RESIDUAL_SEG_VARINT_SPLIT` | 13.372.599 | 11.259.218 | 10.676.819 | 11.121.268 | 10.676.819 | -0,54% |
| `QUAD_CORNER_TURN_VARINT_SPLIT` | 13.944.421 | 11.540.245 | 10.706.441 | 11.353.759 | 10.706.441 | -0,26% |
| `EVEN_RING_TURN_VARINT_SPLIT` | 13.627.239 | 11.491.951 | 10.714.581 | 11.336.003 | 10.714.581 | -0,19% |
| `DELTA_ZIGZAG_VARINT` (baseline) | 13.270.873 | 11.237.124 | 10.734.783 | 11.201.331 | 10.734.783 | ref |
| `TURN90_BITMAP_VARINT_SPLIT` | 13.642.514 | 11.379.602 | 10.762.314 | 11.206.160 | 10.762.314 | +0,26% |
| `CONTEXT_DELTA_T2_M32_VARINT_SPLIT` | 13.370.664 | 11.511.395 | 10.900.166 | 11.403.967 | 10.900.166 | +1,54% |
| `MIDPOINT_REFLECT_SPLIT_VARINT_SPLIT` | 14.089.793 | 12.040.341 | 11.117.653 | 11.881.806 | 11.117.653 | +3,57% |
| `RIGHT_ANGLE_TURN_VARINT_SPLIT` | 13.950.011 | 11.823.938 | 11.166.505 | 11.666.119 | 11.166.505 | +4,02% |
| `MIDPOINT_REFLECT_SYMMETRIC_VARINT_SPLIT` | 14.333.655 | 12.243.439 | 11.265.103 | 12.070.498 | 11.265.103 | +4,94% |
| `DELTA2_ZIGZAG_VARINT_SPLIT` | 14.245.174 | 12.353.710 | 11.693.289 | 12.232.244 | 11.693.289 | +8,93% |
| `DELTA_ZIGZAG_FASTPFOR` (baseline) | 12.411.712 | 11.984.744 | 11.828.580 | 11.949.476 | 11.828.580 | +10,19% |

## Per layer

Smallest-of-three bytes against `DELTA_ZIGZAG_VARINT` on that layer, from the `--layer` runs.
Reference rows are the current research rows that win a layer. The layer's smallest row is bold.

| row | building | transportation | landcover | landuse | water | waterway |
|---|---|---|---|---|---|---|
| `QUAD_CORNER_SHARED_REORDER` | -13,36% | -5,95% | **-12,33%** | **-5,80%** | **-2,47%** | -0,98% |
| `LINE_CONTINUATION_SHARED_DICT` | -4,24% | **-8,09%** | -2,41% | -0,67% | -0,72% | **-5,87%** |
| `QUAD_CORNER_SHARED_RING_CANONICAL` | **-13,62%** | -1,82% | -10,12% | -4,08% | -1,22% | +1,67% |
| `PART_START_SEG_VARINT_SPLIT` | -4,25% | -2,07% | -2,38% | -0,69% | -0,87% | +1,62% |
| `QUAD_CORNER_ROT90_SEG_VARINT_SPLIT` | -9,30% | -0,07% | +0,78% | +0,96% | +1,75% | +4,43% |
| `TURN_SCALAR_SEG_VARINT_SPLIT` | -10,09% | -1,82% | +11,75% | +9,61% | +12,88% | +1,39% |
| `ROT90_FIXED_SEG_VARINT_SPLIT` | -3,10% | -0,30% | +1,07% | +1,68% | +2,26% | +4,27% |
| `QUAD_CORNER_TURN_VARINT_SPLIT` | -8,20% | +3,12% | +5,55% | +4,94% | +8,50% | +9,98% |
| `EVEN_RING_TURN_VARINT_SPLIT` | -2,71% | +0,39% | +2,20% | +2,79% | +4,30% | +6,19% |
| `RDP_RESIDUAL_VARINT_SPLIT` | -1,47% | +1,70% | -1,49% | +0,14% | +0,02% | +4,36% |
| `RDP_RESIDUAL_SEG_VARINT_SPLIT` | -1,45% | +1,78% | -1,40% | +0,14% | +0,31% | +5,05% |
| `TURN90_BITMAP_VARINT_SPLIT` | -0,76% | -0,01% | +6,95% | +6,21% | +9,26% | +1,42% |
| `CONTEXT_DELTA_T2_M32_VARINT_SPLIT` | +3,58% | -1,38% | +0,06% | +2,99% | -1,09% | +0,01% |
| `MIDPOINT_REFLECT_SPLIT_VARINT_SPLIT` | -1,04% | +2,39% | +8,29% | +7,47% | +10,95% | +17,56% |
| `MIDPOINT_REFLECT_SYMMETRIC_VARINT_SPLIT` | +0,97% | +2,53% | +11,23% | +10,65% | +17,34% | +15,97% |
| `RIGHT_ANGLE_TURN_VARINT_SPLIT` | +1,27% | +4,42% | +6,70% | +6,51% | +9,75% | +10,45% |
| `DELTA2_ZIGZAG_VARINT_SPLIT` | +13,03% | +8,53% | +6,30% | +9,99% | +4,91% | +5,02% |

`housenumber` and `poi` (point layers): the baseline `DELTA_ZIGZAG_VARINT` is the smallest row
under Brotli, and every revisited row is at least +0,14% / +0,40% behind it. Nothing there
is a candidate.

## Verdicts

| row | verdict | why |
|---|---|---|
| `QUAD_CORNER_SHARED_RING_CANONICAL` | **kept, flagged** | smallest row on `building` under gzip, Brotli and zstd (3.371.757 / 3.180.420 / 3.321.216 bytes) and on MULTIPOLYGON geometry; -7,99% whole tileset against -7,86% for `QUAD_CORNER_SHARED` |
| `PART_START_SEG` | retired | never ahead of `LINE_CONTINUATION` (-3,37% whole tileset): the same on `building`, `landcover`, `landuse` and `water`, behind on `transportation` (-2,07% vs -3,08%) and `waterway` |
| `QUAD_CORNER_ROT90_SEG`, `ROT90_FIXED_SEG` | retired | `building` only (-9,30% / -3,10%, behind `QUAD_CORNER` at -10,52%), and they lose to the baseline on four of six layers |
| `TURN_SCALAR_SEG` | retired | best revisited non-shared row on `building` (-10,09%) but still behind `QUAD_CORNER`, and +9,61% to +12,88% on three polygon layers |
| `QUAD_CORNER_TURN`, `EVEN_RING_TURN`, `RIGHT_ANGLE_TURN`, `TURN90_BITMAP` | retired | whole tileset within -0,26% to +4,02% of the baseline, and the winners among them (-0,26%, -0,19%) only on `building`, where `QUAD_CORNER` is at -10,52% |
| `RDP_RESIDUAL`, `RDP_RESIDUAL_SEG` | retired | -1,5% on `building` and `landcover`, loses on `transportation` where it was meant to win; the segmented layout does nothing |
| `CONTEXT_DELTA_T2_M32` | retired | +1,54% whole tileset; its small wins (`transportation` -1,38%, `water` -1,09%) are well behind the current rows |
| `MIDPOINT_REFLECT_*` | retired | +3,57% / +4,94% whole tileset; every layer but `building` loses by 2% to 18% |
| `DELTA2_ZIGZAG` | retired | +8,93% whole tileset, worse on every layer |

## What the data says

- **Canonical ring start carries over once the shared references exist.** On plain
  interleaved `VARINT` it was -0,12% gzip ([general-purpose-compression.md](general-purpose-compression.md)).
  On top of `QUAD_CORNER_SHARED` and split varints it is -0,10% gzip / -0,14% Brotli / -0,18%
  zstd whole tileset against `QUAD_CORNER_SHARED`, and -0,31% / -0,51% / -0,37% on `building`.
  It changes nothing on `transportation` and `waterway` (no polygons to rotate), and is
  within 0,16 points of `QUAD_CORNER_SHARED` on `landcover` (0,04 worse), `landuse` (0,03
  better) and `water` (0,16 worse). The gain is small and lives in `building`, whose rings are
  the repeated shapes the rotation is for.
- **It was first measured without feature chaining.** `QUAD_CORNER_SHARED_REORDER` (chained
  features, no rotation) won every layer but `building`; `_RING_CANONICAL` won `building` by
  0,26 points. The composition of the two is measured in the follow-up below.
- **No turn predictor pays under a compressor.** Every turn row grows the raw stream (+2% to
  +8% over `DELTA_ZIGZAG_VARINT`), and only the `building`-specific rows recover any of it
  after compression. A prediction that is only right on right angles costs a full-width
  residual at every other vertex; not measured per vertex here, so this is the likely
  mechanism rather than a result.
- **The FastPFOR casualties stay lost.** `TURN90_BITMAP` is +0,26%, `CONTEXT_DELTA` +1,54%,
  `RIGHT_ANGLE_TURN` +4,02% whole tileset under split varints, consistent with the interleaved
  `VARINT` figures in [general-purpose-compression.md](general-purpose-compression.md).
- **`ROT90_FIXED`'s building win does not carry over.** It was -5,0% under FastPFOR; here it
  is -3,10% and `QUAD_CORNER` is -10,52%.
- **The oracle uses the revisited rows barely.** In the full run it picked
  `QUAD_CORNER_SHARED_REORDER` for 41,6% of bytes and `LINE_CONTINUATION_SHARED_DICT` for 25,4%;
  `QUAD_CORNER_SHARED_RING_CANONICAL` 4,9%, and every other revisited row at most 0,6%. The
  oracle comes to -12,91% Brotli; its composition is not a basis for keeping a row.

## Follow-up: canonical ring start combined with feature chaining

The flagged row was composed with `QUAD_CORNER_SHARED_REORDER`'s feature chaining, in both
orders. `GeometryReorder` gained `RING_CANONICAL_THEN_FEATURE` (rotate every ring, then chain
over the rotated starts, so the chain follows the endpoints actually emitted); chaining first
and rotating afterwards was measured as `QUAD_CORNER_SHARED_REORDER_RING_CANONICAL` and removed.
Same corpus and metric, from `build/combine/`; both rows round-trip in the benchmark.

| row | raw | gzip | brotli11 | zstd19 | smallest | vs `DELTA_ZIGZAG_VARINT` |
|---|---|---|---|---|---|---|
| **`QUAD_CORNER_SHARED_RING_CANONICAL_REORDER`** (rotate, then chain) | 12.824.328 | 10.412.764 | 9.715.160 | 10.251.177 | 9.715.160 | **-9,50%** |
| `QUAD_CORNER_SHARED_REORDER_RING_CANONICAL` (chain, then rotate) | 12.831.357 | 10.419.362 | 9.721.681 | 10.257.511 | 9.721.681 | -9,44% |
| `QUAD_CORNER_SHARED_REORDER` | 12.815.460 | 10.420.477 | 9.726.840 | 10.269.454 | 9.726.840 | -9,39% |
| `QUAD_CORNER_SHARED_RING_CANONICAL` | 12.950.571 | 10.600.022 | 9.877.448 | 10.450.351 | 9.877.448 | -7,99% |

Per layer (smallest of the three, against `DELTA_ZIGZAG_VARINT`):

| row | building | transportation | landcover | landuse | water | waterway |
|---|---|---|---|---|---|---|
| `_RING_CANONICAL_REORDER` | **-13,79%** | -5,95% | -12,31% | -5,71% | -2,23% | -0,98% |
| `_REORDER_RING_CANONICAL` | **-13,79%** | -5,92% | -11,99% | -5,60% | -2,21% | -0,98% |
| `QUAD_CORNER_SHARED_REORDER` | -13,36% | -5,95% | **-12,33%** | **-5,80%** | **-2,47%** | -0,98% |

- Rotate-then-chain is the new smallest tileset row, 11.680 bytes (0,12%) under
  `QUAD_CORNER_SHARED_REORDER`, and is the smallest on `building` (-13,79% against -13,36%;
  3.174.048 vs 3.190.035 bytes). Chain-then-rotate ties it on `building` and is behind everywhere else.
- The gain is all `building`. On the other polygon layers the composition is 0,02 to
  0,24 points behind `QUAD_CORNER_SHARED_REORDER` (cause not isolated; the layers have fewer
  repeated shapes for the rotation to exploit). `transportation` and `waterway` do not move.
- Whole-tileset it wins under every compressor (gzip -0,07%, Brotli -0,12%, zstd -0,18%
  against `QUAD_CORNER_SHARED_REORDER`).
- Registered as `RING_CANONICAL_THEN_FEATURE` in `Baselines.research()`.
  `QUAD_CORNER_SHARED_RING_CANONICAL` alone is dominated by it on every layer and moved to
  `retired()`.

## Bookkeeping

- `Baselines.research()` gains one row, the composition above (the flagged
  `QUAD_CORNER_SHARED_RING_CANONICAL` alone is in `retired()` after the follow-up).
- `Baselines.retired()` now holds the other fourteen as `revisited()`. The old
  `CONTEXT_DELTA_T2_M32_VARINT`, `RIGHT_ANGLE_TURN_VARINT` and `TURN90_BITMAP_VARINT` rows are
  replaced by their `_VARINT_SPLIT` versions, still round-tripped by `VertexBufferCodecTest`.
- `MidpointReflect.name()` now includes its pairing (`MIDPOINT_REFLECT_SYMMETRIC` /
  `_SPLIT`); both used the same name, so a benchmark row had silently shadowed the other.
- Not revisited: BWT/MTF and `TILE_STD_DELTA` (already over `VARINT_SPLIT`), `STD_DELTA`
  (fixed 16-bit by design), the byte-serializer rows, rANS, and the delta-of-delta reset and
  junction back-reference variants (no code left).
