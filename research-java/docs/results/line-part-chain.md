# Chaining the parts of a MultiLineString — `_PART_CHAIN`

> **Consolidation note.** The Gradle task(s) benchmark named below were removed from the build (the code is at git tag `pre-consolidation`); `research measure` and the other `research` commands replace the rest. The numbers below are unchanged history.

**Status:** workshop rows only (`Workshop.partChainRows()`), not in `Baselines.research()`
**Effect:** `transportation`, per-tile gzip vs `DELTA_ZIGZAG_VARINT`:
`LINE_CONTINUATION_PART_CHAIN_REV` -4,85% (`LINE_CONTINUATION` alone -1,37%),
`DELTA_PART_CHAIN_REV` -3,49%; without reversal -3,24% / -1,89%. The joins the idea is built on
never occur: the gain is proximity ordering, not longer continuation runs.
**Corpus:** current baseline (`munich-plantiler.mbtiles`, `data/extractedBaseline`), `transportation`,
354 buffers, 1.180.723 vertices

## The idea

The parts of a MultiLineString are stored in whatever order the tiler produced. If parts that
meet at a shared vertex were emitted end to start, each joined part start would cost a `(0, 0)`
delta, and a continuation predictor could carry the previous part's last edge through the join,
extending `LINE_CONTINUATION`'s delta-of-delta run across part boundaries. Part order inside a
MultiLineString carries no rendering meaning, and `GeometryEquivalence` already compares parts as a
multiset, so nothing is transmitted beyond the permuted part lengths.

## What it does

`GeometryReorder.PART_CHAIN` / `PART_CHAIN_REV` (`encoding/research/GeometryReorder.java`) keep
feature order and touch only MultiLineStrings with two or more parts; only the run-length stream
(`numParts`, or `numRings` in a polygon column) is permuted, within each feature. Per feature:

1. **Join.** From the current exit, take an unused part that enters at exactly that position
   (with `_REV` also one whose last vertex is there, reversed). Among several, the one whose
   second vertex is nearest the previous edge continued, the residual a continuation predictor
   pays across the join, so a junction goes straight on.
2. **Restart.** With no join, take the unused entry nearest the exit (Manhattan), preferring
   entries a chain should start from: odd remaining endpoint degree with `_REV`, more remaining
   parts leaving than arriving without it. The first part of a feature restarts from the previous
   feature's last vertex.

`CHAIN_CONTINUATION` (`Workshop.chainContinuation()`) is `LINE_CONTINUATION` plus: a line part whose
start repeats the previous vertex is a continuation, so its second vertex is predicted as
`2 p[start] - p[start - 2]` (skipping the duplicate) and its later vertices by continuation whatever
the part's length. The join is read off the decoded prefix, nothing is signalled.

Rows: `DELTA`, `LINE_CONTINUATION`, `CHAIN_CONTINUATION`, each over `_PART_CHAIN` and
`_PART_CHAIN_REV`, interleaved over Varint. Mechanism tests in `GeometryReorderMechanismTest`
(chaining order, reversal only when allowed, straight-on at a junction, parts stay in their own
feature, polygons in a mixed column untouched) and `WorkshopTest.chainContinuationPredictsAcrossAJoin`;
`VertexBufferCodecTest` round-trips every row.

## Measured

`./gradlew research --args="workshop --layer transportation --by-zoom"`, per-tile gzip vs the smaller
baseline (`DELTA_ZIGZAG_VARINT`):

| row | all tiles | z4–z11 range | z12 | z13 | z14 |
|---|---|---|---|---|---|
| `LINE_CONTINUATION` (unordered) | -1,37% | +2,33% to -3,16% | -0,84% | -0,97% | -1,45% |
| `CHAIN_CONTINUATION` (unordered) | -1,36% | +2,33% to -3,17% | -1,03% | -1,00% | -1,40% |
| `DELTA_PART_CHAIN` | -1,89% | -1,17% to -3,46% | -1,94% | -1,54% | -1,88% |
| `LINE_CONTINUATION_PART_CHAIN` | -3,24% | +0,79% to -5,94% | -2,76% | -2,48% | -3,30% |
| `DELTA_PART_CHAIN_REV` | -3,49% | -3,29% to -5,21% | -3,61% | -2,88% | -3,52% |
| `LINE_CONTINUATION_PART_CHAIN_REV` | **-4,85%** | -1,59% to -8,07% | -4,45% | -3,83% | **-4,96%** |
| `CHAIN_CONTINUATION_PART_CHAIN_REV` | **-4,86%** | -1,54% to -8,16% | -4,68% | -3,88% | -4,92% |
| `REORDER_CHAIN` (feature chaining, reference) | -3,64% | +0,04% to -0,54% | **-10,45%** | **-6,18%** | -2,58% |

