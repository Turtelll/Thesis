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
 * The rows of the registry's steps that are more than one change to the previous winner: the
 * predictor grids beside their plain rows ({@code frame}, {@code shared},
 * {@code framelayers}, {@code framelines}, {@code linesshared}) and the S3, S6, S7 and S8 rows.
 */
final class StepRows {

    private StepRows() {
    }

    /**
     * The ring frame beside its plain predictor on the {@code building} layer: {@code QUAD_CORNER}
     * against {@code QUAD_CORNER_FRAME}, per segmentation, without and then with the
     * {@link Reorder#HILBERT_CENTROID_ROT_MP} reorder, then the frame on the layer's own composition
     * ({@link LayerCompositions#forLayer}, which uses the frame) with the other terminals. The
     * rows after the grid are the previous default ({@code QUAD_CORNER}), the current one, then variants. Rows that equal a grid row (the planes terminal is
     * {@link PipelineRegistry#DEFAULT}'s) are not repeated: a duplicate name is summed twice by the benchmark.
     */
    static List<Encoding> frameRows() {
        final var rows = new ArrayList<Encoding>();
        for (final var reorder : List.of(Reorder.NONE, Reorder.HILBERT_CENTROID_ROT_MP)) {
            for (final var predictor : List.of(Prediction.QUAD_CORNER, Prediction.QUAD_CORNER_FRAME)) {
                for (final var segmentation : List.of(Segmentation.FLAT, Segmentation.CLASS, Segmentation.ROLE_CLASS)) {
                    rows.add(PipelineRegistry.row(PipelineRegistry.DEFAULT.withReorder(reorder).withPredictor(predictor).withSegmentation(segmentation)));
                }
            }
        }
        // LayerCompositions.forLayer(LayerCompositions.BUILDING) has the shared references on top of the frame since the shared step; the framed
        // row is the one the frame step was fitted on, the plain predictor the one before it
        final var building = LayerCompositions.forLayer(LayerCompositions.BUILDING);
        rows.add(PipelineRegistry.row(building.withPredictor(Prediction.QUAD_CORNER)));
        rows.add(PipelineRegistry.row(building.withPredictor(Prediction.QUAD_CORNER_FRAME)));
        rows.add(PipelineRegistry.row(building.withPredictor(Prediction.QUAD_CORNER_FRAME).withWidth(ByteWidth.VARINT_SPLIT).withTranspose(Transpose.NONE)));
        rows.add(PipelineRegistry.row(building.withPredictor(Prediction.QUAD_CORNER_FRAME).withReorder(Reorder.HILBERT_CENTROID_ROT)));
        return rows;
    }

    /**
     * The shared-vertex references on the {@code building} layer: {@code QUAD_CORNER} and its ring
     * frame against {@code QUAD_CORNER_SHARED} and {@code QUAD_CORNER_SHARED_FRAME}, flat and class
     * streams, without and then with the {@link Reorder#HILBERT_CENTROID_ROT_MP} reorder, on {@link
     * #DEFAULT}'s terminal (escape width, byte planes) and on the terminal {@code building} uses
     * (escape width, no planes). The reorder rotates every ring to start nearest the previous
     * vertex; the references then rotate the rings that share a wall to start where it begins, so
     * the order of the two is what the reorder rows measure.
     */
    static List<Encoding> sharedRows() {
        final var rows = new ArrayList<Encoding>();
        for (final var terminal : List.of(PipelineRegistry.DEFAULT, LayerCompositions.forLayer(LayerCompositions.BUILDING))) {
            for (final var reorder : List.of(Reorder.NONE, Reorder.HILBERT_CENTROID_ROT_MP)) {
                for (final var predictor : List.of(Prediction.QUAD_CORNER, Prediction.QUAD_CORNER_FRAME,
                        Prediction.QUAD_CORNER_SHARED, Prediction.QUAD_CORNER_SHARED_FRAME)) {
                    for (final var segmentation : List.of(Segmentation.FLAT, Segmentation.CLASS)) {
                        rows.add(PipelineRegistry.row(terminal.withReorder(reorder).withPredictor(predictor)
                                .withSegmentation(segmentation)));
                    }
                }
            }
        }
        return rows;
    }

