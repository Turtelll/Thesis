# 90° edge rotation with direction bitmap (`TURN90_BITMAP`) + reflex-signal probe

> **Verdict: Rejected.** +1,27% gzip on the tileset (split varints), +2,15% over plain Varint; the direction
> bitmap costs about seven times what the prediction saves.

## Encoding target
building (Polygon), every polygon ring of every layer.

## Idea
Predict each ring vertex by turning the previous edge 90° (keeping its length) and signal the direction with
one bit. Follow-up: the winding is known, so maybe only the reflex corners need signalling, or a rule like
"after a reflex turn come two convex ones" makes the direction derivable.

## Intention
Same rectilinear prior as the [signalled right-angle turn](right-angle-turn.md), applied to every ring vertex.
The follow-up asked whether the signal can be made cheaper: the winding fixes which way most corners turn, so
only the exceptions (reflex corners) might need a bit, or none at all if their pattern is regular.

## How the encoding works
- Per polygon ring: v0, v1 plain delta; from v2 `d = v[i-1] - v[i-2]`, prediction `v[i-1] + (-dy, dx)` (bit 0) or
  `v[i-1] + (dy, -dx)` (bit 1), encoder picks fewer zigzag bits (tie → 0).
- Bitmap: one bit per predicted vertex (`Σ max(0, n-2)` per ring), MSB-first behind the codec payload with its own
  `StreamMetadata` header. Lines and points plain delta.
- Packings tried: separate bitmap, flag in the x residual's low bit, reflex gaps interleaved.

## How the decoder works
Per predicted vertex read the bit, rotate the decoded previous edge accordingly, add the residual.

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`): **tileset +1,27%**
(`TURN90_BITMAP_VARINT_SPLIT`, computed from the gzip byte total); over plain Varint **+2,15%**. Per layer only
the smallest of gzip/Brotli/zstd was reported: `building` -0,76%.

**Decisive case against:** the bitmap is 179 KB, the prediction saves ~25 KB. Rectangles are not squares, so a
90° turn of the previous edge is exactly right for only 1,7% of predictions, and the residual stays large. The
cheaper signals do not rescue it: the winding is derivable on 100% of rings, but the "two convex after reflex"
rule is violated on 26,5% of flags, and the best packing (flag in the residual's low bit) is still +2,4%
(FastPFOR).

Supporting figures:

- Brotli over plain Varint +1,98%; smallest of gzip/Brotli/zstd, split varints, tileset +0,26%.
- *Not under the headline metric* — current corpus, `building`, uncompressed bytes:

| row | mean bits all-in | FastPFOR bytes | Varint bytes |
|---|---|---|---|
| `DELTA_ZIGZAG` | 4,78 | 4.285.065 | 4.688.602 |
| `QUAD_CORNER_SHARED` | 4,21 (-12,0%) | -3,5% | |
| `TURN90_BITMAP` | 4,65 (-2,6%) | **+3,7%** | +3,8% |

- Residual -9,5% bits but only ~-0,5% bytes; corpus +2,9% bytes. Context-conditioned entropy bound ~112 KB.
- Packings tried: separate bitmap, flag in the x residual's low bit, reflex gaps interleaved.

Source: `research-java/docs/results/failed/turn90-bitmap.md`, `general-purpose-compression.md`,
`revisit-varint-split.md`.
