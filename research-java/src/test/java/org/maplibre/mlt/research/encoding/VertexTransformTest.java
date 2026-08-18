package org.maplibre.mlt.research.encoding;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.maplibre.mlt.converter.geometry.Vertex;

class VertexTransformTest {

  @Test
  void zigZagDelta_roundTripsVertexBuffer() {
    var vertices =
        List.of(
            new Vertex(100, 200),
            new Vertex(105, 190),
            new Vertex(-50, -300),
            new Vertex(-50, -300),
            new Vertex(0, 0));

    var transform = new VertexTransform.ZigZagDelta();
    int[] applied = transform.apply(vertices);
    List<Vertex> restored = transform.invert(applied);

    assertEquals(vertices, restored);
  }

  @Test
  void zigZagDelta_roundTripsEmpty() {
    var transform = new VertexTransform.ZigZagDelta();
    int[] applied = transform.apply(List.of());
    List<Vertex> restored = transform.invert(applied);
    assertEquals(List.of(), restored);
  }
}
