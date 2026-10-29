package org.maplibre.mlt.research.pipeline.mechanism;

import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.ClassifiedPredictor;
import org.maplibre.mlt.research.encoding.PartBoundaries;
import org.maplibre.mlt.research.encoding.Encoding.BufferContext;

import java.util.List;

/**
 * {@link SegmentQuadCorner} plus {@link LineContinuation} on long line runs.
 *
 * <p>The two predictors target disjoint populations, so they stack: a run of exactly four vertices
 * still takes the parallelogram corner, and the 3rd+ vertex of a LineString/MultiLineString run
 * with <em>more than</em> {@value #LINE_RUNS_ABOVE} vertices is predicted by edge continuation
 * ({@code 2*p[i-1] - p[i-2]}). Polygon rings never take continuation — it measured +11% mean
 * residual bits on POLYGON — and a line run of {@value #LINE_RUNS_ABOVE} vertices or fewer stays
 * on plain delta. A run must be bigger than 6 vertices.
 *
 * <p>Four classes, packed like the other classified predictors. Nothing is signalled;
 * {@code geometryTypes} and run length come from the topology streams.
 */
public final class QuadCornerLine implements ClassifiedPredictor {

    // INTERIOR is 0 so the default-zeroed class array already means "interior".
    private static final int INTERIOR = 0;
    private static final int START = 1;
    private static final int CORNER = 2;
    private static final int LINE_INTERIOR = 3;

    /**
     * Continuation applies only when a line run is strictly longer than this: more than 6 vertices.
     */
    private static final int LINE_RUNS_ABOVE = 6;

    @Override
    public String name() {
        return "QUAD_CORNER_LINE";
    }

    @Override
    public int classCount() {
        return 4;
    }

    @Override
    public int[] regionOrder() {
        // Start, interior, then the predicted classes. SharedVertexRef reverses this under
        // SEGMENTED so the wide starts land in FastPFOR's VariableByte tail.
        return new int[]{START, INTERIOR, CORNER, LINE_INTERIOR};
    }

    @Override
    public int[] classify(BufferContext ctx, int vertexCount) {
        final var starts = PartBoundaries.of(ctx, vertexCount);
        final var classes = new int[vertexCount];
        final var isLineRun = PartBoundaries.lineRuns(ctx, starts.length);
        for (var r = 0; r < starts.length; r++) {
            final var start = starts[r];
            final var end = r + 1 < starts.length ? starts[r + 1] : vertexCount;
            if (start < vertexCount) {
                classes[start] = START;
            }
            final var length = end - start;
            if (length == 4 && start + 3 < vertexCount) {
                classes[start + 3] = CORNER;
            }
            if (isLineRun != null && isLineRun[r] && length > LINE_RUNS_ABOVE) {
                for (var i = start + 2; i < end; i++) {
                    classes[i] = LINE_INTERIOR;
                }
            }
        }
        return classes;
    }

    @Override
    public boolean hasPrediction(int vertexClass) {
        return vertexClass == CORNER || vertexClass == LINE_INTERIOR;
    }

    @Override
    public int predictX(int vertexClass, int i, List<Vertex> vertices) {
        if (vertexClass == CORNER) {
            return vertices.get(i - 3).x() + vertices.get(i - 1).x() - vertices.get(i - 2).x();
        }
        return 2 * vertices.get(i - 1).x() - vertices.get(i - 2).x();
    }

    @Override
    public int predictY(int vertexClass, int i, List<Vertex> vertices) {
        if (vertexClass == CORNER) {
            return vertices.get(i - 3).y() + vertices.get(i - 1).y() - vertices.get(i - 2).y();
        }
        return 2 * vertices.get(i - 1).y() - vertices.get(i - 2).y();
    }
}
