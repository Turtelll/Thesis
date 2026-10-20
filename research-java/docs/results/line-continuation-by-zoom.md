# `LINE_CONTINUATION` per zoom level — how road style drives the gain

**Status:** measured, no new encoder — characterizes the registered `LINE_CONTINUATION`
**Effect:** -1,4% to -4,9% mean residual bits on every zoom; bytes (FastPFOR, interleaved)
-0,3% to -2,3% on z4–z11, -0,0% to -0,1% on z12–z14 (Munich); **net regression on
Chicago** (+0,2% bits, +0,3% bytes) — see the second-city section
**Corpus:** current baseline (`munich-plantiler.mbtiles`, `data/extractedBaseline`),
`--layer transportation`, core interleaved only (no `--segmented`, no `--reorder`),
`--set: core -rans`

```
./gradlew benchmark --args="../data/extractedBaseline --layer transportation --by-zoom --verbose"
python data-analysis/transportation_stats.py munich-plantiler
```

`--by-zoom` (added for this measurement) repeats the residual-bits table and the byte
table per tile zoom, each zoom against its own `DELTA_ZIGZAG` / best-baseline reference.
The per-class table comes from the Python scan of the raw MVT tags (`class` is not part
of the extraction); it uses sign+magnitude bit widths and no length gate, so its
percentages are not identical to the Java ones — read it for the direction and the class
mix, not the exact figure.

## Measured

```
zoom  buffers  byte share  DELTA bits  CONT bits  bits gain  FASTPFOR bytes delta  FASTPFOR cont   bytes gain
z4          1       0,1%        2,42       2,30      -4,9%                 2.162          2.158        -0,2%
z5          1       0,1%        2,91       2,78      -4,2%                 1.814          1.798        -0,9%
z6          1       0,1%        3,70       3,57      -3,6%                 2.698          2.666        -1,2%
z7          1       0,3%        4,36       4,20      -3,6%                 8.754          8.550        -2,3%
z8          1       0,4%        4,90       4,68      -4,4%                11.262         11.082        -1,6%
z9          1       1,0%        4,78       4,67      -2,3%                31.696         31.616        -0,3%
z10         4       1,5%        5,39       5,19      -3,7%                45.014         44.758        -0,6%
z11         9       2,0%        5,95       5,70      -4,2%                60.951         60.639        -0,5%
z12        20       7,5%        6,41       6,31      -1,5%               229.897        229.817        -0,0%
z13        63      16,2%        6,52       6,43      -1,4%               497.455        496.923        -0,1%
z14       252      70,9%        6,46       6,32      -2,2%             2.175.001      2.173.529        -0,1%
```

z4–z9 are one tile each — anecdote, not a distribution. Together z4–z11 are 5,5% of
transportation bytes; z12–z14 are 94,5%.

## Why: which roads exist at which zoom

Continuation gain per road `class` (Python scan, interior vertices, `gain` = continuation
vs delta mean bits, `turn50` = median turn angle in degrees, `turn<5` = share of nearly
straight vertices):

```
           z4–z8 (1 tile each)             z11                    z13                    z14
class      gain     turn50            gain   turn50          gain   turn50          gain   turn50  turn<5   verts z14
motorway   -2,3..-10,4%  11–18°      -11,5%   11,5°        -10,1%   10,0°        -13,6%    6,0°   44,0%       7.475
trunk      -3,2..-7,1%   16–22°       -7,0%   14,9°         -8,9%    8,9°        -13,3%    5,5°   45,5%       4.482
primary    -2,8..-4,5%   19–25°       -6,9%   10,1°         -6,2%    7,1°        -10,0%    4,3°   55,6%       5.014
secondary         -           -       -3,5%   12,9°         -5,7%    7,8°         -9,3%    4,8°   51,6%      17.287
rail       -1,7%        11,7°         -5,9%    6,9°         -8,8%    3,8°         -9,6%    2,8°   83,6%      16.126
minor             -           -           -       -         -1,8%   12,5°         -5,5%    7,7°   36,0%      82.971
track             -           -           -       -         -4,4%    9,7°         -6,6%    9,5°   28,3%      34.419
service           -           -           -       -         +2,3%   30,5°         +0,4%   23,7°    9,2%     168.165
path              -           -           -       -         +2,2%   26,6°         -0,9%   18,4°   16,6%     472.509
bridge/pier       -           -           -       -        +10..12%  88–90°      +6..10%  ~88°   0–13%       3.600
```

