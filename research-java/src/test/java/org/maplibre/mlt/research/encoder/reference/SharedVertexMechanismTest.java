package org.maplibre.mlt.research.encoder.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.buffer.Encoding.BufferContext;
import org.maplibre.mlt.research.encoder.format.PayloadHeader;
import org.maplibre.mlt.research.encoder.format.PipelineConfig;
import org.maplibre.mlt.research.encoder.format.PipelineEncoding;
import org.maplibre.mlt.research.registry.PipelineRegistry;
import org.maplibre.mlt.research.encoder.prediction.Prediction;
import org.maplibre.mlt.research.encoder.reorder.Reorder;
import org.maplibre.mlt.research.encoder.segmentation.Segmentation;
import org.maplibre.mlt.research.pipeline.Verification;

/**
 * {@code QUAD_CORNER_SHARED} and {@code QUAD_CORNER_SHARED_FRAME} in the pipeline: a terrace of
 * houses that share walls takes the references, detached houses fall back to the plain predictor
 * with nothing rotated, and either way the decoder needs only the bytes and the topology.
 */
class SharedVertexMechanismTest {

  /** Houses in a row, each sharing its west wall with the east wall of the one before. */
  private static BufferContext terrace(int houses) {
    var vertices = new ArrayList<Vertex>();
    var types = new ArrayList<Integer>();
    var parts = new ArrayList<Integer>();
    var rings = new ArrayList<Integer>();
    var x = 0;
    for (var house = 0; house < houses; house++) {
      var width = 900 + (house * 37) % 90;
      vertices.addAll(
          List.of(
              new Vertex(x, 0),
              new Vertex(x + width, 0),
              new Vertex(x + width, 1000),
              new Vertex(x, 1000)));
      x += width;
      types.add(2); // POLYGON
      parts.add(1);
      rings.add(4);
    }
    return new BufferContext(vertices, types, List.of(), parts, rings);
  }

  /** Squares that touch nowhere. */
  private static BufferContext detached(int houses) {
    var vertices = new ArrayList<Vertex>();
    var types = new ArrayList<Integer>();
    var parts = new ArrayList<Integer>();
    var rings = new ArrayList<Integer>();
    for (var house = 0; house < houses; house++) {
      var x = house * 3000;
      vertices.addAll(
          List.of(
              new Vertex(x, 0),
              new Vertex(x + 1000, 0),
              new Vertex(x + 1000, 1000),
              new Vertex(x, 1000)));
      types.add(2);
      parts.add(1);
      rings.add(4);
    }
    return new BufferContext(vertices, types, List.of(), parts, rings);
  }

  private static BufferContext topologyOf(BufferContext ctx) {
    return new BufferContext(
        List.of(), ctx.geometryTypes(), ctx.numGeometries(), ctx.numParts(), ctx.numRings());
  }

  private static PipelineConfig config(Prediction prediction, Segmentation segmentation) {
    return PipelineRegistry.DEFAULT.withPredictor(prediction).withSegmentation(segmentation);
  }

  /** The predictor a payload was written under, read back from its header. */
  private static Prediction predictorOf(byte[] payload) {
    return PayloadHeader.parse(payload[0] & 0xFF, payload[1] & 0xFF, Reorder.NONE).predictor();
  }

  @ParameterizedTest
  @EnumSource(
      value = Prediction.class,
      names = {"QUAD_CORNER_SHARED", "QUAD_CORNER_SHARED_FRAME"})
  void aTerraceTakesTheReferencesAndIsSmallerThanThePlainPredictor(Prediction prediction) {
    var ctx = terrace(40);
    for (var segmentation : Segmentation.values()) {
      var shared = new PipelineEncoding(config(prediction, segmentation));
      var plain = new PipelineEncoding(config(prediction.plain(), segmentation));
      var encoded = shared.encode(ctx);
      assertEquals(prediction, predictorOf(encoded.payload()), segmentation + " keeps the references");
      assertTrue(
          encoded.payload().length < plain.encode(ctx).payload().length,
          segmentation + ": the references must pay");
      assertNotEquals(ctx.vertices(), shared.emitted(ctx).vertices(), "rings are rotated to start on the wall");
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = Prediction.class,
      names = {"QUAD_CORNER_SHARED", "QUAD_CORNER_SHARED_FRAME"})
  void decodesFromTheTopologyAloneToTheEmittedBuffer(Prediction prediction) {
    for (var ctx : List.of(terrace(40), terrace(3), detached(5))) {
      for (var segmentation : Segmentation.values()) {
        for (var reorder : List.of(Reorder.NONE, Reorder.HILBERT_CENTROID_ROT_MP)) {
          var encoding = new PipelineEncoding(config(prediction, segmentation).withReorder(reorder));
          var encoded = encoding.encode(ctx);
          var decoded = encoding.decode(encoded, topologyOf(ctx), ctx.vertices().size());
          assertEquals(encoding.emitted(ctx).vertices(), decoded, encoding.name());
          assertTrue(Verification.holds(ctx, encoding.emitted(ctx)), encoding.name());
        }
      }
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = Prediction.class,
      names = {"QUAD_CORNER_SHARED", "QUAD_CORNER_SHARED_FRAME"})
  void detachedHousesFallBackToThePlainPredictorAndAreNotRotated(Prediction prediction) {
    var ctx = detached(10);
    var shared = new PipelineEncoding(config(prediction, Segmentation.CLASS));
    var plain = new PipelineEncoding(config(prediction.plain(), Segmentation.CLASS));
    var encoded = shared.encode(ctx);
    assertEquals(prediction.plain(), predictorOf(encoded.payload()));
    assertEquals(plain.encode(ctx).payload().length, encoded.payload().length, "no byte of overhead");
    assertSame(ctx, shared.emitted(ctx), "nothing rotated");
  }

  @Test
  void theHeaderRoundTripsEveryPredictor() {
    for (var prediction : Prediction.values()) {
      var config = PipelineRegistry.DEFAULT.withPredictor(prediction);
      var header = PayloadHeader.write(config);
      assertEquals(
          config,
          PayloadHeader.parse(header[0] & 0xFF, header[1] & 0xFF, config.reorder()));
    }
  }
}
