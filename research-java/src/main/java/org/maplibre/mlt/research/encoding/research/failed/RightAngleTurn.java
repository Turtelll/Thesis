package org.maplibre.mlt.research.encoding.research.failed;

import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.decoder.DecodingUtils;
import org.maplibre.mlt.research.encoding.Encoding;
import org.maplibre.mlt.research.encoding.IntCodec;
import org.maplibre.mlt.research.encoding.PartBoundaries;
import org.maplibre.mlt.research.encoding.StreamMetadata;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.EncodedBuffer;

import java.util.ArrayList;
import java.util.List;

/**
 * Predicts each interior vertex of a &gt;4-vertex polygon ring as a 90° turn off the previous edge,
 * and <b>signals one bit per such vertex</b> saying whether the turn is right (cw) or left (ccw).
 * The encoder picks, per vertex, the direction whose residual is smaller; the decoder reads the bit
 * back. {@code building} outlines are 82,2% right-angled, so the residual on the turn <i>direction</i>
 * is often near-zero.
 *
 * <p>Unlike every {@link org.maplibre.mlt.research.encoding.VertexTransform} here — which only
 * reorders information and signals nothing — this encoding <b>adds a signalled side channel</b>, so
 * it cannot be a plain transform over the {@code vertexCount*2} value stream. It implements {@link
 * Encoding} directly and owns two byte regions, counting a {@link StreamMetadata} header for each so
 * {@link EncodedBuffer#totalBytes()} reflects the true 1-bit/vertex signal cost:
 *
 * <ul>
 *   <li>the residual {@code int[]} (2 slots per vertex, delta+zigzag, interleaved in vertex order),
 *       packed by the injected {@link IntCodec};
 *   <li>a bit-packed turn-direction stream — one bit per turn-predicted vertex, LSB-first.
 * </ul>
 *
 * <p>A vertex is <b>turn-predicted</b> iff its ring is longer than four vertices and it has two
 * in-ring predecessors ({@code i - start >= 2}), so the edge {@code p[i-1]-p[i-2]} is defined.
 * Everything else — the first two vertices of such a ring and every vertex of a ≤4-vertex ring —
 * stays first-order delta. {@code prevX/prevY} advance continuously through the actual vertices
 * regardless of class; no reset seeds. The class of every vertex is derivable from the topology
 * streams on both sides, so only the turn direction is signalled.
 *
 * <p>Registered as a negative result — the signal cost is not covered by the direction saving.
 * Measured: {@code docs/results/failed/right-angle-turn.md}.
 */
public final class RightAngleTurn implements Encoding {

    private final String name;
    private final IntCodec codec;

    public RightAngleTurn(String name, IntCodec codec) {
        this.name = name;
        this.codec = codec;
    }

    @Override
    public String name() {
        return name;
    }

    /**
     * The residual value stream plus the chosen turn-direction bits, before physical coding.
     */
    record Transformed(int[] values, List<Boolean> turns) {
    }

    @Override
    public EncodedBuffer encode(BufferContext ctx) {
        final var vertices = ctx.vertices();
        final var transformed =
                transform(vertices, classify(PartBoundaries.of(ctx, vertices.size()), vertices.size()));

        final var residual = codec.encode(transformed.values());
        final var flags = packBits(transformed.turns());
        final var payload = concat(residual, flags);
        final var metadataBytes =
                StreamMetadata.bytes(transformed.values().length, residual.length)
                        + StreamMetadata.bytes(transformed.turns().size(), flags.length);
        return new EncodedBuffer(payload, metadataBytes);
    }

    /**
     * Builds the residual stream and picks a turn direction per turn-predicted vertex.
     */
    static Transformed transform(List<Vertex> vertices, boolean[] isTurn) {
        final var values = new int[vertices.size() * 2];
        final var turns = new ArrayList<Boolean>();
        var prevX = 0;
        var prevY = 0;
        for (var i = 0; i < vertices.size(); i++) {
            final var vertex = vertices.get(i);
            if (isTurn[i]) {
                final var p1 = vertices.get(i - 1);
                final var edgeX = p1.x() - vertices.get(i - 2).x();
                final var edgeY = p1.y() - vertices.get(i - 2).y();
                // rot90 ccw of (edgeX,edgeY) is (-edgeY, edgeX); cw is (edgeY, -edgeX).
                final var ccwX = EncodingUtils.encodeZigZag(vertex.x() - (p1.x() - edgeY));
                final var ccwY = EncodingUtils.encodeZigZag(vertex.y() - (p1.y() + edgeX));
                final var cwX = EncodingUtils.encodeZigZag(vertex.x() - (p1.x() + edgeY));
                final var cwY = EncodingUtils.encodeZigZag(vertex.y() - (p1.y() - edgeX));
                // Cheaper direction wins; tie goes to ccw. Bit true == cw.
                final var cw = (long) cwX + cwY < (long) ccwX + ccwY;
                turns.add(cw);
                values[i * 2] = cw ? cwX : ccwX;
                values[i * 2 + 1] = cw ? cwY : ccwY;
            } else {
                values[i * 2] = EncodingUtils.encodeZigZag(vertex.x() - prevX);
                values[i * 2 + 1] = EncodingUtils.encodeZigZag(vertex.y() - prevY);
            }
            prevX = vertex.x();
            prevY = vertex.y();
        }
        return new Transformed(values, turns);
    }

