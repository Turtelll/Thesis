package org.maplibre.mlt.research.encoder.prediction.mechanism;

import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.buffer.Encoding.BufferContext;

/**
 * The logical content of a structural predictor, with no opinion on how its residuals are laid
 * out in the output array: for each vertex, which of a fixed set of classes it falls in (derived
 * from the topology streams, identically on encode and decode) and what that class predicts.
 * The pipeline's {@code Segmentation} lays the residuals out (flat, or one stream per class), so a
 * predictor is measured both ways without a second implementation.
 *
 * <p>{@code predictX}/{@code predictY} are given the vertex index and the vertices reconstructed
 * so far (on decode, exactly as far as a real decoder has gotten) plus the vertex classes, and
 * must be pure functions of already-known state — the same "derived, not signalled" rule {@code
 * VertexRuns} exists to enforce for the class itself.
 */
public interface ClassifiedPredictor {

    /**
     * This predictor's contribution to an encoding's name, e.g. {@code QUAD_CORNER}. The pipeline's own configuration name adds the segmentation.
     */
    String name();

    /**
     * Per-vertex class, one of {@code [0, classCount())}, derived from the topology streams alone.
     * Class {@code 0} must be the default/interior class the regression baseline falls back to.
     */
    int[] classify(BufferContext ctx, int vertexCount);

    /**
     * Number of distinct classes {@link #classify} can produce.
     */
    int classCount();

    /**
     * Region order when segmented by class: a permutation of {@code [0, classCount())}. Every
     * existing predictor here orders its regions "start, interior, then everything else ascending"
     * (e.g. {@code {1, 0, 2}}, not {@code {0, 1, 2}}) — this is pinned explicitly per predictor
     * rather than derived, so the refactor cannot silently reorder a region and change output bytes.
     */
    int[] regionOrder();

    /**
     * Whether {@code vertexClass} has a predictor of its own. When {@code false}, the
     * segmentation predicts (prevX, prevY) — the continuous first-order delta every class
     * without a better model falls back to here — and never calls {@link #predictX}/{@link
     * #predictY} for it. Every predictor's {@code INTERIOR} (0) and {@code START} (1) class is
     * plain delta; only classes describing an actual geometric prediction (a parallelogram corner,
     * a fixed turn) return {@code true}.
     */
    boolean hasPrediction(int vertexClass);

    /**
     * Predicted x for vertex {@code i}, given its class and every vertex before it already
     * reconstructed into {@code vertices} (index {@code < i} valid; {@code >= i} must not be read).
     * Only called when {@link #hasPrediction} is {@code true} for this class.
     */
    int predictX(int vertexClass, int i, java.util.List<Vertex> vertices);

    /**
     * Predicted y for vertex {@code i} — see {@link #predictX}.
     */
    int predictY(int vertexClass, int i, java.util.List<Vertex> vertices);
}
