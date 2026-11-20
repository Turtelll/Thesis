# Per-layer composition

**Code names:** `for_layer` (`research-rust/mlt-pipeline/src/registry.rs`)

> **Verdict: Kept.** -5,66% gzip on whole MLT v2 tiles against stock, with no search in the encoder and
> nothing signalled beyond the 2-byte header.

## Encoding target
every MVT layer gets its own fixed composition (Polygon layers: shared ring frame; line layers: shared line
continuation; poi, aerodrome_label: delta); `DEFAULT` for unknown layers.

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
- `for_layer(layer)` maps an MVT layer name to a `Composition` (reorder, predictor, segmentation, terminal).
  All zigzag, escape width and byte planes; reorder `HILBERT_CENTROID_ROT_MP` unless `CHAIN` is noted:

  | layer | predictor | streams |
  |---|---|---|
  | building, landcover, landuse | `QUAD_CORNER_SHARED_RAW_PREVFRAME_FIRST_ONLY` | `CLASS` |
  | water (`CHAIN`) | `QUAD_CORNER_SHARED_RAW_PREVFRAME_FIRST_ONLY` | `CLASS` |
  | aeroway (`CHAIN`) | `QUAD_CORNER_SHARED_RAW_PREVFRAME_FIRST_ONLY` | `FLAT` |
  | park | `QUAD_CORNER_SHARED_RAW` | `FLAT` |
  | place | `QUAD_CORNER_PREVFRAME_FIRST_ONLY` | `FLAT` |
  | transportation (`CHAIN`) | `LINE_CONTINUATION_SHARED` | `CLASS` |
  | transportation_name, waterway (`CHAIN`) | `LINE_CONTINUATION_SHARED` | `FLAT` |
  | boundary | `LINE_CONTINUATION_SHARED` | `FLAT` |
  | mountain_peak, water_name, housenumber | `LINE_CONTINUATION` | `FLAT` |
  | poi | `DELTA` | `CLASS` |
  | aerodrome_label | `DELTA` | `FLAT` |

  `DEFAULT` (no reorder, `QUAD_CORNER_SHARED_RAW_PREVFRAME_FIRST_ONLY`, class streams) is the fallback.
- The table was fitted on the geometry buffers alone (buffers compressed alone under gzip) over an earlier,
  larger grid; `transportation` on geometry plus property columns. It is not re-fitted for the 42-composition
  grid: where the fit chose an option the grid no longer has, the table holds its nearest kept one.
- Every buffer is encoded with its layer's composition; the payload header records the stage choices.

## How the decoder works
Read the two header bytes (predictor, segmentation, map, transpose, width) and decode with them; the reorder is
taken from the layer's composition (nothing to undo).

## Results
**Headline** (whole MLT v2 tiles, `munich-plantiler`, 358 tiles, gzip level 6, `mlt-oracle compare`):
**15.836.613 B, -5,66%** against stock MLT v2 (16.786.524 B; `mlt convert --vertex-pipeline default`).

Supporting figures (same metric):

- The tile oracle, which searches the 42 compositions per (tile, layer) on the whole tile, is 3,26% smaller
  (15.319.927 B, same stream choice as the default).
- Per layer (each layer compressed alone): `building` -18,53%, `landcover` -11,10%, `transportation` -7,42%;
  `poi` +12,37%, `aeroway` +3,01%, `housenumber` +1,87% above stock. The reorders were fitted on the geometry
  alone and do not see what they cost the property columns.

Source: `research-java/docs/results/mlt-v2-rust-port.md`.
