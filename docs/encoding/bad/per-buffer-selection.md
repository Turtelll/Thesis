# Per-buffer selection by trial compression

**Code names:** `Selector`, `--step selector`, per-buffer oracle

> **Verdict: Rejected.** Choosing the smallest composition per buffer makes the tiles *larger*: -3,52% gzip
> against -3,63% for always using `DEFAULT`.

## Encoding target
all layers; choice of composition per (tile, layer) buffer.

## Idea
No single composition is best on every buffer. Encode each buffer with every candidate, compress each alone
with the target backend, keep the smallest. The choice lives in the two header bytes, so signalling costs
nothing extra.

## Intention
Every predictor page shows wins on some buffers and losses on others. If the encoder simply tries them all,
it should get the best of every predictor, and since the header names the composition anyway, the choice is
free on the wire.

## How the encoding works
- Pipeline `Selector`: 24 candidates per buffer (predictor × class streams on/off × {escape + planes, escape,
  split varint}), each compressed alone under gzip / Brotli / zstd; the smallest wins. With and without reorder.
- Old encoding set: per-buffer selection oracle over all registered encodings (FastPFOR era), signalling not
  charged.
- Oracle variant: the full 48-composition grid (reorder × predictor × class × terminal), smallest alone per buffer.

## How the decoder works
Reads the header of each buffer and decodes with the named predictor and stages; needs a decoder for every
candidate.

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`): **`SELECT_<backend>` -3,52%** against
**`DEFAULT` -3,63%** (tileset).

**Decisive case against:** the selector optimises the wrong unit. Each buffer is smaller when compressed
alone (-0,41% gzip), but a tile is compressed as the concatenation of its buffers, and buffers in different
formats share fewer matches, so the tile grows (+0,12% gzip, +0,25% Brotli). Choosing must be done on the
whole tile; that is the tile oracle, which finds -0,65% over the [per-layer default](../good/per-layer-composition.md)
as a free upper bound, so a fixed composition per layer was kept instead.

Supporting figures (same metric):

| row | gzip | Brotli 11 | zstd 19 |
|---|---|---|---|
| `DEFAULT`, per tile | -3,63% | -9,53% | -5,24% |
| `SELECT_<backend>`, per tile | -3,52% | -9,30% | -5,24% |
| `SELECT`, buffers isolated, vs `DEFAULT` isolated | -0,41% | -0,65% | -0,32% |

- Saves 0,3–0,65% when buffers are compressed alone, but is larger per tile.
- 48-composition oracle: per tile +0,24% / +0,54% / +0,26% over the best single composition; alone -0,66% /
  -1,16% / -0,69%.
- *Not under the headline metric* — retired corpus `munich.mbtiles`, FastPFOR: perfect selector over 8
  encodings -0,7% vs always baseline, research encodings only 0,14% of it; one byte of signalling per stream
  would cost ~8,4 KB against 18,6 KB saved.

Source: `research-java/docs/results/openzl-pipeline.md`, `selection-oracle.md`, `tile-oracle.md`.
