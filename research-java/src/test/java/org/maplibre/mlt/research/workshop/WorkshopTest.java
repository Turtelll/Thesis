package org.maplibre.mlt.research.workshop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.IntCodec;
import org.maplibre.mlt.research.encoding.Layout;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.VertexTransform;
import org.maplibre.mlt.research.encoding.research.failed.ContextDelta;
import org.maplibre.mlt.research.encoding.research.failed.MidpointReflect;
import org.maplibre.mlt.research.encoding.research.failed.TurnScalar;

/**
 * Pins the workshop's plumbing: the {@code DELTA} rule is the baseline transform, the
 * midpoint-reflect and context-delta ports predict what the originals do (only without their
 * regions), the turn-scalar pairs leave out exactly the along scalars, and a rule reading ahead of
 * {@code i} does not round-trip.
 */
class WorkshopTest {

  private static final int LINESTRING = 1;
  private static final int POLYGON = 2;

  /** Four rings of 8, 9, 5 and 12 vertices with random coordinates. */
  private static BufferContext rings() {
    var random = new Random(42);
    var lengths = List.of(8, 9, 5, 12);
    var vertices = new ArrayList<Vertex>();
    for (var length : lengths) {
      for (var i = 0; i < length; i++) {
        vertices.add(new Vertex(random.nextInt(4096), random.nextInt(4096)));
      }
    }
    return new BufferContext(
        vertices, List.of(POLYGON, POLYGON, POLYGON, POLYGON), List.of(), List.of(1, 1, 1, 1), lengths);
  }

  /** Two gently curving, jittered 40-vertex lines, so smooth runs and mispredicts both occur. */
  private static BufferContext lines() {
    var random = new Random(7);
    var vertices = new ArrayList<Vertex>();
    for (var line = 0; line < 2; line++) {
      for (var i = 0; i < 40; i++) {
        var angle = 0.05 * i + line;
        vertices.add(new Vertex(
            (int) (1000 + 30 * i * Math.cos(angle)) + random.nextInt(3),
            (int) (800 + 30 * i * Math.sin(angle)) + random.nextInt(3)));
      }
    }
    return new BufferContext(vertices, List.of(LINESTRING, LINESTRING), List.of(), List.of(40, 40), List.of());
  }

  @Test
  void deltaRuleMatchesBaselineTransform() {
    var ctx = rings();
    var delta = Workshop.known().get(0);

    assertEquals("DELTA", delta.name());
    assertArrayEquals(new VertexTransform.ZigZagDelta().apply(ctx), delta.residuals().apply(ctx).pairs());
  }

  @Test
  void midpointReflectPortHasTheOriginalsResiduals() {
    var ctx = rings();
    for (var pairing : MidpointReflect.Pairing.values()) {
      var original = new MidpointReflect(pairing).apply(ctx);
      var port = Workshop.midpointReflect(pairing == MidpointReflect.Pairing.SYMMETRIC).transform().apply(ctx);

      assertArrayEquals(sortedPairs(original), sortedPairs(port), pairing.name());
    }
  }

  @Test
  void contextDeltaPortHasTheOriginalsResiduals() {
    var ctx = lines();
    var n = ctx.vertices().size();
    var payload = new ContextDelta("CONTEXT_DELTA_RAW", IntCodec.RAW, 4, 2, 32).encode(ctx).payload();
    // RAW: the residual stream is the payload's last 2n little-endian ints, behind the count header.
    var original = new int[2 * n];
    ByteBuffer.wrap(payload, payload.length - 8 * n, 8 * n).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(original);
    var port = Workshop.contextDelta(4, 2, 32).transform();
    var residuals = port.apply(ctx);

    assertArrayEquals(sortedPairs(original), sortedPairs(residuals));
    assertFalse(
        Arrays.equals(new VertexTransform.ZigZagDelta().apply(ctx), residuals),
        "the fixture must take contexts above 0, or this compares two plain deltas");
    assertEquals(ctx.vertices(), port.invert(residuals, topologyOnly(ctx)));
  }

  @Test
  void turnScalarPairsDropOnlyTheAlongScalars() {
    var ctx = rings();
    var turnScalar = new TurnScalar(Layout.INTERLEAVED);
    var stream = turnScalar.apply(ctx);
    var pairs = turnScalar.residualPairs(ctx);
    var along = turnScalar.alongCount(ctx);

    assertTrue(along > 0, "the 8- and 12-vertex rings carry turns");
    assertEquals(stream.length - along, pairs.length);
    assertEquals(2 * ctx.vertices().size(), pairs.length);
  }

  @Test
  void lookaheadRuleDoesNotRoundTrip() {
    var ctx = rings();
    var cheat = Predictor.of("NEXT", (t, i, xs, ys, out) -> {
      out[0] = xs[i];
      out[1] = ys[i];
    }).transform();

    assertNotEquals(ctx.vertices(), cheat.invert(cheat.apply(ctx), ctx));
  }

  /** Each vertex's {@code (x, y)} residual as one long, sorted: equal iff the same pairs, permuted. */
  private static long[] sortedPairs(int[] values) {
    var pairs = new long[values.length / 2];
    for (var i = 0; i < pairs.length; i++) {
      pairs[i] = (long) values[2 * i] << 32 | (values[2 * i + 1] & 0xFFFFFFFFL);
    }
    Arrays.sort(pairs);
    return pairs;
  }

  private static BufferContext topologyOnly(BufferContext ctx) {
    return new BufferContext(
        List.of(), ctx.geometryTypes(), ctx.numGeometries(), ctx.numParts(), ctx.numRings());
  }
}
