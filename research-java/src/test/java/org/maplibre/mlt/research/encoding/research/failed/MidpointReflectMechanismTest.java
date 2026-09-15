package org.maplibre.mlt.research.encoding.research.failed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;

/**
 * Pins that {@code MIDPOINT_REFLECT} predicts the second half of a ring by point-reflection: on a
 * centrally-symmetric ring the reflect residuals are zero, it reconstructs from topology alone, and
 * a short ring below {@code MIN_RING} stays pure delta.
 */
class MidpointReflectMechanismTest {

  private static final int POLYGON = 2;

  /**
   * One eight-vertex centrally-symmetric ring: {@code p[i] = 2*center - p[i-4]}, center (100, 100).
   * The second half (indices 4..7) is the point-reflection of the first half, so every reflect
   * residual must be zero.
   */
  private static BufferContext symmetricRing() {
    return new BufferContext(
        List.of(
            new Vertex(60, 80),
            new Vertex(80, 60),
            new Vertex(120, 60),
            new Vertex(140, 80),
            new Vertex(140, 120),
            new Vertex(120, 140),
            new Vertex(80, 140),
            new Vertex(60, 120)),
        List.of(POLYGON),
        List.of(),
        List.of(1),
        List.of(8));
  }

  /** A five-vertex ring — below MIN_RING, so no reflect vertices; pure delta. */
  private static BufferContext shortRing() {
    return new BufferContext(
        List.of(
            new Vertex(10, 10),
            new Vertex(30, 12),
            new Vertex(35, 40),
            new Vertex(20, 55),
            new Vertex(8, 35)),
        List.of(POLYGON),
        List.of(),
        List.of(1),
        List.of(5));
  }

  @Test
  void reflectResidualsAreZeroOnCentralSymmetricRing() {
    // SPLIT pairs opposite points (r <-> r - L/2), which the point-reflection predicts exactly on a
    // centrally-symmetric ring. Three reflect vertices (indices 5,6,7) -> last 6 slots.
    final var values = new MidpointReflect(MidpointReflect.Pairing.SPLIT).apply(symmetricRing());
    for (var s = values.length - 6; s < values.length; s++) {
      assertEquals(0, values[s], "reflect residual on a central-symmetric ring must be zero");
    }
  }

  @Test
  void shortRingHasNoReflectVertices() {
    // On a ring below MIN_RING the transform is pure delta+zigzag start/interior split: the round
    // trip still holds and no residual is a reflection (covered by reconstruction below); here we
    // just assert both pairings agree, since with no reflect vertices they cannot differ.
    final var ctx = shortRing();
    final var sym = new MidpointReflect(MidpointReflect.Pairing.SYMMETRIC).apply(ctx);
    final var split = new MidpointReflect(MidpointReflect.Pairing.SPLIT).apply(ctx);
    assertArrayEquals(sym, split, "no reflect vertices -> pairings identical");
  }

  @Test
  void reconstructsFromTopologyAlone() {
    for (final var pairing : MidpointReflect.Pairing.values()) {
      for (final var ctx : List.of(symmetricRing(), shortRing())) {
        final var transform = new MidpointReflect(pairing);
        assertEquals(
            ctx.vertices(),
            transform.invert(transform.apply(ctx), topologyOnly(ctx)),
            pairing + " must reconstruct " + ctx.vertices().size() + "-vertex ring from topology");
      }
    }
  }

  @Test
  void corruptedStreamDoesNotReconstruct() {
    final var ctx = symmetricRing();
    final var transform = new MidpointReflect(MidpointReflect.Pairing.SYMMETRIC);
    final var values = transform.apply(ctx);
    values[0] += 2;
    assertNotEquals(ctx.vertices(), transform.invert(values, topologyOnly(ctx)));
  }

  private static BufferContext topologyOnly(BufferContext ctx) {
    return new BufferContext(
        List.of(), ctx.geometryTypes(), ctx.numGeometries(), ctx.numParts(), ctx.numRings());
  }
}
