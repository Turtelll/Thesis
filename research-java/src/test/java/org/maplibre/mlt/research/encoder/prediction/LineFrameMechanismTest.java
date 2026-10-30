package org.maplibre.mlt.research.encoder.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.buffer.Encoding.BufferContext;
import org.maplibre.mlt.research.encoder.format.Composition;
import org.maplibre.mlt.research.encoder.format.PipelineEncoding;
import org.maplibre.mlt.research.registry.PipelineRegistry;
import org.maplibre.mlt.research.encoder.reorder.Reorder;
import org.maplibre.mlt.research.encoder.segmentation.Segmentation;
import org.maplibre.mlt.research.measurement.verification.Verification;

/**
 * {@code LINE_CONTINUATION_FRAME}: the plain-delta edges of line parts (the 2nd vertex of every run
 * and the runs the continuation gate leaves alone) are coded in the frame of the nearest decoded
 * run. A street grid rotated off the tile axes, made of 2- and 3-vertex parts and a few long ones,
 * costs fewer bytes than the same grid under plain {@code LINE_CONTINUATION}; the rotation is an
 * exact bijection, so the decoder needs only the bytes and the topology.
 */
class LineFrameMechanismTest {

  private static final int LINESTRING = 1;

  /**
   * Parts along and across a street at {@code degrees} to the tile axes: lengths 20 to 320, 2, 3 or
   * 9 vertices (9 is long enough for the continuation gate), reproducible. {@code jitter} adds up
   * to that much rounding noise to a vertex; the axis-aligned case has none, because on short edges
   * noise makes the neighbours' frame a few degrees off the axes, which costs a little there
   * (measured on Chicago, docs/results/line-frame-prior.md).
   */
  private static BufferContext grid(int parts, double degrees, int jitter) {
    var random = new Random(7);
    var cos = Math.cos(Math.toRadians(degrees));
    var sin = Math.sin(Math.toRadians(degrees));
    var vertices = new ArrayList<Vertex>();
    var types = new ArrayList<Integer>();
    var numParts = new ArrayList<Integer>();
    for (var p = 0; p < parts; p++) {
      var count = new int[] {2, 3, 2, 9, 3}[p % 5];
      var length = 20 + random.nextInt(300);
      var across = p % 2 == 1;
      // a street's own coordinates: u along it, v across it; parts lie on a lattice 60 apart
      var u0 = random.nextInt(2000);
      var v0 = random.nextInt(2000);
      for (var k = 0; k < count; k++) {
        var step = (double) length * k / (count - 1);
        var u = u0 + (across ? 0 : step) + (jitter == 0 ? 0 : random.nextInt(jitter));
        var v = v0 + (across ? step : 0) + (jitter == 0 ? 0 : random.nextInt(jitter));
        vertices.add(
            new Vertex(
                (int) Math.round(2000 + u * cos - v * sin),
                (int) Math.round(2000 + u * sin + v * cos)));
      }
      types.add(LINESTRING);
      numParts.add(count);
    }
    return new BufferContext(vertices, types, List.of(), numParts, List.of());
  }

  private static BufferContext topologyOnly(BufferContext ctx) {
    return new BufferContext(
        List.of(), ctx.geometryTypes(), ctx.numGeometries(), ctx.numParts(), ctx.numRings());
  }

