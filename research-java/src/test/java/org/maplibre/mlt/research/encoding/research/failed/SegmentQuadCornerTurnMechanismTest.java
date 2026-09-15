package org.maplibre.mlt.research.encoding.research.failed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.IntCodec;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.research.SegmentQuadCorner;

/**
 * Pins that {@code SEGMENT_QUAD_CORNER_TURN} routes by ring length: an exactly-four-vertex ring keeps
 * {@link SegmentQuadCorner}'s parallelogram corner ({@code p0+p2-p1}, residual zero), a &gt;4-vertex
 * ring gets the signalled 90°-turn predictor (residual zero on exact right-angle turns), and every
 * case reconstructs from topology alone.
 */
class SegmentQuadCornerTurnMechanismTest {

  private static final int POLYGON = 2;

  private static SegmentQuadCornerTurn encoding() {
    return new SegmentQuadCornerTurn("SEGMENT_QUAD_CORNER_TURN_FASTPFOR", new IntCodec.FastPfor());
  }

  /** One polygon with an exact parallelogram ring: p3 == p0 + p2 - p1. */
  private static BufferContext parallelogramColumn() {
    return ringColumn(
        List.of(
            new Vertex(100, 100),
            new Vertex(140, 105),
            new Vertex(150, 160),
            new Vertex(110, 155)));
  }

  /**
   * A 9-vertex ring of equal-length edges each a 90° turn from the last — a unit-step staircase, so
   * every turn-predicted vertex ({@code i >= start+2}) leaves a zero residual.
   */
  private static BufferContext rightAngleRingColumn() {
    return ringColumn(
        List.of(
            new Vertex(0, 0),
            new Vertex(10, 0),
            new Vertex(10, 10),
            new Vertex(20, 10),
            new Vertex(20, 20),
            new Vertex(30, 20),
            new Vertex(30, 30),
            new Vertex(40, 30),
            new Vertex(40, 40)));
  }

  private static BufferContext ringColumn(List<Vertex> vertices) {
    return new BufferContext(
        vertices, List.of(POLYGON), List.of(), List.of(1), List.of(vertices.size()));
  }

  /** Two 2-vertex line parts and one 3-vertex part — no polygon, so neither predictor fires. */
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

  @Test
  void fourVertexRingKeepsTheParallelogramCorner() {
    final var vertices = parallelogramColumn().vertices();
    final var classes = SegmentQuadCornerTurn.classify(new int[] {0}, vertices.size());
    final var transformed = SegmentQuadCornerTurn.transform(vertices, classes);
    // p0 start (head region), then interior p1/p2, then the corner p3 in the tail region: residual 0.
    final var values = transformed.values();
    assertEquals(0, values[values.length - 2], "corner x residual is 0");
    assertEquals(0, values[values.length - 1], "corner y residual is 0");
    assertEquals(0, transformed.turns().size(), "a 4-ring signals no turn bits");
  }

  @Test
  void longRingUsesTheSignalledTurnPredictor() {
    final var vertices = rightAngleRingColumn().vertices();
    final var classes = SegmentQuadCornerTurn.classify(new int[] {0}, vertices.size());
    final var transformed = SegmentQuadCornerTurn.transform(vertices, classes);
    // Turn residuals are the last 2*turnCount slots (their own region); all zero on exact turns.
    final var turnCount = 7; // vertices 2..8 of a 9-vertex ring
    assertEquals(turnCount, transformed.turns().size(), "one bit per turn vertex");
    final var values = transformed.values();
    for (var s = values.length - turnCount * 2; s < values.length; s++) {
      assertEquals(0, values[s], "exact right-angle turn residual must be 0");
    }
  }

  @Test
  void reconstructsEveryColumnFromTopologyAlone() {
    for (final var ctx : List.of(parallelogramColumn(), rightAngleRingColumn(), lineColumn())) {
      final var encoding = encoding();
      assertEquals(
          ctx.vertices(),
          encoding.decode(encoding.encode(ctx), topologyOnly(ctx), ctx.vertices().size()),
          "must reconstruct from the topology streams without reading the vertices");
    }
  }

  @Test
  void corruptedStreamDoesNotReconstruct() {
    final var ctx = rightAngleRingColumn();
    final var encoding = encoding();
    final var encoded = encoding.encode(ctx);
    encoded.payload()[encoded.payload().length - 1] ^= 0x01; // flip a turn-direction bit
    assertNotEquals(
        ctx.vertices(), encoding.decode(encoded, topologyOnly(ctx), ctx.vertices().size()));
  }

  private static BufferContext topologyOnly(BufferContext ctx) {
    return new BufferContext(
        List.of(), ctx.geometryTypes(), ctx.numGeometries(), ctx.numParts(), ctx.numRings());
  }
}
