# Canonical ring start (`QUAD_CORNER_SHARED_RING_CANONICAL`, `RING_CANONICAL_THEN_FEATURE`)

## encoding target:
building (Polygon)

## Idea of the encoding
A ring's start vertex is arbitrary. Rotating every ring to a canonical start makes repeated building shapes
look identical in the stream, and if features are chained afterwards, the chain follows the start vertices
that are actually emitted.

## How the encoding works
- Every polygon ring is cyclically rotated (winding kept) to a canonical start vertex; not transmitted.
- `RING_CANONICAL_THEN_FEATURE`: rotate every ring, then nearest-endpoint feature chaining over the rotated
  starts.
- Encoded with `QUAD_CORNER_SHARED` over segmented split varints.

## How the decoder works
No change: rotated rings and the chained feature order are emitted as stored (ring rotation and feature order
are geometry-equivalent); the shared ring decoder runs unchanged.

## results
Current corpus `munich-plantiler`, per tile, smallest of gzip/Brotli/zstd vs `DELTA_ZIGZAG_VARINT`:

| row | whole tileset | building |
|---|---|---|
| `QUAD_CORNER_SHARED` | -7,86% | |
| `QUAD_CORNER_SHARED_RING_CANONICAL` | -7,99% | -13,62% |
| `QUAD_CORNER_SHARED_REORDER` | -9,39% | -13,36% |
| **`QUAD_CORNER_SHARED_RING_CANONICAL_REORDER`** (rotate, then chain) | **-9,50%** | **-13,79%** |
| `..._REORDER_RING_CANONICAL` (chain, then rotate) | -9,44% | -13,79% |

- On plain interleaved Varint the canonical start was only -0,12% gzip; it needs the shared references.
- With the frame: `QUAD_CORNER_SHARED_FRAME_RING_CANONICAL_REORDER` -8,19% gzip / -15,21% Brotli / -9,92%
  zstd, the best row of the old encoding set.
- **Retired** in the consolidation (git tag `pre-consolidation`); the pipeline's ring rotation is the `_ROT`
  flag of the Hilbert reorder (Hilbert reorder).
