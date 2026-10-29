package org.maplibre.mlt.research.pipeline.mechanism;

import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.PartBoundaries;
import org.maplibre.mlt.research.encoding.Encoding.BufferContext;

import java.util.List;

/**
 * Winding-fixed 90° turn with one edge-length scalar, composed with {@link
 * org.maplibre.mlt.research.pipeline.mechanism.SegmentQuadCorner}'s
 * parallelogram corner on four-vertex rings.
 *
 * <p>Per vertex class, all derived from the topology streams on both sides:
 *
 * <ul>
 *   <li><b>start / interior</b> — delta against the previous vertex (2 values).
 *   <li><b>corner</b> — the fourth vertex of an exactly-four-vertex run, predicted as {@code p0 + p2
 *       - p1} exactly as {@code QUAD_CORNER} does (2 values).
 *   <li><b>turn</b> — every vertex with two in-ring predecessors of an even-length, {@code >4}-vertex
 *       polygon ring (odd rings stay plain delta).
 *       The outgoing edge {@code w} is predicted along {@code u = rot90(prevEdge)} — CCW for outer
 *       rings, CW for inner rings (outer/inner from {@code numParts}, 100% hit rate on {@code
 *       extractedBaseline} per {@code TurnScalarCli}) — and stored as {@code along = round(dot(w,u)
 *       / |u|)} plus the correction {@code corr = w - round(along·u/|u|)}, zero whenever one scalar
 *       suffices (3 values). A zero previous edge gives {@code along = 0, corr = w}, so the value
 *       count stays derivable from topology alone.
 * </ul>
 *
 * <p>Segmented, the pipeline packs the values as regions start | interior | corner | along | corr,
 * so the near-zero correction population gets its own stream. Turns only
 * fire in polygon-only columns whose {@code numParts} tiles the rings; elsewhere this is exactly
 * {@code QUAD_CORNER}. {@code prevX/prevY} advance continuously through the actual vertices.
 *
 * <p>Unregistered as a negative result — worse than {@code QUAD_CORNER} on every polygon layer,
 * kept for its mechanism test. Measured: {@code docs/results/failed/turn-scalar-encoder.md}.
 */
public final class TurnScalar {

    private TurnScalar() {
    }

    // INTERIOR is 0 so the default-zeroed class array already means "interior".
    public static final int INTERIOR = 0;
    public static final int START = 1;
    public static final int CORNER = 2;
    public static final int TURN = 3;

    private static final int POLYGON = 2;
    private static final int MULTIPOLYGON = 5;

    /**
     * Per-vertex class, and for turn vertices whether the ring is outer (rotate CCW) or inner (CW).
     */
    public record Classes(int[] classes, boolean[] ccw, int turns) {
    }

    public static Classes classify(BufferContext ctx, int vertexCount) {
        final var starts = PartBoundaries.of(ctx, vertexCount);
        final var outer = outerRings(ctx, starts.length);
        final var classes = new int[vertexCount];
        final var ccw = new boolean[vertexCount];
        var turns = 0;
        for (var r = 0; r < starts.length; r++) {
            final var start = starts[r];
            final var end = r + 1 < starts.length ? starts[r + 1] : vertexCount;
            if (start < vertexCount) {
                classes[start] = START;
            }
            if (end - start == 4) {
                classes[start + 3] = CORNER;
            } else if (end - start > 4 && (end - start) % 2 == 0 && outer != null) {
                for (var i = start + 2; i < end; i++) {
                    classes[i] = TURN;
                    ccw[i] = outer[r];
                    turns++;
                }
            }
        }
        return new Classes(classes, ccw, turns);
    }

    /**
     * Per ring, whether it is an outer ring (first ring of its polygon) — or {@code null} if the
     * column is not polygon-only or {@code numParts} does not tile the rings exactly.
     */
    private static boolean[] outerRings(BufferContext ctx, int ringCount) {
        for (final var type : ctx.geometryTypes()) {
            if (type != POLYGON && type != MULTIPOLYGON) {
                return null;
            }
        }
        final var outer = new boolean[ringCount];
        var ring = 0;
        for (final var rings : ctx.numParts()) {
            if (rings <= 0 || ring + rings > ringCount) {
                return null;
            }
            outer[ring] = true;
            ring += rings;
        }
        return ring == ringCount ? outer : null;
    }

    // rot90ccw(e) = (-ey, ex); rot90cw(e) = (ey, -ex), with e = p1 - p2.
    public static int ux(boolean ccw, Vertex p1, Vertex p2) {
        final var ey = p1.y() - p2.y();
        return ccw ? -ey : ey;
    }

    public static int uy(boolean ccw, Vertex p1, Vertex p2) {
        final var ex = p1.x() - p2.x();
        return ccw ? ex : -ex;
    }

    public static int scaled(int along, int u, double len) {
        return len == 0 ? 0 : (int) Math.round(along * (double) u / len);
    }

    public static int cornerX(int i, List<Vertex> vertices) {
        return vertices.get(i - 3).x() + vertices.get(i - 1).x() - vertices.get(i - 2).x();
    }

    public static int cornerY(int i, List<Vertex> vertices) {
        return vertices.get(i - 3).y() + vertices.get(i - 1).y() - vertices.get(i - 2).y();
    }
}