    /**
     * The ring frame on every layer, not just {@code building}: the plain and the framed quad-corner predictor
     * under each (segmentation, width, transpose) the
     * fitted layers use, all with {@link Reorder#HILBERT_CENTROID_ROT_MP}. Read the by-layer table: a layer
     * compares the rows of its own terminal. The rows are layer-independent, so a layer without rings
     * (or whose predictor is not a quad-corner one) shows the frame doing nothing.
     */
    static List<Encoding> frameLayerRows() {
        final var rows = new ArrayList<Encoding>();
        final var terminals = List.of(
                LayerCompositions.composition(Prediction.QUAD_CORNER, Segmentation.CLASS, ByteWidth.ESCAPE,
                        Transpose.INTERLEAVED_PLANES),
                LayerCompositions.composition(Prediction.QUAD_CORNER, Segmentation.CLASS, ByteWidth.ESCAPE, Transpose.NONE),
                LayerCompositions.composition(Prediction.QUAD_CORNER, Segmentation.CLASS, ByteWidth.VARINT_SPLIT, Transpose.NONE),
                LayerCompositions.composition(Prediction.QUAD_CORNER, Segmentation.FLAT, ByteWidth.VARINT_SPLIT, Transpose.NONE));
        for (final var terminal : terminals) {
            for (final var predictor : List.of(Prediction.QUAD_CORNER, Prediction.QUAD_CORNER_FRAME)) {
                rows.add(PipelineRegistry.row(terminal.withPredictor(predictor)));
            }
        }
        return rows;
    }

    /**
     * The frame on line parts: {@code LINE_CONTINUATION} against {@code LINE_CONTINUATION_FRAME_HEADING} (the frame
     * and the heading rotation of the continued vertices), per segmentation, without and then with the {@link Reorder#HILBERT_CENTROID_ROT_MP} reorder, on the
     * terminal {@code transportation} uses (escape width, byte planes: {@link LayerCompositions#forLayer}) and on the
     * one {@code transportation_name} and {@code waterway} use (split varint, no planes). The rows are
     * layer-independent, so read the by-layer table: a layer without line parts shows the frame doing
     * nothing. The {@code transportation} row of the current default is the Hilbert, class-stream one.
     */
    static List<Encoding> frameLineRows() {
        final var rows = new ArrayList<Encoding>();
        final var terminals = List.of(
                LayerCompositions.forLayer(LayerCompositions.TRANSPORTATION),
                LayerCompositions.forLayer(LayerCompositions.WATERWAY));
        for (final var terminal : terminals) {
            for (final var reorder : List.of(Reorder.NONE, Reorder.HILBERT_CENTROID_ROT_MP)) {
                for (final var predictor : List.of(Prediction.LINE_CONTINUATION, Prediction.LINE_CONTINUATION_FRAME_HEADING)) {
                    for (final var segmentation : List.of(Segmentation.FLAT, Segmentation.CLASS)) {
                        rows.add(PipelineRegistry.row(terminal.withReorder(reorder).withPredictor(predictor)
                                .withSegmentation(segmentation)));
                    }
                }
            }
        }
        return rows;
    }

    /**
     * The references to earlier line parts' positions ({@link Prediction#LINE_CONTINUATION_SHARED_FRAME_HEADING}) beside
     * their plain predictors: {@code LINE_CONTINUATION} and the frame and heading one {@code transportation}
     * uses, each without and with the references, per segmentation, without and then with the
     * {@link Reorder#HILBERT_CENTROID_ROT_MP} reorder, on the terminal {@code transportation} uses (escape
     * width, byte planes: {@link LayerCompositions#forLayer}) and on the one {@code transportation_name} and {@code
     * waterway} use (split varint, no planes). The rows are layer-independent, so read the by-layer table:
     * a layer without line parts shows the references doing nothing.
     */
    static List<Encoding> lineSharedRows() {
        final var rows = new ArrayList<Encoding>();
        final var terminals = List.of(
                LayerCompositions.forLayer(LayerCompositions.TRANSPORTATION),
                LayerCompositions.forLayer(LayerCompositions.WATERWAY));
        for (final var terminal : terminals) {
            for (final var reorder : List.of(Reorder.NONE, Reorder.HILBERT_CENTROID_ROT_MP)) {
                for (final var predictor : List.of(
                        Prediction.LINE_CONTINUATION,
                        Prediction.LINE_CONTINUATION_FRAME_HEADING,
                        Prediction.LINE_CONTINUATION_SHARED_FRAME_HEADING)) {
                    for (final var segmentation : List.of(Segmentation.FLAT, Segmentation.CLASS)) {
                        rows.add(PipelineRegistry.row(terminal.withReorder(reorder).withPredictor(predictor)
                                .withSegmentation(segmentation)));
                    }
                }
            }
        }
        // the old pipeline's greedy nearest-endpoint chaining as the reorder, class streams only, on the
        // transportation terminal: chaining already turns junction starts into short jumps, so the
        // references have less to catch (it never reverses a part)
        for (final var reorder : List.of(Reorder.CHAIN, Reorder.CANON_CHAIN)) {
            for (final var predictor : List.of(
                    Prediction.LINE_CONTINUATION_FRAME_HEADING,
                    Prediction.LINE_CONTINUATION_SHARED_FRAME_HEADING)) {
                rows.add(PipelineRegistry.row(LayerCompositions.forLayer(LayerCompositions.TRANSPORTATION).withReorder(reorder).withPredictor(predictor)
                        .withSegmentation(Segmentation.CLASS)));
            }
        }
        return rows;
    }

