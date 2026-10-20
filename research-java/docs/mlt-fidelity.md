# Notes on fidelity to MLT

- `VertexBufferExtractor` is a **verbatim copy**, not a reimplementation, of
  `GeometryEncoder.prepareGeometry` and its private helpers (`flatPolygon`,
  `flatLineString`, `ringToLineString`, `addLineString`, `containsPolygon`). It is
  copied rather than called because the original is private in `mlt-core`.
  `VertexBufferExtractorFidelityTest` pins it against the real method via reflection —
  that is what proves the extracted buffers match what MLT itself would produce. If it
  fails after updating the submodule, the copy has drifted and needs re-syncing.
- `VertexTransform.ZigZagDelta` delegates to `GeometryEncoder.zigZagDeltaEncodeVertices`,
  so the baseline transform cannot drift from upstream either.
- Two rules the spec text does not state, but the extractor (matching the real encoder)
  applies:
  - Polygon/ring closing vertices are **dropped** — a ring's last coordinate, which
    duplicates the first, is not stored.
  - LineString vertex counts go to `numRings` if the column contains any
    (Multi)Polygon, otherwise to `numParts` — decided once per column, not per geometry.
- Vertex order matches feature order in the MVT layer (no reordering), mirroring MLT's
  default non-sorted feature table encoding.
