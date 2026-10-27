# Standardized-length deltas over a fixed Uint16 layout — `STD_DELTA_*`

> **Consolidation note.** The Gradle task(s) benchmark named below were removed from the build (the code is at git tag `pre-consolidation`); `research measure` and the other `research` commands replace the rest. The numbers below are unchanged history.

**Status:** measured, regression. Registered compression-only (`--compress --std-delta`),
class in `encoding/research/failed/StandardDelta.java`
**Effect:** no row beats `DELTA_ZIGZAG_VARINT_SPLIT` under any compressor. The best,
`STD_DELTA_PLAIN_UINT16_PLANES`, is +2,05% gzip / -0,21% Brotli / -1,08% zstd per tile
against `DELTA_ZIGZAG_VARINT`, i.e. +2,95% / +1,61% / +0,67% against `VARINT_SPLIT` on the
same transform. Every real decomposition (`CHUNK`, `QUOTREM`, `POW2`, `SNAP`) is worse than
`PLAIN`: from +2,29% (`QUOTREM16_UINT16_PLANES`) to +31,84% (`SNAP_UINT16`) under zstd.
**Corpus:** current baseline (`munich-plantiler.mbtiles`, `data/extractedBaseline`, 358
tiles, 4.368 buffers). Per-tile compression as in
[general-purpose-compression.md](../general-purpose-compression.md): Brotli quality 9, gzip
level 6, zstd level 19.
**Run:** `./gradlew benchmark --args="--compress --std-delta"`, set `core -rans +compress
+std-delta`.

## Idea

Make the vertex data homogeneous for gzip, Brotli and zstd: one fixed encoding on every layer,
componentwise delta (continuous over the buffer, as `DELTA_ZIGZAG`), with every delta
written as a short sequence of tokens from one global vocabulary of standardized lengths, each
token one fixed 16-bit word. Because the vocabulary is the same for buildings, roads and POIs,
a tile, which is all of its layers concatenated and compressed as one response, should repeat the same 2-byte symbols
across layers, and LZ77 and Huffman should catch that.

Decompositions swept (every token zigzagged unless noted, `S` a fixed global step):

| name | a delta `d` becomes | tokens per coordinate |
|---|---|---|
| `PLAIN` | `zigzag(d)` — `DELTA_ZIGZAG` on a 16-bit word | 1 |
| `CHUNK_S` (16, 64, 256) | `+-S` while `|rest| >= S`, then the remainder (`|r| < S`); self-delimiting | 9,09 / 2,82 / 1,37 |
| `QUOTREM_S` (16, 64, 256) | `q = floor(d / S)` stream, then the `r = d mod S` stream | 2 |
| `POW2` | the set bits of `|d|` as signed exponents `+-(k+1)`, high to low, then terminator 0 | 3,79 |
| `SNAP` | index of the largest `T_i <= |d|` in `{0,1,2,3,4,6,8,12,16,...}` (signed), then the correction `|d| - T_i` | 2 |

Two serializers: `UINT16` (interleaved little-endian words) and `UINT16_PLANES` (all low
bytes, then all high bytes). A token that does not fit a 16-bit word is escaped (`0xFFFF`
plus two words); no buffer of this corpus needed one, so `PLAIN` is byte-identical to
`DELTA_ZIGZAG` over the same serializer (checked in `StandardDeltaMechanismTest`) and its raw
size is exactly 2 bytes x 10.717.858 values.

## Measured

Per tile, every percentage against `DELTA_ZIGZAG_VARINT` under the same compressor.

