package org.maplibre.mlt.research.registry;

import org.maplibre.mlt.research.buffer.Encoding;
import org.maplibre.mlt.research.encoder.format.Composition;
import org.maplibre.mlt.research.encoder.prediction.Prediction;
import org.maplibre.mlt.research.encoder.reorder.Reorder;
import org.maplibre.mlt.research.encoder.segmentation.Segmentation;
import org.maplibre.mlt.research.encoder.stream.ByteWidth;
import org.maplibre.mlt.research.encoder.stream.Transpose;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The grids of compositions the measurement chooses among: the fit grid {@code forLayer} is fitted on,
 * the 32-row oracle grid, the 96-composition grid of {@code --step best} and the trial sets of the
 * selectors. A grid is a list of {@link Composition}s (or rows over them); the registry's steps and
 * the per-layer defaults are elsewhere.
 */
public final class CompositionGrids {

    private CompositionGrids() {
    }

    /**
     * The grid {@link LayerCompositions#forLayer} is fitted on: every predictor (the ring frame, shared references and
     * heading rotation included) x flat or class streams x the
     * two byte terminals (escape with planes, split varint) x the given reorders ({@code --step fit}: {@link Reorder#HILBERT_CENTROID_ROT_MP}
     * and the greedy {@link Reorder#CHAIN}; {@code --step fithilbert}: Hilbert only).
     * The oracle grid has none of the later predictors, so its per-layer winners cannot replace the
     * fitted compositions. Run {@code ./gradlew research --args="measure --step fit"}: it implies {@code --oracle},
     * and the "best fixed composition per layer" table is the fit. Not part of {@link PipelineRegistry#all()}.
     */
    static List<Encoding> fitRows(List<Reorder> reorders) {
        final var rows = new ArrayList<Encoding>();
        for (final var reorder : reorders) {
            for (final var predictor : Prediction.values()) {
                for (final var segmentation : List.of(Segmentation.FLAT, Segmentation.CLASS)) {
                    rows.add(PipelineRegistry.row(PipelineRegistry.DEFAULT.withReorder(reorder).withPredictor(predictor)
                            .withSegmentation(segmentation).withWidth(ByteWidth.ESCAPE)
                            .withTranspose(Transpose.INTERLEAVED_PLANES)));
                    rows.add(PipelineRegistry.row(PipelineRegistry.DEFAULT.withReorder(reorder).withPredictor(predictor)
                            .withSegmentation(segmentation).withWidth(ByteWidth.VARINT_SPLIT)
                            .withTranspose(Transpose.NONE)));
                }
            }
        }
        return rows;
    }

    /**
     * The compositions the oracle chooses among: every combination of the decisions that a buffer's
     * content can change the answer of, not the one-at-a-time variants of the steps. Reorder (none or
     * Hilbert) x predictor x class streams or not x byte terminal (escape with planes, split
     * varint) = 32 rows, the {@code BufferSelector}'s grid with the reorder added. The first row is {@link
     * #DEFAULT}, so a tie goes to it.
     */
    public static List<Encoding> oracle() {
        final var rows = new ArrayList<Encoding>();
        for (final var config : oracleConfigs()) {
            rows.add(PipelineRegistry.row(config));
        }
        return rows;
    }

    /**
     * The grid the per-(tile, layer) oracle of {@code --step best} chooses among: every predictor
     * x flat or class streams x the two byte terminals (escape with
     * planes, split varint) x the three reorders the registry uses ({@link Reorder#NONE},
     * {@link Reorder#HILBERT_CENTROID_ROT_MP}, {@link Reorder#CHAIN}) = 96 compositions, each on {@link
     * #DEFAULT}'s residual map. It holds {@link PipelineRegistry#DEFAULT} and every {@link LayerCompositions#forLayer} composition. Not part
     * of {@link PipelineRegistry#all()}, so the golden dump does not grow; {@code PipelineEncodingTest} round-trips it.
     */
    public static List<Composition> bestGrid() {
        final var configs = new ArrayList<Composition>();
        for (final var reorder : List.of(Reorder.NONE, Reorder.HILBERT_CENTROID_ROT_MP, Reorder.CHAIN)) {
            for (final var predictor : Prediction.values()) {
                for (final var segmentation : List.of(Segmentation.FLAT, Segmentation.CLASS)) {
                    final var base = PipelineRegistry.DEFAULT.withReorder(reorder).withPredictor(predictor).withSegmentation(segmentation);
                    configs.add(base.withWidth(ByteWidth.ESCAPE).withTranspose(Transpose.INTERLEAVED_PLANES));
                    configs.add(base.withWidth(ByteWidth.VARINT_SPLIT).withTranspose(Transpose.NONE));
                }
            }
        }
        return configs;
    }

    /** The reorder variants S1 measures, the identity first. */
    public static List<Reorder> s1Orders() {
        return Arrays.stream(Reorder.values())
                .filter(order -> order != Reorder.CLASS_HILBERT_CENTROID_ROT)
                .toList();
    }

    /** The trial set, the default pipeline's choices first. */
    public static List<Composition> candidateConfigs(Reorder reorder) {
        final var base = PipelineRegistry.DEFAULT.withReorder(reorder);
        final var predictors = List.of(
                Prediction.QUAD_CORNER_SHARED_FRAME, Prediction.QUAD_CORNER, Prediction.LINE_CONTINUATION, Prediction.DELTA);
        final var segmentations = List.of(Segmentation.CLASS, Segmentation.FLAT);
        final var terminals = List.of(
                base.withWidth(ByteWidth.ESCAPE).withTranspose(Transpose.INTERLEAVED_PLANES),
                base.withWidth(ByteWidth.VARINT_SPLIT).withTranspose(Transpose.NONE));
        final var configs = new ArrayList<Composition>();
        for (final var predictor : predictors) {
            for (final var segmentation : segmentations) {
                for (final var terminal : terminals) {
                    configs.add(terminal.withPredictor(predictor).withSegmentation(segmentation));
                }
            }
        }
        return configs;
    }

    /**
     * The oracle grid: {@link Reorder#NONE} and {@link Reorder#HILBERT_CENTROID_ROT_MP}, each times
     * {@link #candidateConfigs}. The first row is {@link PipelineRegistry#DEFAULT}.
     */
    public static List<Composition> oracleConfigs() {
        final var configs = new ArrayList<Composition>();
        for (final var reorder : List.of(Reorder.NONE, Reorder.HILBERT_CENTROID_ROT_MP)) {
            configs.addAll(candidateConfigs(reorder));
        }
        return configs;
    }
}
