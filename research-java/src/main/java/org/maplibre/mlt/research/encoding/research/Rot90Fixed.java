package org.maplibre.mlt.research.encoding.research;

import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.ClassifiedPredictor;
import org.maplibre.mlt.research.encoding.PartBoundaries;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;

import java.util.List;

/**
 * Predicts each interior vertex of an <b>even-length</b>, {@code &gt;4}-vertex polygon ring as a
 * fixed 90°-turn off the previous edge — {@code pred = p[i-1] + rot90ccw(prevEdge)} — with the turn
 * direction fixed by winding (CCW, since {@code building} is 99,3% CCW-wound per {@code
 * BuildingShapeCli}) rather than signalled per vertex.
 *
 * <p>Unlike {@link RightAngleTurn}-style predictors, the direction here is fixed rather than chosen
 * per vertex, so nothing is signalled: the class of every vertex (start / interior / turn) is
 * derivable from the topology streams on both sides, and residuals are only reordered into three
 * contiguous regions — starts, interior, turns — so the near-zero turn population is packed at its
 * own FastPFOR width instead of being widened by the populations around it (the same block-width
 * lever {@code SEGMENT_QUAD_CORNER}'s corner residuals use).
 *
 * <p>A vertex is <b>turn-predicted</b> iff its ring has more than four vertices, an even length, and
 * the vertex has two in-ring predecessors ({@code i - start >= 2}), so the edge {@code
 * p[i-1]-p[i-2]} is defined. Everything else — every vertex of an odd-length or {@code &lt;=4}-vertex
 * ring, and the first two vertices of an even long ring — stays first-order delta. {@code
 * prevX/prevY} advance continuously through the actual vertices regardless of class; no reset seeds.
 *
 * <p>Measured: {@code docs/results/rot90-fixed.md}.
 */
public final class Rot90Fixed implements ClassifiedPredictor {

    // INTERIOR is 0 so the default-zeroed class array already means "interior".
    private static final int INTERIOR = 0;
    private static final int START = 1;
    private static final int TURN = 2;

    @Override
    public String name() {
        return "ROT90_FIXED";
    }

    @Override
    public int classCount() {
        return 3;
    }

    @Override
    public int[] regionOrder() {
        return new int[]{START, INTERIOR, TURN};
    }

    @Override
    public int[] classify(BufferContext ctx, int vertexCount) {
        return classify(PartBoundaries.of(ctx, vertexCount), vertexCount);
    }

    @Override
    public boolean hasPrediction(int vertexClass) {
        return vertexClass == TURN;
    }

    @Override
    public int predictX(int vertexClass, int i, List<Vertex> vertices) {
        final var p1 = vertices.get(i - 1);
        final var edgeY = p1.y() - vertices.get(i - 2).y();
        // rot90 ccw of (edgeX,edgeY) is (-edgeY, edgeX).
        return p1.x() - edgeY;
    }

    @Override
    public int predictY(int vertexClass, int i, List<Vertex> vertices) {
        final var p1 = vertices.get(i - 1);
        final var edgeX = p1.x() - vertices.get(i - 2).x();
        return p1.y() + edgeX;
    }

    static int[] classify(int[] starts, int vertexCount) {
        final var classes = new int[vertexCount];
        for (var s = 0; s < starts.length; s++) {
            final var start = starts[s];
            final var end = s + 1 < starts.length ? starts[s + 1] : vertexCount;
            if (start < vertexCount) {
                classes[start] = START;
            }
            final var length = end - start;
            if (length > 4 && length % 2 == 0) {
                for (var i = start + 2; i < end; i++) {
                    classes[i] = TURN;
                }
            }
        }
        return classes;
    }
}
