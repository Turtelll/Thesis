package org.maplibre.mlt.research.encoding;

import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.research.GeometryReorder;

import java.util.List;

/**
 * Pairs a {@link VertexTransform} (logical) with an {@link IntCodec} (physical), forming a complete
 * {@link Encoding}: the transform and codec supply encode/decode, and this class adds the
 * stream-metadata accounting the benchmark compares on.
 *
 * <p>A {@link GeometryReorder} is an optional third component, defaulting to {@link
 * GeometryReorder#NONE} — every encoding is a uniform {@code (transform, codec, reorder)} triple.
 * Reordering is applied <em>before</em> the transform and is never part of a baseline: the
 * baseline is the fixed yardstick every percentage is computed against (see
 * {@code docs/results/reordering.md}).
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
    private final GeometryReorder reorder;

    /**
     * An encoding that leaves the geometry order untouched.
     */
    public VertexBufferCodec(VertexTransform transform, IntCodec codec) {
        this(transform, codec, GeometryReorder.NONE);
    }

    public VertexBufferCodec(VertexTransform transform, IntCodec codec, GeometryReorder reorder) {
        this.transform = transform;
        this.codec = codec;
        this.reorder = reorder;
    }

    /**
     * Composed from the components, so a name can neither drift from what it describes nor collide
     * with another combination's — e.g. {@code SEGMENT_PAIR_FASTPFOR_REORDER_REV}.
     */
    @Override
    public String name() {
        return transform.name() + "_" + codec.name() + reorder.nameSuffix();
    }

    @Override
    public String toString() {
        return name();
    }

    @Override
    public boolean reordersGeometries() {
        return reorder.reorders();
    }

    /**
     * The buffer this encoding actually emits — what a decoder reconstructs, and what it must equal.
     */
    @Override
    public BufferContext emitted(BufferContext ctx) {
        return reorder.apply(ctx);
    }

    @Override
    public EncodedBuffer encode(BufferContext ctx) {
        final var emitted = reorder.apply(ctx);
        final var payload = codec.encode(transform.apply(emitted));
        return new EncodedBuffer(
                payload, StreamMetadata.bytes(emitted.vertices().size() * 2, payload.length));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Under a reorder, {@code ctx} carries the topology streams of the <em>original</em> buffer
     * while the payload describes the reordered one, and the transform derives its part boundaries
     * from whatever context it is given — so it is handed the reordered topology, exactly as a real
     * decoder would read it from the tile. {@link GeometryReorder#topology} recomputes it without
     * touching the vertices, which this method is forbidden to read.
     */
    @Override
    public List<Vertex> decode(EncodedBuffer encoded, BufferContext ctx, int vertexCount) {
        return transform.invert(
                codec.decode(encoded.payload(), vertexCount * 2), reorder.topology(ctx, vertexCount));
    }
}
