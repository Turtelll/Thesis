# Per-layer composition (`PipelineRegistry.forLayer` / `LayerCompositions`)

## encoding target:
every MVT layer gets its own fixed composition (Polygon layers: shared frame; line layers: shared heading frame; poi, aerodrome_label: delta); `DEFAULT` for unknown layers

## Idea of the encoding
The best predictor follows the geometry of the layer: corner predictors for buildings, continuation for roads,
plain delta for points. Instead of one composition for everything, fix one composition per layer. The decoder
knows the layer name, so the choice costs nothing on the wire (the two header bytes name it anyway).

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

## results
Current corpus `munich-plantiler`, whole corpus, per tile vs `DELTA_ZIGZAG_VARINT` gzip:
- **Today: 9.960.430 B gzip (-11,36%), 9.306.400 B Brotli (-17,18%), 9.773.069 B zstd (-13,03%).**
- First fit (gated reorder): -3,79% vs `DEFAULT` -3,63% gzip; with the frame on `building` -7,36% / -13,55% /
  -9,16%; plus `landcover`, `landuse`, `aeroway` -7,45% / -13,66% / -9,29%; with line heading frame -8,64% gzip.
- Best single composition for all layers: -9,95%; the free per-(tile, layer) tile oracle: -11,94% (0,65% under
  `forLayer`). The best fixed composition per layer on the grid equals `forLayer` on 14 of 16 layers (over all
  -0,10%).
- `poi` and `housenumber` stay above the plain varint baseline under gzip (+3,28%, +3,75% at the first fit).
- Whole-tile size (with property cost of the reorders) not measured per layer.
