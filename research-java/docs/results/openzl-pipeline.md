# OpenZL-style pipeline: results per step

A second, separate encoding pipeline, `org.maplibre.mlt.research.pipeline`, built after the design
in [DesingDocOpenzl.md](DesingDocOpenzl.md) and measured the same way as the old one (see
[general-purpose-compression.md](general-purpose-compression.md)): every extracted (tile, layer)
buffer is encoded, round-tripped, and sized as a browser receives it, the buffers of one tile
concatenated and compressed once per backend. The stages, one step each, in strict order:

```
S1 reorder -> S2 predict -> S3 segment -> S4 residual map -> S6 byte width -> S7 transpose -> S8 backend
```

S5 (a per-stream GCD) was measured below and then removed from the encoder. Later stage numbers are unchanged.

Each step varies only its own stage over the winners of the steps before it and is kept or
discarded on the measured bytes. The rows of a step are `PipelineRegistry.step("sN")`, so
`./gradlew research --args="measure --step sN --by-zoom"` reproduces its table whatever was decided
later. The rule of the per-step gate: a mechanism test (`src/test/.../pipeline/*MechanismTest`),
a lossless round trip of the whole corpus (the CLI aborts on the first mismatch), and a verdict here.

**Corpus and reference.** `data/extractedBaseline`: 4.368 buffers in 358 tiles, 42.871.432 raw
bytes, 16 layers. Every percentage is against the smaller of the two baselines under gzip,
`DELTA_ZIGZAG_VARINT` (11.237.124 bytes), including the Brotli and zstd columns. The step logs are in
`research-java/build/pipeline-<step>.txt`; S0 to S7 ran with Brotli quality 9 for speed, S8 and the
selection with 11 (zstd always level 19). A `Brotli9` figure is therefore not comparable to an S8 one.

**Scope.** Pure predictors (S2): no shared-vertex references, so the old pipeline's best row is out
of reach by decision. The old rows `QUAD_CORNER`, `QUAD_CORNER_SHARED_RING_CANONICAL_REORDER` and
`LINE_CONTINUATION_SHARED_DICT` are shown beside the new ones as read-only references.

**Format.** Per buffer a two-byte header (predictor, segmentation, map, transpose, width),
then one stream per non-empty class; no lengths are written, they follow from the topology streams and
the predictor. The reorder is not signalled: the tile carries the reordered topology, and the decoder
works from that.

**Property cost of a reorder.** Reordering features permutes the MLT property columns, which are
compressed with them. A geometry-only benchmark therefore overstates what the reorder is worth; S1
measures the property side with `PipelineTileSizeCli` and S8 settles it.

## S0 harness

The anchor: identity configuration (`DELTA`, `FLAT`, zigzag, plain varint) must equal the old
`DELTA_ZIGZAG_VARINT` body byte for byte after the header (`PipelineEncodingTest.identityBodyIsTheBaselineVarint`),
and the split varint terminal the old `VARINT_SPLIT` body.

| row | gzip | brotli9 | zstd19 |
|---|---|---|---|
| `DELTA_ZIGZAG_VARINT` (ref) | 11.237.124 | 11.141.924 (-0,85%) | 11.201.331 (-0,32%) |
| `PIPE_DELTA_FLAT_ZIGZAG_VARINT` | 11.243.566 (+0,06%) | 11.147.201 (-0,80%) | 11.207.288 (-0,27%) |
| `PIPE_DELTA_FLAT_ZIGZAG_VARINT_SPLIT` | 11.146.031 (-0,81%) | 10.952.917 (-2,53%) | 11.013.695 (-1,99%) |

The header costs +0,06% gzip (4.368 buffers x 2 bytes raw). **Verdict: keep the split varint** as the
terminal the later steps start from: -0,87% gzip against the plain varint row.

## S1 reorder

