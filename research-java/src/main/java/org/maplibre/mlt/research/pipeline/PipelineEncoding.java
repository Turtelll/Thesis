package org.maplibre.mlt.research.pipeline;

import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.Encoding;
import org.maplibre.mlt.research.encoding.StreamMetadata;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.EncodedBuffer;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * One row of the OpenZL-style pipeline, composed from a {@link PipelineConfig}:
 *
 * <pre>S1 reorder -> S2 predict -> S3 segment -> S4 zigzag -> S6 width -> S7 transpose</pre>
 *
 * followed by whichever general-purpose compressor the tile is delivered under (S8, applied by the
 * benchmark per tile, not here). S5 (a per-stream GCD) was measured and removed; the later stage
 * numbers are unchanged.
 *
 * <p>Payload: a two-byte header carrying every choice but the reorder, then each non-empty stream.
 * Stream lengths are never written; they follow from the topology streams and the predictor.
 */
public final class PipelineEncoding implements Encoding {

    /** Header size in bytes; the anchor tests compare the body after it. */
    public static final int HEADER_BYTES = 2;

    private static final int PREDICTOR_MASK = 0b11;
    private static final int SEGMENTATION_SHIFT = 2;
    private static final int SEGMENTATION_MASK = 0b11;
    private static final int MAP_SHIFT = 4;
    private static final int MAP_MASK = 0b1;
    private static final int TRANSPOSE_SHIFT = 5;
    private static final int TRANSPOSE_MASK = 0b11;

    private final PipelineConfig config;

    public PipelineEncoding(PipelineConfig config) {
        if (config.width().varint() != null && config.transpose() != Transpose.NONE) {
            throw new IllegalArgumentException("a byte-plane transpose needs a fixed width: " + config.name());
        }
        this.config = config;
    }

    public PipelineConfig config() {
        return config;
    }

    @Override
    public String name() {
        return config.name();
    }

    @Override
    public String toString() {
        return name();
    }

    @Override
    public boolean reordersGeometries() {
        return config.reorder().reorders();
    }

    @Override
    public BufferContext emitted(BufferContext ctx) {
        return config.reorder().apply(ctx);
    }

    @Override
    public EncodedBuffer encode(BufferContext ctx) {
        final var emitted = config.reorder().apply(ctx);
        final var predictor = config.predictor().predictor();
        final var n = emitted.vertices().size();
        final var layout = config.segmentation().layout(emitted, predictor, n);
        final var streams = Segmentation.split(layout, predictor, emitted.vertices());

        final var out = new ByteArrayOutputStream();
        out.writeBytes(header(config));
        final var last = lastNonEmpty(streams.length, s -> streams[s].length > 0);
        for (var s = 0; s < streams.length; s++) {
            if (streams[s].length > 0) {
                StreamCodec.encode(out, streams[s], config, s == last);
            }
        }
        final var payload = out.toByteArray();
        return new EncodedBuffer(payload, StreamMetadata.bytes(n * 2, payload.length));
    }

    @Override
    public List<Vertex> decode(EncodedBuffer encoded, BufferContext ctx, int vertexCount) {
        final var in = new Bytes.Reader(encoded.payload(), 0);
        final var decoded = parseHeader(in.readByte(), in.readByte(), config.reorder());
        final var topo = decoded.reorder().topology(ctx);
        final var predictor = decoded.predictor().predictor();
        final var layout = decoded.segmentation().layout(topo, predictor, vertexCount);

        final var counts = layout.valueCounts();
        final var last = lastNonEmpty(counts.length, s -> counts[s] > 0);
        final var streams = new int[counts.length][];
        for (var s = 0; s < counts.length; s++) {
            if (counts[s] == 0) {
                streams[s] = new int[0];
            } else {
                streams[s] = StreamCodec.decode(in, counts[s], decoded, s == last);
            }
        }
        if (in.remaining() != 0) {
            throw new IllegalStateException(in.remaining() + " bytes left after the last stream: the lengths derived from the topology do not match what was written");
        }
        return Segmentation.join(layout, predictor, streams, vertexCount);
    }

    /**
     * Predictor, segmentation, map and transpose in byte 0 (2 bits, 2 bits, 1 bit, 2 bits), the
     * byte-width terminal in byte 1.
     */
    static byte[] header(PipelineConfig c) {
        final var first = c.predictor().ordinal()
                | c.segmentation().ordinal() << SEGMENTATION_SHIFT
                | c.map().ordinal() << MAP_SHIFT
                | c.transpose().ordinal() << TRANSPOSE_SHIFT;
        return new byte[]{(byte) first, (byte) c.width().ordinal()};
    }

    /** The inverse of {@link #header}; the reorder is not in it, so the caller supplies it. */
    static PipelineConfig parseHeader(int first, int second, Reorder reorder) {
        return new PipelineConfig(
                reorder,
                Prediction.values()[first & PREDICTOR_MASK],
                Segmentation.values()[(first >> SEGMENTATION_SHIFT) & SEGMENTATION_MASK],
                ResidualMap.values()[(first >> MAP_SHIFT) & MAP_MASK],
                ByteWidth.values()[second],
                Transpose.values()[(first >> TRANSPOSE_SHIFT) & TRANSPOSE_MASK]);
    }

    /** Highest index for which {@code occupied} holds, or -1 when none does. */
    private static int lastNonEmpty(int count, IntPredicate occupied) {
        for (var s = count - 1; s >= 0; s--) {
            if (occupied.test(s)) {
                return s;
            }
        }
        return -1;
    }
}
