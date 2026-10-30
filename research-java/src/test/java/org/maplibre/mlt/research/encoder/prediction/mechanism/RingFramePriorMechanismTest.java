package org.maplibre.mlt.research.encoder.prediction.mechanism;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.buffer.Encoding.BufferContext;
import org.maplibre.mlt.research.encoder.prediction.Prediction;
import org.maplibre.mlt.research.pipeline.PredictionTransform;

/**
 * Pins {@code QUAD_CORNER_SHARED_FRAME}: ring edges are coded in the frame of the nearest decoded
 * ring, so a street of buildings rotated off the tile axes costs fewer bytes than the same street
 * coded by plain delta, and the rotation is an exact bijection so the decode is lossless.
 */
class RingFramePriorMechanismTest {

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
      final var prior = new RingFramePrior();
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

    final var lone = new RingFramePrior();
    lone.endLine(List.of(new Vertex(0, 0)), 0, 1);
    lone.beginRing(new Vertex(0, 0));
    lone.forward(200, 100);
    assertEquals(200, lone.x());
    assertEquals(100, lone.y());
  }

  @Test
  void theRotationIsExactlyInvertibleInEveryFrame() {
    final var prior = new RingFramePrior();
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
    final var prior = new RingFramePrior();
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

  @Test
  void theHeadingRotationPutsTheEdgeItselfOnThePositiveXAxis() {
    final var prior = new RingFramePrior();
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
