package org.maplibre.mlt.research.pipeline;

import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.Encoding;
import org.maplibre.mlt.research.encoding.StreamMetadata;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.EncodedBuffer;
import org.maplibre.mlt.research.encoding.research.SharedVertexRef;

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

    private static final int PREDICTOR_BITS = 3;
    private static final int PREDICTOR_MASK = 0b111;
    private static final int PREDICTOR_HIGH_SHIFT = 4;
    private static final int WIDTH_MASK = 0b1111;
    private static final int SEGMENTATION_SHIFT = 3;
    private static final int SEGMENTATION_MASK = 0b11;
    private static final int MAP_SHIFT = 5;
    private static final int MAP_MASK = 0b1;
    private static final int TRANSPOSE_SHIFT = 6;
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
        // the references rotate the rings that share a wall and reverse the line parts that end on a
        // junction, so a shared row emits a different buffer
        return config.reorder().reorders() || config.predictor().shared();
    }

    /**
     * What the encoder emits for one buffer: the payload and the buffer it decodes to. Equal to the
     * reordered input unless the references were taken, which rotate the rings.
     */
    private record Choice(byte[] payload, BufferContext emitted) {
    }

    @Override
    public BufferContext emitted(BufferContext ctx) {
        return choose(ctx).emitted();
    }

    @Override
    public EncodedBuffer encode(BufferContext ctx) {
        final var payload = choose(ctx).payload();
        return new EncodedBuffer(payload, StreamMetadata.bytes(ctx.vertices().size() * 2, payload.length));
    }

    /**
     * The plain predictor's buffer, or for a shared predictor the smaller of that and the buffer
     * with references. The plain one is written under the plain predictor's id, so the decoder needs
     * no mode byte.
     */
    private Choice choose(BufferContext ctx) {
        final var reordered = config.reorder().apply(ctx);
        if (!config.predictor().shared()) {
            return new Choice(encodeStreams(reordered, config), reordered);
        }
        final var plain = new Choice(encodeStreams(reordered, config.withPredictor(config.predictor().plain())), reordered);
        final var scan = config.predictor().scan(reordered);
        if (scan == null) {
            return plain;
        }
        final var rotated = new BufferContext(
                scan.vertices(),
                reordered.geometryTypes(),
                reordered.numGeometries(),
                reordered.numParts(),
                reordered.numRings());
        final var shared = encodeShared(rotated, scan);
        return shared.length < plain.payload().length ? new Choice(shared, rotated) : plain;
    }

    /** The header and every non-empty stream of {@code emitted} under {@code c}, no references. */
    private static byte[] encodeStreams(BufferContext emitted, PipelineConfig c) {
        final var predictor = c.predictor().predictor();
        final var layout = c.segmentation().layout(emitted, predictor, emitted.vertices().size());
        final var streams = Segmentation.split(layout, predictor, emitted.vertices());
        final var out = new ByteArrayOutputStream();
        out.writeBytes(header(c));
        writeStreams(out, c, layout, streams);
        return out.toByteArray();
    }

    /**
     * Header, the flag bits and the entry indices (each behind its byte length), the length of every
     * stream (the references take vertices out of them, and which ones is only known while decoding),
     * then the streams.
     */
    private byte[] encodeShared(BufferContext rotated, SharedVertexRef.Scan scan) {
        final var predictor = config.predictor().predictor();
        final var layout = config.segmentation()
                .layout(rotated, predictor, rotated.vertices().size())
                .withoutReferenced(scan.referenced());
        final var streams = Segmentation.split(layout, predictor, rotated.vertices(), scan.referenced());
        final var out = new ByteArrayOutputStream();
        out.writeBytes(header(config));
        Bytes.putVarint(out, scan.flags().length);
        out.writeBytes(scan.flags());
        Bytes.putVarint(out, scan.indices().length);
        out.writeBytes(scan.indices());
        for (final var count : layout.valueCounts()) {
            Bytes.putVarint(out, count);
        }
        writeStreams(out, config, layout, streams);
        return out.toByteArray();
    }

    private static void writeStreams(ByteArrayOutputStream out, PipelineConfig c, Segmentation.Layout layout, int[][] streams) {
        final var last = lastNonEmpty(streams.length, s -> streams[s].length > 0);
        for (var s = 0; s < streams.length; s++) {
            if (streams[s].length > 0) {
                StreamCodec.encode(out, streams[s], streamConfig(c, layout, s), s == last);
            }
        }
    }

    @Override
    public List<Vertex> decode(EncodedBuffer encoded, BufferContext ctx, int vertexCount) {
        final var in = new Bytes.Reader(encoded.payload(), 0);
        final var decoded = parseHeader(in.readByte(), in.readByte(), config.reorder());
        final var topo = decoded.reorder().topology(ctx);
        final var predictor = decoded.predictor().predictor();
        var layout = decoded.segmentation().layout(topo, predictor, vertexCount);

        SharedVertexRef.ReferenceWalk walk = null;
        if (decoded.predictor().shared()) {
            final var flags = in.readBytes(in.readVarint());
            final var indices = in.readBytes(in.readVarint());
            final var counts = new int[layout.streamCount()];
            for (var s = 0; s < counts.length; s++) {
                counts[s] = in.readVarint();
            }
            layout = layout.withCounts(counts);
            walk = decoded.predictor().walkForDecoding(topo, vertexCount, flags, indices);
        }

        final var counts = layout.valueCounts();
        final var last = lastNonEmpty(counts.length, s -> counts[s] > 0);
        final var streams = new int[counts.length][];
        for (var s = 0; s < counts.length; s++) {
            if (counts[s] == 0) {
                streams[s] = new int[0];
            } else {
                streams[s] = StreamCodec.decode(in, counts[s], streamConfig(decoded, layout, s), s == last);
            }
        }
        if (in.remaining() != 0) {
            throw new IllegalStateException(in.remaining() + " bytes left after the last stream: the lengths derived from the topology do not match what was written");
        }
        return Segmentation.join(layout, predictor, streams, vertexCount, walk);
    }

    /**
     * The configuration stream {@code s} is written with. A side stream is not {@code x, y} pairs and
     * can be of odd length, so the transposes that separate the components fall back to byte planes.
     */
    private static PipelineConfig streamConfig(PipelineConfig c, Segmentation.Layout layout, int s) {
        final var separatesComponents = c.transpose() != Transpose.NONE && c.transpose() != Transpose.INTERLEAVED_PLANES;
        return s == layout.sideStream() && separatesComponents ? c.withTranspose(Transpose.INTERLEAVED_PLANES) : c;
    }

    /**
     * Predictor (low 3 bits), segmentation, map and transpose in byte 0 (3 bits, 2 bits, 1 bit, 2 bits),
     * the byte-width terminal in byte 1 (low 4 bits) with the predictor's fourth bit above it: the
     * predictors outgrew the three bits byte 0 has for them.
     */
    static byte[] header(PipelineConfig c) {
        final var predictor = c.predictor().ordinal();
        final var first = (predictor & PREDICTOR_MASK)
                | c.segmentation().ordinal() << SEGMENTATION_SHIFT
                | c.map().ordinal() << MAP_SHIFT
                | c.transpose().ordinal() << TRANSPOSE_SHIFT;
        final var second = c.width().ordinal() | (predictor >> PREDICTOR_BITS) << PREDICTOR_HIGH_SHIFT;
        return new byte[]{(byte) first, (byte) second};
    }

    /** The inverse of {@link #header}; the reorder is not in it, so the caller supplies it. */
    static PipelineConfig parseHeader(int first, int second, Reorder reorder) {
        return new PipelineConfig(
                reorder,
                Prediction.values()[(first & PREDICTOR_MASK) | (second >> PREDICTOR_HIGH_SHIFT) << PREDICTOR_BITS],
                Segmentation.values()[(first >> SEGMENTATION_SHIFT) & SEGMENTATION_MASK],
                ResidualMap.values()[(first >> MAP_SHIFT) & MAP_MASK],
                ByteWidth.values()[second & WIDTH_MASK],
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