  /** What a browser would receive: the payload under gzip. */
  private static int gzipped(byte[] payload) {
    try {
      var bytes = new java.io.ByteArrayOutputStream();
      try (var out = new GZIPOutputStream(bytes)) {
        out.write(payload);
      }
      return bytes.size();
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  private static Composition config(Prediction prediction, Segmentation segmentation) {
    return PipelineRegistry.DEFAULT.withReorder(Reorder.NONE)
        .withPredictor(prediction)
        .withSegmentation(segmentation);
  }

  @Test
  void aGridOffTheTileAxesCostsLessThanPlainContinuation() {
    var ctx = grid(400, 33, 2);
    for (var segmentation : List.of(Segmentation.FLAT, Segmentation.CLASS)) {
      var plain = new PipelineEncoding(config(Prediction.LINE_CONTINUATION, segmentation));
      var framed = new PipelineEncoding(config(Prediction.LINE_CONTINUATION_FRAME, segmentation));
      var plainBytes = gzipped(plain.encode(ctx).payload());
      var framedBytes = gzipped(framed.encode(ctx).payload());
      assertTrue(
          framedBytes < plainBytes,
          segmentation + ": framed " + framedBytes + " gzip bytes vs plain " + plainBytes + " gzip bytes");
    }
  }

  @Test
  void aGridAlongTheTileAxesIsNotMadeWorse() {
    var ctx = grid(400, 0, 0);
    var plain = new PipelineEncoding(config(Prediction.LINE_CONTINUATION, Segmentation.CLASS));
    var framed = new PipelineEncoding(config(Prediction.LINE_CONTINUATION_FRAME, Segmentation.CLASS));
    var plainBytes = gzipped(plain.encode(ctx).payload());
    var framedBytes = gzipped(framed.encode(ctx).payload());
    assertTrue(
        framedBytes <= plainBytes + 16,
        "an axis-aligned grid predicts the frame 0: framed " + framedBytes + " vs plain " + plainBytes);
  }

  @Test
  void decodesFromTheTopologyAloneToTheEmittedBuffer() {
    for (var degrees : new double[] {0, 7, 33, 44.9, 45, 61, -20, 90, 123.4}) {
      var ctx = grid(150, degrees, 2);
      for (var segmentation : Segmentation.values()) {
        for (var reorder : List.of(Reorder.NONE, Reorder.HILBERT_CENTROID_ROT_MP)) {
          var encoding =
              new PipelineEncoding(
                  config(Prediction.LINE_CONTINUATION_FRAME, segmentation).withReorder(reorder));
          var encoded = encoding.encode(ctx);
          // the topology streams of a tile are the emitted (reordered) ones
          var decoded =
              encoding.decode(encoded, topologyOnly(encoding.emitted(ctx)), ctx.vertices().size());
          assertEquals(
              encoding.emitted(ctx).vertices(),
              decoded,
              encoding.name() + " at " + degrees + " degrees");
          assertTrue(Verification.holds(ctx, encoding.emitted(ctx)), encoding.name());
        }
      }
    }
  }

  /**
   * Long winding roads (40 vertices) heading along {@code degrees} (plus the heading they wander
   * to), each step a slowly varying length with a slowly varying bend: what the continuation gate
   * lets {@code LINE_CONTINUATION} predict. Reproducible.
   */
  private static BufferContext roads(int parts, double degrees) {
    var random = new Random(11);
    var vertices = new ArrayList<Vertex>();
    var types = new ArrayList<Integer>();
    var numParts = new ArrayList<Integer>();
    for (var p = 0; p < parts; p++) {
      var x = 500.0 + random.nextInt(3000);
      var y = 500.0 + random.nextInt(3000);
      var heading = Math.toRadians(degrees + random.nextInt(360));
      var length = 30 + random.nextInt(60);
      var bend = 0.0;
      for (var k = 0; k < 40; k++) {
        vertices.add(new Vertex((int) Math.round(x), (int) Math.round(y)));
        bend = 0.8 * bend + 0.05 * random.nextGaussian();
        heading += bend;
        length = Math.max(5, length + random.nextInt(9) - 4);
        x += length * Math.cos(heading);
        y += length * Math.sin(heading);
      }
      types.add(LINESTRING);
      numParts.add(40);
    }
    return new BufferContext(vertices, types, List.of(), numParts, List.of());
  }

  @Test
  void theHeadingFrameCostsLessOnWindingRoadsThanPlainContinuation() {
    var ctx = roads(60, 33);
    for (var segmentation : List.of(Segmentation.FLAT, Segmentation.CLASS)) {
      var plain = new PipelineEncoding(config(Prediction.LINE_CONTINUATION, segmentation));
      var heading = new PipelineEncoding(config(Prediction.LINE_CONTINUATION_HEADING, segmentation));
      var plainBytes = gzipped(plain.encode(ctx).payload());
      var headingBytes = gzipped(heading.encode(ctx).payload());
      assertTrue(
          headingBytes < plainBytes,
          segmentation
              + ": heading "
              + headingBytes
              + " gzip bytes vs plain "
              + plainBytes
              + " gzip bytes");
    }
  }

  @Test
  void theHeadingPredictorsDecodeFromTheTopologyAloneToTheEmittedBuffer() {
    // roads heading everywhere (the generator adds a random heading), plus the grids with short
    // parts, so the heading and the neighbour frame both occur in one buffer
    var buffers = new ArrayList<BufferContext>();
    for (var degrees : new double[] {0, 33, 90, 123.4, -170}) {
      buffers.add(roads(20, degrees));
    }
    buffers.add(grid(150, 33, 2));
    for (var ctx : buffers) {
      for (var prediction :
          List.of(Prediction.LINE_CONTINUATION_HEADING, Prediction.LINE_CONTINUATION_FRAME_HEADING)) {
        for (var segmentation : Segmentation.values()) {
          for (var reorder : List.of(Reorder.NONE, Reorder.HILBERT_CENTROID_ROT_MP)) {
            var encoding =
                new PipelineEncoding(config(prediction, segmentation).withReorder(reorder));
            var encoded = encoding.encode(ctx);
            var decoded =
                encoding.decode(encoded, topologyOnly(encoding.emitted(ctx)), ctx.vertices().size());
            assertEquals(encoding.emitted(ctx).vertices(), decoded, encoding.name());
            assertTrue(Verification.holds(ctx, encoding.emitted(ctx)), encoding.name());
          }
        }
      }
    }
  }

  @Test
  void aBufferWithOneRunIsNotFramed() {
    // the runs are not derivable from a single run, so the framed predictor degrades to the plain one
    var ctx = grid(1, 33, 2);
    var plain = new PipelineEncoding(config(Prediction.LINE_CONTINUATION, Segmentation.CLASS));
    var framed = new PipelineEncoding(config(Prediction.LINE_CONTINUATION_FRAME, Segmentation.CLASS));
    assertEquals(plain.encode(ctx).payload().length, framed.encode(ctx).payload().length);
    assertEquals(
        ctx.vertices(), framed.decode(framed.encode(ctx), topologyOnly(ctx), ctx.vertices().size()));
  }
}
