# Shared rANS frequency tables across buffers — the sparse table wins, sharing barely adds to it

> **Consolidation note.** The Gradle task(s) benchmark named below were removed from the build (the code is at git tag `pre-consolidation`); `research measure` and the other `research` commands replace the rest. The numbers below are unchanged history.

**Status:** sparse table built and measured (`RansCodec.writeTable`/`readTable`);
cross-buffer sharing remains a probe only, `ShareTableCli`
(`./gradlew shareTable --args="../data/extractedBaseline"`), corpus-wide (`munich-buildings-unmerged.mbtiles`,
4.368 buffers, 358 tiles).

**Headline: the confound resolves in favor of sparsity, not cross-buffer similarity.** A
sparse table format alone — no shared state, no addressing, no signalling beyond what a
region already carries — recovers **59,5% of today's dense-table bytes (587.448 of
987.168)**. Sharing a table across buffers recovers more only when the sharing group is
large and homogeneous (same layer, many tiles), and even there the extra it buys over the
sparse control is modest and comes with a real, measured mixture penalty. Per-tile
sharing (all layers in one tile) is barely better than doing nothing, because a tile's
layers have different residual distributions and the model exactly cancels the fixed-cost
gain it removes.

## Why this was probed

`--gzip` compresses `QUAD_CORNER_SEG_RANSSEG` payloads well corpus-wide, and since it runs
**once over the concatenated corpus** (`BenchmarkCli.java:263-270`, deliberately, to expose
cross-tile redundancy), that reads as evidence something repeats across buffers — the
frequency tables looked like the obvious candidate. But `RansCodec.writeTable`
(`RansCodec.java:251-256`) emits **all 33 symbols unconditionally**, so a region with 4
present buckets still pays 66 bytes, 58 of them zero. That is a second, cheaper
explanation for the same gzip number, and it has the opposite fix: serialize sparsely
instead of sharing. This probe measures both so the confound is resolved rather than
assumed, per `docs/evaluation-protocol.md` step 1 (probe before writing an encoder).

## Method

`ShareTableCli` computes, per buffer, the same 33-symbol bucket histogram
`RansCodec` builds for each of `SegmentQuadCorner`'s three residual regions
(`SegmentQuadCorner.regionLengths`, `SegmentQuadCorner.java:81-94`), then prices each
region under five models in **ideal code length** (order-0 bits, not encoded bytes — this
is a probe, not a coder):

- **per-buffer (today):** each region priced at its own histogram — the optimum, and the
  baseline everything else is measured against.
- **sparse-table control:** `[1 B count][count x (1 B symbol, 2 B frequency)]` instead of
  the fixed 66 B; no cross-buffer state.
- **per-tile shared:** all layers in one tile (mean 12,2 buffers/tile, max 16) pool their
  histograms into one table set, charged once per tile.
- **per-layer-across-tiles shared:** one MVT layer's buffers, across every tile, pool into
  one table set, charged once per layer.
- **corpus-static (held out):** table trained on half the tiles, priced on the other half
  — the only row that can honestly claim a ceiling for "bake a table into the codec,"
  since a pooled train==test number would repeat the exact error
  [rans-entropy-coder.md](rans-entropy-coder.md)'s C4 estimate was discounted for.

Mixture penalty = `bits(own histogram, shared table) - bits(own histogram, own table)`,
floored at 0 per buffer (mixing cannot lower cost, so a negative value would mean the
model is wrong). Selector cost (which table a buffer used) is charged at a flat 1
byte/buffer, sufficient for the ≤16-buffer/tile and one-table-per-group case measured
here — a real multi-table scheme would cost more.

## Results (corpus-wide, `--layer` omitted)

```
                          table bytes   penalty bytes   net vs today (987.168 B)   net%
sparse-table control          399.720               0                   587.448   59,5%
per-tile shared                85.276         422.696                   479.196   48,5%
per-layer-across-tiles          7.984         118.442                   860.742   87,2%
corpus-static (held out)            0    369.761 (half-corpus test set alone)        —
```