Three effects explain the per-zoom curve:

1. **Low zooms (z4–z11) only carry the road network's backbone** — motorway, trunk,
   primary, secondary, rail. These are engineered for speed: long, gently curved
   alignments where the next edge nearly continues the previous one. Continuation
   wins -1,6% to -11,5% bits on every one of them, so the zoom totals sit at -2,3% to
   -4,9% bits and the gain survives into bytes (up to -2,3%).
2. **z12–z13 add the dense local network** — `minor` at z12, then `service`, `path`,
   `bridge` and `pier` at z13. `service` and `path` (driveways, parking aisles,
   footpaths) are short, wiggly and turn sharply (median 27°–31° at z13): continuation
   *loses* on them (+2,2% / +2,3%), and `bridge`/`pier` outlines are near-right-angle
   (median ~88°, +10% to +12%). The zoom total collapses to -1,4%/-1,5% bits.
3. **z14 is less generalized**, so every major class gets smoother — turn medians fall to
   3°–6° and 44%–84% of vertices are nearly straight, with gains of -9% to -14% bits on
   motorway/trunk/primary/secondary/tertiary/rail (and -11,4% on `transit`). But `path` +
   `service` are 77% of z14 transportation vertices and gain ~0, so the z14 total is only
   -2,2% bits.

So the gain is a property of the **road style**, not of zoom itself: for a fixed major
class it grows with zoom (motorway -4,8% at z4 → -13,6% at z14; secondary -1,6% at z9 →
-9,3% at z14), while the pooled per-zoom figure stays flat because each new zoom brings in
a wigglier class that dilutes it. This is the same reason
[turn-radius-by-zoom.md](turn-radius-by-zoom.md) found pooled curvature flat on this
corpus — the class mix shifts underneath it.

## Why so little reaches the bytes interleaved

At z12–z14 the -1,4% to -2,2% bits turn into only -0,0% to -0,1% bytes. Interleaved,
the narrow continuation residuals of a motorway share FastPFOR blocks with the wide
part-start jumps and the unchanged `path`/`service` residuals, and FastPFOR prices a
block by its widest value, so the narrowing in the median rarely lowers a block's bit
width. Low-zoom tiles are almost entirely major roads in long runs, so whole blocks
narrow and the saving does reach the bytes. Separating part starts from the interior
values is what [line-continuation.md](line-continuation.md)'s `_SEG` layout does
(-6,8% transportation) — the layer-level answer to this effect, deliberately not used
here.

## Second city: Chicago — the win does not generalize

Same producer and profile (planetiler, OpenMapTiles), different street pattern:
`data/MVT/chicago-25km.mbtiles`, built from Geofabrik's `illinois.osm.pbf` (2026-10-02)
with `--bounds=-87.902,41.766,-87.600,41.990`, extracted to `data/extracted-chicago`
(3.769 buffers, 319 `transportation`).

```
./gradlew benchmark --args="../data/extracted-chicago --layer transportation --by-zoom --verbose"
```

