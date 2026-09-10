package org.maplibre.mlt.research.encoding;

import java.util.List;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.EncodedBuffer;
import org.maplibre.mlt.research.encoding.research.GeometryReorder;
import org.maplibre.mlt.research.encoding.research.SegmentQuadCorner;

/**
 * {@code SEGMENT_QUAD_CORNER} over {@link SegmentedRansCodec}, giving each residual region its own
 * frequency table.
 *
 * <p>This exists as its own {@link Encoding} rather than another {@code (transform, codec, reorder)}
 * triple because the codec needs something the triple cannot supply: the region boundaries, which
 * depend on the buffer's topology. {@link IntCodec} sees only a flat {@code int[]}. Widening that
 * interface would touch every codec to serve one, so instead this class — which already holds both
 * the transform and the codec — reads the boundaries off the transform and hands them over just
 * before encoding.
 *
 * <p>Only the encoder needs them. The lengths are written into the payload, so decoding goes through
 * the ordinary context-free {@link IntCodec#decode} path.
 */
public final class SegmentedEncoding implements Encoding {

  private final SegmentQuadCorner transform = new SegmentQuadCorner();
  private final SegmentedRansCodec codec;
  private final GeometryReorder reorder;
  private final String name;

  public SegmentedEncoding(GeometryReorder reorder) {
    this.reorder = reorder;
    this.codec = new SegmentedRansCodec("RANS_SEG");
    this.name = "SEGMENT_QUAD_CORNER_RANS_SEG" + reorder.nameSuffix();
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public boolean reordersGeometries() {
    return reorder.reorders();
  }

  @Override
  public BufferContext emitted(BufferContext ctx) {
    return reorder.apply(ctx);
  }

  @Override
  public EncodedBuffer encode(BufferContext ctx) {
    final var emitted = reorder.apply(ctx);
    final var values = transform.apply(emitted);
    codec.setPendingRegions(transform.regionLengths(emitted, emitted.vertices().size()));
    final var payload = codec.encode(values);
    return new EncodedBuffer(
        payload, StreamMetadata.bytes(emitted.vertices().size() * 2, payload.length));
  }

  @Override
  public List<Vertex> decode(EncodedBuffer encoded, BufferContext ctx, int vertexCount) {
    return transform.invert(
        codec.decode(encoded.payload(), vertexCount * 2), reorder.topology(ctx, vertexCount));
  }
}
