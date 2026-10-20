# Reordering geometries

**Status:** registered pre-pass, `--reorder feature-rev`
**Effect:** -11,2% NY / -11,8% Munich
**Corpus:** Munich (previous, `munich.mbtiles`) and NY — see banner below

> **Measured on the previous corpus** — the Munich extract of OpenMapTiles v3.11
> (`munich.mbtiles`, planet time 2020-02-10), 8.366 buffers, which also carried ~1.355
> low-zoom **world mask** tiles (that tileset set `maskLevel=5`, so z0-z5 held the whole
> world, not Munich) and took 63% of its vertex mass from them. The corpus is now the
> Munich AOI cut from `germany.mbtiles` (planetiler 0.9.1, 2025-07-08): 358 tiles, 4.272
> buffers, AOI-only at every zoom. Different vendor, 5 years newer, no world mask tiles —
> so **percentages here are not comparable to a current run** and should be re-measured
> before being cited.

Not an encoding but a **pre-pass on the buffer**, and currently the largest single effect
measured here. It reorders whole features so each one starts near where the previous ended,
shrinking the part-start residual that a continuous delta cursor pays at every boundary.

```
                                   plain baseline   reorder   reorder+rev
DELTA_ZIGZAG_FASTPFOR                  13.399.028  12.036.928  11.821.207  -11,8%
SEGMENT_PAIR_FASTPFOR                  12.795.818  11.807.430  11.497.757  -14,2%
SEGMENT_QUAD_CORNER_FASTPFOR           12.579.088  11.595.318  11.285.948  -15,8%
```

| segment | baseline | reorder | +reversal |
|---|---|---|---|
| `transportation` | 4.232.495 | -15,9% | **-17,6%** |
| `boundary` | 1.169.821 | -19,0% | **-22,3%** |
| `building` | 3.228.047 | -11,1% | **-13,5%** |
| `landuse` | 492.037 | -4,4% | -5,2% |
| `landcover` | 1.194.048 | -3,1% | -3,9% |
| `water` | 916.048 | -0,6% | -0,7% |
| LINESTRING | 5.992.732 | -15,7% | **-17,7%** |
| POLYGON | 5.713.018 | -7,4% | -9,0% |
| POINT / `housenumber` / `poi` | — | byte-identical | byte-identical |

**Confirmed on the NY corpus.** 35.641 buffers, identical row sets across all three modes:

```
                                       none      feature   feature-rev
DELTA_ZIGZAG_FASTPFOR            58.403.996   52.910.127   51.845.247   -11,2%
SEGMENT_PAIR_FASTPFOR            55.686.995   51.844.248   50.957.202   -12,8%
SEGMENT_QUAD_CORNER_FASTPFOR     54.695.817   50.841.123   49.960.832   -14,5%
```

| NY segment | baseline | feature-rev |
|---|---|---|
| `boundary` | 1.471.037 | **-18,6%** |
| `transportation` | 16.536.440 | **-15,9%** |
| `building` | 23.160.287 | -13,5% |
| `transportation_name` | 4.011.771 | -9,3% |
| `landuse` | 1.603.241 | -3,7% |
| `landcover` | 3.713.513 | -2,1% |
| `water` | 2.062.937 | -0,8% |
| `housenumber` (point-only) | 3.513.170 | byte-identical |
| LINESTRING | 22.459.493 | **-14,6%** |
| POLYGON | 30.368.015 | -10,8% |
| POINT | 5.253.329 | byte-identical |
| **z14 alone** (73,4% of mass) | 41.649.791 | **-10,4%** |

**Why it is free.** The earlier junction work rejected reordering because an explicit
permutation costs ceil(log2 P)+1 bits per part — "+32,4% of baseline ... for a net +22,2%"
(see [junction back-references](failed/junction-backref.md)). **That cost does not exist.**
The order of geometries in the buffer carries no information that has to be reconstructed:
no coordinate changes, only a geometry's position *within* the buffer, and the spec says so
directly (`maplibre-tile-spec/docs/specification.md`):

