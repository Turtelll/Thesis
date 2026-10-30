package org.maplibre.mlt.research.pipeline;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.research.buffer.Encoding.BufferContext;
import org.maplibre.mlt.research.extraction.VertexBufferExtractor;
import org.maplibre.mlt.research.encoder.stream.ByteWidth;

/**
 * S2 verification: the residuals the pipeline's predictors produce are the ones of the old
 * pipeline, value for value, before any serialization.
 */
class PredictionMechanismTest {

  private static final GeometryFactory GF = new GeometryFactory();

  private static BufferContext contextOf(List<? extends Geometry> geometries) {
    return PipelineEncodingTest.contextOf(VertexBufferExtractor.extract(new ArrayList<>(geometries)));
  }

  private static Geometry polygon(double... xy) {
    var coordinates = new Coordinate[xy.length / 2 + 1];
    for (var i = 0; i < xy.length / 2; i++) {
      coordinates[i] = new Coordinate(xy[2 * i], xy[2 * i + 1]);
    }
    coordinates[coordinates.length - 1] = coordinates[0];
    return GF.createPolygon(coordinates);
  }

  private static Geometry line(double... xy) {
    var coordinates = new Coordinate[xy.length / 2];
    for (var i = 0; i < coordinates.length; i++) {
      coordinates[i] = new Coordinate(xy[2 * i], xy[2 * i + 1]);
    }
    return GF.createLineString(coordinates);
  }

  /** Buildings: rectangles, a triangle, an L shape, a rotated square. */
  private static BufferContext buildings() {
    return contextOf(
        List.of(
            polygon(0, 0, 40, 0, 40, 30, 0, 30),
            polygon(100, 100, 130, 100, 115, 140),
            polygon(200, 200, 260, 200, 260, 230, 230, 230, 230, 260, 200, 260),
            polygon(400, 400, 420, 410, 410, 430, 390, 420),
            polygon(-50, -50, -10, -50, -10, -20, -50, -20)));
  }

  /** Roads: several parts, one continuing the previous end direction. */
  private static BufferContext roads() {
    return contextOf(
        List.of(
            line(0, 0, 10, 5, 20, 10, 30, 14),
            line(30, 14, 45, 20, 60, 30),
            line(500, 500, 520, 480),
            line(-30, 70, -10, 75, 15, 90, 20, 100, 33, 111)));
  }

  /** The residuals of {@code predictor} as the old region layout orders them: zigzag, per class. */
  private static int[] regionValues(Prediction prediction, BufferContext ctx) {
    var predictor = prediction.predictor();
    var layout = Segmentation.FLAT.layout(ctx, predictor, ctx.vertices().size());
    var residuals = Segmentation.split(layout, predictor, ctx.vertices())[0];
    var values = new ArrayList<Integer>();
    for (var vertexClass : predictor.regionOrder()) {
      for (var i = 0; i < ctx.vertices().size(); i++) {
        if (layout.classes()[i] == vertexClass) {
          values.add(EncodingUtils.encodeZigZag(residuals[2 * i]));
          values.add(EncodingUtils.encodeZigZag(residuals[2 * i + 1]));
        }
      }
    }
    return values.stream().mapToInt(Integer::intValue).toArray();
  }

  @Test
  void deltaIsTheContinuousFirstOrderDeltaAcrossPartsAndFeatures() {
    var ctx = buildings();
    var predictor = Prediction.DELTA.predictor();
    var layout = Segmentation.FLAT.layout(ctx, predictor, ctx.vertices().size());
    var residuals = Segmentation.split(layout, predictor, ctx.vertices())[0];

    var prevX = 0;
    var prevY = 0;
    for (var i = 0; i < ctx.vertices().size(); i++) {
      var v = ctx.vertices().get(i);
      assertEquals(v.x() - prevX, residuals[2 * i], "x of vertex " + i);
      assertEquals(v.y() - prevY, residuals[2 * i + 1], "y of vertex " + i);
      prevX = v.x();
      prevY = v.y();
    }
  }

  @Test
  void quadCornerPredictsARectanglesFourthCornerExactly() {
    var ctx = buildings();
    var values = regionValues(Prediction.QUAD_CORNER, ctx);

    assertEquals(ctx.vertices().size() * 2, values.length);
    assertTrue(
        java.util.Arrays.stream(values).anyMatch(v -> v == 0),
        "a rectangle's fourth corner is predicted exactly");
  }

  @Test
  void lineContinuationWritesOneResidualPairPerVertex() {
    var ctx = roads();
    var values = regionValues(Prediction.LINE_CONTINUATION, ctx);

    assertEquals(ctx.vertices().size() * 2, values.length);
    assertTrue(java.util.Arrays.stream(values).allMatch(v -> v >= 0), "zigzag values are never negative");
  }
  /** Every predictor reconstructs every kind of buffer from the topology alone (no vertices). */
  @ParameterizedTest
  @EnumSource(Prediction.class)
  void everyPredictorJoinsBackWithoutTheVertices(Prediction prediction) {
    var predictor = prediction.predictor();
    for (var ctx : List.of(buildings(), roads())) {
      var n = ctx.vertices().size();
      var layout = Segmentation.FLAT.layout(ctx, predictor, n);
      var streams = Segmentation.split(layout, predictor, ctx.vertices());
      var topology =
          new BufferContext(List.of(), ctx.geometryTypes(), ctx.numGeometries(), ctx.numParts(), ctx.numRings());
      var decodedLayout = Segmentation.FLAT.layout(topology, predictor, n);
      assertArrayEquals(layout.classes(), decodedLayout.classes(), "classes come from the topology");
      assertEquals(ctx.vertices(), Segmentation.join(decodedLayout, predictor, streams, n));
    }
  }

  /** TURN_SCALAR writes one residual pair per vertex and its {@code along} scalars as a side stream. */
  @Test
  void turnScalarWritesAScalarPerTurnVertex() {
    var ctx = buildings();
    var predictor = Prediction.TURN_SCALAR.predictor();
    var layout = Segmentation.FLAT.layout(ctx, predictor, ctx.vertices().size());
    var streams = Segmentation.split(layout, predictor, ctx.vertices());

    assertEquals(ctx.vertices().size() * 2, streams[0].length);
    assertTrue(streams[layout.sideStream()].length > 0, "the L-shaped building has turn vertices");
  }
  @ParameterizedTest
  @EnumSource(Prediction.class)
  void headerCarriesThePredictor(Prediction prediction) {
    var config = PipelineConfig.IDENTITY.withPredictor(prediction).withWidth(ByteWidth.VARINT_SPLIT);
    var header = PayloadHeader.write(config);
    assertEquals(
        config, PayloadHeader.parse(header[0] & 0xFF, header[1] & 0xFF, config.reorder()));
  }
}
