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

    /**
     * Everything a codec may condition on: the vertex buffer plus the topology streams that describe
     * how it is partitioned into features, parts and rings.
     *
     * <p>Predictors with structural reset points (see {@code ideen.md}) need the part/ring boundaries,
     * which are derivable from {@code numParts}/{@code numRings}. These are the same streams {@link
     * org.maplibre.mlt.research.extraction.VertexBufferExtractor.ExtractedColumn} produces and the
     * {@code .json} sidecar persists, so a context is reconstructable from an existing extraction
     * without re-extracting.
     */
    public record BufferContext(
            List<Vertex> vertices,
            List<Integer> geometryTypes,
            List<Integer> numGeometries,
            List<Integer> numParts,
            List<Integer> numRings) {

        /**
         * Context for a bare vertex buffer with no topology — for tests and point-only columns.
         */
        public static BufferContext of(List<Vertex> vertices) {
            return new BufferContext(vertices, List.of(), List.of(), List.of(), List.of());
        }
    }

    /**
     * One encoding's result: the payload plus the stream-metadata bytes MLT would write alongside it.
     * The benchmark compares {@link #totalBytes()}, since that is what lands in the tile — an encoding
     * that shrinks the payload but needs a longer header has not necessarily won.
     */
    public record EncodedBuffer(byte[] payload, int metadataBytes) {

        public int totalBytes() {
            return payload.length + metadataBytes;
        }
    }

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