    static List<Encoding> s3Rows() {
        final var rows = new ArrayList<Encoding>();
        // the SegmentPair re-check: plain delta, with and without the role split, over LZ
        for (final var segmentation : List.of(Segmentation.FLAT, Segmentation.CLASS, Segmentation.ROLE)) {
            rows.add(PipelineRegistry.row(PipelineRegistry.AFTER_S2.withPredictor(Prediction.DELTA).withSegmentation(segmentation)));
        }
        for (final var segmentation : Segmentation.values()) {
            rows.add(PipelineRegistry.row(PipelineRegistry.AFTER_S2.withSegmentation(segmentation)));
        }
        // the per-layer winners of S2 under the segmentations that use their classes
        for (final var predictor : List.of(Prediction.QUAD_CORNER, Prediction.LINE_CONTINUATION)) {
            rows.add(PipelineRegistry.row(PipelineRegistry.AFTER_S2.withPredictor(predictor).withSegmentation(Segmentation.CLASS)));
            rows.add(PipelineRegistry.row(PipelineRegistry.AFTER_S2.withPredictor(predictor).withSegmentation(Segmentation.ROLE_CLASS)));
        }
        // the (class, Hilbert) sort: classes contiguous in feature order
        for (final var segmentation : List.of(Segmentation.FLAT, Segmentation.ROLE_CLASS)) {
            rows.add(PipelineRegistry.row(PipelineRegistry.AFTER_S2.withReorder(Reorder.CLASS_HILBERT_CENTROID_ROT).withSegmentation(segmentation)));
        }
        return rows;
    }

    static List<Encoding> s6Rows() {
        final var rows = new ArrayList<Encoding>();
        rows.add(PipelineRegistry.row(PipelineRegistry.AFTER_S4.withWidth(ByteWidth.VARINT)));
        rows.add(PipelineRegistry.row(PipelineRegistry.AFTER_S4));
        for (final var width : List.of(ByteWidth.MIN_FIXED, ByteWidth.ESCAPE, ByteWidth.FORCE_4)) {
            rows.add(PipelineRegistry.row(PipelineRegistry.AFTER_S4.withWidth(width)));
        }
        // the role split, whose point is a separate width per stream (S3 left it open)
        for (final var width : List.of(ByteWidth.MIN_FIXED, ByteWidth.ESCAPE)) {
            rows.add(PipelineRegistry.row(PipelineRegistry.AFTER_S4.withSegmentation(Segmentation.ROLE_CLASS).withWidth(width)));
        }
        return rows;
    }

    static List<Encoding> s7Rows() {
        final var rows = new ArrayList<Encoding>();
        rows.add(PipelineRegistry.row(PipelineRegistry.AFTER_S4));
        for (final var width : List.of(ByteWidth.MIN_FIXED, ByteWidth.ESCAPE, ByteWidth.FORCE_4)) {
            for (final var transpose : Transpose.values()) {
                rows.add(PipelineRegistry.row(PipelineRegistry.AFTER_S6.withWidth(width).withTranspose(transpose)));
            }
        }
        for (final var transpose : Transpose.values()) {
            rows.add(PipelineRegistry.row(PipelineRegistry.AFTER_S6.withSegmentation(Segmentation.ROLE_CLASS).withTranspose(transpose)));
        }
        return rows;
    }

    /**
     * S8: the S7 winner against the choices around it, one row per decision the earlier steps took,
     * so the backend that prefers another one shows. Each is a single change to {@code PipelineRegistry.AFTER_S7}.
     */
    static List<Encoding> s8Rows() {
        return List.of(
                PipelineRegistry.row(PipelineRegistry.AFTER_S7),
                PipelineRegistry.row(PipelineRegistry.AFTER_S7.withReorder(Reorder.NONE)),
                PipelineRegistry.row(PipelineRegistry.AFTER_S7.withReorder(Reorder.HILBERT_CENTROID)),
                PipelineRegistry.row(PipelineRegistry.AFTER_S7.withReorder(Reorder.HILBERT_CENTROID_ROT)),
                PipelineRegistry.row(PipelineRegistry.AFTER_S7.withPredictor(Prediction.DELTA)),
                PipelineRegistry.row(PipelineRegistry.AFTER_S7.withPredictor(Prediction.QUAD_CORNER)),
                PipelineRegistry.row(PipelineRegistry.AFTER_S7.withPredictor(Prediction.LINE_CONTINUATION)),
                PipelineRegistry.row(PipelineRegistry.AFTER_S7.withSegmentation(Segmentation.FLAT)),
                PipelineRegistry.row(PipelineRegistry.AFTER_S7.withWidth(ByteWidth.VARINT_SPLIT).withTranspose(Transpose.NONE)),
                PipelineRegistry.row(PipelineRegistry.AFTER_S7.withWidth(ByteWidth.MIN_FIXED)),
                PipelineRegistry.row(PipelineRegistry.AFTER_S7.withTranspose(Transpose.NONE)));
    }
}