> **Feature order is not necessarily a stable round-trip property.** [...] MLT encoders may
> preserve order, but they may also **sort features by id or spatial locality to improve
> compression** when that optimization is enabled.

So nothing is signalled and no decoder changes — a decoder emits the order it reads. This is the
"reordering can only pay if the permutation is *derived* rather than stored" constraint being
satisfied in the strongest way available: the permutation is not derived either, it is simply
not needed.

**What it does.** `GeometryReorder` greedily chains features: repeatedly take the unused feature
whose entry endpoint is nearest the current cursor (Manhattan distance — that is what the two
componentwise delta cursors actually pay), emit it, advance the cursor to its exit endpoint.
`feature-rev` additionally emits a feature backwards when its far endpoint is nearer.

Two constraints it respects: a multi-part feature's parts stay contiguous and in order (splitting
them changes the feature), and a buffer whose runs are not derivable from the topology streams is
returned untouched — which is why every point-only column above is byte-identical rather than
merely unchanged in aggregate.

**It is not additive with `SEGMENT_PAIR`, but still worth combining.** Both levers attack the same
part-start population — one shrinks those residuals, the other moves them into their own FastPFOR
region:

| | corpus |
|---|---|
| `SEGMENT_PAIR` alone | -4,50% |
| reorder alone | -11,78% |
| both | -14,19% |
| *(if they were additive)* | *(-16,28%)* |

The overlap is 2,09pp. Combining still beats either alone, and the best measured configuration on
this corpus is reorder + `SEGMENT_QUAD_CORNER_FASTPFOR` at **-15,8%**.

**Reversal is the riskier half.** It is free for rendering — a LineString reversed is the same
LineString — but it destroys direction semantics (one-way streets, direction-dependent labelling).
It is a separate mode (`--reorder feature` vs `feature-rev`) so the two are always reported apart;
it is worth roughly 2pp of the total.

**Two caveats that bound this result.**

1. **The property columns are not measured.** It is the *feature table* that is sorted, not the
   vertex buffer alone — attributes must travel with their feature, which is what
   `MltConverter.sortFeaturesAndEncodeGeometryColumn` already does for the existing sorts. A
   spatial sort can make the attribute columns compress *worse* (weaker RLE runs), and the spec
   notes sorting by `id` tested more efficient in their case. **-11,8% on the geometry is not a
   net win on the tile** until that counter-cost is measured. This is the next measurement.
2. **~~This corpus overstates boundary-dominated levers.~~ Measured on both corpora — it does
   not.** The concern was that Munich carries 63% of its vertex mass on z4/z5 overview tiles, where
   part boundaries are a large share of the bits, and that NY (43,7% on z14, with boundary deltas
   only 25,7% of bits against Munich's 37,6%) would therefore be much weaker. It is not:

   | | Munich (8.366 buffers) | NY (35.641 buffers) |
   |---|---|---|
   | corpus | -11,8% | **-11,2%** |
   | z14 only | -10,8% | **-10,4%** (73,4% of NY's mass) |
   | + `SEGMENT_QUAD_CORNER` | -15,8% | **-14,5%** |

   The lever is not the one `SEGMENT_PAIR` depends on. `SEGMENT_PAIR` exploits the *width gap*
   between boundary and interior deltas, which does shrink with zoom; reordering shrinks the
   boundary residuals themselves, and a road network at z14 has just as many features needing to be
   visited in a sensible order as one at z5.

Verified on every buffer: reordering may change the order and nothing else, so
`GeometryEquivalence` checks that the multiset of parts is unchanged — same coordinates, same part
structure, parts compared up to reversal — and the run fails naming the tile and layer otherwise.
Every encoding is still round-tripped exactly against the reordered buffer, so the strict contract
that protects every other result is untouched.
