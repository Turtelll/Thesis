# Road chaining — `LINE_CONTINUATION_ROAD_CHAIN` and the lossy `_ROAD_MERGE`

> **Consolidation note.** The Gradle task(s) benchmark, roadChain named below were removed from the build (the code is at git tag `pre-consolidation`); `research measure` and the other `research` commands replace the rest. The numbers below are unchanged history.

**Status:** registered — win on `transportation`: the smallest row on Chicago, second to
`LINE_CONTINUATION_SHARED_DICT` on Munich overall (and the smallest on z12/z13 there). The lossy merge is a
reported row, never in the oracle.
**Effect** (`transportation`, per-tile gzip vs `DELTA_ZIGZAG_VARINT`):

| corpus | `LINE_CONTINUATION` | `_REORDER` | `_ROAD_ORDER` | `_ROAD_CHAIN` | `_ROAD_MERGE` (lossy) | `_SHARED_DICT` |
|---|---|---|---|---|---|---|
| Munich (`extractedBaseline`) | -3,47% | -7,83% | -8,07% | **-8,38%** | -8,95% | **-9,49%** |
| Chicago (`extracted-chicago`) | -0,94% | -6,40% | -6,75% | **-7,04%** | -7,70% | -5,69% |

**Corpus:** current baseline (`munich-plantiler.mbtiles`, 354 `transportation` buffers) and
`data/extracted-chicago` (319 tiles). All rows round-trip on every buffer.

## The idea

A road that flows in one direction is stored as many features, each a short LineString. A
continuation predictor (`p[i-1] + (p[i-1] - p[i-2])`, [line-continuation.md](line-continuation.md)) needs a run of at
least 6 vertices to apply, and plain delta-of-delta loses ([failed/delta-of-delta.md](failed/delta-of-delta.md)),
so most of these features never get a prediction and each pays a part-start residual. Feature order is free
([reordering.md](reordering.md)): chain the features that meet end to start next to each other, then treat the
chain as one road. Inside a road the junction vertex is a repeat, the second feature's first edge continues the
first's last edge, and the length gate sees the road, not the feature.

## What it does

- **`GeometryReorder.ROAD_CHAIN`** (pre-pass, nothing transmitted). Units are features, forward only: no feature is
  reversed, so direction semantics survive. From the current exit take an unused feature that *starts* exactly
  there (the straightest if several, by the continuation residual of its second vertex); otherwise restart at the
  unused feature nearest the exit, preferring one that no remaining feature arrives at, so a road is entered at its
  beginning. A MultiLineString keeps its part order, entry = first part's first vertex, exit = last part's last
  vertex. Polygon features follow the lines unchanged; a column with points is left alone.
- **`RoadChainEncoding`** (lossless). One bit per line part that has a line part before it: 1 = starts at the previous
  part's last vertex. A joined part's first vertex is not transmitted. A *road* is a maximal run of joined parts;
  `LineContinuation` classifies and predicts over the roads (START, 2nd vertex plain, 3rd+ continuation when the road
  has at least 6 vertices), so the continuation crosses what was a feature boundary. Feature lengths stay in the
  topology streams; the bitmap is the only thing that marks a boundary inside a road. Mode byte, plain fallback when
  the joined payload is not smaller. Controls: `LINE_CONTINUATION_REORDER` (nearest-endpoint feature chaining, no
  joins), `_ROAD_ORDER` (road order, no joins).
- **`RoadMergeEncoding`** (**lossy**). Same chain pass, then every chain of joined features is one feature and every
  road one part, encoded as plain `LineContinuation`; no bitmap. The feature boundaries are gone, on the premise that
  linear referencing restores them. It declares `Encoding.mergesFeatures()` and is verified by `MergeEquivalence`:
  every original line part must be a vertex-aligned slice of a merged part (tiled left to right, no reversal, each
  original part used exactly once), and the polygon rings must be unchanged. That is stronger than "same edges": an
  edge-preserving cross-link at a branch (a-b-c + b-d to a-b-d + b-c) is rejected. `Baselines.lossy()` is in
  `all()` so the round-trip tests cover it; `BenchmarkCli` prints it in its own section and keeps it out of the
  oracle and every winner column.

## Stage 0 probe — `./gradlew roadChain`

`RoadChainCli`, no encoder. Line features only, `transportation`:

