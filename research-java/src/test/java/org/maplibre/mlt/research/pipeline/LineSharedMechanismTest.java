package org.maplibre.mlt.research.pipeline;

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
import org.maplibre.mlt.research.encoding.Encoding.BufferContext;

/**
 * {@code LINE_CONTINUATION_SHARED} and {@code LINE_CONTINUATION_SHARED_FRAME_HEADING} in the
 * pipeline: a ladder of rungs between two rails takes the references, roads that touch nowhere fall
 * back to the plain predictor with nothing reversed, and either way the decoder needs only the bytes
 * and the topology.
 */
class LineSharedMechanismTest {

  private static final int LINESTRING = 1;

  private static final List<Prediction> SHARED =
      List.of(
          Prediction.LINE_CONTINUATION_SHARED, Prediction.LINE_CONTINUATION_SHARED_FRAME_HEADING);

  /**
   * Two rails and {@code rungs} parts off them: every even one a rung from the bottom to the top
   * rail (entry and exit both references), every odd one a feeder that starts in the open and only
   * ends on the bottom rail (the encoder reverses it so its start carries the reference).
   */
  private static BufferContext ladder(int rungs) {
    var bottom = new ArrayList<Vertex>();
    var top = new ArrayList<Vertex>();
    for (var i = 0; i < rungs; i++) {
      bottom.add(new Vertex(i * 1000 + (i * 37) % 90, 0));
      top.add(new Vertex(i * 1000 + (i * 37) % 90, 5000));
    }
    var parts = new ArrayList<List<Vertex>>(List.of(bottom, top));
    for (var i = 0; i < rungs; i++) {
      var x = bottom.get(i).x();
      parts.add(
          i % 2 == 0
              ? List.of(bottom.get(i), new Vertex(x, 2500), top.get(i))
              : List.of(new Vertex(x + 400, 2500), new Vertex(x + 200, 1200), bottom.get(i)));
    }
    return lines(parts);
  }

  /** Roads that touch nowhere. */
  private static BufferContext detached(int roads) {
    var parts = new ArrayList<List<Vertex>>();
    for (var i = 0; i < roads; i++) {
      parts.add(
          List.of(
              new Vertex(i * 3000, 0),
              new Vertex(i * 3000 + 700, 300),
              new Vertex(i * 3000 + 1500, 100)));
    }
    return lines(parts);
  }

  private static BufferContext lines(List<List<Vertex>> parts) {
    var vertices = new ArrayList<Vertex>();
    var types = new ArrayList<Integer>();
    var lengths = new ArrayList<Integer>();
    for (var part : parts) {
      vertices.addAll(part);
      types.add(LINESTRING);
      lengths.add(part.size());
    }
    return new BufferContext(vertices, types, List.of(), lengths, List.of());
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
    return PayloadHeader.parse(payload[0] & 0xFF, payload[1] & 0xFF, Reorder.NONE)
        .predictor();
  }

  @ParameterizedTest
  @EnumSource(
      value = Prediction.class,
      names = {"LINE_CONTINUATION_SHARED", "LINE_CONTINUATION_SHARED_FRAME_HEADING"})
  void aLadderTakesTheReferencesAndIsSmallerThanThePlainPredictor(Prediction prediction) {
    var ctx = ladder(40);
    for (var segmentation : Segmentation.values()) {
      var shared = new PipelineEncoding(config(prediction, segmentation));
      var plain = new PipelineEncoding(config(prediction.plain(), segmentation));
      var encoded = shared.encode(ctx);
      assertEquals(prediction, predictorOf(encoded.payload()), segmentation + " keeps the references");
      assertTrue(
          encoded.payload().length < plain.encode(ctx).payload().length,
          segmentation + ": the references must pay");
      assertNotEquals(
          ctx.vertices(), shared.emitted(ctx).vertices(), "parts ending on a rail are reversed");
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = Prediction.class,
      names = {"LINE_CONTINUATION_SHARED", "LINE_CONTINUATION_SHARED_FRAME_HEADING"})
  void decodesFromTheTopologyAloneToTheEmittedBuffer(Prediction prediction) {
    for (var ctx : List.of(ladder(40), ladder(3), detached(5))) {
      for (var segmentation : Segmentation.values()) {
        for (var reorder : List.of(Reorder.NONE, Reorder.HILBERT_CENTROID_ROT_MP)) {
          var encoding = new PipelineEncoding(config(prediction, segmentation).withReorder(reorder));
          var encoded = encoding.encode(ctx);
          // the tile carries the reordered topology streams
          var topology = topologyOf(reorder.apply(ctx));
          var decoded = encoding.decode(encoded, topology, ctx.vertices().size());
          assertEquals(encoding.emitted(ctx).vertices(), decoded, encoding.name());
          assertTrue(Verification.holds(ctx, encoding.emitted(ctx)), encoding.name());
        }
      }
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = Prediction.class,
      names = {"LINE_CONTINUATION_SHARED", "LINE_CONTINUATION_SHARED_FRAME_HEADING"})
  void roadsThatTouchNowhereFallBackToThePlainPredictorAndAreNotReversed(Prediction prediction) {
    var ctx = detached(10);
    var shared = new PipelineEncoding(config(prediction, Segmentation.CLASS));
    var plain = new PipelineEncoding(config(prediction.plain(), Segmentation.CLASS));
    var encoded = shared.encode(ctx);
    assertEquals(prediction.plain(), predictorOf(encoded.payload()));
    assertEquals(
        plain.encode(ctx).payload().length, encoded.payload().length, "no byte of overhead");
    assertSame(ctx, shared.emitted(ctx), "nothing reversed");
  }

  @Test
  void aColumnWithoutLinePartsIsNotChangedByTheLineReferences() {
    // polygon rings are never candidates: a column of squares falls back to the plain predictor
    var vertices = new ArrayList<Vertex>();
    var types = new ArrayList<Integer>();
    var parts = new ArrayList<Integer>();
    var rings = new ArrayList<Integer>();
    for (var house = 0; house < 6; house++) {
      var x = house * 1000;
      vertices.addAll(
          List.of(new Vertex(x, 0), new Vertex(x + 1000, 0), new Vertex(x + 1000, 1000), new Vertex(x, 1000)));
      types.add(2);
      parts.add(1);
      rings.add(4);
    }
    var ctx = new BufferContext(vertices, types, List.of(), parts, rings);
    for (var prediction : SHARED) {
      var encoding = new PipelineEncoding(config(prediction, Segmentation.CLASS));
      assertEquals(prediction.plain(), predictorOf(encoding.encode(ctx).payload()));
      assertSame(ctx, encoding.emitted(ctx));
    }
  }
}
