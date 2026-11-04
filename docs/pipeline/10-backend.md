# 10. S8 Backend compression

## What it does
The last stage is not part of the payload: a tile is delivered under a general-purpose compressor (gzip,
Brotli or zstd) that a browser decodes natively. Every earlier stage is built to make the bytes easier for
that compressor, not smaller on their own.

## How it works
`research-java/.../measurement/compression/` (`Compressor`, `TileCompression`):

1. Every buffer of one tile is encoded (steps 3 to 9).
2. The buffers of the tile are concatenated (one tile is one HTTP response) and compressed once, one-shot.
3. Sizes are summed over tiles.

Backends: gzip, Brotli (quality 11; some step runs used 9), zstd 19. The reference for every percentage is
the smaller baseline `DELTA_ZIGZAG_VARINT` under gzip, also for the Brotli and zstd columns.

```
./gradlew research --args="measure --step ladder --by-zoom"   # the pipeline built up stage by stage
./gradlew research --args="measure --step layer --by-zoom"    # forLayer vs DEFAULT
```

## How the decoder works
Decompress the tile, then decode each buffer from its header (steps 9 back to 3).

## Why
- **Per tile, not per buffer.** Buffers compressed together share the compressor's window; that is why
  per-buffer selection loses on the delivered tile (step 2) and why every search is done on the tile.
- **One configuration for all backends.** No stage decision flips with the backend; the backend only moves
  the level (S8, `GEOMETRY_BEST` at the time: gzip -6,58%, Brotli 11 -11,72%, zstd 19 -7,99%).
- **Not a bit-packed codec.** FastPFOR (what MLT writes) is opaque to the backends: +6,65% per tile under
  gzip against varint. rANS was retired for the same target
  ([general-purpose-compression.md](https://github.com/Turtelll/Thesis/blob/main/research-java/docs/results/general-purpose-compression.md),
  [../encoding/bad/fastpfor-under-compression.md](../encoding/bad/fastpfor-under-compression.md)).

More: [openzl-pipeline.md#s8-backend](https://github.com/Turtelll/Thesis/blob/main/research-java/docs/results/openzl-pipeline.md#s8-backend),
[evaluation-protocol.md](https://github.com/Turtelll/Thesis/blob/main/research-java/docs/evaluation-protocol.md).
