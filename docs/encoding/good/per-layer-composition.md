# Per-layer composition

**Code names:** `PipelineRegistry.forLayer` / `LayerCompositions`

> **Verdict: Kept.** -11,36% gzip on the tileset, the result of the thesis pipeline; within 0,65% of a free
> per-(tile, layer) oracle, with nothing signalled beyond the 2-byte header.

## Encoding target
every MVT layer gets its own fixed composition (Polygon layers: shared frame; line layers: shared heading
frame; poi, aerodrome_label: delta); `DEFAULT` for unknown layers.

## Idea
The best predictor follows the geometry of the layer: corner predictors for buildings, continuation for roads,
plain delta for points. Instead of one composition for everything, fix one composition per layer. The decoder
knows the layer name, so the choice costs nothing on the wire (the two header bytes name it anyway).

## Intention
Every predictor on the other pages wins on some layers and loses on others; a single composition for the whole
tile has to compromise. Choosing per buffer would be optimal but needs a search in the encoder. The layer is a
cheap, stable proxy for the geometry, so a fixed table fitted once should capture most of the per-buffer gain
with a trivial encoder.

## How the encoding works
- `LayerCompositions.forLayer(layer)` maps an MVT layer name to a `Composition` (reorder, predictor, segmentation,
  map, width, transpose), fitted as the per-layer winner of the oracle grid under gzip.
- Today (`LayerCompositions.java`; all zigzag; reorder `HILBERT_CENTROID_ROT_MP` unless `CHAIN` is noted):

  | layer | predictor | streams | terminal |
  |---|---|---|---|
  | building, landcover, landuse | `QUAD_CORNER_SHARED_FRAME` | `CLASS` | escape + planes |
  | water (`CHAIN`) | `QUAD_CORNER_SHARED_FRAME` | `CLASS` | escape + planes |
  | aeroway (`CHAIN`) | `QUAD_CORNER_SHARED_FRAME` | `FLAT` | split varint |
  | park | `QUAD_CORNER_SHARED` | `FLAT` | split varint |
  | place | `QUAD_CORNER_FRAME` | `FLAT` | split varint |
  | transportation (`CHAIN`) | `LINE_CONTINUATION_SHARED_FRAME_HEADING` | `CLASS` | escape + planes |
  | transportation_name, waterway (`CHAIN`) | `LINE_CONTINUATION_SHARED_FRAME_HEADING` | `FLAT` | split varint |
  | boundary | `LINE_CONTINUATION_SHARED_FRAME_HEADING` | `FLAT` | split varint |
  | mountain_peak | `LINE_CONTINUATION_FRAME_HEADING` | `FLAT` | split varint |
  | water_name | `LINE_CONTINUATION` | `FLAT` | split varint |
  | housenumber | `LINE_CONTINUATION` | `FLAT` | escape + planes |
  | poi | `DELTA` | `CLASS` | escape + planes |
  | aerodrome_label | `DELTA` | `FLAT` | split varint |

  `DEFAULT` (no reorder, `QUAD_CORNER_SHARED_FRAME`, class streams, zigzag, escape, planes) is the fallback.
- Fitted per layer with `measure --step fit` (geometry only, buffers compressed alone under gzip); `transportation`
  fitted on geometry plus property columns; `CHAIN` where its property columns are no larger than Hilbert's net of the
  geometry gain.
- Every buffer is encoded with its layer's composition; the payload header records the stage choices.

## How the decoder works
Read the two header bytes (predictor, segmentation, map, transpose, width) and decode with them; the reorder is
taken from the layer's composition (nothing to undo).

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`, geometry only): **tileset -11,36%**
(9.960.430 B), against -9,95% for the best single composition for all layers.

**Decisive case for inclusion:** 1,4 points better than any single composition, and only 0,65% short of the
tile oracle (-11,94%), which picks the best composition per (tile, layer) for free. The best fixed composition
per layer on the grid equals `forLayer` on 14 of 16 layers (over all -0,10%). It needs no search in the encoder and no extra
signalling.

Supporting figures (same metric):

- Today: 9.960.430 B gzip (-11,36%), 9.306.400 B Brotli (-17,18%), 9.773.069 B zstd (-13,03%).
- First fit (gated reorder): -3,79% vs `DEFAULT` -3,63% gzip; with the frame on `building` -7,36% / -13,55% /
  -9,16%; plus `landcover`, `landuse`, `aeroway` -7,45% / -13,66% / -9,29%; with line heading frame -8,64% gzip.
- `poi` and `housenumber` stay above the plain varint baseline under gzip (+3,28%, +3,75% at the first fit).
- Whole-tile size (with property cost of the reorders) not measured per layer.

Source: `research-java/docs/results/openzl-pipeline.md`, `tile-oracle.md`.