(`net%` of the 987.168 B baseline; RANSSEG's corpus total is 11.393.052 B, so these nets
are 4,2-7,6% of the whole encoding, matching the ceiling estimated before this probe ran.)

**By vertex-count bucket, sparse-table control:**

```
bucket        bufs   dense bytes   sparse bytes   saved    saved%   meanPresent
0-50          1371       309.846        77.466   232.380    75,0%          2,83
50-200         799       180.574        68.758   111.816    61,9%          6,12
200-1000      1048       236.848       111.022   125.826    53,1%          8,33
1000-5000      865       195.490       104.362    91.128    46,6%          9,96
5000-inf       285        64.410        38.112    26.298    40,8%         11,41
```

Mean present symbols per region never exceeds ~11 of 33 even on the largest buffers — the
alphabet stays sparse everywhere, which is exactly what a bit-length bucket alphabet
predicts (few residuals are 2^30-scale). The saving is largest on the smallest buffers
(75,0%), which is also where the fixed table cost matters most — the sparse format fixes
the small-buffer pathology directly, at the source.

**By vertex-count bucket, per-tile sharing** (net turns negative on 5000+ buffers):

```
bucket        bufs   table bytes   penalty bytes   net vs dense    net%
0-50          1371        46.571          12.052       251.223    81,1%
50-200         799        26.789          14.631       139.154    77,1%
200-1000      1048         7.602          65.201       164.045    69,3%
1000-5000      865         2.673         191.802         1.015     0,5%
5000-inf       285         1.641         139.009       -76.240  -118,4%
```

