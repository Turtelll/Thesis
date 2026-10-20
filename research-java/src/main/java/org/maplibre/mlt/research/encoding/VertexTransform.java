package org.maplibre.mlt.research.encoding;

import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.converter.encodings.GeometryEncoder;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.decoder.DecodingUtils;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Logical-layer transform between a vertex buffer and the interleaved int stream a codec sees.
 */
public interface VertexTransform {

    /**
     * The transform's contribution to an encoding's name, e.g. {@code DELTA_ZIGZAG}.
     */
    String name();

    /**
     * Transforms a buffer into the interleaved int stream. {@code ctx} carries the topology streams
     * for transforms that reset at part/ring boundaries; {@link #invert} is given the same streams,
     * so the two derive identical run boundaries.
     */
    int[] apply(BufferContext ctx);

    /**
     * Length of the stream {@link #apply} produces, derivable from topology alone so a decoder
     * knows how many values to unpack. Two per vertex unless a transform stores extra values.
     */
    default int valueCount(BufferContext ctx, int vertexCount) {
        return vertexCount * 2;
    }

    /**
     * Whether values {@code 2i} and {@code 2i + 1} of {@link #apply} belong to vertex {@code i}, so
     * a residual can be paired with another transform's residual for the same vertex. False for a
     * layout that permutes the slots, e.g. {@link Layout#SEGMENTED}.
     */
    default boolean vertexAligned() {
        return true;
    }

    /**
     * The inverse of {@link #apply}. {@code ctx} carries the topology streams for transforms that
     * reset at part/ring boundaries; {@link BufferContext#vertices()} must not be read (see {@link
     * Encoding#decode}).
     */
    List<Vertex> invert(int[] values, BufferContext ctx);

    /**
     * Componentwise delta + zigzag, continuous across the whole buffer (x and y tracked
     * independently), matching {@link GeometryEncoder#zigZagDeltaEncodeVertices}.
     */
    final class ZigZagDelta implements VertexTransform {

        @Override
        public String name() {
            return "DELTA_ZIGZAG";
        }

        @Override
        public int[] apply(BufferContext ctx) {
            return GeometryEncoder.zigZagDeltaEncodeVertices(ctx.vertices());
        }

        @Override
        public List<Vertex> invert(int[] values, BufferContext ctx) {
            var vertices = new ArrayList<Vertex>(values.length / 2);
            int prevX = 0;
            int prevY = 0;
            for (int i = 0; i < values.length; i += 2) {
                int x = prevX + DecodingUtils.decodeZigZag(values[i]);
                int y = prevY + DecodingUtils.decodeZigZag(values[i + 1]);
                vertices.add(new Vertex(x, y));
                prevX = x;
                prevY = y;
            }
            return vertices;
        }
    }

    /**
     * Componentwise delta of the deltas + zigzag, continuous across the whole buffer with both
     * predecessors starting at zero — a port of upstream Rust {@code encode_componentwise_delta2_vec2s}
     * (maplibre-tile-spec 7f56208b, v2 {@code Vertex} logical encoding {@code ComponentwiseDelta2}).
     * Unlike {@code LINE_CONTINUATION} it is not gated by geometry type or run length and does not
     * reset at part boundaries.
     */
    final class ZigZagDelta2 implements VertexTransform {

        @Override
        public String name() {
            return "DELTA2_ZIGZAG";
        }

        @Override
        public int[] apply(BufferContext ctx) {
            var vertices = ctx.vertices();
            var values = new int[vertices.size() * 2];
            int prevX = 0, prevY = 0, prevDx = 0, prevDy = 0;
            for (int i = 0; i < vertices.size(); i++) {
                var v = vertices.get(i);
                int dx = v.x() - prevX;
                int dy = v.y() - prevY;
                values[2 * i] = EncodingUtils.encodeZigZag(dx - prevDx);
                values[2 * i + 1] = EncodingUtils.encodeZigZag(dy - prevDy);
                prevX = v.x();
                prevY = v.y();
                prevDx = dx;
                prevDy = dy;
            }
            return values;
        }

        @Override
        public List<Vertex> invert(int[] values, BufferContext ctx) {
            var vertices = new ArrayList<Vertex>(values.length / 2);
            int x = 0, y = 0, dx = 0, dy = 0;
            for (int i = 0; i < values.length; i += 2) {
                dx += DecodingUtils.decodeZigZag(values[i]);
                dy += DecodingUtils.decodeZigZag(values[i + 1]);
                x += dx;
                y += dy;
                vertices.add(new Vertex(x, y));
            }
            return vertices;
        }
    }
}
