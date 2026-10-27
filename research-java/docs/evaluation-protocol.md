# Evaluation protocol

> **Consolidation note.** The Gradle task(s) benchmark named below were removed from the build (the code is at git tag `pre-consolidation`); `research measure` and the other `research` commands replace the rest. The numbers below are unchanged history.

The pipeline under test is **research encoding (predictor, region layout, any reordering and
byte-aligned serialization, all owned by the encoding) → general-purpose compressor (gzip,
Brotli, zstd)**. Tiles are delivered
under a browser-native compressor, and bit-packed FastPFOR output is opaque to it — per tile,
Varint plus a compressor beats FastPFOR plus the same compressor by 6,7–7,4%
([results/general-purpose-compression.md](results/general-purpose-compression.md)). FastPFOR
stays in the benchmark only as a baseline row.

A predictor changes the residual *distribution*; the last stage decides which part of that
distribution gets charged for. A compressor prices byte frequencies (Huffman/FSE) and exact
repeats (LZ77), not bit widths.

1. **Probe the distribution before writing an `Encoding`.** `./gradlew research --args="residual-bits"` prints mean
   bits, `zero pairs` (share of `(x, y)` residuals exactly `(0, 0)` — exactly-right predictions,
   what a compressor rewards) and the per-vertex change vs `DELTA_ZIGZAG`. For a one-line rule use
   `./gradlew research --args="workshop"`, or write a small purpose-built probe in the style of
   `SharedVertexCli`. Mean bits is a hint, not the target. Kill the idea here if the distribution
   doesn't move.
2. **Register it and read the per-tile table.** A new `Prediction` constant gives rows;
   `./gradlew research --args="measure"` reports them, per tile, under
   gzip, Brotli and zstd, against the smaller baseline under gzip. Read `order-0` first (byte
   entropy per tile, the cost before any match): on this corpus LZ77 finds little below it (1,8%
   under gzip, 2,6% under Brotli on `DELTA_ZIGZAG_VARINT`), so a predictor's job is to lower byte
   entropy; designing for repeats alone is not enough. Layout is part of the result, not an
   add-on: segmentation is the same size as interleaved before compression and still pays after
   it (a run of similar bytes compresses better than the same bytes alternating with outliers).
3. **Reordering last.** Geometry reordering is an optional step an encoding applies itself
   (`GeometryReorder`), not a baseline comparator. Measure the layout without it first; report
   with and without, separately.
4. **Check the oracle.** The `research oracle` row is the best a per-buffer selector could do
   over the Varint rows, with signalling free; its composition table says whether an encoding
   earns a place or only wins a byte per buffer ([results/selection-oracle.md](results/selection-oracle.md)).
   A single row beating the oracle once compressed means the win does not depend on a selector.
   Signalling is not free: `StreamMetadata` has ~2 free
   `LogicalLevelTechnique` ordinals left in production MLT.
5. **Decode time.** Delivery decode is the browser's native gzip, Brotli or zstd, the same
   function for every row, so the 1,5x budget of [results/decode-time.md](results/decode-time.md)
   does not apply to the compressor stage. The predictor's own decode still runs in the client:
   `./gradlew research --args="decode-time ../data/extractedBaseline --encoding <NAME>"` times it against
   `DELTA_ZIGZAG` on the same serializer.

A `ClassifiedPredictor` declares a per-vertex class and prediction once; the pipeline's
`Segmentation` turns it into one residual stream per class (or per role), and the width and
transpose stages decide how those streams become bytes. Measuring a predictor interleaved and
segmented is a configuration choice, not a second implementation (`PredictionTransform` reads a
predictor's interleaved residual pairs for the workshop and the probes).

## History: the FastPFOR pricing model

Results under `results/` measured before this direction were judged by FastPFOR bytes. FastPFOR
prices a block's *maximum*, so a predictor that narrows the median without narrowing the tail
showed no byte win interleaved (`QUAD_CORNER`: -9,6% bits, -0,0% bytes) and only paid once
segmented ([results/segment-quad-corner.md](results/segment-quad-corner.md)). The flags those
files cite (`--segmented`, `--compress`, `--revisit`, `--std-delta`, `--tile-std-delta`, `--rans`)
no longer exist; their rows live in `Baselines.retired()`.

See [results/README.md](results/README.md) for what applying this protocol has found so
far — wins, regressions, and ideas killed before an encoder was written.
