package org.maplibre.mlt.research.encoder.prediction.mechanism;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoder.prediction.Prediction;
import org.maplibre.mlt.research.pipeline.PredictionTransform;
import org.maplibre.mlt.research.buffer.Encoding.BufferContext;
import org.maplibre.mlt.research.encoding.VertexTransform;

/**
 * Pins that {@code LINE_CONTINUATION} predicts on LineString interiors only, never on polygon
 * rings, in a column containing both — the case that decides which stream ({@code numParts} vs
 * {@code numRings}) a run's vertices live in ({@code docs/mlt-fidelity.md}).
 */
class LineContinuationMechanismTest {

  /**
   * One LineString of 6 vertices moving on a straight diagonal (continuation predicts it exactly)
   * followed by one 4-vertex polygon ring. Column contains a polygon, so both runs' lengths live in
   * {@code numRings} — the trap this predictor must not get wrong.
   */
  private static BufferContext mixedColumn() {
    return new BufferContext(
        List.of(
            // LineString: straight line, continuation predicts vertices 2-5 exactly.
            new Vertex(0, 0),
            new Vertex(10, 10),
            new Vertex(20, 20),
            new Vertex(30, 30),
            new Vertex(40, 40),
            new Vertex(50, 50),
            // Polygon ring (closing vertex dropped, per the extractor's convention).
            new Vertex(100, 100),
            new Vertex(110, 100),
            new Vertex(110, 110),
            new Vertex(100, 110)),
        List.of(1, 2), // LINESTRING, POLYGON
        List.of(),
        List.of(1), // polygon: 1 ring
        List.of(6, 4)); // 6-vertex line run, 4-vertex ring
  }

  @Test
  void predictsLineInteriorButNotPolygonInterior() {
    final var ctx = mixedColumn();
    final var classes = new LineContinuation().classify(ctx, ctx.vertices().size());
    // LineString run: start, interior (2nd vertex, no p[i-2] in-run), then line-interior.
    assertEquals(1, classes[0], "part start");
    assertEquals(0, classes[1], "2nd vertex has no in-run predecessor pair, falls back to delta");
    assertEquals(2, classes[2], "3rd+ vertex of a LineString run is LINE_INTERIOR");
    assertEquals(2, classes[3]);
    assertEquals(2, classes[4]);
    assertEquals(2, classes[5]);
    // Polygon ring: start, interior, interior, interior — never LINE_INTERIOR.
    assertEquals(1, classes[6], "ring start");
    assertEquals(0, classes[7], "polygon interior is plain delta, never LINE_INTERIOR");
    assertEquals(0, classes[8]);
    assertEquals(0, classes[9]);
  }

  /** Five vertices — one below the length gate. */
  private static BufferContext shortLineColumn() {
    return new BufferContext(
        List.of(
            new Vertex(0, 0),
            new Vertex(10, 10),
            new Vertex(20, 20),
            new Vertex(30, 30),
            new Vertex(40, 40)),
        List.of(1),
        List.of(),
        List.of(5),
        List.of());
  }

  @Test
  void lineRunBelowMinLengthStaysOnPlainDelta() {
    final var ctx = shortLineColumn();
    final var classes = new LineContinuation().classify(ctx, ctx.vertices().size());
    assertEquals(1, classes[0], "part start");
    assertEquals(0, classes[1], "2nd vertex, plain delta regardless of gate");
    for (var i = 2; i < 5; i++) {
      assertEquals(0, classes[i], "5-vertex run: below MIN_LINE_RUN_LENGTH, plain delta");
    }
  }

  @Test
  void continuationExactlyPredictsAStraightLine() {
    final var ctx = mixedColumn();
    final var predictor = new LineContinuation();
    final var classes = predictor.classify(ctx, ctx.vertices().size());
    assertTrue(predictor.hasPrediction(classes[2]));
    assertEquals(20, predictor.predictX(classes[2], 2, ctx.vertices()));
    assertEquals(20, predictor.predictY(classes[2], 2, ctx.vertices()));
  }

  @Test
  void reconstructsWithoutReadingTheOriginalVertices() {
    final var ctx = mixedColumn();
    final var predictor = new LineContinuation();
    final var decoded =
        ClassSegmented.invert(
            predictor, ClassSegmented.apply(predictor, ctx), topologyOnly(ctx), ctx.vertices().size());
    assertEquals(ctx.vertices(), decoded);
  }

  @Test
  void residualMultisetIsPreservedInterleaved() {
    final var ctx = mixedColumn();
    final var predicted = new PredictionTransform(Prediction.LINE_CONTINUATION).apply(ctx);
    final var baseline = new VertexTransform.ZigZagDelta().apply(ctx);
    assertEquals(baseline.length, predicted.length);
    assertNotEquals(java.util.Arrays.toString(baseline), java.util.Arrays.toString(predicted));
  }

  private static BufferContext topologyOnly(BufferContext ctx) {
    return new BufferContext(
        List.of(), ctx.geometryTypes(), ctx.numGeometries(), ctx.numParts(), ctx.numRings());
  }
}
