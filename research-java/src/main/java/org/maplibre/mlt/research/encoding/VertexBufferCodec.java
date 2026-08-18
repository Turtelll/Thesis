package org.maplibre.mlt.research.encoding;

import java.util.List;
import org.maplibre.mlt.converter.geometry.Vertex;

/**
 * Pairs a {@link VertexTransform} (logical) with an {@link IntCodec} (physical), forming a complete
 * {@link Encoding}: the transform and codec supply encode/decode, and this class adds the
 * stream-metadata accounting the benchmark compares on.
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

    /** Context for a bare vertex buffer with no topology — for tests and point-only columns. */
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

  private final String name;
  private final VertexTransform transform;
  private final IntCodec codec;

  public VertexBufferCodec(String name, VertexTransform transform, IntCodec codec) {
    this.name = name;
    this.transform = transform;
    this.codec = codec;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public EncodedBuffer encode(BufferContext ctx) {
    final var payload = codec.encode(transform.apply(ctx));
    return new EncodedBuffer(
        payload, StreamMetadata.bytes(ctx.vertices().size() * 2, payload.length));
  }

  @Override
  public List<Vertex> decode(EncodedBuffer encoded, BufferContext ctx, int vertexCount) {
    return transform.invert(codec.decode(encoded.payload(), vertexCount * 2), ctx);
  }
}
