# Entropy cost-model estimate (WP7) and the rANS coder

> **Consolidation note.** The Gradle task(s) entropy, condEntropy named below were removed from the build (the code is at git tag `pre-consolidation`); `research measure` and the other `research` commands replace the rest. The numbers below are unchanged history.

**Status:** registered — win. Best corpus result: -27,16% (`SEGMENT_QUAD_CORNER_RANS_REORDER_REV`)
**Corpus:** previous Munich corpus (`munich.mbtiles`) — see [reordering.md](reordering.md)
for the corpus-comparability banner

## WP7: a real gap exists, no coder built (superseded below)

No rANS/FSE implementation exists anywhere in this repository or the `maplibre-tile-spec`
submodule — confirmed by exhaustive search. Building one is a substantial, separate
undertaking (context plumbing through `IntCodec`, which is currently context-free and
cannot see part boundaries or the run-length context `ideen.md`'s design needs; static
vs per-buffer frequency tables; a real bitstream coder). Per the user's decision, WP7
stops at a **cost-model estimate**: does the gap between what FastPFOR actually charges
and what order-0 entropy says is achievable justify that investment at all?

`EntropyCli` (`./gradlew entropy --args="../data/extracted --layer building"`) measures
this for `SEGMENT_QUAD_CORNER`'s three residual regions (starts | interior | corners),
311 Munich building buffers, 2.951.676 residual values:

```
interleaved (one population)      7,153 bits/value  ->    2.639.235 bytes
  starts        584.546 values   10,365 bits/value  ->      757.364 bytes  (alphabet 8.345, table ~50.070 bytes)
  interior    1.924.736 values    6,447 bits/value  ->    1.550.987 bytes  (alphabet 883, table ~5.298 bytes)
  corners       442.394 values    1,720 bits/value  ->       95.126 bytes  (alphabet 163, table ~978 bytes)
partitioned (3 regions)                              ->    2.403.477 bytes  (+ ~56.346 bytes static tables)

Real FastPFOR bytes for the same stream: 2.832.684

Gap: FastPFOR - partitioned entropy = 429.207 bytes (15,15% of FastPFOR)
```

**E7.1, the mixture penalty, confirms the region-split lever directly in entropy terms**,
not just FastPFOR bytes: partitioning by region before computing entropy saves 235.758
bytes (8,93%) over treating the residuals as one population — the same block-width
mechanism `SEGMENT_PAIR`/`SEGMENT_QUAD_CORNER` exploit, restated as an information-
theoretic fact rather than inferred from a bit-packed codec's behavior.

**E7.2/gap.** FastPFOR spends 429.207 more bytes than partitioned order-0 entropy says
is achievable — 15,15% of its own total, and about 373K bytes (13,2%) after subtracting
the ~56K bytes a real static-table implementation would cost to ship. That is a real,
non-trivial ceiling, not a rounding artifact — consistent with [context-delta.md](context-delta.md)'s
finding that FastPFOR prices the block *maximum* while an entropy coder prices the
*median*, and with `ideen.md`'s prediction that continuation-style predictors specifically
should survive under rANS where they lose under FastPFOR.

**What this does and does not establish.** It is a **ceiling**, not an achievable number:
order-0 entropy assumes an ideal coder with no per-symbol overhead, ignores the
context-modelling `ideen.md`'s actual design uses (which could do better by conditioning
on run state, or worse if that context fragments the alphabet further), and the ~56K
table-cost estimate is a rough per-region flat cost (`alphabet × 6 bytes`), not a design
for a real table format. E7.3 (FastPFOR block-occupancy check) was not run separately,
since the gap above already answers the question this measurement was scoped to answer.
**No rANS/FSE coder was implemented** at this point — the 13,2%-of-FastPFOR ceiling was
large enough that a real coder was not obviously not worth building, but the
decoder-complexity and `IntCodec` redesign costs were unmeasured, so this stopped at "the
gap is real and worth knowing," not "build it".

## WP7 follow-up — the coder was built; it captures 38,6% of the estimated gap

`RansCodec` (static rANS, 12-bit precision, 32-bit state / 8-bit renormalisation) now
exists as an `IntCodec`, so it composes with every transform the benchmark already
measures. It needs no `IntCodec` redesign: the alphabet is the residual's **bit-length
bucket** (33 symbols) and the `bucket-1` bits below the leading one are written raw into
a separate forward region. The context is derived from the value itself, so nothing has
to be plumbed through the context-free `IntCodec` interface. The frequency table is
per-buffer (33 × 2 bytes = 66 bytes), which keeps every byte the decoder needs inside the
measured payload — no corpus-trained side table.

Round-tripped over the whole corpus by `BenchmarkCli` on every buffer, plus
`RansCodecTest` (skewed, uniform-wide, all-zero, single-value, empty, extreme, and
all-buckets-present cases).

**Building layer, 311 buffers, same transform on both sides:**

```
SEGMENT_QUAD_CORNER_FASTPFOR   2.834.672
SEGMENT_QUAD_CORNER_RANS       2.668.361   -5,87%   (-166.311 bytes)
order-0 partitioned ideal      2.403.477   (+ ~56K table = 2.459.823)
```

The estimate said up to 431.195 bytes (15,15%) were available. The real coder took
**166.311 bytes — 38,6% of that gap.** The shortfall is not implementation slack, it is
the bucket alphabet: splitting a residual into `bucket + raw bits` is itself lossy
relative to order-0 entropy, because the raw bits below a bucket are *not* uniformly
distributed. A synthetic control confirms the direction — on a geometric source,
`H(bucket) + rawbits` overshoots true order-0 entropy by ~21%. So the WP7 ceiling was
correct as a ceiling, but no bucketed coder can reach it; only a coder over the full
residual alphabet could, at a table cost the 8.345-symbol `starts` region makes
prohibitive.