| | Munich | Chicago |
|---|---|---|
| line features / line vertices | 77.981 / 1.145.220 | 104.247 / 1.174.937 |
| features that can follow another feature (stored order) | 40.384 (51,8%) | 60.505 (58,0%) |
| junctions: clean (1 end, 1 start) / branching | 29.125 / 8.916 | 45.685 / 11.713 |
| ends landing only on an inner MultiLineString part | 19.732 | 15.090 |
| part joins after `ROAD_CHAIN` | 39.428 | 57.709 |
| roads (parts 298.701 / 348.780) | 259.273 | 291.067 |
| mean features / vertices per road | 1,15 / 4,26 | 1,20 / 3,84 |
| line vertices in roads of 2+ features (**gate: >= 5%**) | **10,61%** | **11,78%** |
| continuation-eligible vertices, gate 6: per part today / per road | 34,83% / 39,69% | 28,09% / 33,83% |
| gate 4: per part / per road | 43,83% / 47,88% | 36,60% / 42,03% |
| the vertex after a join: mean zigzag bits, continuation vs delta | 11,06 vs 11,63 (52,8% smaller) | 10,05 vs 10,09 (47,1%) |
| topology entries of line features after fusing (features + `numGeometries` + run lengths) | 79,8% | 75,0% |

- Most roads stay a single feature (mean 1,15 features per road). The gain concentrates at z12 and z13, where
  features are many and short: Munich z12 has 13.708 features that fuse to 3.685, with 21,6% of the vertices in
  multi-feature roads (z14: 8,9%; z4–z8: none, no joins at all).
- The probe shows where the joins are lost: 19.732 / 15.090 feature ends land only on an inner part of a
  MultiLineString, which a feature-level chain cannot use. Chaining at part level (starting a chain from an inner part
  position) is open.
- Branching junctions are 23% of junctions (Munich), so the straightest-continuation tie-break matters; it was not
  ablated.
- Across a join the continuation is only marginally better than plain delta (11,06 vs 11,63 bits Munich, a tie in
  Chicago): roads turn at junctions, the win is not a zero residual at the join.

## Measured

`./gradlew benchmark --args="--layer transportation --by-zoom"`, `--args="../data/extracted-chicago --layer
transportation --by-zoom"`, per-tile gzip, change vs `DELTA_ZIGZAG_VARINT` (Munich 2.795.569, Chicago 2.933.408 bytes):

| row | Munich gzip | Munich | Chicago gzip | Chicago |
|---|---|---|---|---|
| `LINE_CONTINUATION` | 2.698.524 | -3,47% | 2.905.725 | -0,94% |
| `LINE_CONTINUATION_REORDER` | 2.576.563 | -7,83% | 2.745.643 | -6,40% |
| `LINE_CONTINUATION_ROAD_ORDER` | 2.569.993 | -8,07% | 2.735.386 | -6,75% |
| `LINE_CONTINUATION_ROAD_CHAIN` | 2.561.381 | -8,38% | 2.726.825 | -7,04% |
| `LINE_CONTINUATION_ROAD_CHAIN_GATE4` | 2.563.202 | -8,31% | 2.741.445 | -6,54% |
| `LINE_CONTINUATION_SHARED_DICT` | 2.530.310 | **-9,49%** | 2.766.579 | -5,69% |
| `LINE_CONTINUATION_ROAD_MERGE` (lossy) | 2.545.498 | -8,95% | 2.707.549 | -7,70% |
| `LINE_CONTINUATION_ROAD_MERGE_GATE4` (lossy) | 2.547.572 | -8,87% | 2.722.178 | -7,20% |

What each step is worth, Munich / Chicago (percentage points of the baseline):

- **Feature chaining at all** (`_REORDER` vs `LINE_CONTINUATION`): 4,4 / 5,5. Most of the road-chaining gain is the
  order, as in [reordering.md](reordering.md) and [line-part-chain.md](line-part-chain.md).
- **Roads instead of nearest-endpoint order** (`_ROAD_ORDER` vs `_REORDER`): 0,24 / 0,35. Forward-only chaining that
  prefers true joins is at least as good as unconstrained chaining, and keeps direction.
- **The joins** (`_ROAD_CHAIN` vs `_ROAD_ORDER`): 0,31 / 0,29. The junction vertex dropped, the continuation carried
  over, minus the bitmap. Small, and where it is real: z12 and z13.
- **Dropping the feature boundaries** (`_ROAD_MERGE` vs `_ROAD_CHAIN`): 0,62% / 0,71% of the row, vertex buffer only;
  the bitmap is a part of it. What it saves outside the vertex buffer is the topology entries in the probe's last
  row (20-25% fewer for line features), not measured in bytes here.
