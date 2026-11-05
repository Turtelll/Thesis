# Class streams (`CLASS` segmentation, pipeline S3)

> **Verdict: Kept.** -5,86% gzip on the tileset against -4,65% for the same residuals interleaved
> (`building` -8,69% vs -5,01%), with nothing signalled.

## Encoding target
all layers whose predictor has more than one class: building, landcover, landuse, water (Polygon),
transportation (LineString).

## Idea
A predictor produces residuals of different kinds: large jumps to a new run, ordinary edges, near-zero
predicted corners. Interleaved, the outliers break up the runs of similar bytes. Writing each kind in its own
stream gives the compressor homogeneous data. The kind of every vertex follows from the topology, so no
lengths or labels are written.

## Intention
The streams contain the same values as the interleaved layout, so the raw size does not change; the bet is
purely on the compressor. A run of similar bytes compresses better than the same bytes alternating with
outliers: the entropy coder adapts to one distribution at a time, and LZ77 finds longer matches in a stream of
near-zero corners than in one where every fourth value is a jump. This is the general form of
[part-start grouping](part-start-grouping.md), which only separated the jumps.

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

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`, pipeline step S3, `QUAD_CORNER_LINE`):
**tileset -5,86%** with `CLASS` against **-4,65%** `FLAT`; **`building` -8,69%** vs -5,01%.

**Decisive case for inclusion:** +1,21 points on the tileset for zero bytes of signalling, and removing class
streams from the full stack (S8) costs 1,8 points (-6,58% → -4,78%). For a one-class predictor (`DELTA`) it
changes nothing, so it never hurts. The tile oracle picks `CLASS` on 39,8% of buffers holding 63,6% of the
bytes; `FLAT` is still the better choice on the small layers, which is why the
[per-layer default](per-layer-composition.md) picks it per layer.

Supporting figures (same metric):

| row | gzip | Brotli 9 | zstd 19 |
|---|---|---|---|
| `DELTA` `FLAT` / `CLASS` | -3,97% / -3,98% | -5,71% / -5,71% | -5,22% / -5,23% |
| `QUAD_CORNER_LINE` `FLAT` | -4,65% | -6,43% | -6,17% |
| **`QUAD_CORNER_LINE` `CLASS`** | **-5,86%** | **-8,12%** | **-7,34%** |
| `QUAD_CORNER` `CLASS` | -6,07% | -8,30% | -7,60% |

- Old encoding set (segmented split varint): `QUAD_CORNER_VARINT` -0,97% → `_SEG` -2,04% gzip;
  `QUAD_CORNER_SHARED_VARINT_SPLIT` -4,78% → -6,79% Brotli.
- Tile oracle: `CLASS` on 39,8% of buffers, 63,6% of bytes, drop cost +1,197%; `FLAT` is the default of 10 of
  16 layers today.

Source: `research-java/docs/results/openzl-pipeline.md`, `general-purpose-compression.md`, `tile-oracle.md`.
