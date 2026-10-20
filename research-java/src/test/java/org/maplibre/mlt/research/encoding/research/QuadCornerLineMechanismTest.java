package org.maplibre.mlt.research.encoding.research;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;

/**
 * Pins that {@code QUAD_CORNER_LINE} keeps the parallelogram corner on 4-vertex rings and applies
 * continuation only to LineString runs longer than 6 vertices — including inside
 * {@code QUAD_CORNER_LINE_SHARED_SEG}.
 */
class QuadCornerLineMechanismTest {

  private static final int LINESTRING = 1;
  private static final int POLYGON = 2;

  /**
   * A 7-vertex straight line (above the gate), a 6-vertex straight line (on the gate, excluded),
   * and a 4-vertex parallelogram. The column contains a polygon, so every run length lives in
   * {@code numRings}.
   */
  private static BufferContext mixedColumn() {
    return new BufferContext(
        List.of(
            // 7 vertices on y = x. Continuation predicts indices 2..6 exactly.
            new Vertex(0, 0),
            new Vertex(10, 10),
            new Vertex(20, 20),
            new Vertex(30, 30),
            new Vertex(40, 40),
            new Vertex(50, 50),
            new Vertex(60, 60),
            // 6 vertices, still straight — not bigger than 6.
            new Vertex(0, 100),
            new Vertex(10, 110),
            new Vertex(20, 120),
            new Vertex(30, 130),
            new Vertex(40, 140),
            new Vertex(50, 150),
            // Parallelogram: p3 == p0 + p2 - p1.
            new Vertex(100, 100),
            new Vertex(140, 105),
            new Vertex(150, 160),
            new Vertex(110, 155)),
        List.of(LINESTRING, LINESTRING, POLYGON),
        List.of(),
        List.of(1),
        List.of(7, 6, 4));
  }

  @Test
  void continuesOnlyLineRunsLongerThanSix() {
    final var ctx = mixedColumn();
    final var classes = new QuadCornerLine().classify(ctx, ctx.vertices().size());

    assertEquals(1, classes[0], "part start");
    assertEquals(0, classes[1], "2nd vertex has no in-run predecessor pair");
    for (var i = 2; i <= 6; i++) {
      assertEquals(3, classes[i], "7-vertex line: 3rd+ vertex is continuation");
    }

    assertEquals(1, classes[7], "6-vertex line start");
    for (var i = 8; i <= 12; i++) {
      assertEquals(0, classes[i], "6 vertices is not bigger than 6, so plain delta");
    }

    assertEquals(1, classes[13], "ring start");
    assertEquals(0, classes[14]);
    assertEquals(0, classes[15]);
    assertEquals(2, classes[16], "exactly four vertices still take the parallelogram corner");
  }

  @Test
  void polygonLongerThanSixStaysOffContinuation() {
    final var ctx =
        new BufferContext(
            List.of(
                new Vertex(0, 0),
                new Vertex(10, 0),
                new Vertex(20, 0),
                new Vertex(30, 0),
                new Vertex(30, 10),
                new Vertex(20, 10),
                new Vertex(10, 10),
                new Vertex(0, 10)),
            List.of(POLYGON),
            List.of(),
            List.of(1),
            List.of(8));
    final var classes = new QuadCornerLine().classify(ctx, ctx.vertices().size());
    assertEquals(1, classes[0]);
    for (var i = 1; i < 8; i++) {
      assertEquals(0, classes[i], "polygon interiors stay plain delta");
    }
  }

  @Test
  void continuationAndCornerPredictExactly() {
    final var ctx = mixedColumn();
    final var predictor = new QuadCornerLine();
    final var classes = predictor.classify(ctx, ctx.vertices().size());
    assertEquals(20, predictor.predictX(classes[2], 2, ctx.vertices()));
    assertEquals(20, predictor.predictY(classes[2], 2, ctx.vertices()));
    assertEquals(110, predictor.predictX(classes[16], 16, ctx.vertices()));
    assertEquals(155, predictor.predictY(classes[16], 16, ctx.vertices()));
  }

  @Test
  void sharedRoundTripsTheMixedColumn() {
    final var ctx = mixedColumn();
    final var encoding = new SharedVertexRef("QUAD_CORNER_LINE", new QuadCornerLine());
    final var decoded =
        encoding.decode(encoding.encode(ctx), topologyOnly(ctx), ctx.vertices().size());
    assertEquals(encoding.emitted(ctx).vertices(), decoded);
  }

  private static BufferContext topologyOnly(BufferContext ctx) {
    return new BufferContext(
        List.of(), ctx.geometryTypes(), ctx.numGeometries(), ctx.numParts(), ctx.numRings());
  }
}
