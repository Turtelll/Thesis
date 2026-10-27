# Predictors under gzip, Brotli and zstd

> **Consolidation note.** The Gradle task(s) benchmark named below were removed from the build (the code is at git tag `pre-consolidation`); `research measure` and the other `research` commands replace the rest. The numbers below are unchanged history.

**Status:** measured — direction, not one new encoding
**Corpus:** current baseline (`munich-plantiler.mbtiles`, `data/extractedBaseline`, 358
tiles, 4.368 buffers). Every compressed figure below is **per tile**: a tile's buffers
concatenated and compressed on their own, summed over tiles. That is one HTTP response.
The corpus-compressed-once table a run also prints is larger-window and is not cited here.
**Runs:** `./gradlew benchmark --args="--compress"`, plus `--segmented`, `--revisit`,
`--reorder feature-rev` and `--reorder ring-canonical`. Brotli quality 9, gzip level 6,
zstd level 19. Every percentage is against `DELTA_ZIGZAG_VARINT` under the same
compressor.

## Why FastPFOR goes

A browser decompresses gzip, Brotli or zstd natively and nothing else, so the delivery
size is the compressed size, and a bit-packed codec is opaque to a compressor that works
on bytes. Per tile, `DELTA_ZIGZAG_FASTPFOR` is **+6,65% under gzip, +7,42% under Brotli
and +6,68% under zstd** against `DELTA_ZIGZAG_VARINT` — it wins raw (12.411.712 vs
13.270.873) and loses the moment a compressor runs. `--compress` therefore keeps FastPFOR
only as that baseline row and measures every idea over Varint.

The corpus-once table hides this less than it hides everything else: FastPFOR compresses
almost identically once or per tile (gzip 11.967.193 vs 11.984.744), because there is no
cross-tile redundancy left in it. Varint under Brotli does not — 10.609.535 corpus-once
against 11.141.924 per tile, **4,8% of apparent saving that no response can use**. Cite
the per-tile table.

## What a compressor actually charges for

gzip, Brotli and zstd are LZ77 plus an entropy coder over bytes. Two columns separate the
two:

- `order-0` is the byte-entropy bound per tile, before any match. On `DELTA_ZIGZAG_VARINT`
  gzip lands **1,8%** under it (11.237.124 vs 11.441.503) and Brotli 2,6%. Matching finds
  little in this corpus. A predictor pays by lowering byte entropy, not by manufacturing
  repeats for LZ77.
- `zero pairs` in `--- residual bits ---` is the share of `(x, y)` residuals that are
  exactly `(0, 0)`. `DELTA_ZIGZAG` 0,2%, `QUAD_CORNER` 2,4%. Mean bits stays a FastPFOR-era
  hint: it prices a block's maximum, which no compressor here does.

## Byte layout matters about as much as the predictor

Same residuals, four ways of laying them out as bytes. `VARINT_XY` and `VARINT_SPLIT` are
Varint's size before compression; `BYTESPLIT` and `RAW` are 32-bit words.

| layout, on `DELTA_ZIGZAG` | gzip | brotli9 | zstd19 |
|---|---|---|---|
| `VARINT` | ref | ref | ref |
| `VARINT_SPLIT` (first byte of every value, then the continuation bytes) | -0,87% | -1,79% | -1,73% |
| `VARINT_XY` (all x, then all y) | +0,58% | +0,80% | +0,71% |
| `BYTESPLIT` (byte planes) | +3,87% | +0,08% | -0,92% |
| `RAW` (32-bit words) | +41,63% | +23,46% | +23,28% |

`VARINT_SPLIT` wins on every compressor: the common one-byte values stay contiguous and
the rare long values stop interrupting them. Splitting x from y does the opposite of what
was hoped — the alternating stream compresses better, and it regresses everywhere.
`BYTESPLIT` recovers most of `RAW`'s loss under Brotli and zstd and still never beats
`VARINT_SPLIT`; under gzip it does not recover it. None of the three is a tile format on
its own before compression, so only the compressed columns count.

## The ideas carry over, and segmentation pays here too

`QUAD_CORNER_SHARED` is the best idea, as it is under FastPFOR, and the serializer stacks
on top of it instead of replacing it:

| row | gzip | brotli9 | zstd19 |
|---|---|---|---|
| `QUAD_CORNER_VARINT` | -0,97% | -1,15% | -1,30% |
| `QUAD_CORNER_SHARED_VARINT` | -3,07% | -3,25% | -3,38% |
| `QUAD_CORNER_SHARED_VARINT_SPLIT` | -3,78% | -4,78% | -4,87% |
| `QUAD_CORNER_SHARED_SEG_VARINT_SPLIT` | **-5,58%** | **-6,79%** | -6,53% |
| `LINE_CONTINUATION_SHARED_DICT_VARINT` | -1,27% | -1,42% | -1,29% |
| `LINE_CONTINUATION_SHARED_DICT_VARINT_SPLIT` | -2,27% | -3,27% | -3,13% |

`QUAD_CORNER_SHARED_SEG_BYTESPLIT` is the best zstd row at -6,97%, and worse than
`VARINT_SPLIT` under gzip (-2,86%), so it is not the row to ship.

Segmentation was assumed not to carry over to a general-purpose compressor
(`Baselines.segmented()` said so, because under Varint it is the same size before
compression: `QUAD_CORNER_VARINT` and `QUAD_CORNER_SEG_VARINT` are both 13.265.283 raw).
It does. Grouping the part-start jumps away from the shape residuals drops
`QUAD_CORNER_VARINT` from -0,97% to -2,04% under gzip and `QUAD_CORNER_SHARED_VARINT_SPLIT`
from -4,78% to -6,79% under Brotli. The mechanism is the one FastPFOR never had: a run of
similar bytes compresses better than the same bytes alternating with outliers.

