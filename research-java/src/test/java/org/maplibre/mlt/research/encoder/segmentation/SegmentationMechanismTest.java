package org.maplibre.mlt.research.encoder.segmentation;

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
import org.maplibre.mlt.research.buffer.Encoding.BufferContext;
import org.maplibre.mlt.research.extraction.VertexBufferExtractor;
import org.maplibre.mlt.research.encoder.prediction.Prediction;
import org.maplibre.mlt.research.encoder.reorder.Reorder;
import org.maplibre.mlt.research.encoder.format.PayloadHeader;
import org.maplibre.mlt.research.encoder.format.PipelineEncoding;
import org.maplibre.mlt.research.encoder.format.PipelineEncodingTest;
import org.maplibre.mlt.research.registry.PipelineRegistry;
import org.maplibre.mlt.research.pipeline.Verification;

/**
 * S3 verification: stream layouts are a pure function of the topology streams, the lengths a
 * decoder derives equal the lengths the encoder wrote, and the roles are what the design says.
 */
class SegmentationMechanismTest {

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

  private static BufferContext buildings() {
    return contextOf(
        List.of(
            polygon(0, 0, 40, 0, 40, 30, 0, 30),
            polygon(100, 100, 130, 100, 115, 140),
            polygon(200, 200, 260, 200, 260, 230, 230, 230, 230, 260, 200, 260),
            polygon(-50, -50, -10, -50, -10, -20, -50, -20)));
  }

  private static BufferContext roads() {
    return contextOf(
        List.of(
            line(0, 0, 10, 5, 20, 10, 30, 14),
            line(30, 14, 45, 20, 60, 30),
            line(500, 500, 520, 480),
            line(-30, 70, -10, 75, 15, 90, 20, 100, 33, 111)));
  }

  private static BufferContext points() {
    return contextOf(
        List.of(
            GF.createPoint(new Coordinate(5, 6)),
            GF.createPoint(new Coordinate(500, 600)),
            GF.createPoint(new Coordinate(-7, 80))));
  }

  private static BufferContext topology(BufferContext ctx) {
    return new BufferContext(
        List.of(), ctx.geometryTypes(), ctx.numGeometries(), ctx.numParts(), ctx.numRings());
  }

  @Test
  void rolesAreJumpEdgeBodyPerRun() {
    var ctx = buildings();
    var roles = Segmentation.roles(topology(ctx), ctx.vertices().size());
    // squares have four vertices: jump, edge, body, body; the triangle: jump, edge, body
    assertArrayEquals(
        new int[] {0, 1, 2, 2, 0, 1, 2, 0, 1, 2, 2, 2, 2, 0, 1, 2, 2}, roles);
  }

  @Test
  void everyVertexOfAPointColumnIsAJump() {
    var ctx = points();
    var roles = Segmentation.roles(topology(ctx), ctx.vertices().size());
    assertArrayEquals(new int[] {0, 0, 0}, roles);
  }

  @Test
  void aBufferWithoutTopologyIsOneRun() {
    var ctx = BufferContext.of(buildings().vertices());
    var roles = Segmentation.roles(topology(ctx), ctx.vertices().size());
    assertEquals(0, roles[0]);
    assertEquals(1, roles[1]);
    assertTrue(java.util.Arrays.stream(roles, 2, roles.length).allMatch(r -> r == 2));
  }

  /**
   * The stream lengths follow from the topology: the encoder's layout (with vertices) and the
   * decoder's (without) are equal, and the sizes the encoder wrote add up to what the decoder reads.
   */
  @ParameterizedTest
  @EnumSource(Segmentation.class)
  void layoutIsAPureFunctionOfTheTopology(Segmentation segmentation) {
    for (var prediction : Prediction.values()) {
      for (var ctx : List.of(buildings(), roads(), points())) {
        var n = ctx.vertices().size();
        var predictor = prediction.predictor();
        var encoder = segmentation.layout(ctx, predictor, n);
        var decoder = segmentation.layout(topology(ctx), predictor, n);
        assertArrayEquals(encoder.streamOf(), decoder.streamOf());
        assertArrayEquals(encoder.valueCounts(), decoder.valueCounts());

        var streams = Segmentation.split(encoder, predictor, ctx.vertices());
        assertEquals(encoder.streamCount(), streams.length);
        var total = 0;
        for (var s = 0; s < streams.length; s++) {
          assertEquals(encoder.valueCounts()[s], streams[s].length, "stream " + s + " of " + segmentation);
          total += streams[s].length;
        }
        // two values per vertex, plus one side value per vertex that carries one
        var sideValues = encoder.sideStream() < 0 ? 0 : encoder.valueCounts()[encoder.sideStream()];
        assertEquals(2 * n + sideValues, total);
        assertEquals(ctx.vertices(), Segmentation.join(decoder, predictor, streams, n));
      }
    }
  }

  /**
   * The bytes of every stream add up: {@code decode} fails if bytes are left over after the last
   * stream, so a round trip proves the derived lengths equal the written ones.
   */
  @ParameterizedTest
  @EnumSource(Segmentation.class)
  void decoderConsumesExactlyTheWrittenBytes(Segmentation segmentation) {
    var config =
        PipelineRegistry.DEFAULT.withReorder(Reorder.NONE).withSegmentation(segmentation);
    var encoding = new PipelineEncoding(config);
    for (var ctx : List.of(buildings(), roads(), points())) {
      var encoded = encoding.encode(ctx);
      assertEquals(ctx.vertices(), encoding.decode(encoded, ctx, ctx.vertices().size()));
      assertTrue(encoded.payload().length > PayloadHeader.HEADER_BYTES);
    }
  }

  @Test
  void classSortMakesEveryVertexCountContiguous() {
    // features with 4, 3, 4, 3, 4 vertices scattered in space
    var ctx =
        contextOf(
            List.of(
                polygon(0, 0, 40, 0, 40, 30, 0, 30),
                polygon(2000, 2000, 2030, 2000, 2015, 2040),
                polygon(1000, 0, 1040, 0, 1040, 30, 1000, 30),
                polygon(10, 10, 40, 10, 25, 50),
                polygon(3000, 3000, 3040, 3000, 3040, 3030, 3000, 3030)));
    var sorted = Reorder.CLASS_HILBERT_CENTROID_ROT.apply(ctx);
    var counts = sorted.numRings();
    for (var i = 1; i < counts.size(); i++) {
      assertTrue(counts.get(i) >= counts.get(i - 1), "ring sizes ascend: " + counts);
    }
    assertTrue(Verification.holds(ctx, sorted), Verification.describe(ctx, sorted));
  }
}
