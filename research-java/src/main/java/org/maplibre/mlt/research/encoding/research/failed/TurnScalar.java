package org.maplibre.mlt.research.encoding.research.failed;

import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.decoder.DecodingUtils;
import org.maplibre.mlt.research.encoding.Layout;
import org.maplibre.mlt.research.encoding.PartBoundaries;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.VertexTransform;

import java.util.ArrayList;
import java.util.List;

/**
 * Winding-fixed 90° turn with one edge-length scalar, composed with {@link
 * org.maplibre.mlt.research.encoding.research.SegmentQuadCorner}'s
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
 * <p>Under {@link Layout#SEGMENTED} the values are packed as regions start | interior | corner |
 * along | corr, so the near-zero correction population gets its own FastPFOR width. Turns only
 * fire in polygon-only columns whose {@code numParts} tiles the rings; elsewhere this is exactly
 * {@code QUAD_CORNER}. {@code prevX/prevY} advance continuously through the actual vertices.
 *
 * <p>Unregistered as a negative result — worse than {@code QUAD_CORNER} on every polygon layer,
 * kept for its mechanism test. Measured: {@code docs/results/failed/turn-scalar-encoder.md}.
 */
public final class TurnScalar implements VertexTransform {

    // INTERIOR is 0 so the default-zeroed class array already means "interior".
    public static final int INTERIOR = 0;
    public static final int START = 1;
    public static final int CORNER = 2;
    public static final int TURN = 3;

    private static final int POLYGON = 2;
    private static final int MULTIPOLYGON = 5;

    private final Layout layout;

    public TurnScalar(Layout layout) {
        this.layout = layout;
    }

    @Override
    public String name() {
        return layout == Layout.SEGMENTED ? "TURN_SCALAR_SEG" : "TURN_SCALAR";
    }

    @Override
    public int valueCount(BufferContext ctx, int vertexCount) {
        return vertexCount * 2 + classify(ctx, vertexCount).turns();
    }

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

    @Override
    public int[] apply(BufferContext ctx) {
        final var vertices = ctx.vertices();
        final var c = classify(ctx, vertices.size());
        final var slots = new Slots(c);

        final var values = new int[vertices.size() * 2 + c.turns()];
        var prevX = 0;
        var prevY = 0;
        for (var i = 0; i < vertices.size(); i++) {
            final var vertex = vertices.get(i);
            final var vertexClass = c.classes()[i];
            if (vertexClass == TURN) {
                final var p1 = vertices.get(i - 1);
                final var ux = ux(c.ccw()[i], p1, vertices.get(i - 2));
                final var uy = uy(c.ccw()[i], p1, vertices.get(i - 2));
                final var len = Math.hypot(ux, uy);
                final var wx = vertex.x() - p1.x();
                final var wy = vertex.y() - p1.y();
                final var along = len == 0 ? 0 : (int) Math.round(((double) ux * wx + (double) uy * wy) / len);
                values[slots.along()] = EncodingUtils.encodeZigZag(along);
                final var slot = slots.pair(TURN);
                values[slot] = EncodingUtils.encodeZigZag(wx - scaled(along, ux, len));
                values[slot + 1] = EncodingUtils.encodeZigZag(wy - scaled(along, uy, len));
            } else {
                final var predX = vertexClass == CORNER ? cornerX(i, vertices) : prevX;
                final var predY = vertexClass == CORNER ? cornerY(i, vertices) : prevY;
                final var slot = slots.pair(vertexClass);
                values[slot] = EncodingUtils.encodeZigZag(vertex.x() - predX);
                values[slot + 1] = EncodingUtils.encodeZigZag(vertex.y() - predY);
            }
            prevX = vertex.x();
            prevY = vertex.y();
        }
        return values;
    }

