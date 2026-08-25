package org.maplibre.mlt.research.encoding.research;

import java.util.ArrayList;
import java.util.List;

import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.decoder.DecodingUtils;
import org.maplibre.mlt.research.encoding.PartBoundaries;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.VertexTransform;

/**
 * Composes the two winning transforms — {@link SegmentPair}'s part-start grouping and {@link
 * QuadCorner}'s parallelogram-corner predictor — which target disjoint geometry populations, so
 * they stack rather than compete: {@code SegmentPair} wins LINESTRING, {@code QuadCorner} wins
 * POLYGON, and this wins both.
 *
 * <p>Every residual is the same componentwise delta + zigzag as the baseline, split into three
 * populations packed as contiguous regions so FastPFOR can size each to its own width:
 *
 * <ol>
 *   <li><b>part starts</b> — the first vertex of each ring run, delta against the previous vertex.
 *       These are the wide boundary spikes {@code SegmentPair} separates out.
 *   <li><b>interior</b> — every other vertex, delta against the previous vertex. The narrow
 *       population.
 *   <li><b>quad corners</b> — the fourth vertex of a run that is exactly four vertices, predicted
 *       as the parallelogram corner {@code p0 + p2 - p1}. Near-zero for the 40,7% of building quads
 *       that are exact parallelograms, and pulled out of its ring's block so it does not inflate to
 *       the width of the three wide edge deltas.
 * </ol>
 *
 * <p>The three classes are mutually exclusive: a run's fourth vertex ({@code start+3}) is never its
 * start. {@code prevX/prevY} advance continuously through the actual vertices regardless of class —
 * no reset seeds. The class of every vertex is derivable from the topology streams on both sides, so
 * nothing is signalled; the values are only reordered.
 */
public final class SegmentQuadCorner implements VertexTransform {

    // INTERIOR is 0 so the default-zeroed class array already means "interior".
    private static final int INTERIOR = 0;
    private static final int START = 1;
    private static final int CORNER = 2;

    @Override
    public String name() {
        return "SEGMENT_QUAD_CORNER";
    }

    @Override
    public int[] apply(BufferContext ctx) {
        final var vertices = ctx.vertices();
        final var classes = classify(PartBoundaries.of(ctx, vertices.size()), vertices.size());

        final var values = new int[vertices.size() * 2];
        final var cursors = cursors(classes);
        var prevX = 0;
        var prevY = 0;
        for (var i = 0; i < vertices.size(); i++) {
            final var vertex = vertices.get(i);
            final int predX;
            final int predY;
            if (classes[i] == CORNER) {
                predX = vertices.get(i - 3).x() + vertices.get(i - 1).x() - vertices.get(i - 2).x();
                predY = vertices.get(i - 3).y() + vertices.get(i - 1).y() - vertices.get(i - 2).y();
            } else {
                predX = prevX;
                predY = prevY;
            }
            final var slot = cursors[classes[i]];
            values[slot] = EncodingUtils.encodeZigZag(vertex.x() - predX);
            values[slot + 1] = EncodingUtils.encodeZigZag(vertex.y() - predY);
            cursors[classes[i]] += 2;
            prevX = vertex.x();
            prevY = vertex.y();
        }
        return values;
    }

    @Override
    public List<Vertex> invert(int[] values, BufferContext ctx) {
        final var vertexCount = values.length / 2;
        final var classes = classify(PartBoundaries.of(ctx, vertexCount), vertexCount);

        final var vertices = new ArrayList<Vertex>(vertexCount);
        final var cursors = cursors(classes);
        var prevX = 0;
        var prevY = 0;
        for (var i = 0; i < vertexCount; i++) {
            final int predX;
            final int predY;
            if (classes[i] == CORNER) {
                predX = vertices.get(i - 3).x() + vertices.get(i - 1).x() - vertices.get(i - 2).x();
                predY = vertices.get(i - 3).y() + vertices.get(i - 1).y() - vertices.get(i - 2).y();
            } else {
                predX = prevX;
                predY = prevY;
            }
            final var slot = cursors[classes[i]];
            final var x = predX + DecodingUtils.decodeZigZag(values[slot]);
            final var y = predY + DecodingUtils.decodeZigZag(values[slot + 1]);
            cursors[classes[i]] += 2;
            vertices.add(new Vertex(x, y));
            prevX = x;
            prevY = y;
        }
        return vertices;
    }

    /**
     * Per-vertex class: START (run start), CORNER (4th vertex of a 4-run), INTERIOR otherwise.
     */
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

    /**
     * Start offset (in int slots) of each class's contiguous region: starts, then interior, corners.
     */
    private static int[] cursors(int[] classes) {
        var starts = 0;
        var corners = 0;
        for (var c : classes) {
            if (c == START) {
                starts++;
            } else if (c == CORNER) {
                corners++;
            }
        }
        final var interior = classes.length - starts - corners;
        final var cursors = new int[3];
        cursors[START] = 0;
        cursors[INTERIOR] = starts * 2;
        cursors[CORNER] = (starts + interior) * 2;
        return cursors;
    }
}