Reorders the features of a buffer along a space-filling curve and, in the rotated variants, rotates
each polygon ring to start at the vertex nearest the previous run's end. Rows: Hilbert and Morton
order of the first vertex (`_START`) or of the bounding-box centre (`_CENTROID`), `_ROT` (ring
rotation), `_VC` (vertex-class sort), `CHAIN` / `CANON_CHAIN` (nearest-endpoint chaining), `_MP`
(also sorts the members of MultiPoints). Probe: mean |inter-run jump| (the distance from one run's last
vertex to the next run's first, the quantity a reorder shrinks).

| row | gzip | brotli9 | zstd19 | mean jump |
|---|---|---|---|---|
| `NONE` | -0,81% | -2,53% | -1,99% | 903,7 |
| `HILBERT_START` | -3,53% | -5,18% | -4,79% | 594,6 |
| `HILBERT_CENTROID` | -3,45% | -5,23% | -4,72% | 592,0 |
| `MORTON_START` | -3,23% | -4,87% | -4,49% | 621,0 |
| `MORTON_CENTROID` | -3,15% | -4,88% | -4,42% | 616,5 |
| `HILBERT_START_ROT` | -3,96% | -5,64% | -5,22% | 582,1 |
| **`HILBERT_CENTROID_ROT`** | **-3,97%** | -5,71% | -5,22% | 575,6 |
| `HILBERT_START_VC` | -3,54% | -5,15% | -4,81% | 594,3 |
| `CHAIN` | -2,42% | -4,26% | -3,67% | 728,8 |
| `CANON_CHAIN` | -2,55% | -4,36% | -3,84% | 730,7 |
| `HILBERT_CENTROID_ROT_MP` | -4,05% | -5,85% | -5,30% | 576,7 |

Hilbert beats Morton (jump 592 against 617), the centroid and the start vertex are level, ring
rotation is worth 0,5 points, and the chaining rows win on lines (`transportation` -5,48% against
-3,82%) but lose on points (`poi` +3,29% against -19,60%) and overall. By layer the Hilbert rows help
`poi` (-19,60%, the points of a tile end up in sequence), `transportation` (-3,82%), `landcover`
(-3,85%) and `housenumber` (+5,25% against +6,82%; FastPFOR is the smaller baseline on `housenumber`
and `poi`, which is why they read positive or small). By zoom the rotated Hilbert row pays from z6
(z8 -1,78%, z12 -5,53%, z14 -4,33%) and is level from z0 to z5. `HILBERT_CENTROID_ROT_MP` is the smallest row in all three backends, 0,08 points
under `HILBERT_CENTROID_ROT`. It was first left out of the chain without a measured reason; it was
then measured and carried forward, so **the registry's reorder is `HILBERT_CENTROID_ROT_MP`**
(`GEOMETRY_BEST`, and the base of S2 to S8). The `_MP` flag sorts the members of every
MultiPoint along the curve (free for rendering, the order has no meaning; `Verification` compares them as a
set) and, by the early return in `Reorder.apply`, also lets buffers of a single feature through the
reorder. Reading the rows, the gain is not on `housenumber`, the MultiPoint layer (`+5,31%` against
`+5,25%`, `poi` identical), but on `building` (-2,81% against -2,54%) and at z0 to z5 (z0 -0,50% against
+0,02%), where buffers hold few features.

Its property cost, measured with `pipelineTileSize --modes HILBERT_CENTROID_ROT,HILBERT_CENTROID_ROT_MP`
(`build/pipeline-mp-tile.txt`), is the same as `_ROT`'s: whole tile gzip 18.640.381 against 18.640.990
(+2,48% both), props-only 6.236.401 against 6.236.343 (+13,39% both). So the extra geometry gain comes
free. S2 to S8 were rerun on the `_MP` base (`build/pipeline-mp-s2.txt` to `pipeline-mp-s8.txt`, Brotli 9
for S2 to S7, 11 for S8) and no decision changed. The S2 to S7 tables below are the `_ROT` runs
(`pipeline-s2.txt` to `pipeline-s7.txt`); the `_MP` figures differ by a few tenths of a point. S8 gives
both: the `_MP` row is the headline and plain `_ROT` stays as a comparison row. The selection loop
(below) ran its reorder rows with plain `_ROT` and was not rerun (41 minutes).

**The property side** (`pipelineTileSize`, whole tile MLT with and without the reorder, 358 tiles):

| mode | full tile gzip | props-only gzip |
|---|---|---|
| `NONE` | 18.189.926 | 5.499.943 |
| `HILBERT_START` | 18.632.743 (+2,43%) | 6.231.506 (+13,30%) |
| `HILBERT_CENTROID` | 18.640.990 (+2,48%) | 6.236.343 (+13,39%) |
| `MORTON_CENTROID` | 18.670.024 (+2,64%) | 6.254.374 (+13,72%) |

Brotli9 and zstd19 agree (+2,2 to +2,6% full tile). Per layer (gzip of single-layer, property-only tiles,
`HILBERT_CENTROID`): `housenumber` -1,64%, `building` +3,11%, `place` +2,07%, `transportation_name`
+3,99%, `waterway` +5,45%, `aeroway` +7,04%, `landuse` +9,98%, `water` +10,16%, `poi` +14,99%,
`transportation` +22,73%, `landcover` +27,51% (the others level, under 1%). `poi` carries the largest
geometry gain and the largest property cost.

**Why the property columns grow** (`pipelineTileSize --runs`, first 120 tiles, `HILBERT_CENTROID`). Two things
are measured, per layer, over neighbouring features: whether the feature ids still ascend (MLT delta-codes
them), and how often a property keeps its value from one feature to the next (runs).

| layer | ids ascending, before to after | mean log2 of the id step | neighbours with the same value |
|---|---|---|---|
| `transportation` | 96,0% to 49,9% | 17,8 to 27,3 | `class` 78,6% to 56,4%, `brunnel` 83,3% to 28,0%, `layer` 81,9% to 29,5% |
| `landcover` | 99,2% to 49,9% | 17,6 to 25,8 | `class` 72,2% to 69,8% |
| `poi` | 84,2% to 50,9% | 21,3 to 27,1 | `class` 64,5% to 41,4%, `subclass` 56,0% to 35,1% |
| `landuse` | 97,9% to 50,4% | 21,3 to 27,6 | `class` 30,8% to 28,3% |
| `water`, `waterway` | 100% to about 50% | 18,3 to 24,0 / 16,7 to 24,1 | `class` 68,8% to 67,1% / 73,9% to 72,2% |
| `place` | 54,6% to 49,6% | 30,2 to 28,8 | `class` 96,2% to 57,4% |
| `housenumber` | 47,7% to 49,7% | 22,4 to 20,5 | no property repeats |

The tiles arrive with the features ordered by id: ascending for 100% of neighbours in `water`, `waterway` and
`boundary`, 96 to 99% in `transportation`, `landcover` and `landuse`, 84% in `poi`, but only 48 to 55% in
`housenumber`, `place` and `mountain_peak`. A space-filling-curve order makes the sequence random (about
half the steps go down) and the steps 6 to 10 bits larger. The same order also splits the runs of equal values in the category columns
(`class`, `subclass`, `brunnel`, `layer`). `housenumber` has neither ids in order nor repeating values, so it
loses nothing and gains a little (-1,64%). The ranking of the layers by property cost follows the id damage and
the lost runs, not their geometry gain. The bytes of the id column against those of the other columns were not
measured separately.

**Verdict.** Keep `HILBERT_CENTROID_ROT` as the base of S2 to S7: it wins -3,97% gzip on the geometry
buffers, the property cost is a separate account settled in S8. At this point, against plain delta,
the reorder alone *loses* end to end: geometry saves about 3,1 points of 11,2 million (about 350.000),
the properties cost +736.000. Only `housenumber` pays on both sides. S8 revisits it with the predictors in.

## S2 prediction

Pure predictors only, the ones that worked in the old pipeline (`Prediction` wraps them): plain
`DELTA`; `QUAD_CORNER` (corner prediction on rings); `LINE_CONTINUATION` (continuation of the previous
edge on lines); `QUAD_CORNER_LINE`, which applies each where it fits. Probe: order-0 entropy of the FLAT zigzag
residuals, bits per vertex.

| row | gzip | brotli9 | zstd19 |
|---|---|---|---|
| `DELTA` | -3,97% | -5,71% | -5,22% |
| `QUAD_CORNER` | -4,79% | -6,59% | -6,29% |
| `LINE_CONTINUATION` | -3,83% | -5,59% | -5,09% |
| `QUAD_CORNER_LINE` | -4,65% | -6,43% | -6,17% |

Entropy over all vertices, bits per vertex: `DELTA` 15,547, `QUAD_CORNER` 15,273, `LINE_CONTINUATION`
15,495, `QUAD_CORNER_LINE` 15,223. Best per layer by that probe: `QUAD_CORNER` on `building`, `landcover`,
`landuse`, `water`, `park`, `aeroway`; `LINE_CONTINUATION` on `transportation`, `transportation_name`,
`waterway`, `water_name`; `DELTA` on `housenumber`, `poi`, `place`, `boundary`, `mountain_peak`
(points, and `boundary` where the predictors tie). Per layer, gzip of each buffer:

| layer | `DELTA` | `QUAD_CORNER` | `LINE_CONTINUATION` | `QUAD_CORNER_LINE` |
|---|---|---|---|---|
| building | -2,54% | -5,02% | -2,54% | -5,01% |
| transportation | -3,82% | -3,56% | -4,88% | -4,58% |
| transportation_name | -2,77% | -2,59% | -4,58% | -4,30% |
| landcover | -3,85% | -4,08% | -3,85% | -4,08% |
| landuse | -3,44% | -4,10% | -3,44% | -4,10% |
| water | -1,64% | -2,01% | -1,64% | -2,00% |

**Verdict.** Keep `QUAD_CORNER_LINE`. On this building-heavy corpus plain `QUAD_CORNER` is 0,14 points
smaller overall, but loses 1,0 point on `transportation` and 1,7 on `transportation_name`;
`QUAD_CORNER_LINE` is within 0,3 points of the best choice on every layer, which no other single predictor
is. The per-layer choice is left to the selection loop.

## S3 segmentation

Splits the residuals into several streams so the LZ77 stage sees homogeneous data: `FLAT` (one),
`CLASS` (one per predictor class: interior, start, corner), `ROLE` (one per geometric role of a
vertex: jump to a new run, first edge, body), `ROLE_CLASS` (both). Also the sorted order
`CLASS_HILBERT_CENTROID_ROT` (S1 with the classes contiguous in feature order).

| row | gzip | brotli9 | zstd19 |
|---|---|---|---|
| `DELTA`, `FLAT` / `CLASS` / `ROLE` | -3,97% / -3,98% / -3,51% | -5,71% / -5,71% / -5,68% | -5,22% / -5,23% / -4,77% |
| `QUAD_CORNER_LINE`, `FLAT` | -4,65% | -6,43% | -6,17% |
| **`QUAD_CORNER_LINE`, `CLASS`** | **-5,86%** | **-8,12%** | **-7,34%** |
| `QUAD_CORNER_LINE`, `ROLE` | -4,53% | -6,78% | -6,06% |
| `QUAD_CORNER_LINE`, `ROLE_CLASS` | -5,46% | -7,75% | -6,90% |
| `QUAD_CORNER`, `CLASS` | -6,07% | -8,30% | -7,60% |
| `QUAD_CORNER`, `ROLE_CLASS` | -5,54% | -7,81% | -7,03% |
| `LINE_CONTINUATION`, `CLASS` | -3,99% | -6,14% | -5,25% |
| `LINE_CONTINUATION`, `ROLE_CLASS` | -3,42% | -5,68% | -4,66% |
| class-sorted order, `FLAT` | -4,15% | -6,01% | -5,64% |
| class-sorted order, `ROLE_CLASS` | -5,04% | -7,36% | -6,47% |

Class streams are worth +1,21 points gzip over `FLAT` with `QUAD_CORNER_LINE`, and nothing for plain `DELTA`
(it has one class): the gain is the corner and start residuals no longer interleaved with interior ones,
mostly on `building` (-8,69% against -5,01%). The role split loses everywhere: with `DELTA` 0,46
points against `FLAT`, with `QUAD_CORNER_LINE` 1,3 points (`ROLE`) and 0,4 points (`ROLE_CLASS`) against
`CLASS`. Probe: the jump stream has 38,8% of its values above 255 (mean zigzag 582), the edge stream 4,9%
(68,5), the body stream 4,3% (61,8) — different distributions, but the split costs more than it separates.
The class-sorted order costs more in the properties than it gains in the geometry
(`landcover` +33,5% against +27,5%, `transportation` +24,0% against +22,7%) and loses on the geometry too.
**Verdict.** Keep `CLASS`; discard the role split and the class sort. `QUAD_CORNER` + `CLASS` is again 0,2
points ahead on this corpus (`building`) and again behind on lines, so `QUAD_CORNER_LINE` stays.

## S4 residual map

Measured, then limited to zigzag; the bias map was removed from the encoder.
`ZIGZAG` against `BIAS` (the residuals shifted by the per-stream minimum, no sign folding; the bias is
written in front of the stream).

| row | gzip | brotli9 | zstd19 |
|---|---|---|---|
| `ZIGZAG` | 10.578.921 (-5,86%) | 10.324.945 (-8,12%) | 10.412.687 (-7,34%) |
| `BIAS` | 12.306.124 (+9,51%) | 11.522.190 (+2,54%) | 11.789.251 (+4,91%) |

`BIAS` is larger raw (20,4 against 12,9 million) and worse than zigzag on every layer, `poi` included
(-8,96% against -19,55%). **Verdict.** Discard; zigzag stays. The bias map was re-measured over the S6 widths and
the role split (below) and also loses there.

## S5 GCD

Measured, then removed from the encoder. Divide the stream by the greatest common divisor of its values and write the divisor in front.

| row | gzip | brotli9 | zstd19 |
|---|---|---|---|
| no GCD | 10.578.921 (-5,86%) | 10.324.945 (-8,12%) | 10.412.687 (-7,34%) |
| GCD | 10.585.810 (-5,80%) | 10.339.757 (-7,99%) | 10.415.490 (-7,31%) |

Probe: 1.042 of 11.817 streams (8,8%) have a divisor above 1, but they hold 2.232 values, 0,021% of the
corpus; they are the tiny streams (`aerodrome_label` 38%, `water_name` 5,5%, `mountain_peak` 4,0% of their
values; `building` 0,000%, `transportation` 0,000%). The divisors seen are mostly 2 (419) and 3 (141), on
streams of about two values. **Verdict.** Discard; the divisor written in front of every stream costs more
than it saves on the 0,021% of values it shrinks.

## S6 byte width

The terminal that turns a stream of integers into bytes: plain `VARINT`, `VARINT_SPLIT` (first byte of each
varint in one stream, continuation bytes in another, from S0), `MIN_FIXED` (smallest fixed width per stream),
`ESCAPE` (fixed one or two bytes, `0xFF` marks a value above it, exceptions in a side stream), `FORCE_4`.
Probe: of 10.717.858 values 12,76% are 255 or above, none above 65.535; 1.654 streams fit one byte, 10.163 two,
none need four.

| row | gzip | brotli9 | zstd19 |
|---|---|---|---|
| `VARINT` | -4,55% | -6,30% | -5,09% |
| `VARINT_SPLIT` | -5,86% | **-8,12%** | **-7,34%** |
| `MIN_FIXED` | +12,27% | +5,96% | +4,58% |
| **`ESCAPE`** | **-5,90%** | -8,09% | -6,98% |
| `FORCE_4` | +35,18% | +16,00% | +20,41% |
| `ROLE_CLASS` + `MIN_FIXED` | +12,69% | +6,40% | +5,01% |
| `ROLE_CLASS` + `ESCAPE` | -5,57% | -7,80% | -6,64% |
| `BIAS` + `MIN_FIXED` | +23,66% | +18,16% | +16,25% |
| `BIAS` + `ESCAPE` | +20,66% | +15,61% | +14,81% |

A fixed width of two or four bytes wastes bytes (`MIN_FIXED`, `FORCE_4` raw size 20,7 and 42,9 million
against 12,9) and the backend does not make it back. `ESCAPE` keeps one byte per residual with the
rare exceptions on the side: level with `VARINT_SPLIT` under gzip (-0,04 points), 0,03 points worse under Brotli9,
0,36 worse under zstd. The role split gives each stream its own width (its point, left open in S3), and
the bias map the same width as zigzag (left open in S4); neither wins. **Verdict.** Keep `ESCAPE`: it is
level with `VARINT_SPLIT` and is the fixed-width terminal S7's transposes need. Discard `MIN_FIXED`, `FORCE_4`,
the role split and the bias map for good.

## S7 byte-plane transpose

Reorders the bytes of a fixed-width stream before the backend: `INTERLEAVED_PLANES` (all low bytes, then all
high bytes, x and y still interleaved), `PLANE_MAJOR` (planes, each x then y), `COMPONENT_MAJOR` (x
as planes, then y as planes). Only for fixed widths.

| row | gzip | brotli9 | zstd19 |
|---|---|---|---|
| `ESCAPE`, no transpose | -5,90% | -8,09% | -6,98% |
| **`ESCAPE` + `INTERLEAVED_PLANES`** | **-6,51%** | **-8,79%** | **-7,93%** |
| `ESCAPE` + `PLANE_MAJOR` | -5,72% | -8,17% | -7,21% |
| `ESCAPE` + `COMPONENT_MAJOR` | -5,70% | -8,02% | -7,11% |
| `MIN_FIXED` + `INTERLEAVED_PLANES` | -4,64% | -7,87% | -7,50% |
| `FORCE_4` + `INTERLEAVED_PLANES` | -3,19% | -7,41% | -7,27% |
| `ROLE_CLASS` + `ESCAPE` + `INTERLEAVED_PLANES` | -6,17% | -8,47% | -7,59% |

The planes win 0,6 points gzip, 0,7 Brotli9 and 0,9 zstd over the untransposed escape stream, and 0,65 / 0,67 /
0,59 over `VARINT_SPLIT`. They turn the fixed widths from a loss into a win (`MIN_FIXED` +12,27% to
-4,64%, `FORCE_4` +35,18% to -3,19%), but `ESCAPE` stays best. The layouts that separate x from y
(`PLANE_MAJOR`, `COMPONENT_MAJOR`) lose 0,8 points against the interleaved planes. By layer the planes
help `housenumber` (+3,71% against +6,70% without) and `transportation` (-4,93% against -4,23%); at z14,
which holds 73% of the buffers, the row is -7,55% against -6,78% (`ESCAPE`) and -6,85% (`VARINT_SPLIT`). **Verdict.** Keep `ESCAPE` + `INTERLEAVED_PLANES`; discard the other three layouts.

## S8 backend

The last stage is not code: the tile is delivered under gzip, Brotli or zstd. This step runs the S7
winner (`GEOMETRY_BEST`: Hilbert-centre order with ring rotation and sorted MultiPoints
(`HILBERT_CENTROID_ROT_MP`), `QUAD_CORNER_LINE`, class streams,
zigzag, `ESCAPE`, interleaved planes) and every single decision around it under all three
backends (Brotli at quality 11 here, the dev runs of S1 to S7 used 9), against the old pipeline's
best rows (`--with-old`), and puts the geometry-only figure beside the property side. Log
`research-java/build/pipeline-mp-s8.txt` (`--step s8 --stats --brotli-quality 11 --by-zoom --with-old
--isolated`). Percentages are against `DELTA_ZIGZAG_VARINT` under gzip (11.237.124), also for the
Brotli and zstd columns.

| row | gzip | brotli11 | zstd19 |
|---|---|---|---|
| DELTA_ZIGZAG_FASTPFOR (what MLT writes) | 11.984.744 (+6,65%) | 11.828.580 (+5,26%) | 11.949.476 (+6,34%) |
| DELTA_ZIGZAG_VARINT (ref) | 11.237.124 | 10.734.783 (-4,47%) | 11.201.331 (-0,32%) |
| **pipeline, GEOMETRY_BEST** (`HILBERT_CENTROID_ROT_MP`) | **10.498.081 (-6,58%)** | **9.920.672 (-11,72%)** | **10.338.936 (-7,99%)** |
| same with `QUAD_CORNER` | 10.474.838 (-6,78%) | 9.893.102 (-11,96%) | 10.315.593 (-8,20%) |
| same with `LINE_CONTINUATION` | 10.711.850 (-4,67%) | 10.154.750 (-9,63%) | 10.577.193 (-5,87%) |
| same with `DELTA` | 10.773.521 (-4,13%) | 10.250.207 (-8,78%) | 10.648.721 (-5,24%) |
| same with `FLAT` segmentation | 10.699.691 (-4,78%) | 10.100.006 (-10,12%) | 10.538.688 (-6,22%) |
| same, `VARINT_SPLIT`, no planes | 10.570.027 (-5,94%) | 9.931.241 (-11,62%) | 10.403.120 (-7,42%) |
| same, `ESCAPE`, no planes | 10.566.524 (-5,97%) | 9.958.854 (-11,38%) | 10.444.023 (-7,06%) |
| same, `MIN_FIXED` + planes | 10.707.891 (-4,71%) | 9.981.071 (-11,18%) | 10.388.001 (-7,56%) |
| same, `HILBERT_CENTROID_ROT` (no `_MP`) | 10.505.706 (-6,51%) | 9.924.496 (-11,68%) | 10.345.890 (-7,93%) |
| same, plain Hilbert (no `_ROT`) | 10.551.961 (-6,10%) | 9.952.642 (-11,43%) | 10.388.762 (-7,55%) |
| **same, no reorder** (`DEFAULT`) | 10.829.102 (-3,63%) | 10.166.114 (-9,53%) | 10.648.585 (-5,24%) |
| old `QUAD_CORNER` | 10.860.056 (-3,36%) | 10.111.002 (-10,02%) | 10.697.529 (-4,80%) |
| old `QUAD_CORNER_SHARED_RING_CANONICAL_REORDER` (shared-vertex refs) | 10.412.764 (-7,34%) | 9.715.160 (-13,54%) | 10.251.177 (-8,77%) |
| old `LINE_CONTINUATION_SHARED_DICT` | 10.942.929 (-2,62%) | 10.253.376 (-8,75%) | 10.801.358 (-3,88%) |

**Per-backend best configuration: the same one in every backend.** In all three columns the best
pipeline row is the S7 stack; the only single change that wins anywhere is the predictor `QUAD_CORNER`
(-0,2 points in every backend: the corpus is building-heavy), which loses on lines
(`transportation` -4,14% vs -4,93%, `transportation_name` -2,23% vs -3,67%). No decision flips with
the backend, so no per-backend configuration is needed; the backend only moves the level
(Brotli 11 is 5,2 points under gzip, zstd 19 is 1,4 under). The order of the stages by what they bought,
on `QUAD_CORNER_LINE`: class streams -1,8 (gzip, vs `FLAT`: -6,58% vs -4,78%), planes -0,6, reorder
-2,95, `QUAD_CORNER_LINE` over `DELTA` -2,5, `_ROT` -0,4, `_MP` -0,1. `QUAD_CORNER_LINE` itself is
not the best single predictor on this base: plain `QUAD_CORNER` is 0,2 points smaller in every
backend. It was kept because it is the one predictor within 0,3 points of the best on every layer
(S2): on `transportation` it is -4,93% against `LINE_CONTINUATION`'s -5,23% and `QUAD_CORNER`'s
-4,14%, and the building-heavy corpus hides that in the total.

**Isolated against whole tile** (`--isolated`). Every buffer compressed on its own and summed, against a
tile's buffers concatenated and compressed once: the pipeline's best row is 2,0% (gzip), 2,5% (brotli),
1,8% (zstd) smaller per tile than isolated (10.707.093 isolated gzip vs 10.498.081); the baselines
gain 1,1 to 2,6% the same way. The change against the smaller baseline is 0,5 points better when
isolated (-7,09% vs -6,58% gzip), the same order of rows, so the per-buffer figures of S1 to S7
are a faithful *ranking* of the choices and a slight overstatement of the margin.

**The property side** (S1, `PipelineTileSizeCli`). With the predictors and class streams in place the
reorder is worth much less than it was against plain delta: -331.000 bytes gzip (10.829.102 → 10.498.081
with `_MP`, 3,1% of the geometry) against +736.400 for the permuted property columns (+13,4%, the same for
`_ROT` and `_MP`). Per layer, geometry gain from the by-layer rows of the `_ROT` run (reorder vs. no
reorder; `_MP` moves them by under 10.000 bytes) against the S1 property change
(gzip of single-layer property-only tiles):

| layer | geometry | properties | net |
|---|---|---|---|
| building | -32.000 (-8,11% → -8,96%) | +3.300 | **-29.000** |
| housenumber | -12.900 (+5,21% → +3,71%) | -5.900 | **-19.000** |
| landuse | -9.100 | +12.400 | +3.000 |
| landcover | -29.700 | +56.100 | +26.000 |
| transportation_name | -4.600 | +16.800 | +12.000 |
| water | -1.300 | +8.500 | +7.000 |
| transportation | -54.000 | +136.200 | +82.000 |
| poi | -173.600 | +486.400 | +313.000 |

Reordering every layer is a net **+413.000 bytes (+2,4% of the 17,0 million tile bytes)**; only
`building` and `housenumber` pay (about -48.000, -0,3%). The reorder is therefore dropped from the
end-to-end pipeline: `PipelineRegistry.DEFAULT` is the S7 stack with `Reorder.NONE`;
`GEOMETRY_BEST` keeps the reorder and is the best row on the *geometry buffers alone*.

**Estimate of the end-to-end effect** (additive, not a measurement: the MLT writer cannot take the
pipeline's geometry bytes, so the production tile is corrected by the change of the geometry buffers).
Production tile 18.189.926 gzip with the FastPFOR geometry buffer (11.984.744 per-tile gzip of the
buffers). Replacing it by the no-reorder pipeline (10.829.102) saves 1.155.642: **tile about
17.034.000, -6,4%**, no property cost. The geometry-best pipeline saves 1.486.663 but pays +736.000
for the properties, i.e. about 17.440.000 (-4,1%); gating the reorder to `building` and
`housenumber` gives about 16.986.000 (-6,6%). The additive model assumes the geometry buffers compress
the same inside a tile as next to each other (the isolated/tile gap above is +-2%, about +-0,3 million).

**Against the old pipeline.** The pure-predictor pipeline beats the old pure `QUAD_CORNER` by 3,2
points gzip (-6,58% vs -3,36%; no reorder: -3,63%, level, so the gain over the old row is the
Hilbert order, the plane transpose and the escape width, not the predictor) and the old
`LINE_CONTINUATION_SHARED_DICT` by 4,0 points. It stays 0,8% (gzip) and 2,1% (brotli) behind the
old best row `QUAD_CORNER_SHARED_RING_CANONICAL_REORDER` (10.412.764), whose shared-vertex references
are out of scope here by decision: `transportation` -4,93% vs -9,49% (junction sharing) and `building`
-9,20% vs -10,97% are where they pay. In Brotli the old shared-ring row leads by 2,1%; the pipeline
without the reorder (`DEFAULT`) is 4,6% behind it.

**Verdict.** Backend choice is orthogonal to every stage: one configuration serves gzip, Brotli and zstd.
The recommended end-to-end pipeline is `DEFAULT` (no reorder, `QUAD_CORNER_LINE`, class streams, zigzag,
`ESCAPE`, interleaved planes): -3,63% gzip on the geometry buffers against the smaller baseline
(-6,4% on the whole tile under the additive model against what MLT writes today), and the geometry-only
best, with the reorder, is `GEOMETRY_BEST` (-6,58%). The shared-vertex references of the old pipeline
remain the largest unused gain (0,8 to 2,1%).

**The gap to the old best, and why shared-vertex references stay out.** An estimate from logged
rows, not a measurement of a pipeline with references. The pipeline's `GEOMETRY_BEST` is 205.512
Brotli bytes (1,8 points of the gzip baseline) behind the old best row. What the references were
worth in the old pipeline, with no reorder on either side (`QUAD_CORNER` -> `QUAD_CORNER_SHARED`):
220.167 bytes Brotli (1,96 points; gzip 249.754, 2,22 points), about the size of the gap. The
pipeline's reorder is worth more than the old rotate-and-chain reorder (2,18 points Brotli, `DEFAULT`
-> `GEOMETRY_BEST`, against about 1,6 for `QUAD_CORNER_SHARED` -> `..._RING_CANONICAL_REORDER`). If
the two gains added up, a pipeline with references would reach about -13,7% to -14,4% Brotli
against the old -13,54%. Three things argue against reading that as a result:

- **Overlap.** References and the reorder both use neighbouring features that repeat geometry. The
  old pipeline measured them only together (-9,50% against -7,86% for the references alone), so
  independence is an assumption.
- **Weaker base.** Without the reorder, `DEFAULT` is 0,5% *behind* the old plain `QUAD_CORNER` under
  Brotli (10.166.114 vs 10.111.002), level under gzip.
- **Property cost.** The reorder costs +2,4% on the whole tile (above), and the old best row, which
  also reorders features, pays it as well. References need no reorder: the old
  `QUAD_CORNER_SHARED` (9.890.835 Brotli) is 2,7% under the pipeline's `DEFAULT` and carries no
  property cost, which makes it the fairer reference for the end-to-end question.

Decision: the pipeline stays pure-predictor (S2 scope chosen at the start); the references are
documented as the unused gain and not built here. The pipeline-with-references row would be the
next experiment, on the no-reorder base first.

## The whole pipeline, stage by stage

`./gradlew research --args="measure --step ladder --by-zoom"` (log `research-java/build/pipeline-ladder.txt`)
encodes every buffer with the pipeline built up one decision at a time and prints the sizes. It does
**not** round-trip the buffers: every stage is a row of some step, which `PipelineEncodingTest` verifies
over `PipelineRegistry.all()`, and `LadderTest` checks that each stage is such a row and that no two
stages share a name (the benchmark totals by name). Per tile, geometry buffers only, 4.368 buffers in
358 tiles; "vs ref" is against the smaller baseline under gzip (11.237.124), "vs prev" against the stage before.

| stage | gzip | vs ref | vs prev | brotli11 | vs ref | zstd19 | vs ref |
|---|---|---|---|---|---|---|---|
| identity (plain varint) | 11.243.566 | +0,06% | - | 10.734.320 | -4,47% | 11.207.288 | -0,27% |
| S0 split varint | 11.146.031 | -0,81% | -0,87% | 10.573.743 | -5,90% | 11.013.695 | -1,99% |
| S1 + Hilbert reorder (`_MP`) | 10.781.955 | -4,05% | -3,27% | 10.269.388 | -8,61% | 10.642.052 | -5,30% |
| S2 + `QUAD_CORNER_LINE` | 10.705.301 | -4,73% | -0,71% | 10.151.034 | -9,67% | 10.535.419 | -6,24% |
| S3 + class streams | 10.570.027 | -5,94% | -1,26% | 9.931.241 | -11,62% | 10.403.120 | -7,42% |
| S6 + escape width | 10.566.524 | -5,97% | -0,03% | 9.958.854 | -11,38% | 10.444.023 | -7,06% |
| S7 + byte planes (`GEOMETRY_BEST`) | 10.498.081 | -6,58% | -0,65% | 9.920.672 | -11,72% | 10.338.936 | -7,99% |
| S8 reorder dropped (`DEFAULT`) | 10.829.102 | -3,63% | +3,15% | 10.166.114 | -9,53% | 10.648.585 | -5,24% |

S4 (zigzag) and S5 (no GCD) change nothing and have no stage. Reading it: the reorder is the largest single
stage (-3,3 points gzip, -2,9 Brotli), then the class streams (-1,3 / -2,2); the escape width alone is
level (it is the terminal the byte planes need, which then give -0,65 / -0,38). The last row is the
end-to-end choice: the reorder is taken out again because it costs more in the property columns than it
saves in the geometry buffers.

## Oracle: which combination compresses best

`./gradlew research --args="measure --step oracle --by-zoom"` (log `research-java/build/pipeline-oracle.txt`, 4.368
buffers, 358 tiles, Brotli 11, 14 minutes, not round-tripped here: the 48 rows are in
`PipelineRegistry.all()` and so in the round-trip tests). The candidates are the full grid of the decisions
a buffer's content can change: reorder (none or `HILBERT_CENTROID_ROT_MP`) x predictor (`QUAD_CORNER_LINE`,
`QUAD_CORNER`, `LINE_CONTINUATION`, `DELTA`) x class streams on or off x byte terminal (escape with planes,
escape, split varint). Per buffer, per backend, the oracle takes the smallest payload compressed alone and
is charged nothing for the choice.

