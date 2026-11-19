package org.maplibre.mlt.research.encoder.prediction.mechanism;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.buffer.Encoding.BufferContext;
import org.maplibre.mlt.research.encoder.prediction.Prediction;
import org.maplibre.mlt.research.workshop.PredictionTransform;

/**
 * Pins {@code QUAD_CORNER_SHARED_FRAME}: ring edges are coded in the frame of the nearest decoded
 * ring, so a street of buildings rotated off the tile axes costs fewer bytes than the same street
 * coded by plain delta, and the rotation is an exact bijection so the decode is lossless.
 */
class FramePriorMechanismTest {

  private static final int POLYGON = 2;

  /** A rectangle (4 vertices) or an L (6 vertices), counter-clockwise, in local coordinates. */
  private static int[][] shape(int kind, int size) {
    return kind == 0
        ? new int[][] {{0, 0}, {size, 0}, {size, size / 2}, {0, size / 2}}
        : new int[][] {
          {0, 0}, {size, 0}, {size, size / 2}, {size / 2, size / 2}, {size / 2, size}, {0, size}
        };
  }

  /** {@code houses} buildings along a street at {@code degrees} to the tile axes, 40 apart. */
  private static BufferContext street(int houses, double degrees) {
    final var vertices = new ArrayList<Vertex>();
    final var numRings = new ArrayList<Integer>();
    final var cos = Math.cos(Math.toRadians(degrees));
    final var sin = Math.sin(Math.toRadians(degrees));
    for (var h = 0; h < houses; h++) {
      final var size = 300 + 37 * (h % 5);
      final var offset = h * (size + 40);
      final var local = shape(h % 3 == 2 ? 1 : 0, size);
      for (final var p : local) {
        final var x = p[0] + offset;
        final var y = p[1];
        vertices.add(
            new Vertex(
                (int) Math.round(500 + x * cos - y * sin), (int) Math.round(500 + x * sin + y * cos)));
      }
      numRings.add(local.length);
    }
    final var types = new ArrayList<Integer>();
    final var parts = new ArrayList<Integer>();
    for (var h = 0; h < houses; h++) {
      types.add(POLYGON);
      parts.add(1);
    }
    return new BufferContext(vertices, types, List.of(), parts, numRings);
  }

  private static long residualBits(Prediction prediction, BufferContext ctx) {
    var sum = 0L;
    for (final var value : new PredictionTransform(prediction).apply(ctx)) {
      sum += 32 - Integer.numberOfLeadingZeros(value);
    }
    return sum;
  }

  @Test
  void streetOffTheTileAxesCostsFewerResidualBitsThanPlainCorners() {
    final var ctx = street(40, 33);
    final var plain = residualBits(Prediction.QUAD_CORNER, ctx);
    final var framed = residualBits(Prediction.QUAD_CORNER_FRAME, ctx);
    assertTrue(framed < plain, "framed " + framed + " bits vs plain " + plain + " bits");
  }

  @Test
  void streetAlongTheTileAxesIsNotMadeWorse() {
    final var ctx = street(40, 0);
    assertTrue(
        residualBits(Prediction.QUAD_CORNER_FRAME, ctx)
            <= residualBits(Prediction.QUAD_CORNER, ctx) + 8);
  }

