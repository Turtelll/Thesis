package org.maplibre.mlt.research.encoding.research;

import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.Encoding;
import org.maplibre.mlt.research.encoding.IntCodec;
import org.maplibre.mlt.research.encoding.StreamMetadata;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.EncodedBuffer;

import java.util.List;

/**
 * {@link LineContinuation}'s residuals with their own byte layout: one region per vertex class —
 * part starts, plain deltas, continuation-predicted line interiors — back to back, as split
 * varints. Region lengths follow from the topology streams, so nothing is signalled.
 */
public final class LineContinuationEncoding implements Encoding {

    private static final int[] REGION_ORDER = {
            LineContinuation.START, LineContinuation.INTERIOR, LineContinuation.LINE_INTERIOR};

    private final LineContinuation predictor = new LineContinuation();

    @Override
    public String name() {
        return "LINE_CONTINUATION";
    }

    @Override
    public String toString() {
        return name();
    }

    @Override
    public EncodedBuffer encode(BufferContext ctx) {
        final var values = ClassRegions.concat(ClassRegions.split(predictor, ctx), REGION_ORDER);
        final var payload = IntCodec.VARINT_SPLIT.encode(values);
        return new EncodedBuffer(payload, StreamMetadata.bytes(values.length, payload.length));
    }

    @Override
    public List<Vertex> decode(EncodedBuffer encoded, BufferContext ctx, int vertexCount) {
        final var values = IntCodec.VARINT_SPLIT.decode(encoded.payload(), vertexCount * 2);
        final var regions =
                ClassRegions.slice(values, ClassRegions.counts(predictor, ctx, vertexCount), REGION_ORDER);
        return ClassRegions.join(predictor, regions, ctx, vertexCount);
    }
}