| | gzip | brotli11 | zstd19 |
|---|---|---|---|
| ref `DELTA_ZIGZAG_VARINT` | 11.237.124 | 10.734.783 (-4,47%) | 11.201.331 (-0,32%) |
| best single composition | 10.474.838 (-6,78%) | 9.893.102 (-11,96%) | 10.315.593 (-8,20%) |
| oracle, per tile | 10.500.466 (-6,56%) | 9.946.880 (-11,48%) | 10.341.918 (-7,97%) |
| oracle over the best single, per tile | **+0,24%** | **+0,54%** | **+0,26%** |
| oracle, buffers compressed alone | 10.665.982 | 10.078.075 | 10.484.294 |
| best single, buffers compressed alone | 10.736.868 | 10.196.753 | 10.556.793 |
| oracle over the best single, alone | -0,66% | -1,16% | -0,69% |

The best single composition is the same under all three backends: Hilbert `_MP` reorder, plain
`QUAD_CORNER`, class streams, escape width with byte planes (-6,78% / -11,96% / -8,20%), which is
`GEOMETRY_BEST` with the predictor `QUAD_CORNER` instead of `QUAD_CORNER_LINE`.

**What it chose.** Many compositions, none dominant: 44 / 47 / 46 of the 48 are chosen on at least one buffer
(gzip / brotli / zstd). The biggest single share is 19% of the buffers (`QUAD_CORNER_LINE`, flat, split
varint, no reorder: small buffers, 2,6% of the bytes); by bytes the reorder + `QUAD_CORNER_LINE` + class +
planes row leads with 32%. By decision, share of buffers (gzip): reorder 60% / none 40%; predictor
`QUAD_CORNER_LINE` 74%, `LINE_CONTINUATION` 17%, `QUAD_CORNER` 8%, `DELTA` 1%; flat 52% / class 48%; split
varint 49%, planes 40%, escape 12% (Brotli takes the split varint on 65%).