  @Test
  void aTwoVertexLineSetsTheFrameOfTheRunsAfterIt() {
    for (final var degrees : new double[] {7, 33, 61, 123.4}) {
      final var cos = Math.cos(Math.toRadians(degrees));
      final var sin = Math.sin(Math.toRadians(degrees));
      final var road =
          List.of(
              new Vertex(1000, 1000),
              new Vertex((int) Math.round(1000 + 300 * cos), (int) Math.round(1000 + 300 * sin)));
      final var prior = new FramePrior();
      prior.endLine(road, 0, 2);

      // the next run starts nearby: an edge along the road and an edge across it both leave one
      // component near zero (which one depends on the frame folded into [-45, 45)), and a single
      // vertex has no frame to remember
      prior.beginRing(new Vertex(1100, 1100));
      prior.forward((int) Math.round(200 * cos), (int) Math.round(200 * sin));
      assertTrue(
          Math.min(Math.abs(prior.x()), Math.abs(prior.y())) <= 3,
          "along the road at " + degrees + ": " + prior.x() + "," + prior.y());
      prior.forward((int) Math.round(-200 * sin), (int) Math.round(200 * cos));
      assertTrue(
          Math.min(Math.abs(prior.x()), Math.abs(prior.y())) <= 3,
          "across the road at " + degrees + ": " + prior.x() + "," + prior.y());
    }

    final var lone = new FramePrior();
    lone.endLine(List.of(new Vertex(0, 0)), 0, 1);
    lone.beginRing(new Vertex(0, 0));
    lone.forward(200, 100);
    assertEquals(200, lone.x());
    assertEquals(100, lone.y());
  }

  @Test
  void theRotationIsExactlyInvertibleInEveryFrame() {
    final var prior = new FramePrior();
    for (var angle = -90; angle < 90; angle += 3) {
      // Seed a ring whose first wall sets the frame, so forward/inverse run in that frame.
      prior.beginRing(new Vertex(0, 0));
      prior.edge(
          (int) Math.round(5000 * Math.cos(Math.toRadians(angle))),
          (int) Math.round(5000 * Math.sin(Math.toRadians(angle))));
      for (var dx = -700; dx <= 700; dx += 61) {
        for (var dy = -700; dy <= 700; dy += 53) {
          prior.forward(dx, dy);
          final var rx = prior.x();
          final var ry = prior.y();
          prior.inverse(rx, ry);
          assertEquals(dx, prior.x(), "x at frame from " + angle + " deg");
          assertEquals(dy, prior.y(), "y at frame from " + angle + " deg");
        }
      }
    }
  }

  @Test
  void theHeadingRotationIsExactlyInvertibleForEveryEdge() {
    final var prior = new FramePrior();
    final var edges = new ArrayList<int[]>();
    edges.add(new int[] {0, 0});
    for (var angle = -180; angle <= 180; angle += 5) {
      // 45 deg boundaries, the axes, and edges of several lengths
      for (var length : new int[] {1, 2, 17, 600, 8000}) {
        edges.add(
            new int[] {
              (int) Math.round(length * Math.cos(Math.toRadians(angle))),
              (int) Math.round(length * Math.sin(Math.toRadians(angle)))
            });
      }
    }
    for (var edge : edges) {
      for (var dx = -700; dx <= 700; dx += 61) {
        for (var dy = -700; dy <= 700; dy += 53) {
          prior.forwardHeading(edge[0], edge[1], dx, dy);
          final var rx = prior.x();
          final var ry = prior.y();
          prior.inverseHeading(edge[0], edge[1], rx, ry);
          assertEquals(dx, prior.x(), "x for edge " + edge[0] + "," + edge[1]);
          assertEquals(dy, prior.y(), "y for edge " + edge[0] + "," + edge[1]);
        }
      }
    }
  }

  // --- the integer frame ----------------------------------------------------------

  @Test
  void integerStreetOffTheTileAxesCostsFewerResidualBitsThanPlainCorners() {
    final var ctx = street(40, 33);
    final var plain = residualBits(Prediction.QUAD_CORNER, ctx);
    final var framed = residualBits(Prediction.QUAD_CORNER_IFRAME, ctx);
    final var floatFramed = residualBits(Prediction.QUAD_CORNER_FRAME, ctx);
    assertTrue(framed < plain, "integer framed " + framed + " bits vs plain " + plain + " bits");
    // the 12-bit frame and the arctangent table are as good as the float frame, to a percent
    assertTrue(framed <= floatFramed * 1.01 + 8, "integer " + framed + " bits vs float " + floatFramed);
  }

  // --- the previous-run frame -----------------------------------------------------

