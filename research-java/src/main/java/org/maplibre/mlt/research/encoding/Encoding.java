package org.maplibre.mlt.research.encoding;

import java.util.List;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.EncodedBuffer;

/**
 * One vertex-buffer encoding: the single abstraction the harness measures and verifies.
 *
 * <p>{@link #decode} is deliberately part of the contract rather than a separate capability. An
 * encoding that cannot be decoded cannot be shown to be lossless, and a size number from such an
 * encoding is meaningless — an encoder that emits one byte and loses the buffer would otherwise look
 * like the best result in the table. Requiring the inverse here is what lets {@code BenchmarkCli}
 * round-trip every encoding over the real corpus on every run, which is the thesis testing
 * convention (encode, decode, compare against the original extraction) applied where the numbers are
 * actually produced.
 */
public interface Encoding {

  String name();

  EncodedBuffer encode(BufferContext ctx);

  /**
   * The exact inverse of {@link #encode}: must reconstruct the original vertices.
   *
   * @param encoded the result of {@link #encode}. The whole {@link EncodedBuffer} is passed, not
   *     just its payload, because a real decoder reads the stream metadata first.
   * @param ctx the topology streams ({@code numParts}, {@code numRings}, {@code geometryTypes}),
   *     for encodings whose predictor resets at part/ring boundaries. MLT ships these streams in
   *     the tile, so a real decoder has them available before it reads the vertex stream.
   *     <p><strong>{@link BufferContext#vertices()} must not be read by an implementation.</strong>
   *     Reading it would let a decoder return the input it is supposed to be reconstructing,
   *     making any encoding round-trip trivially — including one that discards the buffer
   *     entirely, which is exactly what the mandatory {@code decode} exists to catch.
   * @param vertexCount the number of vertices to reconstruct, likewise known from the metadata.
   */
  List<Vertex> decode(EncodedBuffer encoded, BufferContext ctx, int vertexCount);

  /**
   * Whether this encoding may emit geometries in an order other than the one it received.
   *
   * <p>Such an encoding cannot satisfy {@link #decode}'s usual contract — it does not reconstruct
   * the original vertex sequence, it reconstructs an equivalent one — so the benchmark verifies it
   * with {@link GeometryEquivalence} instead of exact equality. Defaulting to {@code false} is what
   * keeps that relaxation <b>opt-in</b>: without it the check would have to be loosened for every
   * encoding, and a genuinely lossy encoder would stop being caught.
   *
   * <p>The relaxation is exactly what the MLT spec allows and no more ({@code
   * docs/specification.md}):
   *
   * <blockquote>
   * Feature order is not necessarily a stable round-trip property. [...] MLT encoders may preserve
   * order, but they may also sort features by id or spatial locality to improve compression when
   * that optimization is enabled.
   * </blockquote>
   *
   * <p>An encoding claiming this still has to preserve every geometry exactly; it is only released
   * from preserving their <em>order</em>.
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
