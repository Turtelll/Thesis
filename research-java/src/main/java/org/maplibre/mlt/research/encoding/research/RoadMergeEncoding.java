package org.maplibre.mlt.research.encoding.research;

import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.Encoding;
import org.maplibre.mlt.research.encoding.IntCodec;
import org.maplibre.mlt.research.encoding.StreamMetadata;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.EncodedBuffer;
import org.maplibre.mlt.research.encoding.research.RoadMerge.Merged;
import org.maplibre.mlt.research.encoding.research.SharedVertexRef.Runs;

import java.util.List;

/**
 * <b>Lossy.</b> {@link RoadChainEncoding} without the bitmap: after {@link GeometryReorder#ROAD_CHAIN},
 * every chain of features that meet end to start becomes one feature and every road one part, and
 * that merged buffer is encoded as plain {@link LineContinuation}. Nothing signals where the
 * original features began or ended, so the original feature boundaries are gone; the premise is that
 * linear referencing will restore them later by vertex index. The merged buffer is still
 * checked by {@link org.maplibre.mlt.research.encoding.MergeEquivalence}: every original line part
 * must be a vertex-aligned slice of a merged part, so the cut-back is possible.
 *
 * <p>The saving beyond the vertex buffer — one geometry type, part length and feature row per road
 * instead of per feature — is not in this row's bytes; {@code RoadChainCli} reports the topology
 * entries it removes. Not part of the research oracle.
 *
 * <p>No mode byte and no bitmap: the merged topology is what a tile would carry in its own
 * streams, and {@link #decode} recomputes it as {@link GeometryReorder#topology} does for the
 * reordering encodings.
 */
public final class RoadMergeEncoding implements Encoding {

    private final LineContinuation predictor;
    private final int gate;

    public RoadMergeEncoding() {
        this(LineContinuation.MIN_LINE_RUN_LENGTH);
    }

    /**
     * With the continuation length gate {@code gate}, as in {@link RoadChainEncoding}.
     */
    public RoadMergeEncoding(int gate) {
        this.gate = gate;
        this.predictor = new LineContinuation(gate);
    }

    @Override
    public String name() {
        return predictor.name() + "_ROAD_MERGE"
                + (gate == LineContinuation.MIN_LINE_RUN_LENGTH ? "" : "_GATE" + gate);
    }

    @Override
    public String toString() {
        return name();
    }

    @Override
    public boolean mergesFeatures() {
        return true;
    }

    @Override
    public BufferContext emitted(BufferContext ctx) {
        return merged(ctx, true).ctx();
    }

    @Override
    public EncodedBuffer encode(BufferContext original) {
        final var ctx = merged(original, true).ctx();
        final var payload = IntCodec.VARINT_SPLIT.encode(
                ClassRegions.concat(ClassRegions.split(predictor, ctx), predictor.regionOrder()));
        return new EncodedBuffer(payload, StreamMetadata.bytes(ctx.vertices().size() * 2, payload.length));
    }

    @Override
    public List<Vertex> decode(EncodedBuffer encoded, BufferContext original, int vertexCount) {
        // The merged vertex count comes from the merged topology, not from the caller's count.
        final var merged = merged(original, false);
        final var regions = ClassRegions.slice(
                IntCodec.VARINT_SPLIT.decode(encoded.payload(), merged.vertexCount() * 2),
                ClassRegions.counts(predictor, merged.ctx(), merged.vertexCount()),
                predictor.regionOrder());
        return ClassRegions.join(predictor, regions, merged.ctx(), merged.vertexCount());
    }

    /**
     * The merged buffer, or the chained buffer unchanged where no roads can be located. Without
     * {@code withVertices} the context carries topology only.
     */
    private static Merged merged(BufferContext original, boolean withVertices) {
        final var ctx = GeometryReorder.ROAD_CHAIN.apply(original);
        final var n = ctx.vertices().size();
        final var runs = Runs.of(ctx, n);
        if (runs != null) {
            final var joined = RoadMerge.detectJoins(ctx.vertices(), runs);
            final var reduced = RoadMerge.reduce(ctx.vertices(), runs, joined);
            final var merged = RoadMerge.merge(ctx, runs, joined, withVertices ? reduced : List.of());
            if (merged != null) {
                return merged;
            }
        }
        return new Merged(withVertices ? ctx : GeometryReorder.ROAD_CHAIN.topology(original, n), n);
    }
}
