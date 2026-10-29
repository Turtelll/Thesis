package org.maplibre.mlt.research.encoding;

import org.maplibre.mlt.converter.geometry.Vertex;

import java.util.List;

/**
 * Pairs a {@link VertexTransform} (logical) with an {@link IntCodec} (physical), forming a complete
 * {@link Encoding}: the transform and codec supply encode/decode, and this class adds the
 * stream-metadata accounting the benchmark compares on.
 *
 * <p>Reordering is not a component: it is a benchmark-wide add-on applied through {@link
 * org.maplibre.mlt.research.encoding.research.ReorderedEncoding}, so every row here is measured
 * with and without it (see {@code docs/results/reordering.md}).
 */
public final class VertexBufferCodec implements Encoding {

    private final VertexTransform transform;
    private final IntCodec codec;

    public VertexBufferCodec(VertexTransform transform, IntCodec codec) {
        this.transform = transform;
        this.codec = codec;
    }

    /**
     * Composed from the components, so a name can neither drift from what it describes nor collide
     * with another combination's — e.g. {@code PART_START_SEG_FASTPFOR}.
     */
    @Override
    public String name() {
        return transform.name() + "_" + codec.name();
    }

    @Override
    public String toString() {
        return name();
    }

    @Override
    public EncodedBuffer encode(BufferContext ctx) {
        final var values = transform.apply(ctx);
        final var payload = codec.encode(values);
        return new EncodedBuffer(
                payload, StreamMetadata.bytes(values.length, payload.length));
    }

    @Override
    public List<Vertex> decode(EncodedBuffer encoded, BufferContext ctx, int vertexCount) {
        return transform.invert(
                codec.decode(encoded.payload(), transform.valueCount(ctx, vertexCount)), ctx);
    }

    /**
     * The transform alone, for measurements that need the residual stream independent of the codec
     * (e.g. residual-magnitude reporting) — {@link VertexTransform#apply} is pure, so it is safe to
     * call outside the encode/decode pairing {@link #encode} otherwise requires.
     */
    public VertexTransform transform() {
        return transform;
    }

    /**
     * The codec alone, so a measurement can tell which physical codec a row uses without matching on
     * its name.
     */
    public IntCodec codec() {
        return codec;
    }
}