  @Test
  void previousFrameStreetOffTheTileAxesCostsFewerResidualBitsThanPlainCorners() {
    final var ctx = street(40, 33);
    final var plain = residualBits(Prediction.QUAD_CORNER, ctx);
    final var previous = residualBits(Prediction.QUAD_CORNER_PREVFRAME, ctx);
    assertTrue(previous < plain, "previous frame " + previous + " bits vs plain " + plain + " bits");
  }

  @Test
  void previousFrameStreetAlongTheTileAxesIsNotMadeWorse() {
    final var ctx = street(40, 0);
    assertTrue(
        residualBits(Prediction.QUAD_CORNER_PREVFRAME, ctx)
            <= residualBits(Prediction.QUAD_CORNER, ctx) + 8);
  }

  @Test
  void theFrameOfARingIsThePreviousRingsAndNoEdgeOfItsOwnChangesIt() {
    final var prior = FramePrior.previousRun();
    // the first ring has no previous one: the tile's axes
    prior.beginRing(new Vertex(0, 0));
    prior.edge(3000, 4000);
    prior.forward(200, 100);
    assertEquals(200, prior.x());
    assertEquals(100, prior.y());

    // a rectangle turned by 33 deg becomes the frame of the next ring
    final var cos = Math.cos(Math.toRadians(33));
    final var sin = Math.sin(Math.toRadians(33));
    final var ring = new ArrayList<Vertex>();
    for (final var p : new int[][] {{0, 0}, {600, 0}, {600, 300}, {0, 300}}) {
      ring.add(new Vertex((int) Math.round(p[0] * cos - p[1] * sin), (int) Math.round(p[0] * sin + p[1] * cos)));
    }
    prior.endRing(ring, 0, ring.size());

    prior.beginRing(new Vertex(900, 900));
    prior.forward((int) Math.round(200 * cos), (int) Math.round(200 * sin));
    assertTrue(Math.min(Math.abs(prior.x()), Math.abs(prior.y())) <= 3, prior.x() + "," + prior.y());
    final var x = prior.x();
    final var y = prior.y();
    // an edge of the new ring in some other direction does not move its frame
    prior.edge(-5000, 17);
    prior.edge(11, 9000);
    prior.forward((int) Math.round(200 * cos), (int) Math.round(200 * sin));
    assertEquals(x, prior.x());
    assertEquals(y, prior.y());
  }

  @Test
  void theFirstEdgeOfARingSetsTheFrameOfTheEdgesAfterIt() {
    final var cos = Math.cos(Math.toRadians(33));
    final var sin = Math.sin(Math.toRadians(33));
    for (final var prior : new FramePrior[] {FramePrior.previousRunFirstEdge(), FramePrior.previousRunFirstEdgeOnly()}) {
      // no previous ring: the first edge is coded in the tile's axes
      prior.beginRing(new Vertex(0, 0));
      prior.forward(200, 100);
      assertEquals(200, prior.x());
      assertEquals(100, prior.y());
      // the first edge, 33 deg off the axes, sets the frame of what follows
      prior.edge((int) Math.round(600 * cos), (int) Math.round(600 * sin));
      prior.forward((int) Math.round(-300 * sin), (int) Math.round(300 * cos));
      assertTrue(Math.min(Math.abs(prior.x()), Math.abs(prior.y())) <= 3, prior.x() + "," + prior.y());
      final var x = prior.x();
      final var y = prior.y();
      // a second edge in another direction does not move it
      prior.edge(-5000, 17);
      prior.forward((int) Math.round(-300 * sin), (int) Math.round(300 * cos));
      assertEquals(x, prior.x());
      assertEquals(y, prior.y());
    }
  }

