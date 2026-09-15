package org.maplibre.mlt.research.encoding.research.failed;

import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.decoder.DecodingUtils;
import org.maplibre.mlt.research.encoding.PartBoundaries;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.VertexTransform;
import org.maplibre.mlt.research.encoding.research.SegmentQuadCorner;

import java.util.ArrayList;
import java.util.List;

/**
 * Predicts the second half of a polygon ring from its first half by point-reflection, targeting the
 * larger, near-centrally-symmetric rings that dominate {@code landcover} (where the four-vertex
 * {@link SegmentQuadCorner} quad predictor does not reach).
 *
 * <p>A ring is walked as usual through its first half; each second-half vertex is then predicted as
 * the point-reflection of a first-half partner through the midpoint of the ring's {@code
 * start → apex} diagonal:
 *
 * <pre>{@code   pred = start + apex - partner        // integer, exact -> lossless }</pre>
 * <p>
 * This is the parallelogram identity {@code p0 + p2 - p1} generalized to the ring diagonal, so it
 * stays integer-exact and needs no rounding. For a perfectly central-symmetric ring the residual is
 * zero; otherwise it is the small deviation from symmetry — where a first-order delta would carry a
 * full edge. The apex itself stays delta-coded so it is available as an already-decoded prediction
 * input.
 *
 * <p>As in {@link SegmentQuadCorner}, residuals split into contiguous populations packed as separate
 * FastPFOR regions (starts | interior | reflect), so the near-zero reflect residuals are sized to
 * their own narrow width. {@code prevX/prevY} advance continuously through every vertex — no reset
 * seeds — and every class is derivable from the topology streams, so nothing is signalled.
 *
 * <p>Two pairing strategies, measured against each other:
 *
 * <ul>
 *   <li>{@link Pairing#SYMMETRIC} — mirror across ring traversal: position {@code r} pairs with
 *       {@code L - r}, apex at {@code L/2}.
 *   <li>{@link Pairing#SPLIT} — linear split: position {@code L/2 + t} pairs with {@code t}, apex at
 *       {@code L/2}.
 * </ul>
 * <p>
 * Rings shorter than {@link #MIN_RING} stay pure delta (no meaningful half).
 *
 * <p>Unregistered — measured and rejected: the symmetry prior does not hold for this corpus, so
 * the reflection residual is wider than a first-order delta rather than narrower. Kept, with its
 * mechanism test, so the negative result need not be re-derived. Measured:
 * {@code docs/results/failed/midpoint-reflect.md}.
 */
public final class MidpointReflect implements VertexTransform {

    /**
     * Below this ring length a "half" is not meaningful; the ring stays delta-coded.
     */
    private static final int MIN_RING = 8;

    // INTERIOR is 0 so the default-zeroed class array already means "interior".
    private static final int INTERIOR = 0;
    private static final int START = 1;
    private static final int REFLECT = 2;

    public enum Pairing {
        SYMMETRIC,
        SPLIT
    }

    private final Pairing pairing;

    public MidpointReflect(Pairing pairing) {
        this.pairing = pairing;
    }

    @Override
    public String name() {
        return "MIDPOINT_REFLECT";
    }

    @Override
    public int[] apply(BufferContext ctx) {
        final var vertices = ctx.vertices();
        final var starts = PartBoundaries.of(ctx, vertices.size());
        final var classes = classify(starts, vertices.size());

        final var values = new int[vertices.size() * 2];
        final var cursors = cursors(classes);
        var prevX = 0;
        var prevY = 0;
        for (var i = 0; i < vertices.size(); i++) {
            final var vertex = vertices.get(i);
            final int predX;
            final int predY;
            if (classes[i] == REFLECT) {
                final var p = predictReflect(vertices, starts, i, vertices.size());
                predX = p.x();
                predY = p.y();
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
        final var starts = PartBoundaries.of(ctx, vertexCount);
        final var classes = classify(starts, vertexCount);

        final var vertices = new ArrayList<Vertex>(vertexCount);
        final var cursors = cursors(classes);
        var prevX = 0;
        var prevY = 0;
        for (var i = 0; i < vertexCount; i++) {
            final int predX;
            final int predY;
            if (classes[i] == REFLECT) {
                final var p = predictReflect(vertices, starts, i, vertexCount);
                predX = p.x();
                predY = p.y();
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
     * Point-reflection of vertex {@code i}'s partner through the {@code start → apex} diagonal:
     * {@code start + apex - partner}. Reads only vertices decoded before {@code i} (partner is in the
     * first half, apex at the midpoint), so it is valid in both the encode pass and the in-order
     * decode pass.
     */
    private Vertex predictReflect(List<Vertex> vertices, int[] starts, int i, int vertexCount) {
        final var start = ringStart(starts, i);
        final var length = ringLength(starts, i, vertexCount);
        final var apexIdx = start + length / 2;
        final var r = i - start;
        final var partnerIdx = start + (pairing == Pairing.SYMMETRIC ? length - r : r - length / 2);
        final var s = vertices.get(start);
        final var apex = vertices.get(apexIdx);
        final var partner = vertices.get(partnerIdx);
        return new Vertex(s.x() + apex.x() - partner.x(), s.y() + apex.y() - partner.y());
    }

    /**
     * Per-vertex class: START (run start), REFLECT (second-half vertex of a ring of length {@code >=
     * MIN_RING}, excluding the apex), INTERIOR otherwise.
     */
    private static int[] classify(int[] starts, int vertexCount) {
        final var classes = new int[vertexCount];
        for (var s = 0; s < starts.length; s++) {
            final var start = starts[s];
            final var end = s + 1 < starts.length ? starts[s + 1] : vertexCount;
            final var length = end - start;
            if (start < vertexCount) {
                classes[start] = START;
            }
            if (length >= MIN_RING) {
                // Second half past the apex; the apex (start + length/2) stays delta-coded.
                for (var i = start + length / 2 + 1; i < end; i++) {
                    classes[i] = REFLECT;
                }
            }
        }
        return classes;
    }

    /**
     * Start offset (in int slots) of each class's contiguous region: starts, then interior, reflect.
     */
    private static int[] cursors(int[] classes) {
        var startCount = 0;
        var reflectCount = 0;
        for (var c : classes) {
            if (c == START) {
                startCount++;
            } else if (c == REFLECT) {
                reflectCount++;
            }
        }
        final var interior = classes.length - startCount - reflectCount;
        final var cursors = new int[3];
        cursors[START] = 0;
        cursors[INTERIOR] = startCount * 2;
        cursors[REFLECT] = (startCount + interior) * 2;
        return cursors;
    }

    private static int ringStart(int[] starts, int i) {
        var start = starts[0];
        for (final var s : starts) {
            if (s <= i) {
                start = s;
            } else {
                break;
            }
        }
        return start;
    }

    private static int ringLength(int[] starts, int i, int vertexCount) {
        for (var s = 0; s < starts.length; s++) {
            if (starts[s] <= i && (s + 1 == starts.length || starts[s + 1] > i)) {
                return (s + 1 < starts.length ? starts[s + 1] : vertexCount) - starts[s];
            }
        }
        return vertexCount - starts[0];
    }
}