**The choice follows the layer** (the `--oracle` report prints it per backend and layer; gzip below, share of
the layer's buffers). The aggregate shares above hide it: `transportation` takes `LINE_CONTINUATION` on 91%
of its buffers, `building` `QUAD_CORNER_LINE` on 95% with class streams on 96% and the reorder on 92%,
`boundary` splits `QUAD_CORNER` 46% / `QUAD_CORNER_LINE` 46%, `waterway` 48% / 45% between
`QUAD_CORNER_LINE` and `LINE_CONTINUATION`, `transportation_name` 50% / 46%, while `place`, `park`,
`water_name` and `aerodrome_label` take `QUAD_CORNER_LINE` on 98 to 100% of theirs with no reorder on most
(`park` 99% none, `aerodrome_label` 100% none). The reorder share by layer is 97% on `transportation` and
`landcover`, 92% on `building`, 51% on `transportation_name`, 1% on `park`. This is the S2 result again (the
predictor follows the geometry), now over combinations.

**Verdict: the combination does not beat the best single composition.** Choosing per buffer saves 0,7 to
1,2% when each buffer is compressed alone and *costs* 0,2 to 0,5% on the delivered tiles, the same effect
as the selection loop below: a tile compresses the concatenation of its buffers, and buffers written in
different formats share fewer matches, so the alone-measure over-selects. By zoom the oracle (gzip) is
-7,51% at z14 and -6,51% at z12, level with `GEOMETRY_BEST` (-7,55%, -6,47%) at the zooms that carry the
bytes. The practical result is one fixed composition, the same under all three backends, the S8 row with
`QUAD_CORNER` (0,2 points under `QUAD_CORNER_LINE`, S8): plain `QUAD_CORNER` wins on this building-heavy
corpus, `QUAD_CORNER_LINE` was kept for the lines. A choice made on the tile (one per tile and layer,
trialled on the concatenation) is what could still pay and was not built.

## Per-layer default

The oracle's choice follows the layer (above), so the pipeline's default is a fixed composition per
layer, `PipelineRegistry.forLayer(layer)`, with `DEFAULT` (the single S8 row) as the fallback for an
unknown layer. The table is the per-layer winner under gzip from `--step oracle` (summed isolated
payloads). The reorder was first allowed only on `building` and `housenumber`, the two layers where the
property side pays for it (see S1); it is now `HILBERT_CENTROID_ROT_MP` on every fitted layer, on top of
that fit. Nothing is signalled: the decoder knows the layer and takes the same row.
Every composition in the table is a row of the oracle grid, so `PipelineEncodingTest` round-trips it,
and `LayerDefaultTest` checks that, that every fitted layer reorders, and the fallback. The sizes below
are the gated fit, before the reorder was opened to every layer.
`pipeline --step layer --by-zoom` sizes it against `DEFAULT`.

**`building`, `landcover`, `landuse` and `aeroway` now use the ring frame** (`QUAD_CORNER_FRAME`, `landuse`
`QUAD_CORNER_LINE_FRAME`; [orientation-prior.md](orientation-prior.md)), entries that are not rows of the
oracle grid: `TileSelector` appends the layer defaults the grid lacks (52 candidates) so a tile can still
start from its default. The three added layers were picked with `--step framelayers`, each layer's own
terminal, framed against plain, the rule being a win under gzip, Brotli and zstd (per tile, change vs
`DELTA_ZIGZAG_VARINT`, plain → framed): `landcover` -4,70% → -5,15% / -7,38% → -7,92% / -5,64% → -6,21%,
`landuse` -4,70% → -5,72% / -8,41% → -9,63% / -6,16% → -7,29%, `aeroway` (58 buffers, 22 kB)
-2,14% → -2,21% / -16,47% → -17,84% / -10,96% → -11,13%. Not switched: `water` (-2,41% → -2,24% gzip, the
reference metric, Brotli +0,07 points), `boundary` and `park` (ties within 0,02 points). With the three
added layers the whole-corpus `PER LAYER` row is 10.399.717 gzip (-7,45%), 9.702.273 Brotli (-13,66%),
10.192.769 zstd (-9,29%), against -7,36% / -13,55% / -9,16% with `building` alone: the layers are small
(together about 2,2 MB of the 11,2 MB `DELTA_ZIGZAG_VARINT` gzip reference). Decode on those layers is cheaper than on `building`: `landcover`
2,41x FastPFOR (plain 0,56x), `landuse` 1,55x (plain 0,46x), as they carry fewer ring vertices.
The `building` numbers follow. With it, `--step layer --by-zoom` (4.368 buffers,
358 tiles, reorder on every layer, sizes only) gives `PER LAYER` 10.410.201 bytes gzip (-7,36%), 9.713.986
Brotli (-13,55%), 10.207.389 zstd (-9,16%) against `DEFAULT` -3,63% / -9,52% / -5,24%; `building` -12,69%
gzip against -8,11% for `DEFAULT`. The frame is worth about 131.800 bytes of the gzip total (derived from
the `building` rows of `--step frame`: 3.433.158 with `QUAD_CORNER`, 3.301.330 with the frame), i.e. the
default would be -6,19% gzip without it. Decode of the `building` buffers
(`decodeTime --layer building`, 310 buffers, end to end): 490 ms against 127 ms for the previous
`building` row and 84 ms for FastPFOR, so 5,86x FastPFOR (the previous row 1,52x), 112 ns/value above 5000
vertices against 29 ns. That is outside the 1,5x budget of `decodeTime`, which does not apply to the
general-purpose-compression target ([general-purpose-compression.md](general-purpose-compression.md)).

| Row (geometry buffers, per tile) | gzip | Brotli 11 | zstd 19 |
|---|---|---|---|
| `DEFAULT` (one row for all layers) | 10.829.102 (-3,63%) | 10.166.114 (-9,53%) | 10.648.585 (-5,24%) |
| `PER LAYER` | 10.811.249 (-3,79%) | 10.148.523 (-9,69%) | 10.641.871 (-5,30%) |

Against `DEFAULT`, per layer gzip (isolated): `building` -9,20% against -8,11%, `transportation_name`
-3,39% against -2,69%, `transportation` -3,28% against -3,00%, `housenumber` +3,75% against +5,21%,
`poi` +3,28% against +4,96%, the other layers level. By zoom whole tiles are ahead at most zooms from z6
up (z6 -0,71% against -0,02%, z13 -2,44% against -2,09%, z14 -4,35% against -4,21%), a little behind at
z1 and z12, and mixed below.

**Verdict: keep as the default, the gain is small.** It is 0,16 points under gzip and 0,06 to 0,16 under
Brotli and zstd on the gated fit above. It stays 2,8 points (gzip) short of `GEOMETRY_BEST` on the
geometry buffers. Reordering every layer costs the property side +2,48% of the whole tile, which is why
the fit first left it off except `building` and `housenumber`; the registry now applies
`HILBERT_CENTROID_ROT_MP` on every fitted layer anyway. The whole-tile size of the per-layer default is **not measured**:
`pipelineTileSize` applies one reorder to all layers, so the figure needs a per-layer mode.
`poi` and `housenumber` stay worse than the plain varint baseline under gzip (+3,28%, +3,75%), the
point layers the pipeline does not beat.

## Selection loop

`Selector` (`--step selector`, log `research-java/build/pipeline-selector.txt`, 4.368 buffers in 358
tiles, every buffer round-tripped, 41 minutes at Brotli quality 11). Per buffer it encodes 24
candidates (predictor x class streams on/off x {escape + planes, escape, split varint}), compresses
each alone with the target backend and keeps the smallest; the choice is the two header bytes, so
signalling costs nothing extra. One selector per backend, each with and without the reorder.
`DEFAULT` (no reorder) and `GEOMETRY_BEST` (reorder) are the fixed rows it is held against. Mechanism
tests (`SelectorTest`): round trip, never larger than `DEFAULT` under its own compressor, a smooth
curve does not take the plain delta predictor.

| row | gzip | brotli11 | zstd19 |
|---|---|---|---|
| `DEFAULT`, per tile | 10.829.102 (-3,63%) | 10.166.114 (-9,53%) | 10.648.585 (-5,24%) |
| `SELECT_<backend>`, per tile | 10.841.698 (-3,52%) | 10.191.813 (-9,30%) | 10.647.858 (-5,24%) |
| `DEFAULT`, buffers isolated | 11.037.843 | 10.398.651 | 10.844.361 |
| `SELECT_<backend>`, buffers isolated | 10.992.538 (-0,41%) | 10.331.452 (-0,65%) | 10.809.231 (-0,32%) |
| `GEOMETRY_BEST` with plain `_ROT`, per tile | 10.505.706 (-6,51%) | 9.924.496 (-11,68%) | 10.345.890 (-7,93%) |
| `SELECT_HILBERT_CENTROID_ROT_<backend>`, per tile | 10.509.111 (-6,48%) | 9.953.064 (-11,43%) | 10.351.565 (-7,88%) |

**What it picks** (share of buffers, own backend; gzip / brotli11 / zstd19): `QUAD_CORNER_LINE` 74 / 69 / 74%,
`LINE_CONTINUATION` 17 / 17 / 16%, `QUAD_CORNER` 8 / 10 / 8%, `DELTA` 1 / 4 / 1%; class streams 52 / 46 / 50%;
byte planes 41 / 32 / 43%, escape without planes 14 / 13 / 11%, split varint 45 / 55 / 47%. By layer
the predictor follows the geometry, as S2 found: `transportation` takes `LINE_CONTINUATION` on 92% of
its buffers under gzip, `building` `QUAD_CORNER_LINE` on 97% (and class streams on 97%), `boundary`
splits between `QUAD_CORNER` (46%) and `QUAD_CORNER_LINE` (45%); the planes go to the layers of many
tiny buffers (`place` 83%, `housenumber` 80%), the split varint to `poi`, `mountain_peak` and
`aerodrome_label` (87 to 100%). Brotli takes the split varint more often than gzip and zstd (55% against
45% and 47%). z14 holds 73% of the buffers and sets the totals; the low zooms have under 20 buffers
each and no pattern worth reading. No one configuration is best on every buffer.

**The selection is not kept.** Per buffer it saves: isolated, 0,3 to 0,65% against the fixed default,
and per layer `transportation` -3,51% (against -3,00%), `transportation_name` -3,97% (-2,69%) and
`poi` +3,27% (+4,96%). Per tile, the figure that is delivered, it is *larger*: +12.600 bytes
(+0,12%) gzip, +25.700 (+0,25%) Brotli, -700 (level) zstd; with the reorder +3.400 (+0,03%), +28.600
(+0,29%), +5.700 (+0,05%). The isolated-to-tile gap shows where it goes: the fixed `DEFAULT` gains
1,9% (gzip) when its buffers share a window, the selection only 1,4%, since buffers written in
different formats share fewer matches. Trial compression of a buffer on its own is therefore a proxy
that over-selects: it rewards each buffer for what it does alone, and a tile compresses the
*concatenation*. The by-zoom gzip table shows where it does help: z4 to z6 tiles (7 or 8 buffers
each), by 0,7 to 1,2 points, and it loses at z14 (-4,03% against -4,21%), which carries most of the
bytes. Under the additive tile model of S8 the selector tile is about 17.046.000 (-6,3%) against
17.034.000 (-6,4%) for the fixed default: no gain, and 24 encodes and compressions per buffer.

**Verdict.** Discard. The gain a per-buffer search finds is real in isolation and absent in the
delivered tile. A selection that could pay would have to choose on the tile, one choice per
(tile, layer) trialled on the concatenated tile, so the formats inside a tile stay uniform; it was
not built, because the isolated gain it would be chasing is 0,3 to 0,65%. `PipelineRegistry.DEFAULT` stays
the recommended pipeline.

## Summary

| step | decision |
|---|---|
| S0 | split varint terminal as the harness anchor (-0,87% gzip against plain varint) |
| S1 | Hilbert-centre reorder with ring rotation: wins on the geometry buffers, costs more in the property columns end to end, dropped in S8 |
| S2 | `QUAD_CORNER_LINE`, the one predictor within 0,3 points of the best on every layer |
| S3 | class streams; role split and class sort discarded |
| S4 | zigzag; bias map discarded |
| S5 | no GCD (it fires on 0,021% of the values) |
| S6 | escape width; minimal and forced widths discarded |
| S7 | interleaved byte planes; x/y-separating transposes discarded |
| S8 | `DEFAULT` = no reorder: -3,63% gzip on the geometry buffers against the smaller baseline (-6,58% with the reorder); one configuration for gzip, Brotli and zstd |
| selection | per-buffer trial compression discarded: isolated -0,3 to -0,65%, per tile +0,0 to +0,3% |

The shared-vertex references of the old pipeline are out of this pipeline's scope and remain the
largest unused gain (0,8% gzip, 2,1% Brotli over `GEOMETRY_BEST`, S8).
