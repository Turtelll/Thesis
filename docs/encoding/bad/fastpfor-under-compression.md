# FastPFOR as delivery terminal (`*_FASTPFOR`)

## encoding target:
all layers; integer codec of the vertex buffer (what MLT writes today)

## Idea of the encoding
FastPFOR bit-packs blocks of integers at the width of their widest value (with patched exceptions), so it is
smaller than varint before any compression. Most predictor ideas up to the general-purpose-compression switch
were measured over it, with residual populations separated into regions so each block is packed at its own width.

## How the encoding works
- Zigzag residuals are packed with `Composition(FastPFOR, VariableByte)`: full 256-value blocks (128 with
  `FASTPFOR128`) bit-packed at the block's width with exceptions, the last < block-size values as VariableByte.
- Region-separating encodings (part-start grouping, quad corner `_SEG`, rot90) put each class in a contiguous
  region so no narrow population shares a block with wide values.

## How the decoder works
Unpack each block at its width, patch the exceptions, VariableByte-decode the tail, unzigzag, then split into
regions and undo the prediction.

## results
Current corpus `munich-plantiler`, per tile vs `DELTA_ZIGZAG_VARINT`:
- `DELTA_ZIGZAG_FASTPFOR` raw 12.411.712 vs 13.270.873 B (smaller), but **+6,65% gzip / +7,42% Brotli / +6,68%
  zstd** once compressed; in the pipeline S8 table +6,65% / +5,26% / +6,34%.
- FastPFOR compresses almost identically per tile or corpus-once: nothing left for a compressor.
- Region separation is a FastPFOR-only lever on its own (byte-identical over plain varint), but segmentation
  turned out to pay under compressors too (class streams).
- **Retired** as research direction: the browser decodes gzip/Brotli/zstd natively, and the target is compressed
  bytes per tile against the smaller baseline. FastPFOR stays as a baseline row and for decode-time comparison.
