package org.maplibre.mlt.research.encoding.research.failed;

import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.decoder.DecodingUtils;
import org.maplibre.mlt.research.encoding.Encoding;
import org.maplibre.mlt.research.encoding.IntCodec;
import org.maplibre.mlt.research.encoding.StreamMetadata;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.EncodedBuffer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link RdpResidual}, physically split into two FastPFOR blocks instead of one interleaved stream:
 * key-vertex residuals (plain delta, same magnitude as the baseline) in one block, dropped-vertex
 * residuals (small — interpolated-point offsets, see {@link RdpResidual}) in the other.
 *
 * <p>{@link RdpResidual}'s own {@code ClassifiedPredictor}/{@link
 * org.maplibre.mlt.research.encoding.Layout#SEGMENTED} path does not fit here: that grouping is
 * class-based (start/interior/line-interior), derived from topology alone, while RDP's split is
 * "kept" vs "dropped" — exactly the bitmap this class already carries and nothing {@code
 * ClassifiedPredictor} models. So this groups directly off the bitmap instead, the same shape as
 * {@link org.maplibre.mlt.research.encoding.SegmentedEncoding} groups off {@code regionLengths()}.
 *
 * <p>Measured separately from {@code RDP_RESIDUAL_FASTPFOR}'s interleaved layout (see {@code
 * docs/results/line-continuation.md} for why a real median improvement can still cost the same
 * interleaved: FastPFOR prices a block's widest value, and dropped-vertex residuals are far
 * narrower than key-vertex ones — mixing them into one block lets the wide population set the width
 * for the narrow one). Splitting them lets each be packed at its own width.
 */
public final class RdpResidualSegmented implements Encoding {

    private final IntCodec codec;

    /** The original measurement: FastPFOR over both regions. */
    public RdpResidualSegmented() {
        this(IntCodec.FASTPFOR);
    }

    public RdpResidualSegmented(IntCodec codec) {
        this.codec = codec;
    }

    @Override
    public String name() {
        return "RDP_RESIDUAL_SEG_" + codec.name();
    }

    @Override
    public EncodedBuffer encode(BufferContext ctx) {
        final var vertices = ctx.vertices();
        final var runs = RdpResidual.Runs.of(ctx, vertices.size());

        byte[] best = null;
        for (final var epsilonSquared : RdpResidual.EPSILON_SQUARED_CANDIDATES) {
            final var candidate = encodeWith(vertices, runs, epsilonSquared, codec);
            if (best == null || candidate.length < best.length) {
                best = candidate;
            }
        }
        return new EncodedBuffer(best, StreamMetadata.bytes(vertices.size() * 2, best.length));
    }