    @Override
    public List<Vertex> invert(int[] values, BufferContext ctx) {
        final var vertexCount = vertexCount(values.length, ctx);
        final var c = classify(ctx, vertexCount);
        final var slots = new Slots(c);

        final var vertices = new ArrayList<Vertex>(vertexCount);
        var prevX = 0;
        var prevY = 0;
        for (var i = 0; i < vertexCount; i++) {
            final var vertexClass = c.classes()[i];
            final int x;
            final int y;
            if (vertexClass == TURN) {
                final var p1 = vertices.get(i - 1);
                final var ux = ux(c.ccw()[i], p1, vertices.get(i - 2));
                final var uy = uy(c.ccw()[i], p1, vertices.get(i - 2));
                final var len = Math.hypot(ux, uy);
                final var along = DecodingUtils.decodeZigZag(values[slots.along()]);
                final var slot = slots.pair(TURN);
                x = p1.x() + scaled(along, ux, len) + DecodingUtils.decodeZigZag(values[slot]);
                y = p1.y() + scaled(along, uy, len) + DecodingUtils.decodeZigZag(values[slot + 1]);
            } else {
                final var predX = vertexClass == CORNER ? cornerX(i, vertices) : prevX;
                final var predY = vertexClass == CORNER ? cornerY(i, vertices) : prevY;
                final var slot = slots.pair(vertexClass);
                x = predX + DecodingUtils.decodeZigZag(values[slot]);
                y = predY + DecodingUtils.decodeZigZag(values[slot + 1]);
            }
            vertices.add(new Vertex(x, y));
            prevX = x;
            prevY = y;
        }
        return vertices;
    }

    /**
     * Every vertex's {@code (x, y)} residual pair in vertex order — a turn's correction, everything
     * else's delta or corner residual — without the {@code along} scalars. Interleaved layout only.
     */
    public int[] residualPairs(BufferContext ctx) {
        if (layout != Layout.INTERLEAVED) {
            throw new IllegalStateException("residual pairs are read in vertex order: interleaved layout only");
        }
        final var values = apply(ctx);
        final var classes = classify(ctx, ctx.vertices().size()).classes();
        final var pairs = new int[classes.length * 2];
        var cursor = 0;
        for (var i = 0; i < classes.length; i++) {
            if (classes[i] == TURN) {
                cursor++;
            }
            pairs[2 * i] = values[cursor++];
            pairs[2 * i + 1] = values[cursor++];
        }
        return pairs;
    }

    /** Number of {@code along} scalars {@link #apply} writes besides the residual pairs. */
    public int alongCount(BufferContext ctx) {
        return classify(ctx, ctx.vertices().size()).turns();
    }

    /**
     * Turns exist only in polygon-only columns, where {@code numRings} tiles the buffer, so there the
     * ring total is the vertex count. Everywhere else the stream is plain 2-per-vertex.
     */
    private static int vertexCount(int valueCount, BufferContext ctx) {
        var ringTotal = 0;
        for (final var length : ctx.numRings()) {
            ringTotal += length;
        }
        return valueCount == ringTotal * 2 + classify(ctx, ringTotal).turns() ? ringTotal : valueCount / 2;
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

    /**
     * Write cursors. Interleaved: one cursor in vertex order, a turn writing along then corr.
     * Segmented: regions start | interior | corner | along | corr.
     */
    private final class Slots {
        private final int[] cursors = new int[TURN + 1];
        private int alongCursor;
        private int next;

        Slots(Classes c) {
            if (layout == Layout.SEGMENTED) {
                final var counts = new int[TURN + 1];
                for (final var vertexClass : c.classes()) {
                    counts[vertexClass]++;
                }
                cursors[START] = 0;
                cursors[INTERIOR] = counts[START] * 2;
                cursors[CORNER] = cursors[INTERIOR] + counts[INTERIOR] * 2;
                alongCursor = cursors[CORNER] + counts[CORNER] * 2;
                cursors[TURN] = alongCursor + counts[TURN];
            }
        }

        int along() {
            return layout == Layout.SEGMENTED ? alongCursor++ : next++;
        }

        int pair(int vertexClass) {
            if (layout == Layout.SEGMENTED) {
                final var slot = cursors[vertexClass];
                cursors[vertexClass] += 2;
                return slot;
            }
            final var slot = next;
            next += 2;
            return slot;
        }
    }
}
