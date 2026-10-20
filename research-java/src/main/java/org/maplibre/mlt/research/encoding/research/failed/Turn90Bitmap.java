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
import java.util.Arrays;
import java.util.List;

/**
 * Every edge of a polygon ring predicted as the previous edge turned by 90°, with one signalled bit
 * per predicted vertex choosing the direction.
 *
 * <p>Per polygon ring: the first vertex is plain delta from the vertex before it, the second is plain
 * delta (the first edge). From the third on, with {@code d = v[i-1] - v[i-2]}, the prediction is
 * {@code v[i-1] + (-dy, dx)} (bit 0) or {@code v[i-1] + (dy, -dx)} (bit 1); the encoder picks the one
 * with fewer zigzag residual bits, ties going to bit 0. The rotated edge keeps the previous edge's
 * length, so on a rectangle the residual is the difference of the two side lengths. Line and point
 * vertices are plain delta and carry no bit.
 *
 * <p>The bit count — {@code max(0, n - 2)} per polygon ring — is derived from the topology streams,
 * so the bitmap needs no length of its own. It is packed MSB-first behind the codec's payload and
 * priced as a second stream with its own {@link StreamMetadata} header.
 *
 * <p>Regression, unregistered: +3,7% building bytes (FastPFOR) — see {@code
 * docs/results/failed/turn90-bitmap.md}. Kept for its mechanism test and for {@code Turn90BitmapCli}.
 */
public final class Turn90Bitmap implements Encoding {

    private final IntCodec codec;

    public Turn90Bitmap(IntCodec codec) {
        this.codec = codec;
    }

    @Override
    public String name() {
        return transformName() + "_" + codec.name();
    }

    @Override
    public String toString() {
        return name();
    }

    /**
     * {@link #name()} without the codec.
     */
    public String transformName() {
        return "TURN90_BITMAP";
    }

    @Override
    public EncodedBuffer encode(BufferContext ctx) {
        final var streams = streams(ctx);
        final var residualBytes = codec.encode(streams.residuals());
        final var bitmap = pack(streams.turns());
        final var payload = Arrays.copyOf(residualBytes, residualBytes.length + bitmap.length);
        System.arraycopy(bitmap, 0, payload, residualBytes.length, bitmap.length);
        return new EncodedBuffer(
                payload,
                StreamMetadata.bytes(streams.residuals().length, residualBytes.length)
                        + StreamMetadata.bytes(streams.turns().length, bitmap.length));
    }

    @Override
    public List<Vertex> decode(EncodedBuffer encoded, BufferContext ctx, int vertexCount) {
        final var predicted = predicted(ctx, vertexCount);
        var turnCount = 0;
        for (final var p : predicted) {
            if (p) {
                turnCount++;
            }
        }
        final var payload = encoded.payload();
        final var residualLength = payload.length - (turnCount + 7) / 8;
        final var residuals = codec.decode(Arrays.copyOf(payload, residualLength), vertexCount * 2);

        final var vertices = new ArrayList<Vertex>(vertexCount);
        var turn = 0;
        var prevX = 0;
        var prevY = 0;
        for (var i = 0; i < vertexCount; i++) {
            var predX = prevX;
            var predY = prevY;
            if (predicted[i]) {
                final var dx = prevX - vertices.get(i - 2).x();
                final var dy = prevY - vertices.get(i - 2).y();
                final var bit = (payload[residualLength + turn / 8] >>> (7 - turn % 8)) & 1;
                turn++;
                predX += bit == 0 ? -dy : dy;
                predY += bit == 0 ? dx : -dx;
            }
            final var x = predX + DecodingUtils.decodeZigZag(residuals[2 * i]);
            final var y = predY + DecodingUtils.decodeZigZag(residuals[2 * i + 1]);
            vertices.add(new Vertex(x, y));
            prevX = x;
            prevY = y;
        }
        return vertices;
    }

    /**
     * Zigzag residuals, interleaved {@code x, y} per vertex, plus one flag per predicted vertex
     * ({@code true} = bit 1 = {@code (dy, -dx)}).
     */
    public record Streams(int[] residuals, boolean[] turns) {
    }

    public static Streams streams(BufferContext ctx) {
        final var vertices = ctx.vertices();
        final var predicted = predicted(ctx, vertices.size());
        final var residuals = new int[vertices.size() * 2];
        final var turns = new ArrayList<Boolean>();
        var prevX = 0;
        var prevY = 0;
        for (var i = 0; i < vertices.size(); i++) {
            final var v = vertices.get(i);
            var rx = EncodingUtils.encodeZigZag(v.x() - prevX);
            var ry = EncodingUtils.encodeZigZag(v.y() - prevY);
            if (predicted[i]) {
                final var dx = prevX - vertices.get(i - 2).x();
                final var dy = prevY - vertices.get(i - 2).y();
                final var ccwX = EncodingUtils.encodeZigZag(v.x() - (prevX - dy));
                final var ccwY = EncodingUtils.encodeZigZag(v.y() - (prevY + dx));
                final var cwX = EncodingUtils.encodeZigZag(v.x() - (prevX + dy));
                final var cwY = EncodingUtils.encodeZigZag(v.y() - (prevY - dx));
                final var cw = bits(cwX) + bits(cwY) < bits(ccwX) + bits(ccwY);
                turns.add(cw);
                rx = cw ? cwX : ccwX;
                ry = cw ? cwY : ccwY;
            }
            residuals[2 * i] = rx;
            residuals[2 * i + 1] = ry;
            prevX = v.x();
            prevY = v.y();
        }
        final var flags = new boolean[turns.size()];
        for (var t = 0; t < flags.length; t++) {
            flags[t] = turns.get(t);
        }
        return new Streams(residuals, flags);
    }

    /**
     * Whether vertex {@code i} is the third or later vertex of a polygon ring, derived from the
     * topology streams alone. A buffer whose runs cannot be mapped to geometry types predicts nothing.
     */
    public static boolean[] predicted(BufferContext ctx, int vertexCount) {
        final var predicted = new boolean[vertexCount];
        final var starts = PartBoundaries.of(ctx, vertexCount);
        final var isLine = PartBoundaries.lineRuns(ctx, starts.length);
        if (isLine == null) {
            return predicted;
        }
        for (var r = 0; r < starts.length; r++) {
            if (isLine[r]) {
                continue;
            }
            final var end = r + 1 < starts.length ? starts[r + 1] : vertexCount;
            for (var i = starts[r] + 2; i < end; i++) {
                predicted[i] = true;
            }
        }
        return predicted;
    }

    private static byte[] pack(boolean[] flags) {
        final var out = new byte[(flags.length + 7) / 8];
        for (var t = 0; t < flags.length; t++) {
            if (flags[t]) {
                out[t / 8] |= (byte) (1 << (7 - t % 8));
            }
        }
        return out;
    }

    private static int bits(int zigzag) {
        return 32 - Integer.numberOfLeadingZeros(zigzag);
    }
}
