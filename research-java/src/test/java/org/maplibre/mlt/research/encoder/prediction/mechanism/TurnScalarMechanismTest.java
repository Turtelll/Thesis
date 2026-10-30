package org.maplibre.mlt.research.encoder.prediction.mechanism;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoder.prediction.Prediction;
import org.maplibre.mlt.research.pipeline.PredictionTransform;
import org.maplibre.mlt.research.buffer.Encoding.BufferContext;

/**
 * Pins {@code TURN_SCALAR}: four-vertex rings keep {@code QUAD_CORNER}'s parallelogram corner,
 * {@code >4}-vertex rings store one length scalar plus a correction that is zero on a rectilinear
 * ring, the rotation follows winding (outer CCW, inner CW) so convex corners get a positive scalar,
 * and the transform reconstructs from the topology streams alone.
 */
class TurnScalarMechanismTest {

  private static final ClassifiedPredictor PREDICTOR = Prediction.TURN_SCALAR.predictor();

  private static final int POLYGON = 2;
  private static final int LINESTRING = 1;

  /** CCW outer square, 4 vertices. */
  private static final List<Vertex> SQUARE =
      List.of(new Vertex(0, 0), new Vertex(40, 0), new Vertex(40, 40), new Vertex(0, 40));

  /** CCW rectilinear L-shape; vertex 3 is the one reflex corner. */
  private static final List<Vertex> L_SHAPE =
      List.of(
          new Vertex(0, 0),
          new Vertex(20, 0),
          new Vertex(20, 10),
          new Vertex(10, 10),
          new Vertex(10, 20),
          new Vertex(0, 20));

  /** The same L-shape wound CW, as an inner ring would be. */
  private static final List<Vertex> L_SHAPE_CW = reversed(L_SHAPE);

  private static List<Vertex> reversed(List<Vertex> ring) {
    final var copy = new ArrayList<>(ring);
    java.util.Collections.reverse(copy);
    return copy;
  }

  /** One polygon per entry of {@code ringsPerPolygon}, rings taken in order. */
  private static BufferContext polygons(List<Integer> ringsPerPolygon, List<List<Vertex>> rings) {
    final var vertices = new ArrayList<Vertex>();
    final var numRings = new ArrayList<Integer>();
    for (final var ring : rings) {
      vertices.addAll(ring);
      numRings.add(ring.size());
    }
    final var types = new ArrayList<Integer>();
    for (var i = 0; i < ringsPerPolygon.size(); i++) {
      types.add(POLYGON);
    }
    return new BufferContext(vertices, types, List.of(), ringsPerPolygon, numRings);
  }

  @Test
  void fourVertexRingUsesQuadCornerAndLongRingUsesTurns() {
    final var ctx = polygons(List.of(1, 1), List.of(SQUARE, L_SHAPE));
    final var c = TurnScalar.classify(ctx, 10);
    // START=1, INTERIOR=0, CORNER=2, TURN=3.
    assertArrayEquals(new int[] {1, 0, 0, 2, 1, 0, 3, 3, 3, 3}, c.classes());
    assertEquals(4, c.turns());
  }

  @Test
  void rectilinearRingHasZeroCorrectionAndSignedLengthScalar() {
    final var ctx = polygons(List.of(1), List.of(L_SHAPE));
    final var values = ClassSegmented.apply(PREDICTOR, ctx);
    // Regions: start 2 | interior 2 | corner 0 | turn corr 8 | along 4 (the side stream last).
    assertEquals(16, values.length);
    final var corr = Arrays.copyOfRange(values, 4, 12);
    final var along = Arrays.copyOfRange(values, 12, 16);
    assertArrayEquals(new int[8], corr);
    // Edges after a convex corner have a positive length; after the reflex corner a negative one.
    assertArrayEquals(new int[] {zz(10), zz(10), zz(-10), zz(10)}, along);
  }

  @Test
  void innerRingRotatesClockwiseSoConvexCornersStayPositive() {
    final var ctx = polygons(List.of(2), List.of(SQUARE, L_SHAPE_CW));
    final var values = ClassSegmented.apply(PREDICTOR, ctx);
    // Regions: start 4 | interior 6 | corner 2 | turn corr 8 | along 4.
    final var along = Arrays.copyOfRange(values, 20, 24);
    // Only the edge after the reflex corner is negative; the last edge is 20 long.
    assertArrayEquals(new int[] {zz(10), zz(-10), zz(10), zz(20)}, along);
    assertArrayEquals(new int[8], Arrays.copyOfRange(values, 12, 20));
  }

  @Test
  void oddRingStaysOnPlainDelta() {
    final var odd = new ArrayList<>(L_SHAPE);
    odd.add(new Vertex(0, 10));
    final var c = TurnScalar.classify(polygons(List.of(1), List.of(odd)), 7);
    assertArrayEquals(new int[] {1, 0, 0, 0, 0, 0, 0}, c.classes());
  }

  @Test
  void nonPolygonColumnIsPlainQuadCorner() {
    final var ctx =
        new BufferContext(L_SHAPE, List.of(LINESTRING), List.of(), List.of(6), List.of());
    assertEquals(0, TurnScalar.classify(ctx, 6).turns());
    assertEquals(12, new PredictionTransform(Prediction.TURN_SCALAR).apply(ctx).length);
  }

  @Test
  void reconstructsFromTopologyAlone() {
    final var skewed =
        List.of(
            new Vertex(3, 1),
            new Vertex(27, 9),
            new Vertex(22, 24),
            new Vertex(13, 21),
            new Vertex(10, 30),
            new Vertex(-1, 26),
            new Vertex(0, 0));
    for (final var ctx :
        List.of(
            polygons(List.of(2, 1), List.of(SQUARE, L_SHAPE_CW, skewed)),
            polygons(List.of(1), List.of(L_SHAPE)))) {
      final var n = ctx.vertices().size();
      final var values = ClassSegmented.apply(PREDICTOR, ctx);
      assertEquals(
          n * 2 + TurnScalar.classify(ctx, n).turns(), values.length, "one residual pair per vertex plus an along per turn");
      assertEquals(ctx.vertices(), ClassSegmented.invert(PREDICTOR, values, topologyOnly(ctx), n));
    }
  }
  @Test
  void corruptedStreamDoesNotReconstruct() {
    final var ctx = polygons(List.of(1), List.of(L_SHAPE));
    final var values = ClassSegmented.apply(PREDICTOR, ctx);
    values[4] += 2;
    assertNotEquals(
        ctx.vertices(), ClassSegmented.invert(PREDICTOR, values, topologyOnly(ctx), ctx.vertices().size()));
  }

  private static int zz(int value) {
    return EncodingUtils.encodeZigZag(value);
  }

  private static BufferContext topologyOnly(BufferContext ctx) {
    return new BufferContext(
        List.of(), ctx.geometryTypes(), ctx.numGeometries(), ctx.numParts(), ctx.numRings());
  }
}