    /** The interleaved residual stream {@link #encode} hands the codec; the direction bits are not in it. */
    public int[] residuals(BufferContext ctx) {
        final var vertices = ctx.vertices();
        return transform(vertices, classify(PartBoundaries.of(ctx, vertices.size()), vertices.size())).values();
    }

    /** Number of turn-direction bits {@link #encode} signals besides the residuals. */
    public int turnBits(BufferContext ctx) {
        var bits = 0;
        for (final var turn : classify(PartBoundaries.of(ctx, ctx.vertices().size()), ctx.vertices().size())) {
            if (turn) {
                bits++;
            }
        }
        return bits;
    }

    @Override
    public List<Vertex> decode(EncodedBuffer encoded, BufferContext ctx, int vertexCount) {
        final var isTurn = classify(PartBoundaries.of(ctx, vertexCount), vertexCount);

        final var payload = encoded.payload();
        final var residualLen = readLengthPrefix(payload);
        final var values = codec.decode(sliceResidual(payload), vertexCount * 2);
        final var flags = sliceFlags(payload, residualLen);

        final var vertices = new ArrayList<Vertex>(vertexCount);
        var turnCursor = 0;
        var prevX = 0;
        var prevY = 0;
        for (var i = 0; i < vertexCount; i++) {
            final int x;
            final int y;
            if (isTurn[i]) {
                final var p1 = vertices.get(i - 1);
                final var edgeX = p1.x() - vertices.get(i - 2).x();
                final var edgeY = p1.y() - vertices.get(i - 2).y();
                final var cw = readBit(flags, turnCursor++);
                final var predX = cw ? p1.x() + edgeY : p1.x() - edgeY;
                final var predY = cw ? p1.y() - edgeX : p1.y() + edgeX;
                x = predX + DecodingUtils.decodeZigZag(values[i * 2]);
                y = predY + DecodingUtils.decodeZigZag(values[i * 2 + 1]);
            } else {
                x = prevX + DecodingUtils.decodeZigZag(values[i * 2]);
                y = prevY + DecodingUtils.decodeZigZag(values[i * 2 + 1]);
            }
            vertices.add(new Vertex(x, y));
            prevX = x;
            prevY = y;
        }
        return vertices;
    }

    /**
     * Marks each turn-predicted vertex: ring length &gt; 4 and at least two in-ring predecessors, so
     * the previous edge is defined.
     */
    static boolean[] classify(int[] starts, int vertexCount) {
        final var isTurn = new boolean[vertexCount];
        for (var s = 0; s < starts.length; s++) {
            final var start = starts[s];
            final var end = s + 1 < starts.length ? starts[s + 1] : vertexCount;
            if (end - start > 4) {
                for (var i = start + 2; i < end; i++) {
                    isTurn[i] = true;
                }
            }
        }
        return isTurn;
    }

    // --- turn-direction bit stream ------------------------------------------------

    private static byte[] packBits(List<Boolean> bits) {
        final var bytes = new byte[(bits.size() + 7) / 8];
        for (var i = 0; i < bits.size(); i++) {
            if (bits.get(i)) {
                bytes[i / 8] |= (byte) (1 << (i % 8));
            }
        }
        return bytes;
    }

    private static boolean readBit(byte[] bytes, int i) {
        return (bytes[i / 8] & (1 << (i % 8))) != 0;
    }

    // --- payload framing: [4-byte residualLen][residual][flags] -------------------

    private static byte[] concat(byte[] residual, byte[] flags) {
        final var out = new byte[4 + residual.length + flags.length];
        out[0] = (byte) (residual.length >>> 24);
        out[1] = (byte) (residual.length >>> 16);
        out[2] = (byte) (residual.length >>> 8);
        out[3] = (byte) residual.length;
        System.arraycopy(residual, 0, out, 4, residual.length);
        System.arraycopy(flags, 0, out, 4 + residual.length, flags.length);
        return out;
    }

    private static int readLengthPrefix(byte[] payload) {
        return ((payload[0] & 0xFF) << 24)
                | ((payload[1] & 0xFF) << 16)
                | ((payload[2] & 0xFF) << 8)
                | (payload[3] & 0xFF);
    }

    private static byte[] sliceResidual(byte[] payload) {
        final var len = readLengthPrefix(payload);
        final var residual = new byte[len];
        System.arraycopy(payload, 4, residual, 0, len);
        return residual;
    }

    private static byte[] sliceFlags(byte[] payload, int residualLen) {
        final var start = 4 + residualLen;
        final var flags = new byte[payload.length - start];
        System.arraycopy(payload, start, flags, 0, flags.length);
        return flags;
    }
}
