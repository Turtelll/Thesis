# Standard deltas learned per tile — `TILE_STD_DELTA_*`

**Status:** measured, regression. Registered compression-only (`--compress --tile-std-delta`),
class in `encoding/research/failed/TileStandardDelta.java`
**Effect:** every row is worse than `DELTA_ZIGZAG_VARINT` under every compressor. The best,
`TILE_STD_DELTA_VECTOR_K1_SINGLE_VARINT_SPLIT` (at most one entry per delta), is +7,48% gzip /
+4,77% Brotli / +3,98% zstd per tile. Without the cap the best is
`TILE_STD_DELTA_VECTOR_K1_VARINT_SPLIT` at +14,17% / +9,40% / +7,31%, worse than the
fixed-vocabulary `STD_DELTA_CHUNK64_UINT16_PLANES` (+13,33% / +7,52% / +4,06%).
**Corpus:** current baseline (`munich-plantiler.mbtiles`, `data/extractedBaseline`, 358
tiles, 4.368 buffers). Per-tile compression as in
[general-purpose-compression.md](../general-purpose-compression.md): Brotli quality 9, gzip
level 6, zstd level 19.
**Run:** `./gradlew benchmark --args="--compress --tile-std-delta"`, set `core -rans +compress
+tile-std-delta`; the `STD_DELTA_CHUNK64` row is from the same run with `--std-delta` added.

## Idea

[standard-delta-uint16.md](standard-delta-uint16.md) used one fixed global vocabulary of
lengths and lost. Here the vocabulary is learned from the data: per tile, over all its
layers, the K most frequent deltas become the vocabulary, stored once in the tile, and
every layer's deltas are written as sums of those entries plus a remainder, so the layers of
one tile repeat the same tokens for the compressor.

- **Unit.** `SCALAR`: an entry is one signed dx or dy value. `VECTOR`: an entry is one signed
  (dx, dy) pair.
- **Learning.** Deltas as `DELTA_ZIGZAG` (componentwise, continuous over the buffer). The top
  K by count among those with a component of at least 16 (`MIN_LENGTH`), ties by value.
- **Header.** Varint entry count, then the entries zigzag-varint. Charged once per tile.
- **Tokens.** Per delta: while a vocabulary entry shortens the rest (L1), subtract the one
  that shortens it most and emit its index + 1; then a terminator 0. A buffer is all step
  tokens, then all zigzagged remainders, behind a varint word count.
- **`_SINGLE`.** At most one entry per delta: exactly one step token per delta (index + 1, or
  0 for none) and no terminator.
- **Rows.** K in {1, 4, 16}, with and without `_SINGLE`, over `VARINT_SPLIT` and `UINT16_PLANES` (tokens above 16 bits
  escaped as in `STD_DELTA_*`).

## Measured

Per tile, vocabulary header included, every percentage against `DELTA_ZIGZAG_VARINT` under
the same compressor.

