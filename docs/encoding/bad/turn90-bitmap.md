# 90° edge rotation with direction bitmap (`TURN90_BITMAP`) + reflex-signal probe

## encoding target:
building (Polygon), every polygon ring of every layer

## Idea of the encoding
Predict each ring vertex by turning the previous edge 90° (keeping its length) and signal the direction with one bit.
Follow-up: the winding is known, so maybe only the reflex corners need signalling, or a rule like "after a reflex
turn come two convex ones" makes the direction derivable.

## How the encoding works
- Per polygon ring: v0, v1 plain delta; from v2 `d = v[i-1] - v[i-2]`, prediction `v[i-1] + (-dy, dx)` (bit 0) or
  `v[i-1] + (dy, -dx)` (bit 1), encoder picks fewer zigzag bits (tie → 0).
- Bitmap: one bit per predicted vertex (`Σ max(0, n-2)` per ring), MSB-first behind the codec payload with its own
  `StreamMetadata` header. Lines and points plain delta.
- Packings tried: separate bitmap, flag in the x residual's low bit, reflex gaps interleaved.

## How the decoder works
Per predicted vertex read the bit, rotate the decoded previous edge accordingly, add the residual.

## results
Current corpus `munich-plantiler`, `building`:

| row | mean bits all-in | FastPFOR bytes | Varint bytes |
|---|---|---|---|
| `DELTA_ZIGZAG` | 4,78 | 4.285.065 | 4.688.602 |
| `QUAD_CORNER_SHARED` | 4,21 (-12,0%) | -3,5% | |
| `TURN90_BITMAP` | 4,65 (-2,6%) | **+3,7%** | +3,8% |

- Residual -9,5% bits but only ~-0,5% bytes; bitmap 179 KB vs ~25 KB saved; only 1,7% of predictions exact (rectangles
  are not squares). Corpus +2,9% bytes.
- Winding derivable on 100% of rings, but the "two convex after reflex" rule is violated on 26,5% of flags; best
  packing (flag in low bit) still +2,4% (FastPFOR). Context-conditioned entropy bound ~112 KB.
- General-purpose compression: +2,15% gzip / +1,98% Brotli; split varints +0,26% whole tileset, `building` -0,76%.
  Retired.
