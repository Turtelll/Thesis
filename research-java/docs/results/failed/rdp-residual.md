# RDP simplification with a lossless interpolated residual — `RDP_RESIDUAL`

> **Revisited under split varints:** `RDP_RESIDUAL_VARINT_SPLIT` -0,61%, `_SEG_` -0,54% whole
> tileset (smallest of gzip/Brotli/zstd); +1,70% / +1,78% on `transportation`, its target
> layer. Stays retired. See [../revisit-varint-split.md](../revisit-varint-split.md).

> **Revisited over split varints** ([../revisit-varint-split.md](../revisit-varint-split.md)):
> `RDP_RESIDUAL_VARINT_SPLIT` -0,61% and `RDP_RESIDUAL_SEG_VARINT_SPLIT` -0,54% per tile (smallest
> of gzip/Brotli/zstd); +1,70% / +1,78% on `transportation`. Retired again.

**Status:** regression, unregistered — removed from `Baselines` after measuring
**Effect:** never beats `DELTA_ZIGZAG_FASTPFOR` on `transportation`, interleaved or
segmented; 0 of 354 buffers won against baseline either way
**Corpus:** current baseline (`munich-plantiler.mbtiles`, `data/extractedBaseline`, 358
tiles, 4.368 buffers, `--set: core -rans`)

**These encodings are no longer in `Baselines`** (`encoding/research/failed/RdpResidual.java`,
`RdpResidualSegmented.java`) — they were removed after measuring. The mechanism test
(`RdpResidualMechanismTest`) is kept so the negative result stays reproducible.

## What it does

Ramer-Douglas-Peucker simplifies each LineString/MultiLineString run of at least 3
vertices to a set of "key" vertices (always both endpoints), trying eight `epsilon`
candidates per buffer and keeping whichever gives the smallest payload. `epsilon` itself
is never stored — only the key/drop choice is, as one bit per interior vertex of every
eligible run (`RDP_RESIDUAL_FASTPFOR`'s bitmap).

A dropped vertex's along-segment position is not signalled either: the decoder already
knows its index and the bounding keys' indices from the bitmap alone, so both sides
interpolate the same predicted point, `A + round((B - A) * (i - a) / (b - a))`, in exact
integer arithmetic (`RdpResidual.interpolate`). Only the residual against that
point — `actual - predicted`, both components, zigzag-encoded — is sent. `RdpResidualSegmented`
is the same idea with key-vertex and dropped-vertex residuals split into two physical
FastPFOR blocks instead of one interleaved stream, the same lever `LINE_CONTINUATION_SEG`
uses.

An earlier version anchored the along-component to the segment start instead of
interpolating it — a real bug, since a vertex far along a long segment then costs a
large stored coordinate regardless of how well it fits the chord. Switching to
interpolation fixed that (see "the residual math is sound" below) without changing the
overall verdict.

## Results

```
transportation, interleaved
DELTA_ZIGZAG_FASTPFOR     3.064.512        ref
RDP_RESIDUAL_FASTPFOR     3.140.251      +2,5%

transportation, segmented (--segmented)
DELTA_ZIGZAG_FASTPFOR     3.064.512        ref
RDP_RESIDUAL_SEG_FASTPFOR 3.057.042      -0,2%   (still last of 11 registered rows, 0 wins)
LINE_CONTINUATION_SEG_FASTPFOR 2.856.668 -6,8%   (for comparison — the layer's actual winner)
```

Mean residual bits (`transportation`, 2.361.446 values):

| transform | mean bits | vs `DELTA_ZIGZAG` |
|---|---|---|
| `DELTA_ZIGZAG` | 6,39 | ref |
| `LINE_CONTINUATION` | 6,25 | -2,1% |
| `RDP_RESIDUAL` | 6,37 | -0,2% |

Zero buffers picked `RDP_RESIDUAL_FASTPFOR` or `RDP_RESIDUAL_SEG_FASTPFOR` in the
per-buffer selection oracle, interleaved or segmented.

## The residual math is sound — the coverage isn't

A direct measurement (per-vertex bit cost, `epsilon^2 = 16`, 354 `transportation`
buffers) isolates the two effects segmentation and the byte tables collapse into one
number:

```
eligible-run vertices (LineString/MultiLineString runs >= 3 vertices): 820.150 of 1.180.723 (69,5%)
  dropped by RDP:  237.267 (28,9% of eligible-run vertices)
  kept as keys:    310.551 (the run start/end plus RDP's chosen keys)

on DROPPED vertices only:
  mean plain-delta bits:            11,08
  mean interpolated-residual bits:   8,38   (-24,4%)
```

**Per dropped vertex, the interpolated residual is a real -24,4% against plain delta** —
the design works exactly as intended on the population it touches. Three things dilute
that into a corpus loss:

1. **70,4% of `transportation` parts are exactly 2 vertices** (see
   [../segment-pair.md](../segment-pair.md)) and never enter an eligible run at all — 0%
   of their vertices can ever be dropped.
2. **Of the vertices that are eligible, 71,1% stay as keys** at the byte-minimizing
   `epsilon`, gaining nothing — RDP only pays off on the minority it actually simplifies.
3. **The kept/drop bitmap costs 1 bit per dropped vertex unconditionally**, eating back
   part of the per-vertex win before the FastPFOR block is even built.

Interleaved, FastPFOR block-max pricing erases the win outright (+2,5% bytes) — the same
mechanism [../line-continuation.md](../line-continuation.md) documents for why a real
median improvement can still cost the same or more when a wide and a narrow residual
population share one block. Splitting the two populations into separate FastPFOR blocks
(`RdpResidualSegmented`) recovers most of that (+2,5% -> -0,2%), confirming the
diagnosis, but -0,2% still is not close to `LINE_CONTINUATION_SEG_FASTPFOR`'s -6,8% on
the same layer, and the encoding never wins a single buffer.

## Why a 1-scalar residual isn't possible here

The natural simplification — store only a perpendicular distance, not a full 2D
offset — is not available under the losslessness requirement. The interpolated point is
a *prediction* of where the vertex sits along the segment, from its index fraction;
index and arc-length only coincide when vertices are evenly spaced, which real
polylines are not. The true along-position is real, non-zero information the index
alone does not supply, so `actual - predicted` is a genuine 2D vector regardless of the
reference frame — 2 components is the floor for exact reconstruction, not a design
choice this implementation happened to make.

## Would a tile ever pick one of these?

No. Even after fixing the residual design and adding segmentation, `RDP_RESIDUAL_SEG_FASTPFOR`
never wins a single `transportation` buffer against 10 other registered rows, so a
per-buffer selector gains nothing from including it. The mechanism it relies
on — a genuinely cheaper residual on the vertices it simplifies — is real, but on this
layer the encoder/decoder complexity (an 8-candidate `epsilon` search per buffer, a
kept/drop bitmap, index-interpolated reconstruction) buys less than a five-line
delta+zigzag baseline already gets for free. `LINE_CONTINUATION_SEG_FASTPFOR` reaches
-6,8% on the same layer with none of that machinery, which is the more useful
comparison than any absolute number here.