Varint bytes move the same way (`LINE_CONTINUATION_PART_CHAIN_REV` -3,69%). Mean absolute residual
drops from 158/160 (`LINE_CONTINUATION`) to 132/129 tile units. z4–z9 are one buffer each.

## Why: there are no joins

Counted directly over the same buffers with a throwaway probe (not kept):

| | value |
|---|---|
| MultiLineString features with 2+ parts | 15.904 |
| their parts | 236.624 |
| part boundaries inside them | 220.720 |
| boundaries where the part starts on the previous part's end, stored order | **0** |
| the same after `PART_CHAIN` / `PART_CHAIN_REV` | **0** / **0** |
| distinct parts of one feature sharing an endpoint at all | **0** (1.249 apparent cases are closed loops, one part's own first and last vertex) |
| mean part-start jump inside a feature, stored / `PART_CHAIN` / `_REV` | 650 / 514 / 438 tile units |

The `(0, 0)` share in the workshop tables agrees: 0,3% for `DELTA` and for every `_PART_CHAIN`
row. That is consistent with planetiler's line merge joining every pair of parts that meet end to
end (not checked in its source): where a feature's roads do meet (38% of transportation vertices
are shared, [line-shared-vertices.md](line-shared-vertices.md)) a part ends on another part's
interior or on another feature. So `CHAIN_CONTINUATION` has nothing to act on inside a feature. On
the whole layer it is within 0,01 points of `LINE_CONTINUATION` on every ordering; per zoom it gains
up to 0,4 points over `LINE_CONTINUATION_PART_CHAIN_REV` on z6–z11 (a few buffers each). That can
only come from joins between consecutive *features*. Starting a feature at the entry nearest the
previous feature's exit can create such a join.

What does pay is the restart rule alone: greedy nearest-endpoint ordering of the parts inside each
feature, the within-feature counterpart of feature chaining ([reordering.md](reordering.md)).
It shortens the part-start jump by a third with reversal, and part starts are frequent (the
MultiLineString parts alone are one per five vertices). It composes with
`LINE_CONTINUATION` (the gains add up: -1,37% and -3,49% to -4,85%), because one shrinks the part
starts and the other the interiors.

`REORDER_CHAIN` and `_PART_CHAIN` address different boundaries. Feature chaining wins on z12/z13,
where features are many and short; part chaining on z14 (-4,96% against -2,58%) and on every
z4–z11 tile, where most vertex mass sits in large merged MultiLineStrings whose internal order
feature chaining keeps.

## Caveats

- **Direction.** `_REV` reverses parts, with `FEATURE_REV`'s caveat: one-way and
  direction-dependent styling lose their meaning. Reversal is worth about 1,6 points
  (`LINE_CONTINUATION_PART_CHAIN` -3,24% vs `_REV` -4,85%).
- **Interleaved Varint workshop only.** No segmentation, no split-varint layout, no comparison
  against the registered `transportation` winner (`LINE_CONTINUATION_SHARED_DICT`). Promote before
  citing as a result.
- **One corpus, one tiler.** Other tilers may leave touching parts unmerged, and there the join
  path (`CHAIN_CONTINUATION`) would get something to act on.

## Not measured / open

- `PART_CHAIN_REV` then `FEATURE_REV` (as `RING_CANONICAL_THEN_FEATURE` composes ring rotation and
  chaining): the two act on different boundaries, so they should mostly add up.
- With `LINE_CONTINUATION_SHARED_DICT`: chaining moves part starts next to the previous exit, which
  may compete with the dictionary references that replace those starts.
- Promotion to a self-contained `Encoding` in `Baselines.research()` and `./gradlew benchmark`.
- Part order chosen to put a part's start on an earlier part's interior vertex (where this corpus's
  junctions actually are): a T-junction cannot be a `(0, 0)` delta, but it could be a reference.