  @Test
  void aShortFirstEdgeDoesNotOverrideThePreviousFrameWhenBlendedAndALongOneDoes() {
    // the previous ring: a rectangle turned by 33 deg
    final var cos = Math.cos(Math.toRadians(33));
    final var sin = Math.sin(Math.toRadians(33));
    final var ring = new ArrayList<Vertex>();
    for (final var p : new int[][] {{0, 0}, {600, 0}, {600, 300}, {0, 300}}) {
      ring.add(new Vertex((int) Math.round(p[0] * cos - p[1] * sin), (int) Math.round(p[0] * sin + p[1] * cos)));
    }
    for (final var length : new int[] {4, 3000}) {
      final var plain = FramePrior.previousRunFirstEdgeOnly();
      final var blended = FramePrior.previousRunFirstEdgeBlended();
      for (final var prior : new FramePrior[] {plain, blended}) {
        prior.beginRing(new Vertex(0, 0));
        // a long first edge at 33 deg: the frame this ring is remembered by
        prior.edge((int) Math.round(6000 * cos), (int) Math.round(6000 * sin));
        prior.endRing(ring, 0, ring.size());
        // the next ring starts with an edge along the tile's axes, 4 or 3000 units long
        prior.beginRing(new Vertex(900, 900));
        prior.edge(length, 0);
        prior.forward((int) Math.round(200 * cos), (int) Math.round(200 * sin));
      }
      if (length == 4) {
        // still (about, the 4 units pull it by under a degree) the previous ring's 33 deg: the blend kept it,
        // the plain mode took the 0 deg of the short edge
        assertTrue(Math.min(Math.abs(blended.x()), Math.abs(blended.y())) <= 8, blended.x() + "," + blended.y());
        assertTrue(Math.min(Math.abs(plain.x()), Math.abs(plain.y())) > 3, plain.x() + "," + plain.y());
      } else {
        // a long wall wins the blend over weight 50 almost entirely: the frame is its 0 deg
        assertTrue(Math.abs(blended.x() - 200 * cos) <= 8 && Math.abs(blended.y() - 200 * sin) <= 8,
            blended.x() + "," + blended.y());
      }
    }
  }

  @Test
  void aRingWithAZeroFirstEdgeKeepsThePreviousFrame() {
    final var prior = FramePrior.previousRunFirstEdgeOnly();
    prior.beginRing(new Vertex(0, 0));
    prior.edge(0, 0);
    prior.forward(200, 100);
    assertEquals(200, prior.x());
    assertEquals(100, prior.y());
  }

  @Test
  void theDirectionTableStaysWithinOneUnitOfAtan2() {
    // units of 1/16384 turn; the table is read at a slope quantized to 1/4096
    for (var angle = -180.0; angle <= 180.0; angle += 0.37) {
      for (var length : new double[] {1, 3, 250, 123456}) {
        final var y = length * Math.sin(Math.toRadians(angle));
        final var x = length * Math.cos(Math.toRadians(angle));
        final var exact = Math.atan2(y, x) * (8192 / Math.PI);
        final var tabulated = FramePrior.IntTables.direction(y, x);
        assertTrue(Math.abs(tabulated - exact) < 1.01, angle + " deg: " + tabulated + " vs " + exact);
      }
    }
    assertEquals(0, FramePrior.IntTables.direction(0, 0));
    assertEquals(0, FramePrior.IntTables.direction(0, 5));
    assertEquals(8192, FramePrior.IntTables.direction(0, -5));
    assertEquals(4096, FramePrior.IntTables.direction(5, 0));
    assertEquals(-4096, FramePrior.IntTables.direction(-5, 0));
    assertEquals(2048, FramePrior.IntTables.direction(7, 7));
  }

  @Test
  void theIntegerRotationIsExactlyInvertibleInEveryFrame() {
    final var prior = new FramePrior(true);
    for (var angle = -90; angle < 90; angle += 3) {
      prior.beginRing(new Vertex(0, 0));
      prior.edge(
          (int) Math.round(5000 * Math.cos(Math.toRadians(angle))),
          (int) Math.round(5000 * Math.sin(Math.toRadians(angle))));
      for (var dx = -9000; dx <= 9000; dx += 611) {
        for (var dy = -9000; dy <= 9000; dy += 533) {
          prior.forward(dx, dy);
          final var rx = prior.x();
          final var ry = prior.y();
          prior.inverse(rx, ry);
          assertEquals(dx, prior.x(), "x at frame from " + angle + " deg");
          assertEquals(dy, prior.y(), "y at frame from " + angle + " deg");
        }
      }
    }
  }

