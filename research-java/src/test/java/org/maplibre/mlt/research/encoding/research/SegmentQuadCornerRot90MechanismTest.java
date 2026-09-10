package org.maplibre.mlt.research.encoding.research;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;

/**
 * Pins that {@code SEGMENT_QUAD_CORNER_ROT90} composes {@link SegmentQuadCorner}'s parallelogram
 * corner (on exactly-four-vertex rings) with {@link Rot90Fixed}'s fixed-ccw turn predictor (on
 * even-length rings longer than four vertices), routing each into its own region, and reconstructs
 * from the topology streams alone.
 */
class SegmentQuadCornerRot90MechanismTest {

  private static final int POLYGON = 2;

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

  /** CCW rectilinear L-shape hexagon: five convex corners, one reflex. */
  private static BufferContext lShapeColumn() {
    return new BufferContext(
        List.of(
            new Vertex(0, 0),
            new Vertex(20, 0),
            new Vertex(20, 10),
            new Vertex(10, 10),
            new Vertex(10, 20),
            new Vertex(0, 20)),
        List.of(POLYGON),
        List.of(),
        List.of(1),
        List.of(6));
  }

  /** Two rings in one column: a parallelogram (n=4) and an L-shape (n=6). */
  private static BufferContext mixedColumn() {
    final var p = parallelogramColumn();
    final var l = lShapeColumn();
    final var vertices = new java.util.ArrayList<Vertex>(p.vertices());
    vertices.addAll(l.vertices());
    return new BufferContext(vertices, List.of(POLYGON, POLYGON), List.of(), List.of(1, 1), List.of(4, 6));
  }

  @Test
  void onAFourVertexRingReducesToQuadCorner() {
    final var ctx = parallelogramColumn();
    assertArrayEquals(
        new SegmentQuadCorner().apply(ctx),
        new SegmentQuadCornerRot90().apply(ctx),
        "with only a four-vertex ring the transform must match SegmentQuadCorner exactly");
  }

  @Test
  void appliesTheParallelogramPredictorOnTheFourRing() {
    final var ctx = mixedColumn();
    final var classes = SegmentQuadCornerRot90.classify(new int[] {0, 4}, ctx.vertices().size());
    // The four-ring's corner is global vertex index 3 (CORNER = 2, per the class's constant).
    assertEquals(2, classes[3], "vertex 3 of the four-ring must be classified CORNER");
    final var values = new SegmentQuadCornerRot90().apply(ctx);
    final var cornerSlot = slotOf(classes, 3);
    assertEquals(0, values[cornerSlot], "corner x residual against p0+p2-p1 must be zero");
    assertEquals(0, values[cornerSlot + 1], "corner y residual against p0+p2-p1 must be zero");
  }

  /** Recomputes the region layout independently of the transform, to locate one vertex's slot. */
  private static int slotOf(int[] classes, int vertexIndex) {
    final var targetClass = classes[vertexIndex];
    // Count slots occupied by every class that sorts before targetClass in the region layout
    // (START=1, INTERIOR=0, CORNER=2, TURN=3 -> layout order START, INTERIOR, CORNER, TURN).
    final var order = new int[] {1, 0, 2, 3};
    var offset = 0;
    for (final var c : order) {
      if (c == targetClass) {
        break;
      }
      for (var v : classes) {
        if (v == c) {
          offset += 2;
        }
      }
    }
    var indexWithinClass = 0;
    for (var i = 0; i < vertexIndex; i++) {
      if (classes[i] == targetClass) {
        indexWithinClass++;
      }
    }
    return offset + indexWithinClass * 2;
  }

  @Test
  void appliesTheTurnPredictorOnTheSixRing() {
    final var ctx = mixedColumn();
    // The L-shape occupies global indices 4..9; ring-local vertex 3 (global 7) is a genuine ccw
    // turn (see Rot90FixedMechanismTest's lShape fixture, from which this ring is copied): its
    // predicted rot90ccw(prevEdge) residual is exactly zero.
    final var classes = SegmentQuadCornerRot90.classify(new int[] {0, 4}, ctx.vertices().size());
    assertEquals(3, classes[7], "vertex 7 (L-shape vertex 3) must be classified TURN");
    final var values = new SegmentQuadCornerRot90().apply(ctx);
    final var slot = slotOf(classes, 7);
    assertEquals(0, values[slot], "turn vertex 7 x residual");
    assertEquals(0, values[slot + 1], "turn vertex 7 y residual");
  }

  @Test
  void reconstructsFromTopologyAlone() {
    for (final var ctx : List.of(parallelogramColumn(), lShapeColumn(), mixedColumn())) {
      final var transform = new SegmentQuadCornerRot90();
      assertEquals(ctx.vertices(), transform.invert(transform.apply(ctx), topologyOnly(ctx)));
    }
  }

  @Test
  void corruptedStreamDoesNotReconstruct() {
    final var ctx = mixedColumn();
    final var transform = new SegmentQuadCornerRot90();
    final var values = transform.apply(ctx);
    values[0] += 2;
    assertNotEquals(ctx.vertices(), transform.invert(values, topologyOnly(ctx)));
  }

  private static BufferContext topologyOnly(BufferContext ctx) {
    return new BufferContext(
        List.of(), ctx.geometryTypes(), ctx.numGeometries(), ctx.numParts(), ctx.numRings());
  }
}
