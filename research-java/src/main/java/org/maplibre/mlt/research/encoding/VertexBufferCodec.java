package org.maplibre.mlt.research.encoding;

import java.util.List;
import org.maplibre.mlt.converter.geometry.Vertex;

/** Pairs a {@link VertexTransform} (logical) with an {@link IntCodec} (physical). */
public final class VertexBufferCodec {

  private final VertexTransform transform;
  private final IntCodec codec;

  public VertexBufferCodec(VertexTransform transform, IntCodec codec) {
    this.transform = transform;
    this.codec = codec;
  }

  public byte[] encode(List<Vertex> vertices) {
    return codec.encode(transform.apply(vertices));
  }

  public List<Vertex> decode(byte[] data, int numVertices) {
    return transform.invert(codec.decode(data, numVertices * 2));
  }

  /** Registry of the baseline {@link VertexBufferCodec} variants to benchmark against. */
  public enum Baseline {
    DELTA_ZIGZAG_FASTPFOR(
        new VertexBufferCodec(new VertexTransform.ZigZagDelta(), new IntCodec.FastPfor())),
    DELTA_ZIGZAG_VARINT(
        new VertexBufferCodec(new VertexTransform.ZigZagDelta(), new IntCodec.Varint()));

    private final VertexBufferCodec codec;

    Baseline(VertexBufferCodec codec) {
      this.codec = codec;
    }

    public VertexBufferCodec codec() {
      return codec;
    }
  }
}