The mixture penalty grows with buffer size (more values to mis-price) while the table
saving shrinks (a tile's fixed cost is already small relative to its large buffers), so
large buffers actively lose under per-tile sharing.

**Per-layer-across-tiles does best** (net 87,2% corpus-wide) because a layer's buffers
share a geometry type and hence a residual distribution far more than a tile's mixed
layers do — but it needs cross-buffer addressing (which layer-wide table set applies),
which is exactly the mechanism that made junction back-references and shape-reuse
unworkable (`failed/junction-backref.md`, `failed/shape-reuse.md`: 88-92% of matches are
cross-tile). Even here the penalty is not free: 118.442 B, 12,1% of the 979.184 B the
table-byte reduction (987.168 → 7.984 across regions plus framing) removes.

**Corpus-static (held out) is the cautionary row.** Trained on 179 tiles / 2.101 buffers,
priced on the other 179 tiles / 2.267 buffers: **369.761 B of penalty on the test half
alone** — larger than the entire per-layer-across-tiles penalty (118.442 B) over the whole
corpus. A table baked into the codec and never adapted to the tile it is decoding costs
more than any of the adaptive sharing schemes, confirming `RansCodec.java:22-25`'s
original reasoning for rejecting it ("only valid for tiles resembling the training set")
with a real number rather than an assertion.

## Built and measured

The sparse format — `[1 B present count][present x (1 B symbol, 2 B frequency)]` —
replaced `RansCodec`'s fixed 66-byte dense table directly (`RansCodec.java:248-266`),
no shared state, no `Encoding` API change, exactly as recommended below. Measured by
diffing `./gradlew benchmark --args="--segmented --csv"` before/after over the same
baseline corpus, matched per buffer:

```
QUAD_CORNER_SEG_RANSSEG corpus total, dense tables:   11.393.052 B
QUAD_CORNER_SEG_RANSSEG corpus total, sparse tables:  10.812.307 B
saved: 580.745 B (5,10% of RANSSEG; the ShareTableCli control predicted 587.448 B ideal-bits
table savings — a 1,1% miss, inside the ~21% ideal-vs-real-coder gap this file's caveats
already flagged)
```

By vertex-count bucket (same buckets as the sparse-table control above):

```
bucket        bufs   RANSSEG before   RANSSEG after   saved    saved%
0-50          1371          435.191         206.122   229.069   52,6%
50-200         799          413.628         303.243   110.385   26,7%
200-1000      1048        1.499.512       1.374.919   124.593    8,3%
1000-5000      865        4.291.915       4.201.498    90.417    2,1%
5000-inf       285        4.752.806       4.726.525    26.281    0,6%
```

**The headline effect is on the buffer-size crossover, not just total bytes.**
`rans-entropy-coder.md` found `QUAD_CORNER_SEG_RANSSEG` a net corpus loss of **+9,62%**
against `QUAD_CORNER_SEG_RANS` (one shared table, no region split), entirely because the
fixed ~152 B/buffer of dense per-region tables sank the small-buffer majority. Under the
sparse table, that corpus-wide comparison is now:

```
QUAD_CORNER_SEG_RANS     corpus total:  10.811.936 B
QUAD_CORNER_SEG_RANSSEG  corpus total:  10.812.307 B   (+371 B, +0,00%)
```

The region split goes from a clear corpus-wide loss to a wash — the fixed table cost that
used to force a per-buffer-size decision (segment above ~1.000 vertices, don't below) is
no longer large enough to move the corpus total either way. Per-bucket, the crossover
itself hasn't flipped (0-50 is still +83,9% worse under RANSSEG, 5000+ is -4,5% better —
mixture-penalty-free per-region pricing still wins on large buffers, table cost still
loses on tiny ones), but the losing side lost far less: 0-50's penalty roughly halved
(was ~+130% per `rans-entropy-coder.md`, now +83,9%).

Round-trip correctness: `RansCodecTest` and `SegmentedRansCodecTest` pass unchanged, plus
two new assertions pinning the sparse header directly (`encoded[0]` equals the present-symbol
count) rather than a byte-count golden value, so the tests stay valid if the coded payload
size changes for unrelated reasons.

## What this settles

**The confound was sparsity, not similarity — mostly.** A local, stateless fix (sparse
table serialization) captures 59,5% of the fixed-cost problem with none of the
architectural cost cross-buffer sharing would add (no two-pass `BenchmarkCli`, no
`Encoding` API change, no selector signalling beyond what is already inside a buffer).
Per-layer-across-tiles sharing adds a further 27,7 percentage points of net saving over
the sparse control alone (87,2% vs 59,5%), which is real but requires solving the same
addressing problem every other cross-tile idea in this repo has failed on.

**Recommendation (built):** the sparse table format was built as a strictly local change
to `RansCodec.writeTable`/`readTable` — it needed no shared state, and its win was not
conditional on buffer-size crossovers the way `RANSSEG` is; see "Built and measured"
above for the corpus delta. Cross-buffer table sharing remains not pursued: even its best
grouping (per-layer-across-tiles) gained only ~28 points over what the sparse fix got for
free, at the cost of solving addressing, and the corpus-static extreme of that idea
measurably backfires. The sparse table's residual fixed cost still sinks small buffers
relative to `QUAD_CORNER_SEG_RANS` (0-50 bucket: +83,9%, down from ~+130% under dense
tables) — if that gap still matters, per-layer-across-tiles sharing is the grouping to
revisit, not per-tile.

## Caveats

- Ideal code length (order-0 bits), not a real coder's bytes — `RansCodec`'s own bucket +
  raw-bits split already loses ~21% to true order-0 on a synthetic control
  (`rans-entropy-coder.md`), so a real implementation of any row here would show smaller
  absolute numbers than the bits reported. The *relative* comparison between rows — which
  this result is about — is not sensitive to that gap, since it applies equally to all of
  them.
- Selector cost is a flat 1 byte/buffer, not designed against `StreamMetadata`'s ~2 free
  `LogicalLevelTechnique` ordinals (`selection-oracle.md`) — a real per-layer-across-tiles
  scheme needs a real signalling design before its 87,2% number can be trusted as
  shippable.
- `--layer` filtering makes per-tile groups size 1 (nothing to share), so single-layer
  runs of `ShareTableCli` show a small negative net on that row from the selector byte
  alone — expected, not a bug; only the unfiltered corpus run measures real per-tile
  sharing.