```
zoom  buffers  byte share  DELTA bits  CONT bits  bits gain  FASTPFOR delta  FASTPFOR cont  bytes gain
z4          1       0,0%        2,66       2,54      -4,4%           1.162          1.150       -1,0%
z5          1       0,1%        3,10       3,06      -1,5%           2.366          2.358       -0,3%
z6          1       0,1%        3,64       3,61      -0,9%           3.590          3.586       -0,1%
z7          1       0,4%        3,93       3,98      +1,3%          13.694         13.770       +0,6%
z8          1       0,4%        4,64       4,66      +0,6%          14.050         14.194       +1,0%
z9          2       1,5%        4,55       4,55      -0,0%          51.472         51.648       +0,3%
z10         4       1,9%        5,08       5,03      -1,0%          64.950         65.178       +0,4%
z11         9       2,5%        5,61       5,52      -1,7%          85.048         85.300       +0,3%
z12        24      10,8%        6,18       6,15      -0,5%         359.443        359.971       +0,1%
z13        62      23,7%        5,93       5,99      +1,0%         789.258        790.422       +0,1%
z14       213      58,5%        6,16       6,16      +0,0%       1.951.908      1.957.924       +0,3%
layer     319     100,0%        6,01       6,02      +0,2%       3.336.941      3.345.501       +0,3%
```

**On Chicago `LINE_CONTINUATION` is a net regression** (+0,2% bits, +0,3% bytes) where
Munich gained -2,1% bits. The class table (Python scan) shows why:

```
class        Munich z14 gain  Chicago z14 gain  Chicago turn50  Chicago z14 verts
path                  -0,9%            +3,2%           17,1°            349.028
service               +0,4%            +3,8%           23,7°            211.794
minor                 -5,5%            -2,4%            9,7°             56.837
bridge                +5,9%           +18,8%           88,9°             19.439
secondary             -9,3%            -2,4%            3,9°             17.406
motorway             -13,6%           -12,3%            4,9°             11.834
rail                  -9,6%            -6,6%            3,7°             35.800
```

- **Grid streets are straight but unevenly spaced.** Chicago's `secondary`/`tertiary`/
  `minor` are as straight as Munich's (median turn 4°–10°, more than half of `secondary`
  vertices below 5°), yet gain only -2% to -4% at z14 and *lose* at z11 (+0,7%/+1,0%).
  Continuation predicts the direction *and the length* of the previous edge. On an
  axis-aligned grid street the perpendicular component is already ~0 under plain delta,
  so the only thing left to predict is the along-street length — and vertices sit at
  intersections and driveways with irregular spacing, so the length guess is often no
  better than the delta itself.
- **More short, sharp geometry.** Chicago's `path` and `service` (alleys, parking aisles,
  park paths) lose +3% to +4% at z14 instead of breaking even, and there are 6× more
  `bridge` vertices than in Munich (Chicago River, rail/expressway overpasses), which are
  near-right-angle outlines that lose +19%.
- **What still wins is the same as in Munich:** curved, engineered alignments —
  `motorway` -12,3%, `transit` -8,0%, `track` -7,2%, `rail` -6,6% — but in Chicago they
  are an even smaller share of vertices.

**Chicago z7 is not the exception it looks like.** It holds only major roads (primary,
motorway, trunk), 37% of its line-interior vertices turn less than 5°, and still
continuation is +1,3% bits / +0,6% bytes there (`z7_check.py` on `7_32_47`). The tile is
not limited to the 25 km box — `--bounds` only selects tiles, and the single z7 tile spans
nearly the full 4096-unit extent with the Illinois highway network — and generalization
leaves one vertex per bend, so the *length* of consecutive edges is unrelated: only 16%
of next edges are within ±20% of the previous edge's length, and 85% of the
nearly-straight vertices miss by more than that. Continuation guesses the direction
right and the distance wrong; only 3,1% of residuals are exactly zero.

So the Munich conclusion holds in mechanism (the gain follows road style) but not in sign:
whether the layer as a whole gains depends on the city's mix of curved engineered roads
versus grid streets, footpaths and bridges. A corpus-level win of `LINE_CONTINUATION`
interleaved should not be assumed for grid cities.

## Same comparison under Varint

Bytes per zoom, `LINE_CONTINUATION_VARINT` vs `DELTA_ZIGZAG_VARINT` (both cities, from
the benchmark's `--csv` output aggregated by `z`; residual bits are codec-independent, so
the bit tables above apply unchanged):

