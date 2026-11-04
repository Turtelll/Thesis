# The vertex-buffer pipeline, step by step

How one MLT `VertexBuffer` becomes the bytes a tile carries, and how a decoder gets it back. The code is
`research-java/src/main/java/org/maplibre/mlt/research/encoder/` (one package per stage); the byte-level
format is [research-java/docs/bitstream.md](https://github.com/Turtelll/Thesis/blob/main/research-java/docs/bitstream.md), the measured history of
every stage [research-java/docs/results/openzl-pipeline.md](https://github.com/Turtelll/Thesis/blob/main/research-java/docs/results/openzl-pipeline.md).

## The steps

```
.mbtiles ─► extract ─► VertexBuffer + topology streams
                              │
              choose a Composition (per layer)
                              │
   S1 reorder ─► S2 predict (+ shared references) ─► S3 segment ─► S4 zigzag ─► S6 byte width ─► S7 transpose
                              │
             2-byte header + streams = payload of one buffer
                              │
          S8: all buffers of a tile concatenated, gzip / Brotli / zstd
```

| # | step | what it does | page |
|---|---|---|---|
| 1 | input | `.mbtiles` → one `VertexBuffer` per (tile, layer) plus the topology streams the decoder already has | [01-input.md](01-input.md) |
| 2 | composition | picks the six stage choices for a buffer (per layer default) | [02-composition.md](02-composition.md) |
| 3 | S1 reorder | sorts features (and rotates rings) so consecutive runs are close; not signalled | [03-reorder.md](03-reorder.md) |
| 4 | S2 prediction | replaces each vertex by its residual against a prediction from decoded vertices | [04-prediction.md](04-prediction.md) |
| 5 | S2 shared references | replaces vertices an earlier ring / line part already holds by a reference | [05-shared-references.md](05-shared-references.md) |
| 6 | S3 segmentation | splits the residuals into streams (one per predictor class) | [06-segmentation.md](06-segmentation.md) |
| 7 | S4 residual map | signed → unsigned (zigzag) | [07-residual-map.md](07-residual-map.md) |
| 8 | S6 byte width | unsigned values → bytes (split varint or escape width) | [08-byte-width.md](08-byte-width.md) |
| 9 | S7 transpose | byte planes of a fixed-width stream | [09-transpose.md](09-transpose.md) |
| 10 | S8 backend | per-tile general-purpose compression; how sizes are measured | [10-backend.md](10-backend.md) |

S5 (divide a stream by the GCD of its values) was measured and removed
([../encoding/bad/gcd.md](../encoding/bad/gcd.md)); the later stage numbers were kept.

## Rules every step follows

- **Nothing derivable is written.** The decoder reads the topology streams before the vertex buffer and
  derives from them the runs, the class of every vertex and the value count of every stream. No stream
  length, segment boundary or class label is in the payload (two exceptions: the byte length of a varint
  stream that is not last, and the stream lengths of a buffer with shared references).
- **Lossless.** Every composition round-trips every buffer (`research measure`, `PipelineEncodingTest`,
  `PipelineRoundTripProperties`). Allowed changes are the ones a renderer cannot see: feature order,
  ring start, MultiPoint member order, and (shared line references) line direction.
- **Self-describing.** Every choice except the reorder is in the two header bytes, so a buffer can be
  decoded with nothing but its bytes and the topology.

## Payload of one buffer

```
header (2 bytes)                predictor, segmentation, map, transpose, byte width
[ shared-reference block ]      only for a *_SHARED predictor that took references
stream 0 | stream 1 | ...       non-empty streams, in stream order
```

Detail: [bitstream.md](https://github.com/Turtelll/Thesis/blob/main/research-java/docs/bitstream.md).

## Worked example

One quad building ring, first in its buffer (the previous vertex is the origin), composition: no reorder,
`QUAD_CORNER`, `CLASS`, zigzag, `ESCAPE`, `INTERLEAVED_PLANES`.

```
vertices        p0=(100,200)  p1=(130,200)  p2=(130,240)  p3=(100,240)
S2 class        START         INTERIOR      INTERIOR      CORNER
S2 prediction   prev (0,0)    p0            p1            p0+p2-p1 = (100,240)
S2 residual     (100,200)     (30,0)        (0,40)        (0,0)
S3 streams      START: 100 200 | INTERIOR: 30 0 0 40 | CORNER: 0 0
S4 zigzag       START: 200 400 | INTERIOR: 60 0 0 80 | CORNER: 0 0
S6 + S7         START:    04 C8 FF 91     descriptor (1 B main, escape, 1 B exceptions), 200, marker, 400-255
                INTERIOR: 00 3C 00 00 50  descriptor (1 B), values
                CORNER:   00 00 00
header          49 03
payload         49 03 04 C8 FF 91 00 3C 00 00 50 00 00 00   (14 bytes)
```

The decoder reads the header, derives from the topology that the ring has 4 vertices (classes
`START INTERIOR INTERIOR CORNER`, so 2 + 4 + 2 values), reads the three streams, unzigzags, and walks the
vertices adding each residual to the same prediction.

## Where the pipeline stands

Munich corpus (`munich-plantiler.mbtiles`, 358 tiles, 4.368 buffers), geometry buffers per tile under gzip,
against the smaller baseline `DELTA_ZIGZAG_VARINT` (11.237.124 B):

| row | gzip | vs ref |
|---|---|---|
| `DELTA_ZIGZAG_FASTPFOR` (what MLT writes) | 11.984.744 | +6,65% |
| `DEFAULT` (one composition, no reorder) | 10.450.157 | -7,00% |
| `GEOMETRY_BEST` (`DEFAULT` + Hilbert reorder) | 10.118.776 | -9,95% |
| `forLayer` (per-layer default) | 9.960.430 | -11,36% |
| tile oracle (upper bound, not a pipeline row) | 9.895.841 | -11,94% |

Sources: [tile-oracle.md](https://github.com/Turtelll/Thesis/blob/main/research-java/docs/results/tile-oracle.md),
[openzl-pipeline.md](https://github.com/Turtelll/Thesis/blob/main/research-java/docs/results/openzl-pipeline.md). The Rust port of this pipeline as
the MLT v2 vertex encoder: [mlt-v2-rust-port.md](https://github.com/Turtelll/Thesis/blob/main/research-java/docs/results/mlt-v2-rust-port.md).
