# 10. Backend compression and measurement (S8)

## The idea

A tile is not delivered as raw bytes. It travels compressed with gzip, Brotli or zstd, which every browser
decodes natively. The pipeline does not try to replace that compressor; every earlier stage exists to hand
it data it can compress well: small values, uniform bytes, homogeneous streams, long runs of zeros.

This shapes every decision. The pipeline's payload is often larger than stock MLT before compression and
clearly smaller after it. In the MLT v2 measurement, the tile-oracle tiles are 1,2% larger raw and 9,3%
smaller under gzip. A stage is kept only if it makes the compressed tile smaller.

## How sizes are measured

1. Every buffer of a tile is encoded.
2. The buffers of the tile are concatenated, since one tile is one HTTP response, and compressed once.
3. The compressed sizes are summed over all tiles of the corpus.

gzip at level 6 is the reference, because every client supports it. Brotli (quality 11) and zstd
(level 19) are reported alongside. Two measurements are used throughout these pages:

- The research harness (Java) measures the vertex buffers only, against the smaller of the two stock
  baselines under gzip, plain delta + zigzag + varint (11.237.124 bytes on the corpus). Stage figures on
  these pages are of this kind.
- The MLT v2 encoder (Rust) measures whole tiles, every layer with its property columns, against stock MLT
  v2. The headline table in the [overview](README.md) is of this kind.

```
./gradlew research --args="measure --step ladder --by-zoom"   # the pipeline built up decision by decision
./gradlew research --args="measure --step layer --by-zoom"    # per-layer default against DEFAULT
mlt-oracle compare --mbtiles ../data/MVT/munich-plantiler.mbtiles --by-zoom --by-layer   # whole tiles against stock MLT v2
```

## Decoding

Decompress the tile, then decode each buffer from its header, running the stages in reverse.

## Why the design follows the compressor

- Per tile, not per buffer. Buffers compressed together share the compressor's window, so a buffer that
  looks like its neighbours compresses better. That is why choosing the best composition for each buffer
  on its own loses on the delivered tile, and why every search is done on whole tiles
  ([compositions](02-composition.md)).
- One configuration for all compressors. No stage decision flipped between gzip, Brotli and zstd; the
  compressor only moves the level (on the geometry buffers, gzip -9,95%, Brotli -15,97%, zstd -11,68% for
  the best single composition).
- No bit-packing. Stock MLT can write vertices with FastPFOR, a bit-packed integer codec that is small raw
  but opaque to a byte-oriented compressor: it is 6,65% larger per tile under gzip than plain varint. On
  whole MLT v2 tiles, simply writing every vertex buffer as plain delta + split varint is already 3,48%
  smaller than stock, about a third of the pipeline's total gain. The likely cause is that stock MLT picks
  its integer codec by raw size, which is not the smaller one after gzip. An entropy coder (rANS) inside the
  payload was tried and retired for the same reason.

Further reading: [general-purpose compression](https://github.com/Turtelll/Thesis/blob/main/research-java/docs/results/general-purpose-compression.md),
[evaluation protocol](https://github.com/Turtelll/Thesis/blob/main/research-java/docs/evaluation-protocol.md),
[S8 results](https://github.com/Turtelll/Thesis/blob/main/research-java/docs/results/openzl-pipeline.md#s8-backend).
