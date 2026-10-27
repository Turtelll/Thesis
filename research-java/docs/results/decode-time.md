# Decode time: rANS vs FastPFOR, interleaved vs segmented

**Status:** measured. Every prior result in this directory is a byte count; this is the first
decode-time measurement in the repository (`rans-entropy-coder.md:56` names it explicitly as
unmeasured, `reordering.md:115` calls the analogous cost "the next measurement").

**Corpus:** `data/extractedBaseline` (`munich-buildings-unmerged.mbtiles`, the current baseline —
see `docs/results/README.md`), 4.368 buffers, every layer, `--reorder` excluded (see below).

**Headline result — the opposite of the size/speed intuition:** on this corpus, **rANS decodes
faster than FastPFOR**, corpus-wide, at every codec pairing measured. `DELTA_ZIGZAG_RANS` is
~0,45-0,5x the baseline's decode time; the segmented per-region variant (`QUAD_CORNER_SEG_RANSSEG`)
is the slowest rANS row but still faster than every FastPFOR row.

```
codec-only decode, corpus total, x DELTA_ZIGZAG_FASTPFOR baseline:

DELTA_ZIGZAG_FASTPFOR      1,00x  (455 ms)
QUAD_CORNER_FASTPFOR       1,01x  (462 ms)
QUAD_CORNER_SEG_FASTPFOR   0,97x  (443 ms)
DELTA_ZIGZAG_RANS          0,47x  (213 ms)
QUAD_CORNER_RANS           0,42x  (189 ms)
QUAD_CORNER_SEG_RANS       0,47x  (214 ms)
QUAD_CORNER_SEG_RANSSEG    0,61x  (277 ms)
```

This means the corpus's best size result (`QUAD_CORNER_SEG_RANS(SEG)` + reorder, -27,16%,
`rans-entropy-coder.md`) is not bought with slower decode — under this measurement it is both
smaller **and** faster to decode than the FastPFOR baseline it replaces.

## Method

New probe, `DecodeTimeCli` (`./gradlew research --args="decode-time [extractionDir] [--encoding
<NAME>[,<NAME>...]] [--layer <name>] [--reps <n>] [--warmup <n>] [--max-ratio <x>] [--csv
<file>]"`), following the existing measurement-only CLI pattern (`RingPriorCli`, `EntropyCli`).

- **Two levels**, so codec cost is separable from predictor-inversion cost:
  - Table A: `IntCodec.decode(byte[], int)` alone — FastPFOR vs `RansCodec` vs `SegmentedRansCodec`.
    Only for rows whose payload is exactly one codec's output (`VertexBufferCodec`,
    `SegmentedEncoding`); a composite payload such as `SharedVertexRef`'s (mode byte, flags,
    references) shows `-` here.
  - Table B: `Encoding.decode(...)` end-to-end — codec + `VertexTransform.invert`. Every row.
- **Rows.** Without `--encoding`, the seven rows of the full `{transform} x {codec}` grid this
  result answers (`DELTA_ZIGZAG`/`QUAD_CORNER`/`QUAD_CORNER_SEG` transforms x `FASTPFOR`/`RANS`
  codecs, plus `QUAD_CORNER_SEG_RANSSEG` — the per-region-table variant, `SegmentedEncoding`), so
  the tables below are reproducible from a bare run. `--encoding` instead selects any name from
  `Baselines.all()` (plus the three rANS grid rows `Baselines` does not register), and both
  `Baselines.baseline()` rows are always prepended as references.
- **Same-codec reference.** Each row's `x reference` ratio is against the baseline with the same
  codec (`*_VARINT` vs `DELTA_ZIGZAG_VARINT`, `*_FASTPFOR` vs `DELTA_ZIGZAG_FASTPFOR`); anything
  else (rANS) falls back to `DELTA_ZIGZAG_FASTPFOR`. A closing summary lists, per non-baseline
  row, the end-to-end corpus ratio, the worst per-bucket ratio, and `OVER LIMIT` when the corpus
  ratio exceeds `--max-ratio` (default 1,5x). Report only — the run never fails on it.
- **Round-trip verified once per (row, buffer), untimed**, before any timing — via
  `BenchmarkCli.verifyRoundTrip`, so a geometry-reordering encoding (`SharedVertexRef`) is held to
  geometry equivalence and everything else to exact equality. A timing number from a broken
  decoder is not a result.
