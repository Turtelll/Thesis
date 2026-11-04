# 2. Composition: which choices a buffer is encoded with

## What it does
A `Composition` is one choice per stage. Before encoding, each buffer is given one; the stages then run
with it.

```java
record Composition(Reorder reorder, Prediction predictor, Segmentation segmentation,
                   ResidualMap map, ByteWidth width, Transpose transpose)
```

`encoder/format/Composition.java`. Its name is derived from the choices (`PIPE_<reorder>_<predictor>_
<segmentation>_<map>_<width>_<transpose>`), so a row's name cannot drift from what it does.
`PipelineEncoding` is the `Encoding` that runs one composition.

## How a composition is chosen
`registry/`:

- **`PipelineRegistry.DEFAULT`**: one composition for every buffer, the end of the step-by-step build-up
  (`--step ladder`): no reorder, `QUAD_CORNER_SHARED_FRAME`, `CLASS`, `ZIGZAG`, `ESCAPE`,
  `INTERLEAVED_PLANES`. `GEOMETRY_BEST` is the same with the Hilbert reorder.
- **`LayerCompositions.forLayer(layer)`**: the pipeline's default, one fixed composition per MVT layer,
  `DEFAULT` for an unknown layer. Fitted with `measure --step fit` (per layer the smallest total under gzip).
  The decoder knows the layer, so the choice costs nothing on the wire.

| layer | reorder | predictor | streams | terminal |
|---|---|---|---|---|
| building, landcover, landuse | Hilbert | `QUAD_CORNER_SHARED_FRAME` | `CLASS` | escape + planes |
| water | chain | `QUAD_CORNER_SHARED_FRAME` | `CLASS` | escape + planes |
| aeroway | chain | `QUAD_CORNER_SHARED_FRAME` | `FLAT` | split varint |
| park | Hilbert | `QUAD_CORNER_SHARED` | `FLAT` | split varint |
| place | Hilbert | `QUAD_CORNER_FRAME` | `FLAT` | split varint |
| transportation | chain | `LINE_CONTINUATION_SHARED_FRAME_HEADING` | `CLASS` | escape + planes |
| transportation_name, waterway | chain | `LINE_CONTINUATION_SHARED_FRAME_HEADING` | `FLAT` | split varint |
| boundary | Hilbert | `LINE_CONTINUATION_SHARED_FRAME_HEADING` | `FLAT` | split varint |
| mountain_peak | Hilbert | `LINE_CONTINUATION_FRAME_HEADING` | `FLAT` | split varint |
| water_name | Hilbert | `LINE_CONTINUATION` | `FLAT` | split varint |
| housenumber | Hilbert | `LINE_CONTINUATION` | `FLAT` | escape + planes |
| poi | Hilbert | `DELTA` | `CLASS` | escape + planes |
| aerodrome_label | Hilbert | `DELTA` | `FLAT` | split varint |

All zigzag. Hilbert = `HILBERT_CENTROID_ROT_MP`, chain = `CHAIN` (step 3).

## What the decoder needs
The two header bytes name everything but the reorder; the reorder needs nothing undone (step 3). So the
decoder needs no composition table at all, only the bytes and the topology.

## Alternatives measured

| row | gzip | vs `DELTA_ZIGZAG_VARINT` |
|---|---|---|
| `DEFAULT` | 10.450.157 | -7,00% |
| best single composition of the grid | 10.118.776 | -9,95% |
| `forLayer` | 9.960.430 | -11,36% |
| per-buffer oracle (each buffer alone-best) | 9.925.196 | -11,67% |
| tile oracle (descent on the compressed tile) | 9.895.841 | -11,94% |

- **Per-buffer selection** (trial-compress each buffer alone, keep the smallest): smaller in isolation,
  larger per delivered tile, because buffers in different formats share fewer matches inside the tile's
  compression window. Discarded ([../encoding/bad/per-buffer-selection.md](../encoding/bad/per-buffer-selection.md)).
- **Tile oracle** (`measure --step best`): one composition per (tile, layer) out of `bestGrid()`, chosen
  by coordinate descent on the whole compressed tile. 0,65% under `forLayer`; an upper bound, not a row
  ([tile-oracle.md](https://github.com/Turtelll/Thesis/blob/main/research-java/docs/results/tile-oracle.md)). Searching on raw sizes instead of
  gzip picks worse ([../encoding/bad/raw-size-search.md](../encoding/bad/raw-size-search.md)).

More: [../encoding/good/per-layer-composition.md](../encoding/good/per-layer-composition.md).
