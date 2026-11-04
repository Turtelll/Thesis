# Per-buffer selection by trial compression (`Selector`, `--step selector`, per-buffer oracle)

## encoding target:
all layers; choice of composition per (tile, layer) buffer

## Idea of the encoding
No single composition is best on every buffer. Encode each buffer with every candidate, compress each alone with
the target backend, keep the smallest. The choice lives in the two header bytes, so signalling costs nothing extra.

## How the encoding works
- Pipeline `Selector`: 24 candidates per buffer (predictor × class streams on/off × {escape + planes, escape,
  split varint}), each compressed alone under gzip / Brotli / zstd; the smallest wins. With and without reorder.
- Old encoding set: per-buffer selection oracle over all registered encodings (FastPFOR era), signalling not
  charged.
- Oracle variant: the full 48-composition grid (reorder × predictor × class × terminal), smallest alone per buffer.

## How the decoder works
Reads the header of each buffer and decodes with the named predictor and stages; needs a decoder for every
candidate.

## results
Current corpus `munich-plantiler`, per tile vs `DELTA_ZIGZAG_VARINT` gzip:

| row | gzip | Brotli 11 | zstd 19 |
|---|---|---|---|
| `DEFAULT`, per tile | -3,63% | -9,53% | -5,24% |
| `SELECT_<backend>`, per tile | -3,52% | -9,30% | -5,24% |
| `SELECT`, buffers isolated, vs `DEFAULT` isolated | -0,41% | -0,65% | -0,32% |

- Saves 0,3–0,65% when buffers are compressed alone, but is **larger per tile** (+0,12% gzip, +0,25% Brotli): a tile
  compresses the concatenation, and buffers in different formats share fewer matches.
- 48-composition oracle: per tile +0,24% / +0,54% / +0,26% over the best single composition; alone -0,66% / -1,16% /
  -0,69%.
- Retired corpus `munich.mbtiles`, FastPFOR: perfect selector over 8 encodings -0,7% vs always baseline, research
  encodings only 0,14% of it; one byte of signalling per stream would cost ~8,4 KB against 18,6 KB saved.
- Discarded. Choosing on the tile (coordinate descent, the tile oracle)
  finds -0,65% over `forLayer` as a free upper bound, not built.
