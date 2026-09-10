package org.maplibre.mlt.research.encoding.research.failed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.IntCodec;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;

/**
 * Pins that {@code RIGHT_ANGLE_TURN} fires its 90°-turn predictor exactly on the interior vertices
 * of &gt;4-vertex rings: a ring built as perfect right-angle turns leaves zero turn residual, a
 * ≤4-vertex ring and a line column fall back to plain delta (predictor never fires), and every case
 * reconstructs from topology alone.
 */
class RightAngleTurnMechanismTest {

  private static final int POLYGON = 2;

  private static RightAngleTurn encoding() {
    return new RightAngleTurn("RIGHT_ANGLE_TURN_FASTPFOR", new IntCodec.FastPfor());
  }

  /**
   * A 9-vertex ring of equal-length edges, each a 90° turn from the last — a unit-step staircase. The
   * predictor is {@code p[i-1] + rot90(previous edge)}, so it zeroes a turn only when the turn is 90°
   * <b>and</b> the edge length repeats; both hold here, so every one of the 7 turn-predicted vertices
   * leaves a zero residual — the near-zero population the encoding targets.
   */
  private static BufferContext rightAngleRingColumn() {
    return ringColumn(
        List.of(
            new Vertex(0, 0),
            new Vertex(10, 0), // edge (10,0)
            new Vertex(10, 10), // ccw turn, len 10
            new Vertex(20, 10), // cw turn, len 10
            new Vertex(20, 20), // ccw turn, len 10
            new Vertex(30, 20), // cw turn, len 10
            new Vertex(30, 30), // ccw turn, len 10
            new Vertex(40, 30), // cw turn, len 10
            new Vertex(40, 40))); // ccw turn, len 10
  }

  private static BufferContext ringColumn(List<Vertex> vertices) {
    return new BufferContext(
        vertices, List.of(POLYGON), List.of(), List.of(1), List.of(vertices.size()));
  }

  /** One 4-vertex polygon: length is not &gt; 4, so the turn predictor never fires. */
  private static BufferContext quadColumn() {
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

  /** A line column: no polygon, so no ring lengths and no turn class. */
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
  void reconstructsEveryColumnFromTopologyAlone() {
    for (final var ctx : List.of(rightAngleRingColumn(), quadColumn(), lineColumn())) {
      final var encoding = encoding();
      final var encoded = encoding.encode(ctx);
      assertEquals(
          ctx.vertices(),
          encoding.decode(encoded, topologyOnly(ctx), ctx.vertices().size()),
          "must reconstruct from the topology streams without reading the vertices");
    }
  }

  @Test
  void perfectRightAnglesLeaveZeroTurnResidual() {
    // The staircase ring's 7 turn-predicted vertices (index >= start+2, ring length 9 > 4) are all
    // exact 90° turns, so the 90°-predictor residual is (0,0) at each — the near-zero population the
    // encoding targets. Assert the residual value stream is zero exactly on the turn slots, proving
    // the predictor (not first-order delta) carries them. One direction bit is emitted per turn.
    final var vertices = rightAngleRingColumn().vertices();
    final var isTurn = RightAngleTurn.classify(new int[] {0}, vertices.size());
    final var transformed = RightAngleTurn.transform(vertices, isTurn);

    var turnCount = 0;
    for (var i = 0; i < vertices.size(); i++) {
      if (isTurn[i]) {
        turnCount++;
        assertEquals(0, transformed.values()[i * 2], "turn vertex " + i + " x residual must be 0");
        assertEquals(0, transformed.values()[i * 2 + 1], "turn vertex " + i + " y residual must be 0");
      }
    }
    assertEquals(7, turnCount, "vertices 2..8 of a 9-vertex ring are turn-predicted");
    assertEquals(turnCount, transformed.turns().size(), "one direction bit per turn vertex");
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
