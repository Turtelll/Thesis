package org.maplibre.mlt.research.encoding.research;

import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.decoder.DecodingUtils;
import org.maplibre.mlt.research.encoding.Encoding;
import org.maplibre.mlt.research.encoding.IntCodec;
import org.maplibre.mlt.research.encoding.StreamMetadata;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.EncodedBuffer;
import org.maplibre.mlt.research.encoding.research.SharedVertexRef.BitReader;
import org.maplibre.mlt.research.encoding.research.SharedVertexRef.BitWriter;
import org.maplibre.mlt.research.encoding.research.SharedVertexRef.Runs;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * {@link LineContinuation} over roads: line parts that start exactly where the previous part ended
 * are joined into one road, so the continuation predictor runs across what was a feature boundary
 * and the road, not the short feature, is what the length gate sees. Lossless: feature boundaries
 * stay in the topology streams, and the only thing signalled is one bit per line part that has a
 * line part before it, 1 when the part starts at the previous part's last vertex. That vertex is
 * then not transmitted; the decoder copies it.
 *
 * <p>With {@link GeometryReorder#ROAD_CHAIN} as the reorder, features that meet end to start are
 * adjacent, which is what makes the joins exist. Controls take another reorder, or {@code joins =
 * false}, to separate the effect of the order from the effect of the joins. The emitted buffer is
 * the reordered one, verified by {@code GeometryEquivalence}; no part is reversed.
 *
 * <p>Payload: a mode byte. {@code PLAIN} is {@link LineContinuation}'s regions as split varints over
 * the reordered buffer. {@code JOINED} is the varint-framed join bitmap, then the regions of {@link
 * LineContinuation} over the reduced road stream. The encoder keeps the smaller.
 */
public final class RoadChainEncoding implements Encoding {

    private static final int PLAIN = 0;
    private static final int JOINED = 1;

    private final LineContinuation predictor;
    private final GeometryReorder reorder;
    private final boolean joins;
    private final int gate;

    /**
     * The full encoding: {@link GeometryReorder#ROAD_CHAIN} with joins.
     */
    public RoadChainEncoding() {
        this(GeometryReorder.ROAD_CHAIN, true);
    }

    public RoadChainEncoding(GeometryReorder reorder, boolean joins) {
        this(reorder, joins, LineContinuation.MIN_LINE_RUN_LENGTH);
    }

    /**
     * With the continuation length gate {@code gate}: a road, not a feature, must have that many
     * vertices before its 3rd and later vertices are predicted by continuation.
     */
    public RoadChainEncoding(GeometryReorder reorder, boolean joins, int gate) {
        this.reorder = reorder;
        this.joins = joins;
        this.gate = gate;
        this.predictor = new LineContinuation(gate);
    }

    @Override
    public String name() {
        final var base = predictor.name();
        final var suffix = gate == LineContinuation.MIN_LINE_RUN_LENGTH ? "" : "_GATE" + gate;
        if (reorder == GeometryReorder.ROAD_CHAIN) {
            return base + (joins ? "_ROAD_CHAIN" : "_ROAD_ORDER") + suffix;
        }
        return base + reorder.nameSuffix() + (joins ? "_JOINED" : "") + suffix;
    }

    @Override
    public String toString() {
        return name();
    }

    @Override
    public boolean reordersGeometries() {
        return true;
    }

    @Override
    public BufferContext emitted(BufferContext ctx) {
        return reorder.apply(ctx);
    }

    @Override
    public EncodedBuffer encode(BufferContext original) {
        final var ctx = reorder.apply(original);
        final var plain = withMode(PLAIN, encodePlain(ctx));
        var best = plain;
        if (joins) {
            final var runs = Runs.of(ctx, ctx.vertices().size());
            if (runs != null) {
                final var joined = RoadMerge.detectJoins(ctx.vertices(), runs);
                if (RoadMerge.joinCount(joined) > 0) {
                    final var shared = encodeJoined(ctx, runs, joined);
                    if (shared != null && shared.length < plain.length) {
                        best = shared;
                    }
                }
            }
        }
        return new EncodedBuffer(best, StreamMetadata.bytes(original.vertices().size() * 2, best.length));
    }

    @Override
    public List<Vertex> decode(EncodedBuffer encoded, BufferContext original, int vertexCount) {
        // The streams of the reordered buffer: in a tile they are read, here they are recomputed
        // (without vertices), see GeometryReorder#topology.
        final var ctx = reorder.topology(original, vertexCount);
        try {
            final var in = new ByteArrayInputStream(encoded.payload());
            final var mode = in.read();
            if (mode == PLAIN) {
                return decodePlain(in.readAllBytes(), ctx, vertexCount);
            }
            final var runs = Runs.of(ctx, vertexCount);
            final var bits = new BitReader(in.readNBytes(DecodingUtils.decodeVarint(in)));
            final var joined = new boolean[runs.count()];
            for (var r = 0; r < joined.length; r++) {
                joined[r] = RoadMerge.hasBit(runs, r) && bits.read(1) == 1;
            }
            final var merged = RoadMerge.merge(ctx, runs, joined, List.of());
            final var reducedCount = vertexCount - RoadMerge.joinCount(joined);
            final var regions = ClassRegions.slice(
                    IntCodec.VARINT_SPLIT.decode(in.readAllBytes(), reducedCount * 2),
                    ClassRegions.counts(predictor, merged.ctx(), reducedCount),
                    predictor.regionOrder());
            final var reduced = ClassRegions.join(predictor, regions, merged.ctx(), reducedCount);
            return RoadMerge.expand(reduced, runs, joined);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --- payloads -------------------------------------------------------------

    private byte[] encodePlain(BufferContext ctx) {
        return IntCodec.VARINT_SPLIT.encode(
                ClassRegions.concat(ClassRegions.split(predictor, ctx), predictor.regionOrder()));
    }

    private List<Vertex> decodePlain(byte[] body, BufferContext ctx, int vertexCount) {
        final var regions = ClassRegions.slice(
                IntCodec.VARINT_SPLIT.decode(body, vertexCount * 2),
                ClassRegions.counts(predictor, ctx, vertexCount),
                predictor.regionOrder());
        return ClassRegions.join(predictor, regions, ctx, vertexCount);
    }

    /**
     * The joined payload, or {@code null} when the features cannot be located.
     */
    private byte[] encodeJoined(BufferContext ctx, Runs runs, boolean[] joined) {
        final var reduced = RoadMerge.reduce(ctx.vertices(), runs, joined);
        final var merged = RoadMerge.merge(ctx, runs, joined, reduced);
        if (merged == null) {
            return null;
        }
        final var bits = new BitWriter();
        for (var r = 0; r < joined.length; r++) {
            if (RoadMerge.hasBit(runs, r)) {
                bits.write(joined[r] ? 1 : 0, 1);
            }
        }
        final var bitmap = bits.toBytes();
        try {
            final var bytes = new ByteArrayOutputStream();
            final var out = new DataOutputStream(bytes);
            out.write(JOINED);
            EncodingUtils.putVarInt(out, bitmap.length);
            out.write(bitmap);
            out.write(encodePlain(merged.ctx()));
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] withMode(int mode, byte[] body) {
        final var out = new byte[body.length + 1];
        out[0] = (byte) mode;
        System.arraycopy(body, 0, out, 1, body.length);
        return out;
    }
}
