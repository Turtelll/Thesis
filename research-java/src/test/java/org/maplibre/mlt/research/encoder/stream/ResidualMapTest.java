package org.maplibre.mlt.research.encoder.stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.buffer.Encoding.BufferContext;
import org.maplibre.mlt.research.encoder.format.PipelineEncoding;
import org.maplibre.mlt.research.registry.PipelineRegistry;
import org.maplibre.mlt.research.encoder.reorder.Reorder;

/** S4 verification: zigzag is a bijection over the whole int range. */
class ResidualMapTest {

  private static final int[] EXTREME = {
    Integer.MAX_VALUE, Integer.MIN_VALUE, 0, -1, 1, Integer.MIN_VALUE, Integer.MAX_VALUE, 12345
  };

  @Test
  void zigzagThenUnzigzagIsTheIdentityOverTheFullRange() {
    var side = ResidualMap.ZIGZAG.side(EXTREME);
    assertEquals(0, side.length);
    assertArrayEquals(EXTREME, ResidualMap.ZIGZAG.unmap(ResidualMap.ZIGZAG.map(EXTREME, side), side));
  }

  @Test
  void aFullBufferRoundTripsThroughTheStreamCodec() {
    var encoding = new PipelineEncoding(PipelineRegistry.DEFAULT.withReorder(Reorder.NONE));
    var vertices =
        List.of(
            new Vertex(Integer.MAX_VALUE, Integer.MIN_VALUE),
            new Vertex(Integer.MIN_VALUE, Integer.MAX_VALUE),
            new Vertex(0, 0),
            new Vertex(7, -9),
            new Vertex(Integer.MIN_VALUE, Integer.MIN_VALUE));
    var ctx = BufferContext.of(vertices);
    assertEquals(vertices, encoding.decode(encoding.encode(ctx), ctx, vertices.size()));
  }
}
