package org.maplibre.mlt.research.encoding.research;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;

/**
 * Pins that {@code SEGMENT_QUAD_CORNER} really is the composition of its two parts: on a line column
 * with no four-vertex rings it is byte-identical to {@link SegmentPair} (only the start grouping
 * fires), and on a polygon column it also applies {@link QuadCorner}'s parallelogram predictor
 * (fourth-corner residual zero), reconstructing from topology alone.
 */
class SegmentQuadCornerMechanismTest {

  private static final int POLYGON = 2;

  /** Two 2-vertex line parts and one 3-vertex part — no four-vertex run, so only starts group. */
  private static BufferContext lineColumn() {
    return new BufferContext(
        List.of(
            new Vertex(10, 10),
            new Vertex(12, 11),
            new Vertex(500, 400),
            new Vertex(503, 402),
            new Vertex(20, 900),
            new Vertex(22, 903),
            new Vertex(25, 905)),
        List.of(1, 1, 1),
        List.of(),
        List.of(2, 2, 3),
        List.of());
  }

  /** One polygon with an exact parallelogram ring: p3 == p0 + p2 - p1. */
  private static BufferContext parallelogramColumn() {
    return new BufferContext(
        List.of(
            new Vertex(100, 100),
            new Vertex(140, 105),
            new Vertex(150, 160),
            new Vertex(110, 155)),
        List.of(POLYGON),
        List.of(),
        List.of(1),
        List.of(4));
  }

  @Test
  void onLinesReducesToSegmentPair() {
    final var ctx = lineColumn();
    assertArrayEquals(
        new SegmentPair().apply(ctx),
        new SegmentQuadCorner().apply(ctx),
        "with no four-vertex ring the transform must match SegmentPair exactly");
  }

  @Test
  void appliesTheParallelogramPredictor() {
    final var values = new SegmentQuadCorner().apply(parallelogramColumn());
    // p0 is the only start (region head); the corner p3 is the tail two slots and must be (0,0).
    assertEquals(0, values[values.length - 2], "p3 x residual against p0+p2-p1 must be zero");
    assertEquals(0, values[values.length - 1], "p3 y residual against p0+p2-p1 must be zero");
  }

  @Test
  void reconstructsBothColumnsFromTopologyAlone() {
    for (final var ctx : List.of(lineColumn(), parallelogramColumn())) {
      final var transform = new SegmentQuadCorner();
      assertEquals(ctx.vertices(), transform.invert(transform.apply(ctx), topologyOnly(ctx)));
    }
  }

  @Test
  void corruptedStreamDoesNotReconstruct() {
    final var ctx = parallelogramColumn();
    final var transform = new SegmentQuadCorner();
    final var values = transform.apply(ctx);
    values[0] += 2;
    assertNotEquals(ctx.vertices(), transform.invert(values, topologyOnly(ctx)));
  }

  private static BufferContext topologyOnly(BufferContext ctx) {
    return new BufferContext(
        List.of(), ctx.geometryTypes(), ctx.numGeometries(), ctx.numParts(), ctx.numRings());
  }
}