```
zoom   Munich delta   Munich cont    gain   Chicago delta  Chicago cont    gain
z4            3.247         3.247   +0,0%          1.458         1.458   +0,0%
z5            2.433         2.433   +0,0%          2.836         2.834   -0,1%
z6            3.168         3.168   +0,0%          4.031         4.033   +0,0%
z7            9.827         9.820   -0,1%         14.825        14.887   +0,4%
z8           11.978        11.907   -0,6%         14.620        14.731   +0,8%
z9           33.289        33.269   -0,1%         54.677        54.827   +0,3%
z10          46.126        45.740   -0,8%         67.373        67.746   +0,6%
z11          61.406        60.510   -1,5%         86.127        86.697   +0,7%
z12         232.528       231.482   -0,4%        361.663       362.819   +0,3%
z13         501.137       498.174   -0,6%        807.931       811.446   +0,4%
z14       2.212.306     2.197.144   -0,7%      1.973.364     1.991.015   +0,9%
layer     3.117.445     3.096.894   -0,7%      3.388.905     3.412.493   +0,7%
```

- **Varint keeps more of the bit gain at z12–z14 than FastPFOR does** (Munich -0,4% to
  -0,7% vs -0,0% to -0,1%): Varint prices each value on its own, so every residual that
  drops below a 7-bit boundary saves a byte, with no block maximum to absorb it.
- **It loses the low-zoom gain entirely** (z4–z6 +0,0% despite -0,9% to -4,9% bits):
  at 2–4 mean bits nearly every value already fits in one Varint byte, and a narrower
  residual cannot go below one byte. FastPFOR's bit-packing is what turned those into
  -0,2% to -2,3% bytes.
- **The Chicago regression is codec-independent** — +0,3% to +0,9% on every zoom from
  z7 up under Varint, as under FastPFOR.
- **Varint is still the wrong codec for this layer**: the best Varint row is +1,0%
  (Munich) / +2,3% (Chicago) over `DELTA_ZIGZAG_FASTPFOR`, so its larger relative gain
  never makes it the smallest encoding at the layer level.

## The other road layer: `transportation_name`

Byte change of `LINE_CONTINUATION` vs `DELTA_ZIGZAG` under the same codec
(`--layer transportation_name`, same two extractions):

```
zoom   Munich Varint  Munich FastPFOR  Chicago Varint  Chicago FastPFOR
z6           -1,1%           -4,4%          +0,2%            +0,5%
z7           -1,3%           -1,6%          +0,1%            -0,3%
z8           -3,6%           -3,8%          +1,5%            +0,7%
z9           -2,2%           -1,6%          +1,9%            +1,0%
z10          +0,0%           +0,0%          +0,0%            +0,0%
z11          +0,0%           +0,0%          +0,0%            +0,0%
z12          -0,2%           -0,2%          +0,0%            +0,0%
z13          -1,6%           -0,9%          +0,3%            +0,3%
z14          -1,9%           -0,6%          -0,1%            -0,1%
layer        -1,8%           -0,6%          -0,0%            -0,0%
```

Mean residual bits: Munich -3,6%, Chicago -1,3%. This layer only holds named roads, with
same-name segments merged into longer label lines (OpenMapTiles profile). That plausibly
explains the stronger win in Munich and a wash instead of a loss in Chicago, but this
layer was not broken down by road class, so it is not confirmed. z10–z11 are
+0,0% in both cities.

## What this means for future work

- A class-aware gate (continue on major roads/rail, plain delta on `service`/`path`/
  `bridge`/`pier`) would target exactly the split above, but `class` is a feature
  property, not part of the geometry column — the decoder would need the property
  column before geometry, or one signalled bit per feature.
- The per-class bit gains at z14 (-9% to -14%) are the headroom such a gate could reach
  on the major classes. Those classes (motorway, trunk, primary, secondary, tertiary,
  rail, transit) are only 8,4% of z14 transportation vertices, though, so the ceiling for
  the layer is small unless `minor`/`track` (-5,5% / -6,6%, another 14%) are included too.
