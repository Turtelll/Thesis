package org.maplibre.mlt.research.pipeline;

import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.ClassifiedPredictor;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.research.LineContinuation;
import org.maplibre.mlt.research.encoding.research.QuadCornerLine;
import org.maplibre.mlt.research.encoding.research.SegmentQuadCorner;

import java.util.List;

/**
 * S2: prediction. Turns absolute coordinates into signed residuals. The delta chain is continuous
 * across part and feature boundaries: a vertex without a geometric prediction is predicted by the
 * previous vertex of the buffer, whatever feature it belongs to.
 *
 * <p>Predictors are the ones that worked in the old pipeline, reused unchanged.
 */
public enum Prediction {

    /** Componentwise first-order delta, the baseline. */
    DELTA,

    /** Fourth corner of the previous three in a quad ring (buildings), old {@code QUAD_CORNER}. */
    QUAD_CORNER,

    /** Line parts continue the previous segment (roads), old {@code LINE_CONTINUATION}. */
    LINE_CONTINUATION,

    /** Both of the above, one class set, old {@code QUAD_CORNER_LINE}. */
    QUAD_CORNER_LINE;

    /** The predictor behind this choice. Stateless, so a fresh instance per call is cheap. */
    public ClassifiedPredictor predictor() {
        return switch (this) {
            case DELTA -> new Delta();
            case QUAD_CORNER -> new SegmentQuadCorner();
            case LINE_CONTINUATION -> new LineContinuation();
            case QUAD_CORNER_LINE -> new QuadCornerLine();
        };
    }

    /** First-order delta only: one class, never a geometric prediction. */
    private static final class Delta implements ClassifiedPredictor {

        @Override
        public String name() {
            return "DELTA";
        }

        @Override
        public int[] classify(BufferContext ctx, int vertexCount) {
            return new int[vertexCount];
        }

        @Override
        public int classCount() {
            return 1;
        }

        @Override
        public int[] regionOrder() {
            return new int[]{0};
        }

        @Override
        public boolean hasPrediction(int vertexClass) {
            return false;
        }

        @Override
        public int predictX(int vertexClass, int i, List<Vertex> vertices) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int predictY(int vertexClass, int i, List<Vertex> vertices) {
            throw new UnsupportedOperationException();
        }
    }
}
