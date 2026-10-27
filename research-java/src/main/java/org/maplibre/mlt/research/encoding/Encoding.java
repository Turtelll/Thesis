package org.maplibre.mlt.research.encoding;

import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.EncodedBuffer;

import java.util.List;

/**
 * One vertex-buffer encoding: the single abstraction the harness measures and verifies.
 *
 * <p>{@link #decode} is mandatory: an encoding that cannot be decoded cannot be shown to be
 * lossless, so {@code BenchmarkCli} round-trips every encoding over the real corpus on every run.
 */
public interface Encoding {

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
     * Whether this encoding is <b>lossy</b> in the one way a later linear-referencing step can undo:
     * it fuses line features that meet end to start into one, dropping the feature boundaries.
     *
     * <p>Such an encoding is verified with {@link MergeEquivalence} against {@link #emitted}, which
     * is the merged buffer (fewer vertices and features than the input), and must not be part of any
     * oracle or headline row: it is not a lossless candidate. Defaults to {@code false}.
     */
    default boolean mergesFeatures() {
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
