package org.maplibre.mlt.research.registry;

import org.maplibre.mlt.research.buffer.Encoding;
import org.maplibre.mlt.research.encoder.stream.ByteWidth;
import org.maplibre.mlt.research.encoder.stream.Transpose;
import org.maplibre.mlt.research.encoder.prediction.Prediction;
import org.maplibre.mlt.research.encoder.reorder.Reorder;
import org.maplibre.mlt.research.encoder.format.Composition;
import org.maplibre.mlt.research.encoder.format.PipelineEncoding;
import org.maplibre.mlt.research.encoder.segmentation.Segmentation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * The rows of the pipeline, per implementation step. {@code ./gradlew research --args="measure --step s3"}
 * measures {@code step("s3")}; {@link #all()} is the round-trip surface the tests parameterize over.
 *
 * <p>Each step is measured over the winners of the steps before it ({@code AFTER_S0} ...) and varies
 * only its own stage, so re-running a step reproduces the figures of {@code
 * docs/results/openzl-pipeline.md} whatever was decided later. The last winner is {@link #DEFAULT}.
 */
public final class PipelineRegistry {

    private PipelineRegistry() {
    }

    /** S0: the split varint terminal (-0,87% gzip against plain varint), the harness anchor. */
    static final Composition AFTER_S0 = Composition.IDENTITY.withWidth(ByteWidth.VARINT_SPLIT);

    /**
     * S1: Hilbert order of the bounding-box centre with ring rotation (-3,97% gzip of the geometry
     * buffers; applied to a layer only where its property columns do not lose more, see the S1
     * verdict).
     */
    static final Composition AFTER_S1 = AFTER_S0.withReorder(Reorder.HILBERT_CENTROID_ROT_MP);

    /**
     * S2: {@code QUAD_CORNER_LINE}, the one predictor within 0,3 points of the best choice on every
     * layer (the per-layer choice is the selector's).
     */
    static final Composition AFTER_S2 = AFTER_S1.withPredictor(Prediction.QUAD_CORNER_LINE);

    /** S3: one stream per predictor class (-1,2 points over FLAT); the role split and class sort lose. */
    static final Composition AFTER_S3 = AFTER_S2.withSegmentation(Segmentation.CLASS);

    /** S4: zigzag. The bias map was measured and removed. */
    static final Composition AFTER_S4 = AFTER_S3;

    /**
     * S6: the escape width (fixed-width bytes, {@code 0xFF} marks an exception): level with the
     * split varint under gzip and the terminal the S7 transposes need. {@code MIN_FIXED},
     * {@code FORCE_4} lose. S5 (GCD) was removed; this still follows S4.
     */
    static final Composition AFTER_S6 = AFTER_S4.withWidth(ByteWidth.ESCAPE);

    /**
     * S7: byte planes of the interleaved values (all low bytes, then all high bytes): -0,6 points gzip
     * over the untransposed escape stream, -0,7 brotli, -0,9 zstd; the x/y-separating layouts lose.
     */
    static final Composition AFTER_S7 = AFTER_S6.withTranspose(Transpose.INTERLEAVED_PLANES);

    /**
     * The best row measured on the geometry buffers alone: everything through S7, reorder included.
     * It is not the end-to-end choice, see {@link #DEFAULT}.
     */
    public static final Composition GEOMETRY_BEST = AFTER_S7;

    /**
     * S8: the end-to-end choice. The same pipeline without the S1 reorder: with the predictors and
     * class streams in place the reorder saves 3,0% of the geometry bytes (-323.000 gzip) but its
     * permutation of the property columns costs +736.000 on this corpus, a net loss everywhere
     * except {@code building} and {@code housenumber} (see the S8 verdict).
     */
    static final Composition AFTER_S8 = AFTER_S7.withReorder(Reorder.NONE);

    /** What the selection loop builds on: the winners recorded so far. */
    public static final Composition DEFAULT = AFTER_S8;

    static Encoding row(Composition config) {
        return new PipelineEncoding(config);
    }

    /** One stage of {@link #ladder()}: the label of the decision and the pipeline with it applied. */
    public record Stage(String label, Composition config) {
    }

    /**
     * The pipeline built up decision by decision, each stage the previous one plus one step's winner:
     * plain varint, S0 split varint, S1 reorder, S2 predictor, S3 class streams, S6 escape width, S7 byte
     * planes, then S8 (the reorder dropped end to end). S4 (zigzag) changes nothing, so it has no stage.
     * Every stage is also a row of a step, hence round-tripped by the tests.
     */
    public static List<Stage> ladder() {
        return List.of(
                new Stage("identity (plain varint)", Composition.IDENTITY),
                new Stage("S0 split varint", AFTER_S0),
                new Stage("S1 + Hilbert reorder", AFTER_S1),
                new Stage("S2 + QUAD_CORNER_LINE", AFTER_S2),
                new Stage("S3 + class streams", AFTER_S3),
                new Stage("S6 + escape width", AFTER_S6),
                new Stage("S7 + byte planes (GEOMETRY_BEST)", AFTER_S7),
                new Stage("S8 reorder dropped (DEFAULT)", AFTER_S8));
    }

    /** The step names, in order. */
    public static List<String> steps() {
        return List.of("s0", "s1", "s2", "s3", "s4", "s6", "s7", "s8", "selector", "turn", "frame", "framelayers",
                "shared", "framelines", "linesshared");
    }

    /** The rows measured by one step. */
    public static List<Encoding> step(String name) {
        return switch (name) {
            case "s0" -> List.of(
                    row(Composition.IDENTITY), row(Composition.IDENTITY.withWidth(ByteWidth.VARINT_SPLIT)));
            case "s1" -> CompositionGrids.s1Orders().stream().map(order -> row(AFTER_S0.withReorder(order))).toList();
            case "s2" -> Arrays.stream(Prediction.values())
                    .map(predictor -> row(AFTER_S1.withPredictor(predictor)))
                    .toList();
            case "s3" -> StepRows.s3Rows();
            case "s4" -> List.of(row(AFTER_S4));
            case "s6" -> StepRows.s6Rows();
            case "s7" -> StepRows.s7Rows();
            case "s8" -> StepRows.s8Rows();
            // the fixed rows the per-buffer selection is measured against; the selectors themselves
            // need a compressor and are built by MeasureHarness
            case "selector" -> List.of(row(DEFAULT), row(GEOMETRY_BEST));
            // not a step of the build-up: TURN_SCALAR beside QUAD_CORNER, for the building layer
            case "turn" -> StepRows.turnRows();
            // not a step of the build-up: the ring frame beside QUAD_CORNER, for the building layer
            case "frame" -> StepRows.frameRows();
            // the ring frame on every layer, under each layer terminal
            case "framelayers" -> StepRows.frameLayerRows();
            // the shared-vertex references beside the plain and framed predictors, for the building layer
            case "shared" -> StepRows.sharedRows();
            // the ring frame on line parts: LINE_CONTINUATION beside LINE_CONTINUATION_FRAME
            case "framelines" -> StepRows.frameLineRows();
            // the references to earlier line parts' positions beside the line predictors
            case "linesshared" -> StepRows.lineSharedRows();
            // not a step of the build-up: the cumulative stages, for the benchmark run
            case "ladder" -> ladder().stream().map(stage -> row(stage.config())).toList();
            // the grid the oracle chooses among; its rows are added to all() below
            case "oracle" -> CompositionGrids.oracle();
            // the grid forLayer is fitted on (frame, shared and heading predictors, chain reorder included)
            case "fit" -> CompositionGrids.fitRows(List.of(Reorder.HILBERT_CENTROID_ROT_MP, Reorder.CHAIN));
            // the same without the chain reorder, whose property-column cost is only measured for transportation
            case "fithilbert" -> CompositionGrids.fitRows(List.of(Reorder.HILBERT_CENTROID_ROT_MP));
            // the previous default, beside which the benchmark sizes the per-layer default (forLayer)
            case "layer" -> List.of(row(DEFAULT));
            // the fixed rows the tile selection is measured against; the TileSelectors need a
            // compressor and are built by MeasureHarness
            case "tile" -> List.of(row(DEFAULT));
            // the grid of the per-(tile, layer) oracle; the oracles themselves need a compressor and are
            // built by MeasureHarness. Not in steps(), so all() does not take the grid
            case "best" -> CompositionGrids.bestGrid().stream().map(PipelineRegistry::row).toList();
            default -> throw new IllegalArgumentException("unknown step: " + name + " (known: " + steps() + ")");
        };
    }

    /** Every row of every step, once. */
    public static List<Encoding> all() {
        final var byName = new LinkedHashMap<String, Encoding>();
        for (final var step : steps()) {
            for (final var encoding : step(step)) {
                byName.putIfAbsent(encoding.name(), encoding);
            }
        }
        // the oracle's grid holds combinations no step measures; round-tripped with the rest
        for (final var encoding : CompositionGrids.oracle()) {
            byName.putIfAbsent(encoding.name(), encoding);
        }
        // so are the per-layer compositions (the chain ones pair it with predictors no step measures)
        for (final var layer : LayerCompositions.fittedLayers()) {
            final var encoding = row(LayerCompositions.forLayer(layer));
            byName.putIfAbsent(encoding.name(), encoding);
        }
        return new ArrayList<>(byName.values());
    }
}