- **Length gate 4 vs 6:** 6 stays. Gate 4 is 0,07 / 0,50 points worse, as 4–5 vertex runs already were
  ([line-continuation.md](line-continuation.md): +9,7% on Chicago); the road length changes the share of eligible
  vertices (probe above) but not that a short run's continuation is no better than delta.

By zoom (whole tiles, gzip bytes; `_ROAD_CHAIN` vs the winner of the other rows):

| z | Munich `_ROAD_CHAIN` vs ref | Munich winner | Chicago `_ROAD_CHAIN` vs ref |
|---|---|---|---|
| z12 | -18,6% (170.122) | `_ROAD_CHAIN` | -20,0% (250.467) |
| z13 | -11,6% (399.097) | `_ROAD_CHAIN` | -9,2% (634.737) |
| z14 | -6,9% | `_SHARED_DICT` -9,3% | -4,5% (`_SHARED_DICT` -5,5% wins) |

The research oracle on Munich `transportation` picks `_SHARED_DICT` for 322 of 354 buffers and `_ROAD_CHAIN` for 32
(11,7% of the uncompressed bytes) and is -10,35% against the Varint baseline under gzip (the best single row,
`_SHARED_DICT`, -9,49%); on Chicago the two split 233 / 68 buffers (27,4% of the bytes `_ROAD_CHAIN`). Each wins
somewhere.

## Why `_SHARED_DICT` still wins on Munich, and does not on Chicago

Both attack the same junction vertices: a part start that equals an earlier part's position. `_SHARED_DICT`
references any earlier position (T-junctions and ends, reversing a part whose end alone is shared); road chaining
only the previous feature's end with the next feature's start, forward. The pattern is consistent with
`_SHARED_DICT` being the stronger junction coder on a network with many T-junctions (Munich z14, 252 of 354 buffers and
71% of the gzip mass) and road chaining the stronger one at z12/z13 and on Chicago's grid, where the feature order is
the cost. That explanation was not isolated. The two do not run together as built; combining them (chain, then
reference the road starts and ends that remain) is the obvious next row and is open.

## Caveats

- **Lossless row, lossy row.** `_ROAD_CHAIN` keeps every feature and order is the only change; `_ROAD_MERGE` loses the
  feature boundaries (and with them the features' own attributes unless linear referencing restores them) and so
  must not be compared in the same table as a winner.
- **Feature order is the pre-pass's gain, not free of the property columns.** As in [reordering.md](reordering.md),
  these rows are geometry-only: attributes travel with their feature and weaker RLE runs in the property columns
  are not measured. `_ROAD_ORDER` and `_ROAD_CHAIN` reorder every feature; the end-to-end default of the pipeline
  has none for that reason ([openzl-pipeline.md](openzl-pipeline.md)).
- **The decoder recomputes the order.** In a tile the reordered `geometryTypes`/`numGeometries`/`numParts` are read.
  Here `GeometryReorder.topology` and `RoadMergeEncoding.decode` recompute them from the original buffer, which the
  existing reordering rows do too; so decode time of these rows is not measured.
- **Direction.** No feature is reversed, so one-way semantics are kept; the cost is the joins that only a reversal
  would create (joins in reverse were not measured).
- **Tiler.** No two parts of one MultiLineString share an endpoint ([line-part-chain.md](line-part-chain.md)), consistent
  with the tiler merging lines that meet end to end inside a feature (not checked in its source); a tiler that leaves
  them unmerged would give road chaining more joins. The joins measured here are between features.
- **Not measured:** Tokyo and the Overture corpora; the end-to-end tile size; decode time; the straight-on tie-break
  versus nearest only; part-level chaining through inner MultiLineString parts (19.732 / 15.090 lost ends);
  combination with `_SHARED_DICT`.

## Mechanism tests

`RoadChainMechanismTest`: scrambled features chained end to start; a feature that only ends at the exit is neither
joined nor reversed; the straighter branch is taken; a MultiLineString joins only at its outer ends and keeps its
part order; polygons follow the lines, points fall back; a straight road has zero continuation residuals across its
joins, reduces to 10 vertices and expands back; the joined payload is chosen and round-trips; plain fallback; a
polygon column round-trips; the merged road is one LineString and passes `MergeEquivalence`; a merge that keeps the
junction vertices or drops an edge, and a cross-link at a branch, are rejected.
