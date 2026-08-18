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
   * @param vertexCount the number of vertices to reconstruct, likewise known from the metadata.
   */
  List<Vertex> decode(EncodedBuffer encoded, int vertexCount);
}