`LINE_CONTINUATION_SHARED_DICT` keeps its lead over `_MARKED` (-1,27% vs -0,80% gzip), so
the wider dictionary still earns its index bits once a compressor runs.

## What did not carry over

**Recency-coded references (`_RECENT`).** Replacing the bit-packed candidate index with a
varint of the distance back from the newest candidate — so "the road just emitted" is the
same small byte however large the dictionary grows — changes nothing a compressor can see.
`QUAD_CORNER_SHARED_RECENT_VARINT` is -3,03% gzip against -3,07% for the packed indices,
and `_DICT_RECENT` ties `_DICT`. The index stream is too small a fraction of the payload
for its byte alignment to matter. Kept, measured, not a win.

**The three FastPFOR casualties (`--revisit`).** Their loss was not the block-maximum
pricing. `CONTEXT_DELTA_T2_M32` is +3,41% gzip / +3,24% Brotli, `RIGHT_ANGLE_TURN` +6,31% /
+6,18%, `TURN90_BITMAP` +2,15% / +1,98% — and +0,31% is the best any serializer manages for
the bitmap (Brotli, `VARINT_SPLIT`). The -2,6% all-in bits `TURN90_BITMAP` showed under
FastPFOR does not survive as bytes here either. They stay in `failed/`.

**Feature reordering (`--reorder feature-rev`).** Real, and smaller than the serializer.
`DELTA_ZIGZAG_VARINT_REORDER_REV` is -1,54% gzip / -1,72% Brotli, and on top of the shared
references `QUAD_CORNER_SHARED_VARINT_REORDER_REV` reaches -4,16% gzip against -3,07%
without it. Not crossed with `VARINT_SPLIT`: `--reorder` wraps the rows in the byte tables,
and the serializer rows are compression-only.

**Canonical ring start (`--reorder ring-canonical`).** Every polygon ring rotated, winding
kept, to start at its lowest-x then lowest-y vertex, so a translated copy of a shape emits
the same interior residuals. It does not pay: `DELTA_ZIGZAG_VARINT_RING_CANONICAL` is
-0,12% gzip / -0,08% Brotli, and on `QUAD_CORNER_SHARED_VARINT` it moves gzip from -3,07%
to -3,21%. The reason is the start residual. It is still the delta from the previous
feature, which differs per copy, so the byte string of a shape is never actually repeated —
only a tail of it is, and the tails are shorter than LZ77 bothers with. `zero pairs` moves
from 0,2% to 0,3%. Making the strings genuinely identical would mean resetting the delta at
each ring, which is the seed cost [selection-oracle.md](selection-oracle.md) already priced
at a loss.

**Revisited over `VARINT_SPLIT`.** Every retired predictor (`PART_START_SEG`, the rot90 rows,
`TURN_SCALAR_SEG`, midpoint reflection, upstream delta2, the three turn encodings, context
delta, RDP) was re-run segmented over split varints, and `RING_CANONICAL` over the shared
references. Only the canonical ring start moves: -0,10% gzip / -0,14% Brotli / -0,18% zstd
against `QUAD_CORNER_SHARED`, and the smallest row on `building`. The rest stay lost; see
[revisit-varint-split.md](revisit-varint-split.md). That supersedes the plain-`VARINT` verdicts
for `--revisit` and `--reorder ring-canonical` above for the rows it names.

**Standardized-length tokens over fixed Uint16 (`--std-delta`).** One fixed encoding for all
layers, with deltas decomposed into a global token vocabulary so layers repeat each other's
2-byte symbols. Best row `STD_DELTA_PLAIN_UINT16_PLANES` +2,05% gzip / -0,21% Brotli / -1,08%
zstd, behind `VARINT_SPLIT`; every real decomposition is worse. LZ77 matches the runs the
tokens create and nothing more: see
[failed/standard-delta-uint16.md](failed/standard-delta-uint16.md).

**Standard deltas learned per tile (`--tile-std-delta`).** The same idea with the vocabulary
taken from the data: the K most frequent deltas of all a tile's layers, stored once in the
tile, every delta a sum of entries plus a remainder. Best row
`TILE_STD_DELTA_VECTOR_K1_SINGLE_VARINT_SPLIT` (at most one entry per delta) +7,48% gzip /
+4,77% Brotli / +3,98% zstd, +14,17% / +9,40% / +7,31% without the cap, and a larger K is
worse: see [failed/tile-standard-delta.md](failed/tile-standard-delta.md).

## Where this leaves the per-buffer selector

The `varint oracle` — smallest Varint row per buffer, no signalling charged — is -4,50%
gzip / -4,85% Brotli / -4,80% zstd per tile. One row,
`QUAD_CORNER_SHARED_SEG_VARINT_SPLIT`, beats that on its own (-5,58% / -6,79% / -6,53%)
without any per-buffer choice. Same shape as `PART_START_SEG` in
[selection-oracle.md](selection-oracle.md): the win does not depend on a selector.

## Decode time

The 1,5x decode budget from [decode-time.md](decode-time.md) does not apply here. That
budget compares custom codecs the client has to run itself. In this direction the client
decompresses gzip, Brotli or zstd with the browser's native decompressor, which is the fast
path the direction exists for, and that decompression is the same function for every row.
A size win is judged on the compressed bytes alone.