    private static byte[] encodeWith(
            List<Vertex> vertices, RdpResidual.Runs runs, long epsilonSquared, IntCodec codec) {
        final var kept = runs.keptMask(vertices, epsilonSquared);
        final var bitmap = runs.packBitmap(kept);
        final var split = Regions.split(vertices, runs, kept);
        final var keyPayload = codec.encode(split.keyResiduals());
        final var dropPayload = codec.encode(split.dropResiduals());

        try {
            final var bytes = new ByteArrayOutputStream();
            final var out = new DataOutputStream(bytes);
            EncodingUtils.putVarInt(out, bitmap.length);
            out.write(bitmap);
            EncodingUtils.putVarInt(out, keyPayload.length);
            out.write(keyPayload);
            out.write(dropPayload);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<Vertex> decode(EncodedBuffer encoded, BufferContext ctx, int vertexCount) {
        final var runs = RdpResidual.Runs.of(ctx, vertexCount);
        try {
            final var in = new ByteArrayInputStream(encoded.payload());
            final var bitmapLength = DecodingUtils.decodeVarint(in);
            final var bitmap = in.readNBytes(bitmapLength);
            final var kept = runs.unpackKeptMask(bitmap);
            final var keyCount = Regions.keyCount(runs, kept, vertexCount);
            final var dropCount = vertexCount - keyCount;

            final var keyPayloadLength = DecodingUtils.decodeVarint(in);
            final var keyPayload = in.readNBytes(keyPayloadLength);
            final var dropPayload = in.readAllBytes();

            final var keyResiduals = codec.decode(keyPayload, keyCount * 2);
            final var dropResiduals = codec.decode(dropPayload, dropCount * 2);
            return Regions.reconstruct(runs, kept, keyResiduals, dropResiduals, vertexCount);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Splits/merges the two physical regions, in the same visitation order on both sides: every
     * vertex is visited index-ascending, key vertices' residuals go to one array and dropped
     * vertices' to the other, each array growing only when its kind is visited. Because both {@link
     * #split} and {@link #reconstruct} walk vertices in the same order off the same {@code kept}
     * mask, the two region cursors always agree without needing their own length prefix per run.
     */
    private static final class Regions {

        record Split(int[] keyResiduals, int[] dropResiduals) {
        }

        static int keyCount(RdpResidual.Runs runs, boolean[] kept, int vertexCount) {
            var count = 0;
            for (var i = 0; i < vertexCount; i++) {
                if (kept[i]) {
                    count++;
                }
            }
            return count;
        }

        static Split split(List<Vertex> vertices, RdpResidual.Runs runs, boolean[] kept) {
            final var keyCount = keyCount(runs, kept, vertices.size());
            final var keyResiduals = new int[keyCount * 2];
            final var dropResiduals = new int[(vertices.size() - keyCount) * 2];
            var keyCursor = 0;
            var dropCursor = 0;

            var prevX = 0;
            var prevY = 0;
            for (var r = 0; r < runs.starts.length; r++) {
                final var start = runs.starts[r];
                final var end = runs.end(r);
                if (runs.isLine[r] && end - start >= RdpResidual.MIN_LINE_RUN_LENGTH) {
                    final var startVertex = vertices.get(start);
                    keyResiduals[keyCursor++] = EncodingUtils.encodeZigZag(startVertex.x() - prevX);
                    keyResiduals[keyCursor++] = EncodingUtils.encodeZigZag(startVertex.y() - prevY);
                    prevX = startVertex.x();
                    prevY = startVertex.y();

                    var keyIndex = start;
                    for (var i = start + 1; i < end; i++) {
                        if (kept[i]) {
                            final var a = vertices.get(keyIndex);
                            final var b = vertices.get(i);
                            for (var j = keyIndex + 1; j < i; j++) {
                                final var v = vertices.get(j);
                                final var predicted = RdpResidual.interpolate(a, b, j - keyIndex, i - keyIndex);
                                dropResiduals[dropCursor++] = EncodingUtils.encodeZigZag(v.x() - predicted.x());
                                dropResiduals[dropCursor++] = EncodingUtils.encodeZigZag(v.y() - predicted.y());
                            }
                            keyResiduals[keyCursor++] = EncodingUtils.encodeZigZag(b.x() - prevX);
                            keyResiduals[keyCursor++] = EncodingUtils.encodeZigZag(b.y() - prevY);
                            prevX = b.x();
                            prevY = b.y();
                            keyIndex = i;
                        }
                    }
                } else {
                    for (var i = start; i < end; i++) {
                        final var v = vertices.get(i);
                        keyResiduals[keyCursor++] = EncodingUtils.encodeZigZag(v.x() - prevX);
                        keyResiduals[keyCursor++] = EncodingUtils.encodeZigZag(v.y() - prevY);
                        prevX = v.x();
                        prevY = v.y();
                    }
                }
            }
            return new Split(keyResiduals, dropResiduals);
        }

        static List<Vertex> reconstruct(
                RdpResidual.Runs runs, boolean[] kept, int[] keyResiduals, int[] dropResiduals, int vertexCount) {
            final var vertices = new Vertex[vertexCount];
            var keyCursor = 0;
            var dropCursor = 0;
            var prevX = 0;
            var prevY = 0;
            for (var r = 0; r < runs.starts.length; r++) {
                final var start = runs.starts[r];
                final var end = runs.end(r);
                if (runs.isLine[r] && end - start >= RdpResidual.MIN_LINE_RUN_LENGTH) {
                    final var startX = prevX + DecodingUtils.decodeZigZag(keyResiduals[keyCursor++]);
                    final var startY = prevY + DecodingUtils.decodeZigZag(keyResiduals[keyCursor++]);
                    vertices[start] = new Vertex(startX, startY);
                    prevX = startX;
                    prevY = startY;

                    var keyIndex = start;
                    var i = start + 1;
                    while (i < end) {
                        if (kept[i]) {
                            final var x = prevX + DecodingUtils.decodeZigZag(keyResiduals[keyCursor++]);
                            final var y = prevY + DecodingUtils.decodeZigZag(keyResiduals[keyCursor++]);
                            vertices[i] = new Vertex(x, y);
                            prevX = x;
                            prevY = y;
                            keyIndex = i;
                            i++;
                        } else {
                            var nextKey = i;
                            while (!kept[nextKey]) {
                                nextKey++;
                            }
                            final var a = vertices[keyIndex];
                            final var bx = prevX + DecodingUtils.decodeZigZag(keyResiduals[keyCursor++]);
                            final var by = prevY + DecodingUtils.decodeZigZag(keyResiduals[keyCursor++]);
                            final var b = new Vertex(bx, by);
                            vertices[nextKey] = b;
                            prevX = bx;
                            prevY = by;
                            for (var j = i; j < nextKey; j++) {
                                final var rx = DecodingUtils.decodeZigZag(dropResiduals[dropCursor++]);
                                final var ry = DecodingUtils.decodeZigZag(dropResiduals[dropCursor++]);
                                final var predicted = RdpResidual.interpolate(a, b, j - keyIndex, nextKey - keyIndex);
                                vertices[j] = new Vertex(predicted.x() + rx, predicted.y() + ry);
                            }
                            keyIndex = nextKey;
                            i = nextKey + 1;
                        }
                    }
                } else {
                    for (var v = start; v < end; v++) {
                        final var x = prevX + DecodingUtils.decodeZigZag(keyResiduals[keyCursor++]);
                        final var y = prevY + DecodingUtils.decodeZigZag(keyResiduals[keyCursor++]);
                        vertices[v] = new Vertex(x, y);
                        prevX = x;
                        prevY = y;
                    }
                }
            }
            final var result = new ArrayList<Vertex>(vertexCount);
            for (final var v : vertices) {
                result.add(v);
            }
            return result;
        }
    }
}