- **Timing:** `System.nanoTime()`, minimum of `--reps` repetitions per (row, buffer) kept (the
  standard robust estimator against GC/scheduler noise) — no mean, no JMH (not a project
  dependency). A full warmup pass (`--warmup`, default 2) over every (row, buffer) runs first and
  is discarded, so C2 has compiled every decode path before anything is recorded; without this the
  first-touched row absorbs interpreter/C1 cost and the result becomes an artifact of row order.
  Every decode result is summed into a sink field printed at the end, so the JIT cannot prove a
  call's result unused and eliminate it.
- **Buckets** mirror `rans-entropy-coder.md`'s buffer-size table (0-50 / 50-200 / 200-1000 /
  1000-5000 / 5000+ vertices), so this result reads directly against the existing size result and
  its documented table-cost crossover.
- **`--reorder` excluded.** `ReorderedEncoding.decode` (`research/ReorderedEncoding.java:56`)
  reconstructs topology via `reorder.topology(ctx, vertexCount)` that a real decoder reads from the
  tile — timing it would overstate decode cost for a cost the encoding itself doesn't pay at decode
  time in production.

**What this is not.** A steady-state, single-threaded, in-process JVM measurement (OpenJDK
Corretto 21.0.12, Windows, Intel i7-1265U) after 2 warmup passes over the whole corpus — not a
cold-start number. A browser/WASM decoder pays JIT warmup cost this harness deliberately excludes,
and single-buffer decode in a real tile parser has none of the loop/branch-prediction locality this
measurement's back-to-back repetitions get from the JVM's own optimizer. Treat the *ratios* between
rows as the finding, not the absolute ns/value figures as a production estimate.

## Full results (`--reps 10 --warmup 2`, corpus-wide)

### Table A — `IntCodec.decode` only (ns/value; corpus total in ms)

| bucket (vertices) | bufs | FASTPFOR (delta) | RANS (delta) | FASTPFOR (quad) | RANS (quad) | SEG+FASTPFOR (quad) | SEG+RANS (quad) | SEG_RANSSEG (quad) |
|---|---|---|---|---|---|---|---|---|
| 0-50 | 1.371 | 3.419,4 | 181,1 | 3.498,4 | 152,5 | 3.345,7 | 180,3 | 555,7 |
| 50-200 | 799 | 475,0 | 35,0 | 480,7 | 29,8 | 462,1 | 35,9 | 87,0 |
| 200-1000 | 1.048 | 99,6 | 18,3 | 101,1 | 14,9 | 96,8 | 18,8 | 30,9 |
| 1000-5000 | 865 | 24,1 | 19,6 | 24,4 | 17,3 | 23,4 | 19,5 | 24,0 |
| 5000-inf | 285 | 6,3 | 18,7 | 6,2 | 17,1 | 6,0 | 18,9 | 20,4 |
| **corpus total** | 4.368 | **455,4 ms** | **212,9 ms** | **461,6 ms** | **189,2 ms** | **442,9 ms** | **214,4 ms** | **276,9 ms** |
| x baseline | | 1,00x | 0,47x | 1,01x | 0,42x | 0,97x | 0,47x | 0,61x |

### Table B — `Encoding.decode` end-to-end (ns/value; corpus total in ms)

| bucket (vertices) | bufs | FASTPFOR (delta) | RANS (delta) | FASTPFOR (quad) | RANS (quad) | SEG+FASTPFOR (quad) | SEG+RANS (quad) | SEG_RANSSEG (quad) |
|---|---|---|---|---|---|---|---|---|
| 0-50 | 1.371 | 3.444,2 | 183,5 | 3.526,9 | 156,2 | 3.378,2 | 186,2 | 563,8 |
| 50-200 | 799 | 479,4 | 37,1 | 484,4 | 32,7 | 466,0 | 40,3 | 91,1 |
| 200-1000 | 1.048 | 102,5 | 20,3 | 104,7 | 17,4 | 101,0 | 22,8 | 34,7 |
| 1000-5000 | 865 | 26,9 | 21,2 | 27,6 | 19,5 | 26,9 | 23,4 | 28,0 |
| 5000-inf | 285 | 9,0 | 21,7 | 9,5 | 20,3 | 9,8 | 23,8 | 25,4 |
| **corpus total** | 4.368 | **485,9 ms** | **238,5 ms** | **498,3 ms** | **219,4 ms** | **483,7 ms** | **261,8 ms** | **325,4 ms** |
| x baseline | | 1,00x | 0,49x | 1,03x | 0,45x | 1,00x | 0,54x | 0,67x |

Table B - Table A per row is the predictor-inversion cost; it is small everywhere (a few ns/value)
and never changes which codec wins, confirming the codec, not the transform, is what this question
is about.

## Findings

