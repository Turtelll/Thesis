package org.maplibre.mlt.research.encoding;

import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.EncodedBuffer;
import org.maplibre.mlt.research.encoding.research.SegmentQuadCorner;

import java.util.List;

/**
 * {@code QUAD_CORNER_SEG} over {@link SegmentedRansCodec}, giving each residual region its own
 * frequency table.
 *
 * <p>This exists as its own {@link Encoding} rather than another {@code (transform, codec)}
 * pair because the codec needs something the triple cannot supply: the region boundaries, which
 * depend on the buffer's topology. {@link IntCodec} sees only a flat {@code int[]}. Widening that
 * interface would touch every codec to serve one, so instead this class — which already holds both
 * the transform and the codec — reads the boundaries off the transform and hands them over just
 * before encoding.
 *
 * <p>Only the encoder needs them. The lengths are written into the payload, so decoding goes through
 * the ordinary context-free {@link IntCodec#decode} path.
 */
public final class SegmentedEncoding implements Encoding {

    private final SegmentQuadCorner predictor = new SegmentQuadCorner();
    private final VertexTransform transform = new PredictedTransform(predictor, Layout.SEGMENTED);
    private final SegmentedRansCodec codec = new SegmentedRansCodec("RANS_SEG");

    @Override
    public String name() {
        // QUAD_CORNER_SEG is the transform (segmented residual layout); RANSSEG is the codec
        // (one frequency table per region). The two _SEG ideas are different levers, so the codec
        // half does not repeat the suffix — QUAD_CORNER_SEG_RANS_SEG would read as one lever twice.
        return "QUAD_CORNER_SEG_RANSSEG";
    }

    @Override
    public EncodedBuffer encode(BufferContext ctx) {
        final var values = transform.apply(ctx);
        codec.setPendingRegions(predictor.regionLengths(ctx, ctx.vertices().size()));
        final var payload = codec.encode(values);
        return new EncodedBuffer(
                payload, StreamMetadata.bytes(ctx.vertices().size() * 2, payload.length));
    }

    @Override
    public List<Vertex> decode(EncodedBuffer encoded, BufferContext ctx, int vertexCount) {
        return transform.invert(codec.decode(encoded.payload(), vertexCount * 2), ctx);
    }

    /**
     * The codec alone, for measurements that need to time it separately from the transform (e.g.
     * decode-time probes) — {@link SegmentedRansCodec#decode} is context-free, so it is safe to call
     * outside the encode/decode pairing {@link #encode} otherwise requires.
     */
    public IntCodec codec() {
        return codec;
    }

    /**
     * The transform alone, for measurements that need the residual stream independent of the codec
     * (e.g. residual-magnitude reporting) — {@link VertexTransform#apply} is pure, so it is safe to
     * call outside the encode/decode pairing {@link #encode} otherwise requires.
     */
    public VertexTransform transform() {
        return transform;
    }
}
