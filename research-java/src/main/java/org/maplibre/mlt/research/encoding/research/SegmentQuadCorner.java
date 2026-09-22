package org.maplibre.mlt.research.encoding.research;

import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.ClassifiedPredictor;
import org.maplibre.mlt.research.encoding.PartBoundaries;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;

import java.util.List;

/**
 * Composes {@link SegmentPair}'s part-start grouping with a parallelogram-corner predictor, which
 * target disjoint geometry populations (LINESTRING vs POLYGON) so they stack rather than compete.
 *
 * <p>Every residual is the same componentwise delta + zigzag as the baseline, split into three
 * populations packed as contiguous regions so FastPFOR can size each to its own width:
 *
 * <ol>
 *   <li><b>part starts</b> — the first vertex of each ring run, delta against the previous vertex.
 *   <li><b>interior</b> — every other vertex, delta against the previous vertex.
 *   <li><b>quad corners</b> — the fourth vertex of a run that is exactly four vertices, predicted
 *       as the parallelogram corner {@code p0 + p2 - p1}.
 * </ol>
 *
 * <p>The three classes are mutually exclusive: a run's fourth vertex ({@code start+3}) is never its
 * start. {@code prevX/prevY} advance continuously through the actual vertices regardless of class —
 * no reset seeds. The class of every vertex is derivable from the topology streams on both sides, so
 * nothing is signalled; the values are only reordered.
 *
 * <p>Measured: {@code docs/results/segment-quad-corner.md}.
 */
public final class SegmentQuadCorner implements ClassifiedPredictor {

    // INTERIOR is 0 so the default-zeroed class array already means "interior".
    private static final int INTERIOR = 0;
    private static final int START = 1;
    private static final int CORNER = 2;

    @Override
    public String name() {
        return "QUAD_CORNER";
    }

    @Override
    public int classCount() {
        return 3;
    }

    @Override
    public int[] regionOrder() {
        return new int[]{START, INTERIOR, CORNER};
    }

    @Override
    public int[] classify(BufferContext ctx, int vertexCount) {
        return classify(PartBoundaries.of(ctx, vertexCount), vertexCount);
    }

    @Override
    public boolean hasPrediction(int vertexClass) {
        return vertexClass == CORNER;
    }

    @Override
    public int predictX(int vertexClass, int i, List<Vertex> vertices) {
        return vertices.get(i - 3).x() + vertices.get(i - 1).x() - vertices.get(i - 2).x();
    }

    @Override
    public int predictY(int vertexClass, int i, List<Vertex> vertices) {
        return vertices.get(i - 3).y() + vertices.get(i - 1).y() - vertices.get(i - 2).y();
    }

    /**
     * Length in int slots of each contiguous residual region: starts, interior, corners.
     *
     * <p>Exposed for {@link org.maplibre.mlt.research.encoding.SegmentedRansCodec}, which prices
     * each region with its own frequency table rather than one shared table. The regions are the
     * ones {@link org.maplibre.mlt.research.encoding.PredictedTransform#apply} already writes under
     * {@link org.maplibre.mlt.research.encoding.Layout#SEGMENTED}; this only reports their extents.
     */
    public int[] regionLengths(BufferContext ctx, int vertexCount) {
        final var classes = classify(PartBoundaries.of(ctx, vertexCount), vertexCount);
        var starts = 0;
        var corners = 0;
        for (final var c : classes) {
            if (c == START) {
                starts++;
            } else if (c == CORNER) {
                corners++;
            }
        }
        final var interior = classes.length - starts - corners;
        return new int[]{starts * 2, interior * 2, corners * 2};
    }

    private static int[] classify(int[] starts, int vertexCount) {
        final var classes = new int[vertexCount];
        for (var i = 0; i < starts.length; i++) {
            final var start = starts[i];
            final var end = i + 1 < starts.length ? starts[i + 1] : vertexCount;
            if (start < vertexCount) {
                classes[start] = START;
            }
            if (end - start == 4) {
                classes[start + 3] = CORNER;
            }
        }
        return classes;
    }
}
