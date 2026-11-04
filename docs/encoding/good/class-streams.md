# Class streams (`CLASS` segmentation, pipeline S3)

## encoding target:
all layers whose predictor has more than one class: building, landcover, landuse, water (Polygon), transportation (LineString)

## Idea of the encoding
A predictor produces residuals of different kinds: large jumps to a new run, ordinary edges, near-zero predicted
corners. Interleaved, the outliers break up the runs of similar bytes. Writing each kind in its own stream gives
the compressor homogeneous data. The kind of every vertex follows from the topology, so no lengths or labels are
written.

## How the encoding works
`encoder/segmentation/Segmentation.java`:
- The predictor's `classify` assigns each vertex a class (e.g. quad corner: `START`, `INTERIOR`, `CORNER`).
- Residuals (x, y interleaved) are split into one stream per class, written in the predictor's `regionOrder`;
  empty streams are skipped.
- Each stream then goes through map, width and transpose separately.
- Header byte 0 bits 3–4: `FLAT`=0, `CLASS`=1.

```
FLAT:   S I I C S I I C ...
CLASS:  S S ... | I I I I ... | C C ...
```

## How the decoder works
Derive classes from the topology, count values per class → stream lengths; decode each stream; walk the vertices
in order, each taking the next value from its class stream.

## results
Current corpus `munich-plantiler`, S3, per tile vs `DELTA_ZIGZAG_VARINT`:

| row | gzip | Brotli 9 | zstd 19 |
|---|---|---|---|
| `DELTA` `FLAT` / `CLASS` | -3,97% / -3,98% | -5,71% / -5,71% | -5,22% / -5,23% |
| `QUAD_CORNER_LINE` `FLAT` | -4,65% | -6,43% | -6,17% |
| **`QUAD_CORNER_LINE` `CLASS`** | **-5,86%** | **-8,12%** | **-7,34%** |
| `QUAD_CORNER` `CLASS` | -6,07% | -8,30% | -7,60% |

- +1,21 points gzip over `FLAT` (nothing for `DELTA`, one class), mostly `building` (-8,69% vs -5,01%). S8:
  removing class streams costs 1,8 points (-6,58% → -4,78%).
- Old encoding set (segmented split varint): `QUAD_CORNER_VARINT` -0,97% → `_SEG` -2,04% gzip;
  `QUAD_CORNER_SHARED_VARINT_SPLIT` -4,78% → -6,79% Brotli.
- Tile oracle: `CLASS` on 39,8% of buffers, 63,6% of bytes, drop cost +1,197%; `FLAT` is the default of 10 of
  16 layers today (per-layer composition).
