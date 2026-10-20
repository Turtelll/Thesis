# Turn-scalar (1-scalar-per-edge)

**Status:** Stage 0 probe, killed before an encoder existed
**Corpus:** previous Munich corpus (`munich.mbtiles`) — see [../reordering.md](../reordering.md)
for the corpus-comparability banner

Answers the open question [../rot90-fixed.md](../rot90-fixed.md) leaves — "untested here" —
directly: does replacing `Rot90Fixed`'s 2D turn residual with one signed scalar plus a
direction bit win, on the theory that a right-angled turn should cost one value instead of
two? A purpose-built probe (original `TurnScalarCli`, deleted after this first run)
measured the residual **distribution and exactness rate**, no bytes, no `Encoding`
written, per Stage 0 of the protocol. Re-derived in 2026-09 as `TurnScalarCli`
(`./gradlew turnScalar`), kept in the tree — see "Re-run" below.

**The scalar form is not exact for a general 90° turn, which is the reason to probe
before building.** For a turn vertex with previous edge `e` and rotated basis
`u = rot90ccw(e)`, the outgoing edge `w` is an exact integer multiple of `u` only when
`|w|` happens to be an integer multiple of `|e|` — otherwise reconstructing `w` from one
scalar needs a division. The probe measures two scalarizations against baseline `B`
(plain delta) and `R2` (`Rot90Fixed`'s existing 2D residual): `S1`, an exact-but-wider
projection pair, and `S2`, the real candidate — `along` (distance) + `perp` (near-zero
if the turn is truly 90°), reconstructed by rounding and correcting any escape.

```
building layer, 582.296 turn-predicted vertices (even, >4-vertex rings)
  turn is exactly 90 deg (cross(u,w)==0)              12,87%
  ...and w is an exact integer multiple of u           2,83%   <- one scalar suffices
  S2 rounded reconstruction exact (no escape needed)  91,55%   (escape rate 8,45%)

predictor                            mean   median      p90      p99    p99.9  %zero-comp
B  baseline delta (2 values)         4,17        5        6        8        9      8,56%
R2 Rot90Fixed 2D residual (2 values) 4,20        5        7        8        9      8,64%
S2-along scalar (1 value)            5,15        5        7        8        9
S2-perp residual (1 value)           1,52        1        4        7        8     44,48%
S1 exact projection pair (2 values)  9,11/4,79   9/5     12/8     14/12    16/14
```

**Result: dead on arrival, confirmed on `landcover` and `landuse` as a reproducibility
control** (exact-integer-multiple share 0,31% / 0,59%; `S2-perp` median 5 / 4 bits — not
a near-zero population outside `building`). Both conditions the idea needs fail
simultaneously:

1. **The exact-integer-multiple share is small everywhere** (2,83% best case, `building`),
   exactly as the corpus's 4,2%-axis-aligned / 24,8%-grid-aligned priors predict — so a
   single scalar practically never suffices without an escape, and the escape path (a
   full 2D delta plus a signalled bit) has to carry the other ~90%.
2. **`S2-perp` is only near-zero on `building`**, and even there it is not narrow enough
   to matter: `S2-along` alone (mean 5,15) is already wider than one component of the
   2-value baseline (mean 4,17/2 ≈ 2,09), so `along` + `perp` does not clearly beat `B`
   or `R2` before a direction bit and an escape bit are even added. On `landcover`/
   `landuse`, `perp`'s median (5, 4 bits) is barely narrower than `B`'s own median (5, 5
   bits) — not the near-zero population the scheme depends on.

**Reproduced on `data/extractedBaseline`** (`munich-buildings-unmerged.mbtiles`, the
project's baseline tileset — the figures above are from the `germany.mbtiles`-cut
corpus): building exact-integer-multiple share 3,04% (468.828 turn vertices, escape rate
8,54%, `S2-along` mean 5,37 vs `S2-perp` mean 1,22); landcover 0,33%, landuse 0,52%, both
with `S2-perp` medians (5, 4 bits) no narrower than baseline. Same failure pattern, same
verdict, on the tileset this repo actually targets.

**No `Encoding` was written.** Same verdict shape as the `QuadCornerResidualCli` and
`RingPriorCli` Stage 0 probes: a candidate that does not move the distribution
enough, checked before paying for an encoder, a round-trip test, and a benchmark column.
This also closes the open question from [../rot90-fixed.md](../rot90-fixed.md)'s note — a
1-scalar-per-edge residual does not pack better than the existing 2D residual, because the
scalar decomposition is not close enough to exact on this corpus to avoid paying for both
the escape path and a still-nontrivial `along` value.

## Re-run (2026-09) — `TurnScalarCli`, bits per vertex

Rebuilt as `TurnScalarCli` on `data/extractedBaseline`, with two changes against the original
probe: (1) **all** ring lengths, every vertex with `i - start >= 2`, split into `n=4` / even
`n>4` / odd; turn direction from winding (outer CCW, inner CW, outer/inner from `numParts`);
(2) cost compared **per vertex**, not per value — `S` stores three values (`along`, `corr.x`,
`corr.y`, `corr = w - round(along·u/|u|)`) against two for `B`/`R2`, so the per-value means
above did not settle it.

```
building                   winding  exact90  corr==0   bits/vertex  B      R2     S
n=4      (521.554 vtx)     100%     12,23%   53,03%                 9,37   6,59   6,95
even n>4 (607.090 vtx)     100%      8,14%   42,02%                 8,21   8,39   7,24
odd n    (304.664 vtx)     100%      6,42%   31,99%                 8,20   8,58   7,91

landcover (control)
n=4      (17.282 vtx)      100%      8,75%   27,27%                 9,95  10,12  10,48
even n>4 (302.516 vtx)     100%      0,90%    5,60%                10,38  11,48  13,26
odd n    (294.510 vtx)     100%      0,73%    4,84%                10,40  11,51  13,34
```

**Result: looked like a partial reversal on `building` `n>4` — the encoder built on it then
regressed; see [turn-scalar-encoder.md](turn-scalar-encoder.md). Original verdict stands.**

- Winding rule holds on 100% of rings on both layers — the direction really is free.
- `building` even `n>4`: `S` 7,24 bits/vertex vs `B` 8,21 (−11,8%) and `R2` 8,39. Odd `n`:
  −3,5% vs `B`. The original "dead" reading came from comparing `S-along` per value against
  half of `B`; per vertex, the near-zero `corr` (median 0) pays for the third value.
- `building` `n=4`: `S` loses to `R2` (6,95 vs 6,59) — and `n=4` is already owned by
  `SEGMENT_QUAD_CORNER`'s parallelogram corner, so it is not the target population.
- `landcover`: `S` loses everywhere (+28% vs `B` on `n>4`) — the prior is building-only.

Caveats before an encoder: `bits()` counts a zero as 0 bits, which flatters a three-value
scheme with a near-zero component; FastPFOR prices the block max, and `corr` has a p99 of
6–7 bits. Only a segmented region layout (`corr` in its own region, like `ROT90_FIXED`'s turn
region) can realize the median-0 population. Next step, if pursued: an encoder restricted to
`building` `n>4` rings, `corr` in its own FastPFOR region, measured against `ROT90_FIXED`.