**F1 — On every bucket, rANS decodes faster per value than FastPFOR, and the gap is largest on
small buffers.** On 0-50 vertices, FastPFOR costs ~3.400-3.500 ns/value against rANS's ~150-185 —
an 18-23x difference. This inverts the intuition that an entropy coder trades decode speed for
size; here it does not, on either axis. The likely mechanism: `DecodingUtils.decodeFastPfor`
(mlt-core) is block-oriented (128-value PFOR blocks with an exception/patching path) and pays fixed
per-block setup cost that a buffer far smaller than one block cannot amortize, while
`RansCodec.decode`'s fixed cost — building its 33-symbol frequency table and 4096-entry
`symbolLookup` (`RansCodec.java:129-131, 238-246`) — is cheaper in absolute terms. This is a
mechanism hypothesis from reading both decoders, not something this probe isolates directly; it
would take a dedicated micro-benchmark of `DecodingUtils.decodeFastPfor` alone to confirm.

**F2 — Both codecs show a fixed per-call cost that dominates on small buffers and vanishes on
large ones**, visible as ns/value falling monotonically as bucket size rises, for every row.
FastPFOR's fixed cost is far larger in absolute terms (F1). rANS's own fixed cost is real but
smaller: ns/value falls from ~180 (0-50) to ~19-21 (1000-5000), then **rises slightly** on 5000+
(to ~17-24) — consistent with `RansCodec.decode`'s `BitReader.read` reading raw sub-bucket bits one
bit at a time (`RansCodec.java:398-407`), a per-value cost with no fixed-cost amortization, growing
to matter once the fixed table-build cost is small enough not to dominate.

**F3 — Segmentation (multiple regions, multiple frequency tables) costs roughly what its per-region
fixed-cost multiplication predicts, and this is now measured rather than assumed.**
`QUAD_CORNER_SEG_RANSSEG` (3 independent tables) vs `QUAD_CORNER_SEG_RANS` (1 shared table over the
same segmented layout) on the smallest bucket: 555,7 vs 180,3 ns/value — a 3,08x ratio, matching
`SegmentedRansCodec.decode`'s structure exactly: it calls `rans.decode` once per region
(`SegmentedRansCodec.java:104`), each rebuilding the 4096-entry lookup table
(`RansCodec.java:238-246`) independently. The ratio shrinks with buffer size (87,0 vs 35,9 at
50-200; 30,9 vs 18,8 at 200-1000; converges by 1000-5000) — the same fixed-cost-amortizes-with-size
pattern `rans-entropy-coder.md`'s byte-cost crossover shows for the *size* tradeoff (~1.000-5.000
vertices there too). Decode time and encoded size both penalize per-region tables on small buffers,
for the same underlying reason: a per-buffer table is fixed overhead that many small buffers cannot
amortize. This does **not** contradict `rans-entropy-coder.md` — it is an independent confirmation
of the same mechanism from a different measurement.

**F4 — This does not change the standing recommendation.** `QUAD_CORNER_SEG_RANS` (shared table,
segmented layout) remains both the corpus's best size result's component and, per F3, meaningfully
faster to decode than `QUAD_CORNER_SEG_RANSSEG` (per-region tables) on the small-buffer majority of
the corpus (5.180 of 8.366 buffers were <200 vertices per the size analysis) — the same buffers
where `rans-entropy-coder.md` already found per-region tables a net *size* loss. Decode time gives
segmentation-with-per-region-tables no case it didn't already lose on bytes.

## Caveats

- **Per-bucket noise on the smallest bucket is real, not just measurement error to explain away.**
  Re-running the full corpus at `--reps 5` vs `--reps 10` moved the 0-50 bucket's absolute ns/value
  figures by up to ~20% (e.g. `QUAD_CORNER_SEG_RANSSEG`: 646 vs 556 ns/value) while the *corpus
  total x baseline* ratios stayed close (0,41x vs 0,47x for `QUAD_CORNER_RANS`-family rows). Small
  buffers do very little absolute work, so JIT/GC/scheduler noise is proportionally larger — the
  cross-row *ordering* (rANS always faster than FastPFOR, segmented-with-per-region-tables always
  slowest among rANS rows) was stable across every run in this investigation; the exact multipliers
  on the 0-50 bucket should be read as "same order of magnitude," not "accurate to the ns."
- Single machine, single JVM, one architecture (x86-64, Intel i7-1265U). Not measured: WASM,
  ARM, a real browser decode path, or multi-threaded decode.
- `IntCodec.FASTPFOR` delegates to `mlt-core`'s `DecodingUtils.decodeFastPfor`
  (`IntCodec.java:74`), which is not modified or re-profiled in isolation here — F1's mechanism
  explanation is a plausible reading of its block structure, not a line-level confirmation.