  @Test
  void theIntegerHeadingRotationIsExactlyInvertibleAndAlignsTheEdge() {
    final var prior = new FramePrior(true);
    for (var angle = -180; angle <= 180; angle += 5) {
      for (var length : new int[] {1, 2, 17, 600, 8000}) {
        final var ex = (int) Math.round(length * Math.cos(Math.toRadians(angle)));
        final var ey = (int) Math.round(length * Math.sin(Math.toRadians(angle)));
        for (var dx = -700; dx <= 700; dx += 61) {
          for (var dy = -700; dy <= 700; dy += 53) {
            prior.forwardHeading(ex, ey, dx, dy);
            final var rx = prior.x();
            final var ry = prior.y();
            prior.inverseHeading(ex, ey, rx, ry);
            assertEquals(dx, prior.x(), "x for edge " + ex + "," + ey);
            assertEquals(dy, prior.y(), "y for edge " + ex + "," + ey);
          }
        }
      }
    }
    prior.forwardHeading(0, 0, 5, -9);
    assertEquals(5, prior.x());
    assertEquals(-9, prior.y());
    for (var angle = -180; angle < 180; angle += 7) {
      final var ex = (int) Math.round(3000 * Math.cos(Math.toRadians(angle)));
      final var ey = (int) Math.round(3000 * Math.sin(Math.toRadians(angle)));
      prior.forwardHeading(ex, ey, ex, ey);
      assertTrue(Math.abs(prior.x() - 3000) <= 3, "along at " + angle + ": " + prior.x());
      assertTrue(Math.abs(prior.y()) <= 3, "across at " + angle + ": " + prior.y());
    }
  }

  @Test
  void theIntegerFrameFollowsAStreetAndIsRememberedWithoutAngles() {
    final var prior = new FramePrior(true);
    for (final var degrees : new double[] {7, 33, 61, 123.4}) {
      final var cos = Math.cos(Math.toRadians(degrees));
      final var sin = Math.sin(Math.toRadians(degrees));
      final var road =
          List.of(
              new Vertex(1000, 1000),
              new Vertex((int) Math.round(1000 + 300 * cos), (int) Math.round(1000 + 300 * sin)));
      final var fresh = new FramePrior(true);
      fresh.endLine(road, 0, 2);
      fresh.beginRing(new Vertex(1100, 1100));
      fresh.forward((int) Math.round(200 * cos), (int) Math.round(200 * sin));
      assertTrue(
          Math.min(Math.abs(fresh.x()), Math.abs(fresh.y())) <= 3,
          "along the road at " + degrees + ": " + fresh.x() + "," + fresh.y());
    }
    prior.endLine(List.of(new Vertex(0, 0)), 0, 1);
    prior.beginRing(new Vertex(0, 0));
    prior.forward(200, 100);
    assertEquals(200, prior.x());
    assertEquals(100, prior.y());
  }

  @Test
  void theHeadingRotationPutsTheEdgeItselfOnThePositiveXAxis() {
    final var prior = new FramePrior();
    for (var angle = -180; angle < 180; angle += 7) {
      final var ex = (int) Math.round(3000 * Math.cos(Math.toRadians(angle)));
      final var ey = (int) Math.round(3000 * Math.sin(Math.toRadians(angle)));
      prior.forwardHeading(ex, ey, ex, ey);
      assertTrue(Math.abs(prior.x() - 3000) <= 3, "along at " + angle + ": " + prior.x());
      assertTrue(Math.abs(prior.y()) <= 3, "across at " + angle + ": " + prior.y());
      // a step across the edge (a quarter turn left) lands on the y axis
      prior.forwardHeading(ex, ey, -ey, ex);
      assertTrue(Math.abs(prior.x()) <= 3, "left, along at " + angle + ": " + prior.x());
      assertTrue(Math.abs(prior.y() - 3000) <= 3, "left, across at " + angle + ": " + prior.y());
    }
  }
}
