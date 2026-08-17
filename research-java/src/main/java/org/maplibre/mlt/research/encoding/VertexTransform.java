package org.maplibre.mlt.research.encoding;

import java.util.ArrayList;
import java.util.List;
import org.maplibre.mlt.converter.encodings.GeometryEncoder;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.decoder.DecodingUtils;

/** Logical-layer transform between a vertex buffer and the interleaved int stream a codec sees. */
public interface VertexTransform {

  int[] apply(List<Vertex> vertices);

  List<Vertex> invert(int[] values);

  /**
   * Componentwise delta + zigzag, continuous across the whole buffer (x and y tracked
   * independently), matching {@link GeometryEncoder#zigZagDeltaEncodeVertices}.
   */
  final class ZigZagDelta implements VertexTransform {

    @Override
    public int[] apply(List<Vertex> vertices) {
      return GeometryEncoder.zigZagDeltaEncodeVertices(vertices);
    }

    @Override
    public List<Vertex> invert(int[] values) {
      var vertices = new ArrayList<Vertex>(values.length / 2);
      int prevX = 0;
      int prevY = 0;
      for (int i = 0; i < values.length; i += 2) {
        int x = prevX + DecodingUtils.decodeZigZag(values[i]);
        int y = prevY + DecodingUtils.decodeZigZag(values[i + 1]);
        vertices.add(new Vertex(x, y));
        prevX = x;
        prevY = y;
      }
      return vertices;
    }
  }
}