**The entropy coder and the transform are additive, not competing.** rANS on the plain
baseline transform is already worth -12,96%; stacking it on `SEGMENT_QUAD_CORNER` gives
-17,34%, and with reordering **-27,16%** — the corpus's new best, beating the previous
best ([segment-quad-corner.md](segment-quad-corner.md)'s
`SEGMENT_QUAD_CORNER_FASTPFOR_REORDER_REV`, -21,48%) by 5,7 points.

Corpus-wide, `SEGMENT_QUAD_CORNER_RANS_REORDER_REV` wins transportation (-31,0%),
building (-27,2%), landcover (-10,8%), LINESTRING (-29,9%) and POLYGON (-18,6%), and
lifts the always-one-encoding total from 13.399.028 baseline bytes to **10.601.321**.

**What did not work: conditioning y on x.** `CondEntropyCli` (`./gradlew condEntropy`)
measures the three claims a context-modelled coder would rest on. The axis-alignment
hypothesis — that on building walls a zero x-residual predicts a large |y| — is
**worth almost nothing**: H(y|coarse(x)) is only 0,015-0,092 bits below H(y), a total of
8.380 bytes (0,3% of FastPFOR) on `SEGMENT_QUAD_CORNER`, and only 20.499 bytes (0,7%) on
the raw delta stream where the predictors have not yet absorbed it. The correlation was
never strong, so **coding x and y as separate streams costs essentially nothing**, and
the interleaved single-state layout is preferable only for its one-flush overhead.
Order-1 over the bucket alphabet (`bucket | previous bucket`) is worth more but still
modest — 46.548 bytes, concentrated almost entirely in the `starts` region (38.046),
which is the one region whose alphabet is too wide to model cheaply.

## Does the region split help rANS? Only on large buffers — a table-cost tradeoff

`SEGMENT_QUAD_CORNER` already packs residuals into three contiguous regions, but
`SEGMENT_QUAD_CORNER_RANS` codes all three with **one shared frequency table**, pricing
every region at the mixture of all three. `SegmentedRansCodec` gives each region its own
table (region lengths written into the payload, so decode stays context-free; the extra
bytes are inside every number reported here).

`CondEntropyCli`'s C4 measures the ideal on the alphabet rANS actually codes — buckets,
not full residuals, since the raw sub-bucket bits are unaffected by any split:

```
one shared bucket table   ->  1.239.357 bytes
per-region bucket tables  ->  1.031.482 bytes (+ 132 bytes extra tables)
net gain from splitting:        207.743 bytes (16,76%)
```

**Building layer, isolating the split as the only variable:**

```
SEGMENT_QUAD_CORNER_RANS              2.668.361
SEGMENT_QUAD_CORNER_RANS_SEG          2.510.165   -5,93%
SEGMENT_QUAD_CORNER_RANS_REORDER_REV      2.351.388
SEGMENT_QUAD_CORNER_RANS_SEG_REORDER_REV  2.277.120   -3,16%
```

`SEGMENT_QUAD_CORNER_RANS_SEG_REORDER_REV` is the building layer's new best at **-29,5%**
against the baseline.

**Corpus-wide, however, the split is a net loss of 9,62%** — and the reason is entirely
buffer size, not geometry. Splitting costs a fixed ~152 bytes per buffer (two extra
66-byte tables plus 20 bytes of region headers), which has to be recovered from the
mixture penalty:

```
vertices              RANS         SEG    delta   bufs   fixed cost as % of RANS
0-50               414.993   1.095.062 +163,87%   3543        129,8%
50-200             580.908     885.320  +52,40%   1637         42,8%
200-1000         2.319.476   2.641.480  +13,88%   1963         12,9%
1000-5000        4.482.117   4.529.651   +1,06%    980          3,3%
5000-inf         4.188.332   3.987.602   -4,79%    243          0,9%
```

The crossover is around **1.000-5.000 vertices**. Below it the tables cost more than the
mixture penalty they remove; above it the split pays and keeps paying. Building wins
because its buffers are large; the corpus loses because 5.180 of 8.366 buffers have fewer
than 200 vertices.

**Superseded by the sparse table.** `RansCodec`'s dense 66-byte-per-table format — the
fixed cost driving this loss — was replaced with a sparse `[count][symbol,freq]...`
serialization ([shared-tables.md](shared-tables.md), "Built and measured"). Under the
sparse table the corpus-wide RANSSEG-vs-RANS verdict goes from this section's +9,62% loss
to +0,00% (a wash): the fixed table cost shrank enough that it no longer moves the corpus
total either way, though the per-bucket crossover itself is unchanged (small buffers still
lose, large buffers still win — see the linked file for the re-measured numbers).

**So segmentation is not a free win under rANS, and the earlier per-region entropy figures
overstate it.** C4's 16,76% ideal is computed over the pooled corpus, where the large
buffers dominate the value count — it does not price the per-buffer table cost that
decides the outcome on small buffers. The honest form of the result is conditional: worth
taking above ~1.000 vertices, harmful below, which makes it a per-buffer decision rather
than a universal encoding improvement. This matches the project's standing rule that
segmentation is a niche add-on, not a baseline comparator (see
`docs/evaluation-protocol.md`) — here it is measured as an isolated variable against the
same transform and codec, with and without reordering reported separately.
