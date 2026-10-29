package org.maplibre.mlt.research.encoding;

import org.maplibre.mlt.converter.geometry.Vertex;

import java.util.List;

/**
 * One vertex-buffer encoding: the single abstraction the harness measures and verifies.
 *
 * <p>{@link #decode} is mandatory: an encoding that cannot be decoded cannot be shown to be
 * lossless, so {@code BenchmarkCli} round-trips every encoding over the real corpus on every run.
 */
public interface Encoding {

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
    record BufferContext(
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
    record EncodedBuffer(byte[] payload, int metadataBytes) {

        public int totalBytes() {
            return payload.length + metadataBytes;
        }
    }

    String name();

    EncodedBuffer encode(BufferContext ctx);

    /**
     * The exact inverse of {@link #encode}: must reconstruct the original vertices.
     *
     * @param encoded     the result of {@link #encode}. The whole {@link EncodedBuffer} is passed, not
     *                    just its payload, because a real decoder reads the stream metadata first.
     * @param ctx         the topology streams ({@code numParts}, {@code numRings}, {@code geometryTypes}),
     *                    for encodings whose predictor resets at part/ring boundaries. MLT ships these streams in
     *                    the tile, so a real decoder has them available before it reads the vertex stream.
     *                    <p><strong>{@link BufferContext#vertices()} must not be read by an implementation.</strong>
     *                    Reading it would let a decoder return the input it is supposed to be reconstructing,
     *                    making any encoding round-trip trivially — including one that discards the buffer
     *                    entirely, which is exactly what the mandatory {@code decode} exists to catch.
     * @param vertexCount the number of vertices to reconstruct, likewise known from the metadata.
     */
    List<Vertex> decode(EncodedBuffer encoded, BufferContext ctx, int vertexCount);

    /**
     * Whether this encoding may emit geometries in an order other than the one it received.
     *
     * <p>Such an encoding is verified with {@link GeometryEquivalence} instead of exact equality.
     * Defaults to {@code false} so the relaxation is opt-in. The relaxation is exactly what the MLT
     * spec allows and no more ({@code maplibre-tile-spec/docs/specification.md}): feature order is
     * not a stable round-trip property, but every geometry must still be preserved exactly — only
     * its <em>order</em> is released. See {@code docs/architecture.md}.
     */
    default boolean reordersGeometries() {
        return false;
    }

    /**
     * The buffer this encoding actually emits, which is what {@link #decode} must reconstruct.
     *
     * <p>Identical to {@code ctx} unless {@link #reordersGeometries()} — the benchmark compares the
     * decode against this, and the geometry equivalence of this against the extraction, so a
     * reordering encoding that silently dropped a feature is still caught.
     */
    default BufferContext emitted(BufferContext ctx) {
        return ctx;
    }
}
