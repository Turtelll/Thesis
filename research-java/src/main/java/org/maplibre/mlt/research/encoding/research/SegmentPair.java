package org.maplibre.mlt.research.encoding.research;

import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.ClassifiedPredictor;
import org.maplibre.mlt.research.encoding.PartBoundaries;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;

import java.util.List;

/**
 * Componentwise delta + zigzag as in the baseline, but emitted as two grouped populations: every
 * part-start residual first, then every within-part delta.
 *
 * <p>The values are the same values in a different order, so this neither adds nor removes
 * information — it only changes what shares a block. Nothing is signalled: the split point is
 * derivable from the topology streams on both sides.
 *
 * <p>Has no predictor of its own — both classes fall back to first-order delta ({@link
 * #hasPrediction} is always {@code false}) — so the whole idea under test <em>is</em> the region
 * split; pair with {@link org.maplibre.mlt.research.encoding.PredictedTransform} under {@link
 * org.maplibre.mlt.research.encoding.Layout#INTERLEAVED} to confirm it (byte-identical to the
 * plain baseline).
 *
 * <p>Measured: {@code docs/results/segment-pair.md}.
 */
public final class SegmentPair implements ClassifiedPredictor {

    // INTERIOR is 0 so the default-zeroed class array already means "interior".
    private static final int INTERIOR = 0;
    private static final int START = 1;

    @Override
    public String name() {
        return "SEGMENT_PAIR";
    }

    @Override
    public int classCount() {
        return 2;
    }

    @Override
    public int[] regionOrder() {
        return new int[]{START, INTERIOR};
    }

    @Override
    public int[] classify(BufferContext ctx, int vertexCount) {
        final var classes = new int[vertexCount];
        for (final var start : PartBoundaries.of(ctx, vertexCount)) {
            if (start < vertexCount) {
                classes[start] = START;
            }
        }
        return classes;
    }

    @Override
    public boolean hasPrediction(int vertexClass) {
        return false;
    }

    @Override
    public int predictX(int vertexClass, int i, List<Vertex> vertices) {
        throw new UnsupportedOperationException("SEGMENT_PAIR has no class with a real predictor");
    }

    @Override
    public int predictY(int vertexClass, int i, List<Vertex> vertices) {
        throw new UnsupportedOperationException("SEGMENT_PAIR has no class with a real predictor");
    }
}