| row | raw | order-0 | gzip | brotli9 | zstd19 |
|---|---|---|---|---|---|
| `DELTA_ZIGZAG_VARINT` | 13.270.873 | 11.441.503 | ref | ref | ref |
| `DELTA_ZIGZAG_VARINT_SPLIT` | 13.270.873 | 11.441.503 | -0,87% | **-1,79%** | **-1,73%** |
| `STD_DELTA_CHUNK64_UINT16_PLANES` | 60.544.726 | 21.834.651 | +13,33% | +7,52% | +4,06% |
| `TILE_STD_DELTA_VECTOR_K1_SINGLE_VARINT_SPLIT` | 18.545.514 | 13.173.234 | +7,48% | +4,77% | +3,98% |
| `TILE_STD_DELTA_VECTOR_K1_SINGLE_UINT16_PLANES` | 32.162.364 | 14.646.736 | +10,80% | +6,52% | +4,83% |
| `TILE_STD_DELTA_VECTOR_K4_SINGLE_VARINT_SPLIT` | 18.420.599 | 13.157.942 | +12,24% | +8,71% | +7,52% |
| `TILE_STD_DELTA_SCALAR_K1_SINGLE_VARINT_SPLIT` | 23.833.594 | 14.236.007 | +14,10% | +9,69% | +7,86% |
| `TILE_STD_DELTA_SCALAR_K1_SINGLE_UINT16_PLANES` | 42.880.067 | 15.959.053 | +17,94% | +11,66% | +8,82% |
| `TILE_STD_DELTA_VECTOR_K4_SINGLE_UINT16_PLANES` | 32.164.613 | 15.250.768 | +16,06% | +10,90% | +8,84% |
| `TILE_STD_DELTA_VECTOR_K16_SINGLE_VARINT_SPLIT` | 18.305.226 | 13.211.800 | +15,77% | +12,49% | +10,87% |
| `TILE_STD_DELTA_VECTOR_K16_SINGLE_UINT16_PLANES` | 32.173.770 | 15.586.697 | +20,06% | +15,25% | +12,76% |
| `TILE_STD_DELTA_SCALAR_K4_SINGLE_VARINT_SPLIT` | 23.658.879 | 14.394.301 | +21,91% | +16,34% | +13,74% |
| `TILE_STD_DELTA_SCALAR_K4_SINGLE_UINT16_PLANES` | 42.881.142 | 17.094.244 | +26,38% | +18,88% | +15,35% |
| `TILE_STD_DELTA_SCALAR_K16_SINGLE_VARINT_SPLIT` | 23.549.035 | 14.329.603 | +26,14% | +21,66% | +17,97% |
| `TILE_STD_DELTA_SCALAR_K16_SINGLE_UINT16_PLANES` | 42.885.439 | 16.849.814 | +31,06% | +24,83% | +19,86% |
| `TILE_STD_DELTA_VECTOR_K1_VARINT_SPLIT` | 36.355.929 | 15.748.523 | +14,17% | +9,40% | +7,31% |
| `TILE_STD_DELTA_VECTOR_K1_UINT16_PLANES` | 68.850.595 | 20.755.054 | +19,62% | +12,27% | +9,29% |
| `TILE_STD_DELTA_SCALAR_K1_VARINT_SPLIT` | 67.054.502 | 17.757.212 | +24,45% | +15,70% | +10,30% |
| `TILE_STD_DELTA_SCALAR_K1_UINT16_PLANES` | 131.594.189 | 28.000.750 | +31,33% | +19,00% | +13,28% |
| `TILE_STD_DELTA_VECTOR_K4_VARINT_SPLIT` | 62.277.308 | 23.716.802 | +31,04% | +21,67% | +16,77% |
| `TILE_STD_DELTA_SCALAR_K4_VARINT_SPLIT` | 101.483.653 | 27.162.025 | +42,20% | +29,79% | +17,82% |
| `TILE_STD_DELTA_SCALAR_K4_UINT16_PLANES` | 202.850.565 | 45.670.926 | +47,28% | +29,63% | +19,09% |
| `TILE_STD_DELTA_VECTOR_K4_UINT16_PLANES` | 122.669.618 | 35.062.448 | +37,69% | +24,58% | +19,45% |
| `TILE_STD_DELTA_SCALAR_K16_VARINT_SPLIT` | 81.225.395 | 24.731.313 | +47,32% | +38,80% | +23,80% |
| `TILE_STD_DELTA_SCALAR_K16_UINT16_PLANES` | 162.434.781 | 38.090.552 | +52,23% | +39,55% | +24,91% |
| `TILE_STD_DELTA_VECTOR_K16_VARINT_SPLIT` | 72.837.500 | 30.657.133 | +44,33% | +34,98% | +25,12% |
| `TILE_STD_DELTA_VECTOR_K16_UINT16_PLANES` | 145.376.666 | 44.592.289 | +49,73% | +36,20% | +26,71% |

## What the numbers show

- **More vocabulary is worse.** Under every compressor and both units, with and without
  `_SINGLE`, K1 < K4 < K16. With `_SINGLE` the raw and order-0 sizes barely move with K
  (`VECTOR`: order-0 13.173.234 / 13.157.942 / 13.211.800) while every compressed size grows.
- **The cap roughly halves the loss and does not remove it.** `VECTOR_K1_VARINT_SPLIT` goes
  from +14,17% / +9,40% / +7,31% to +7,48% / +4,77% / +3,98%. Its order-0 bound, 13.173.234,
  is still 15,1% above the baseline's 11.441.503: the step token per delta costs more than
  the shorter remainders save.
- **`VECTOR` beats `SCALAR` at K1 and K4**, and with `_SINGLE` at every K, and `VARINT_SPLIT` beats `UINT16_PLANES` in every
  pair except `SCALAR_K4` under Brotli (+29,79% vs +29,63%).
- **The token stream is 3 to 15 times the baseline's bytes before compression.** One varint
  byte per small token, so `SCALAR_K1_VARINT_SPLIT` at 67.054.502 bytes holds far more
  tokens than the 10.717.858 terminators and 10.717.858 remainders a step-free encoding
  would. A delta much longer than its nearest vocabulary entry is written as a long run of
  that entry's token, the same explosion `CHUNK16` showed with a fixed step. The run output
  has no per-delta step count, so the split between long jumps and ordinary deltas is not
  measured here.
- The compressors remove most of that again (67,1 MB raw to 12,4 MB zstd), and still end
  above the Varint row, as in [standard-delta-uint16.md](standard-delta-uint16.md).

## Not measured

- Using an entry only on an exact match instead of whenever it shortens the delta.
- Any `MIN_LENGTH` other than 16, or a cost-based choice of entries instead of plain frequency.
