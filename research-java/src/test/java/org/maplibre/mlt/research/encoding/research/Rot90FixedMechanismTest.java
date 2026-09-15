package org.maplibre.mlt.research.encoding.research;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.Layout;
import org.maplibre.mlt.research.encoding.PredictedTransform;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;

/**
 * Pins {@code ROT90_FIXED}'s parity gate: the fixed ccw 90°-turn predictor fires on interior
 * vertices of even-length, {@code >4}-vertex rings and never on odd-length ones, its residual is
 * zero on a CCW turn and nonzero at the one CW (reflex) corner every rectilinear hexagon must have,
 * and the transform reconstructs from the topology streams alone.
 */
class Rot90FixedMechanismTest {

  private static final int POLYGON = 2;

  /**
   * A CCW-wound rectilinear L-shape: (0,0)-(20,0)-(20,10)-(10,10)-(10,20)-(0,20). Any simple
   * rectilinear polygon's turns sum to +360°, so an even-vertex ring with more than four vertices
   * can never be <em>all</em> +90° turns — a hexagon is necessarily five convex (+90°) corners and
   * one reflex (-90°) corner. Vertex 2 here is the reflex corner; the predictor is fixed-ccw, so its
   * y residual is nonzero (its x residual happens to still be zero on this particular shape).
   */
  private static List<Vertex> lShape() {
    return List.of(
        new Vertex(0, 0),
        new Vertex(20, 0),
        new Vertex(20, 10),
        new Vertex(10, 10),
        new Vertex(10, 20),
        new Vertex(0, 20));
  }

  /** A ring with no closed-polygon constraint, used only to exercise the odd-length gate. */
  private static List<Vertex> staircase(int n) {
    final var vertices = new ArrayList<Vertex>(n);
    var x = 0;
    var y = 0;
    for (var i = 0; i < n; i++) {
      vertices.add(new Vertex(x, y));
      if (i % 2 == 0) {
        x += 10;
      } else {
        y += 10;
      }
    }
    return vertices;
  }

  private static BufferContext ringColumn(List<Vertex> vertices) {
    return new BufferContext(
        vertices, List.of(POLYGON), List.of(), List.of(1), List.of(vertices.size()));
  }

  @Test
  void classifiesInteriorVerticesOfAnEvenRingAsTurns() {
    final var classes = Rot90Fixed.classify(new int[] {0}, 6);
    assertArrayEquals(new int[] {1, 0, 2, 2, 2, 2}, classes);
  }

  @Test
  void leavesOddRingsOnPlainDelta() {
    final var classes = Rot90Fixed.classify(new int[] {0}, 7);
    for (var i = 2; i < classes.length; i++) {
      assertEquals(0, classes[i], "vertex " + i + " of an odd ring must not be turn-predicted");
    }
  }

  @Test
  void leavesShortRingsOnPlainDelta() {
    // n == 4 is SEGMENT_QUAD_CORNER's population, not this transform's.
    final var classes = Rot90Fixed.classify(new int[] {0}, 4);
    assertArrayEquals(new int[] {1, 0, 0, 0}, classes);
  }

  private static PredictedTransform segmented() {
    return new PredictedTransform(new Rot90Fixed(), Layout.SEGMENTED);
  }

  @Test
  void residualIsZeroOnAConsistentCcwTurnAndNonzeroAtTheReflexCorner() {
    final var ctx = ringColumn(lShape());
    final var values = segmented().apply(ctx);
    // Turn region is the last 4 vertices' worth of slots (vertices 2..5), in ring order.
    final var turnRegion = values.length - 8;
    // Vertex 2 is the ring's one reflex (cw) corner: fixed ccw prediction misses it on y.
    assertEquals(0, values[turnRegion], "vertex 2 x residual (reflex corner)");
    assertNotEquals(0, values[turnRegion + 1], "vertex 2 y residual (reflex corner)");
    // Vertex 3: a genuine ccw turn, predicted exactly.
    assertEquals(0, values[turnRegion + 2], "vertex 3 x residual");
    assertEquals(0, values[turnRegion + 3], "vertex 3 y residual");
    // Vertex 5: also a genuine ccw turn, predicted exactly.
    assertEquals(0, values[turnRegion + 6], "vertex 5 x residual");
    assertEquals(0, values[turnRegion + 7], "vertex 5 y residual");
  }

  @Test
  void onOddRingEveryValueIsAPlainDelta() {
    final var ctx = ringColumn(staircase(7));
    final var values = segmented().apply(ctx);
    // No vertex is turn-classified, so every residual is a plain zigzag delta against the
    // previous vertex in ring order (start region: vertex 0; interior region: vertices 1..6).
    final var vertices = ctx.vertices();
    var prev = new Vertex(0, 0);
    var slot = 2;
    for (var i = 1; i < vertices.size(); i++) {
      final var v = vertices.get(i);
      assertEquals(
          EncodingUtils.encodeZigZag(v.x() - prev.x()), values[slot], "vertex " + i + " x residual");
      assertEquals(
          EncodingUtils.encodeZigZag(v.y() - prev.y()),
          values[slot + 1],
          "vertex " + i + " y residual");
      prev = v;
      slot += 2;
    }
  }

  @Test
  void reconstructsFromTopologyAlone() {
    for (final var ring : List.of(lShape(), staircase(7), staircase(4))) {
      final var ctx = ringColumn(ring);
      final var transform = segmented();
      assertEquals(ctx.vertices(), transform.invert(transform.apply(ctx), topologyOnly(ctx)));
    }
  }

  @Test
  void corruptedStreamDoesNotReconstruct() {
    final var ctx = ringColumn(lShape());
    final var transform = segmented();
    final var values = transform.apply(ctx);
    values[0] += 2;
    assertNotEquals(ctx.vertices(), transform.invert(values, topologyOnly(ctx)));
  }

  private static BufferContext topologyOnly(BufferContext ctx) {
    return new BufferContext(
        List.of(), ctx.geometryTypes(), ctx.numGeometries(), ctx.numParts(), ctx.numRings());
  }
}