| row | raw | order-0 | gzip | brotli9 | zstd19 |
|---|---|---|---|---|---|
| `DELTA_ZIGZAG_VARINT` | 13.270.873 | 11.441.503 | ref | ref | ref |
| `DELTA_ZIGZAG_VARINT_SPLIT` | 13.270.873 | 11.441.503 | -0,87% | **-1,79%** | **-1,73%** |
| `STD_DELTA_PLAIN_UINT16_PLANES` | 21.435.716 | 12.980.320 | +2,05% | -0,21% | -1,08% |
| `STD_DELTA_QUOTREM16_UINT16_PLANES` | 42.871.432 | 15.283.607 | +13,39% | +6,44% | +2,29% |
| `STD_DELTA_QUOTREM64_UINT16_PLANES` | 42.871.432 | 15.968.662 | +9,30% | +4,91% | +3,49% |
| `STD_DELTA_CHUNK64_UINT16_PLANES` | 60.544.726 | 21.834.651 | +13,33% | +7,52% | +4,06% |
| `STD_DELTA_QUOTREM256_UINT16_PLANES` | 42.871.432 | 16.720.980 | +13,35% | +8,60% | +6,68% |
| `STD_DELTA_CHUNK256_UINT16_PLANES` | 29.352.128 | 15.932.582 | +16,22% | +14,31% | +10,99% |
| `STD_DELTA_CHUNK16_UINT16_PLANES` | 194.751.506 | 46.767.861 | +35,89% | +22,88% | +14,71% |
| `STD_DELTA_SNAP_UINT16_PLANES` | 42.871.432 | 17.185.695 | +23,60% | +17,99% | +17,12% |
| `STD_DELTA_POW2_UINT16_PLANES` | 81.267.774 | 24.859.891 | +34,61% | +27,67% | +20,58% |
| `STD_DELTA_PLAIN_UINT16` | 21.435.716 | 12.980.320 | +17,69% | +12,81% | +10,75% |

The interleaved `UINT16` rows of the other decompositions are worse again, at +28% to +59%
gzip and +17% to +32% zstd; the full table is in the run output. Byte planes beat
interleaved words for every decomposition and compressor, by 5 to 30 points, for the same reason
`BYTESPLIT` beats `RAW`: the high plane of small values is a zero run.

## Why it does not pay

**LZ77 does find the repetition, and it finds exactly what was added.** `order-0` minus the
compressed size is what matching recovered. On `DELTA_ZIGZAG_VARINT` that is 1,8% gzip /
2,6% Brotli / 2,1% zstd. On `PLAIN_UINT16_PLANES` it is 11,7% / 14,4% / 14,6%, and on
`CHUNK64_UINT16_PLANES` 45% / 47% (Brotli / zstd). The decompositions do create long runs of
identical words, and the compressors match them. But the runs are made of the tokens the
decomposition itself inserted: the `+-S` repeats, the 0 terminators, and the high byte of
every word. A compressor can at best remove this redundancy again. It cannot get below the
information in the deltas, and every row ends above the Varint row it started from.

**The vocabulary was already shared.** `zigzag` + Varint is already one fixed, global code:
a delta of 3 is the byte `0x06` in every layer of every tile. What differs between layers is
the *distribution* of deltas (buildings: short axis-aligned edges; roads: longer smooth
steps; POIs: one large jump per point), not the code that writes them. A fixed token
vocabulary does not change the distribution, so it does not make the layers more alike to
a compressor. To share more across layers, the deltas themselves would have to repeat, as
they do with the shared-vertex references
([shared-vertices.md](../shared-vertices.md)) and the predictors.

**A wide fixed word costs more than LZ77 wins back.** Most residuals fit in one byte
(5,82 mean bits on `DELTA_ZIGZAG`). Varint writes them as one byte, and Uint16 always writes
two. Under Brotli and zstd, `PLAIN_UINT16_PLANES` nearly closes that gap, at -0,21% and
-1,08%, because the high plane compresses to almost nothing. It still ends behind
`VARINT_SPLIT`, which keeps the dominant one-byte values contiguous without spending a
second byte on them. Under gzip, whose 32 KiB window and Huffman blocks deal with the
two-plane layout less well, it is +2,05%.

**Decompositions trade one token for several, and each token carries entropy.** `QUOTREM`
and `SNAP` split one value into two streams whose sum of entropies is at least the
original's. `CHUNK` and `POW2` multiply the token count (1,37x to 9,09x) and pay a
termination symbol per delta. For `CHUNK`, the large-`S` and small-`S` failures mirror each
other: `CHUNK256` rarely splits anything (1,37 tokens per coordinate) but leaves a
remainder token with almost the full delta's entropy beside the continuation symbol, while
`CHUNK16` turns every part-start jump into hundreds of words.

## Takeaway

Making the token alphabet uniform is not the lever. A general-purpose compressor already sees
a uniform, layer-independent code in `DELTA_ZIGZAG_VARINT`. What it rewards is a lower-entropy
delta distribution (predictors, shared references) and a byte layout that keeps the common
short values contiguous (`VARINT_SPLIT`, segmentation). If fixed-width words are wanted
anyway, use `UINT16_PLANES`, never interleaved `UINT16`.
