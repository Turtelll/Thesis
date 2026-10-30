package org.maplibre.mlt.research.encoding;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.buffer.Encoding.BufferContext;

class VertexTransformTest {

  private static void assertRoundTrip(List<Vertex> vertices) {
    var transform = new VertexTransform.ZigZagDelta();
    var ctx = BufferContext.of(vertices);
    int[] applied = transform.apply(ctx);
    List<Vertex> restored = transform.invert(applied, ctx);

    assertEquals(vertices, restored);
  }

  @Test
  void zigZagDelta_roundTripsVertexBuffer() {
    assertRoundTrip(
        List.of(
            new Vertex(100, 200),
            new Vertex(105, 190),
            new Vertex(-50, -300),
            new Vertex(-50, -300),
            new Vertex(0, 0)));
  }

  @Test
  void zigZagDelta_roundTripsEmpty() {
    assertRoundTrip(List.of());
  }
}
