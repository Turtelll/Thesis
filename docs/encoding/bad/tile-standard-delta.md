# Standard deltas learned per tile (`TILE_STD_DELTA_*`)

## encoding target:
all layers of a tile together

## Idea of the encoding
Instead of a fixed global token vocabulary (which lost), learn it from the data: per tile, over all layers, the K most
frequent deltas become a vocabulary stored once in the tile, and every delta is written as a sum of vocabulary entries
plus a remainder, so the layers of a tile repeat the same tokens.

## How the encoding works
- Deltas as `DELTA_ZIGZAG` (continuous). Vocabulary: top K by count among deltas with a component ≥ 16
  (`MIN_LENGTH`). Unit `SCALAR` (one dx or dy) or `VECTOR` (a (dx, dy) pair).
- Tile header: varint entry count, entries as zigzag varints.
- Per delta: while an entry shortens the rest (L1), subtract the one that shortens it most, emit index + 1; then
  terminator 0. Buffer = all step tokens, then all zigzagged remainders, behind a varint word count.
- `_SINGLE`: at most one entry per delta, exactly one step token (index + 1 or 0), no terminator.
- K ∈ {1, 4, 16}, over `VARINT_SPLIT` or `UINT16_PLANES`.

## How the decoder works
Read the tile's vocabulary; per buffer read the step tokens and remainders; each delta = sum of the referenced entries
+ remainder; integrate.

## results
Current corpus `munich-plantiler`, per tile vs `DELTA_ZIGZAG_VARINT`:

| row | gzip | Brotli 9 | zstd 19 |
|---|---|---|---|
| `VECTOR_K1_SINGLE_VARINT_SPLIT` (best) | +7,48% | +4,77% | +3,98% |
| `VECTOR_K4_SINGLE_VARINT_SPLIT` | +12,24% | +8,71% | +7,52% |
| `VECTOR_K1_VARINT_SPLIT` (uncapped) | +14,17% | +9,40% | +7,31% |
| `SCALAR_K16_UINT16_PLANES` | +52,23% | +39,55% | +24,91% |

More vocabulary is worse (K1 < K4 < K16); the step token per delta costs more than the shorter remainders save (order-0
still 15,1% above baseline). Token stream 3–15× the baseline bytes raw. Regression.
