package org.maplibre.mlt.research.encoding.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.Layout;
import org.maplibre.mlt.research.encoding.PredictedTransform;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;

/**
 * Pins how {@code LINE_CONTINUATION_SHARED_DICT} uses junctions: a part starting or ending on an
 * earlier part's position references it by dictionary index instead of a residual, and a part
 * whose end alone is shared is reversed so its start carries the reference.
 */
class LineSharedVertexRefMechanismTest {

  private static final int LINESTRING = 1;

  /** A road, then a road branching off its middle vertex: a T-junction. */
  private static BufferContext tJunction() {
    return lines(
        List.of(new Vertex(0, 0), new Vertex(1000, 0), new Vertex(2000, 0)),
        List.of(new Vertex(1000, 0), new Vertex(1000, 1000), new Vertex(1000, 2000)));
  }

  /** Two roads, then a road connecting a vertex of each: entry and exit both shared. */
  private static BufferContext connector() {
    return lines(
        List.of(new Vertex(0, 0), new Vertex(1000, 0)),
        List.of(new Vertex(0, 1000), new Vertex(1000, 1000)),
        List.of(new Vertex(0, 0), new Vertex(0, 500), new Vertex(0, 1000)));
  }

  /** A road, then a road that only ends on it. */
  private static BufferContext endsOnEarlierRoad() {
    return lines(
        List.of(new Vertex(0, 0), new Vertex(1000, 0)),
        List.of(new Vertex(5000, 5000), new Vertex(3000, 3000), new Vertex(1000, 0)));
  }

  /** Two roads that touch nowhere. */
  private static BufferContext detached() {
    return lines(
        List.of(new Vertex(0, 0), new Vertex(1000, 0)),
        List.of(new Vertex(5000, 5000), new Vertex(6000, 6000)));
  }

  /**
   * Two rails and nine rungs between them, each rung starting and ending on a rail vertex — enough
   * sharing for the shared mode to beat the plain fallback.
   */
  private static BufferContext ladder() {
    final var rails = 9;
    final var bottom = new ArrayList<Vertex>();
    final var top = new ArrayList<Vertex>();
    for (var i = 0; i < rails; i++) {
      bottom.add(new Vertex(i * 1000, 0));
      top.add(new Vertex(i * 1000, 5000));
    }
    final var parts = new ArrayList<List<Vertex>>(List.of(bottom, top));
    for (var i = 0; i < rails; i++) {
      parts.add(List.of(new Vertex(i * 1000, 0), new Vertex(i * 1000, 5000)));
    }
    return lines(parts);
  }

  @SafeVarargs
  private static BufferContext lines(List<Vertex>... parts) {
    return lines(List.of(parts));
  }

  private static BufferContext lines(List<List<Vertex>> parts) {
    final var vertices = new ArrayList<Vertex>();
    final var types = new ArrayList<Integer>();
    final var lengths = new ArrayList<Integer>();
    for (final var part : parts) {
      vertices.addAll(part);
      types.add(LINESTRING);
      lengths.add(part.size());
    }
    return new BufferContext(vertices, types, List.of(), lengths, List.of());
  }

  /**
   * Side bits for the T-junction: B's start is an entry (flag + 2-bit index into A's 3 positions),
   * its last vertex is offered an exit flag and misses. The entry drops B's start residuals.
   */
  @Test
  void junctionStartPaysOnlyFlagAndIndexBits() {
    final var ctx = tJunction();
    final var shared = new LineSharedVertexRef();
    final var allIn = shared.allInBits(ctx);
    final var values = new PredictedTransform(new LineContinuation(), Layout.INTERLEAVED).apply(ctx);
    final var plain = residualBits(values);
    final var startResiduals = residualBits(java.util.Arrays.copyOfRange(values, 6, 8));
    assertEquals(12, allIn[1], "two values per vertex, referenced ones included");
    assertEquals(plain - startResiduals + 1 + 2 + 1, allIn[0]);
  }

  @Test
  void connectorReferencesBothEndpoints() {
    final var ctx = connector();
    final var shared = new LineSharedVertexRef();
    final var values = new PredictedTransform(new LineContinuation(), Layout.INTERLEAVED).apply(ctx);
    final var referenced =
        residualBits(java.util.Arrays.copyOfRange(values, 8, 10))
            + residualBits(java.util.Arrays.copyOfRange(values, 12, 14));
    // The second road misses at entry and exit (2 flags). The connector: entry flag + 2-bit index,
    // exit flag + 2-bit index.
    assertEquals(residualBits(values) - referenced + 2 + 1 + 2 + 1 + 2, shared.allInBits(ctx)[0]);
  }

  /**
   * Marked table, connector: the first roads' starts recur as the connector's ends, so each is
   * remembered (1 bit) and the connector references both with 1-bit indices into 2 entries. The
   * other ends get a remember bit of 0. Side bits: road A 1 + (1 + 1), road B (1 + 1) + (1 + 1),
   * connector (1 + 1) + (1 + 1).
   */
  @Test
  void markedConnectorRemembersOnlyRecurringEnds() {
    final var ctx = connector();
    final var shared =
        new LineSharedVertexRef(LineSharedVertexRef.Table.MARKED);
    final var values = new PredictedTransform(new LineContinuation(), Layout.INTERLEAVED).apply(ctx);
    final var referenced =
        residualBits(java.util.Arrays.copyOfRange(values, 8, 10))
            + residualBits(java.util.Arrays.copyOfRange(values, 12, 14));
    assertEquals(residualBits(values) - referenced + 11, shared.allInBits(ctx)[0]);
  }

  /**
   * Marked table, T-junction: the shared position is first seen inside road A, where nothing can be
   * marked, so road B's start cannot reference it. Only the four remember bits of 0 are paid.
   */
  @Test
  void markedTableMissesAJunctionOntoAnInterior() {
    final var ctx = tJunction();
    final var shared =
        new LineSharedVertexRef(LineSharedVertexRef.Table.MARKED);
    final var values = new PredictedTransform(new LineContinuation(), Layout.INTERLEAVED).apply(ctx);
    assertEquals(residualBits(values) + 4, shared.allInBits(ctx)[0]);
  }

  @Test
  void partEndingOnAnEarlierRoadIsReversed() {
    final var encoding = new LineSharedVertexRef();
    final var ctx = withMoreSharing(endsOnEarlierRoad());
    assertEquals(1, encoding.encode(ctx).payload()[0], "mode byte: SHARED");
    assertEquals(
        List.of(new Vertex(1000, 0), new Vertex(3000, 3000), new Vertex(5000, 5000)),
        encoding.emitted(ctx).vertices().subList(2, 5));
  }

  @Test
  void ladderChoosesTheSharedMode() {
    final var payload =
        new LineSharedVertexRef().encode(ladder()).payload();
    assertEquals(1, payload[0], "mode byte: SHARED");
  }

  @Test
  void detachedRoadsFallBackToPlainAndAreNotReversed() {
    final var ctx = detached();
    final var encoding = new LineSharedVertexRef();
    assertEquals(0, encoding.encode(ctx).payload()[0], "mode byte: PLAIN");
    assertSame(ctx, encoding.emitted(ctx));
  }

  @Test
  void reconstructsFromTopologyAlone() {
    final var fixtures =
        List.of(
            tJunction(),
            connector(),
            endsOnEarlierRoad(),
            withMoreSharing(endsOnEarlierRoad()),
            detached(),
            ladder(),
            closedLoopAndSinglePoint());
    for (final var ctx : fixtures) {
      for (final var table : LineSharedVertexRef.Table.values()) {
        final var encoding = new LineSharedVertexRef(table);
        final var decoded =
            encoding.decode(encoding.encode(ctx), topologyOnly(ctx), ctx.vertices().size());
        assertEquals(encoding.emitted(ctx).vertices(), decoded, encoding.name());
      }
    }
  }

  /**
   * A closed road (its end repeats its own start), a one-vertex part on that start, and the ladder.
   */
  private static BufferContext closedLoopAndSinglePoint() {
    final var loop =
        lines(
            List.of(new Vertex(0, 0), new Vertex(1000, 0), new Vertex(1000, 1000), new Vertex(0, 0)),
            List.of(new Vertex(0, 0)));
    return withMoreSharing(loop);
  }

  /**
   * Appends the ladder after {@code ctx}, so the buffer shares enough for the shared mode to win
   * and {@code emitted} reflects the shared stream's reversals.
   */
  private static BufferContext withMoreSharing(BufferContext ctx) {
    final var more = ladder();
    final var vertices = new ArrayList<>(ctx.vertices());
    vertices.addAll(more.vertices());
    final var types = new ArrayList<>(ctx.geometryTypes());
    types.addAll(more.geometryTypes());
    final var lengths = new ArrayList<>(ctx.numParts());
    lengths.addAll(more.numParts());
    return new BufferContext(vertices, types, List.of(), lengths, List.of());
  }

  private static long residualBits(int[] values) {
    var sum = 0L;
    for (final var value : values) {
      sum += 32 - Integer.numberOfLeadingZeros(value);
    }
    return sum;
  }

  private static BufferContext topologyOnly(BufferContext ctx) {
    return new BufferContext(
        List.of(), ctx.geometryTypes(), ctx.numGeometries(), ctx.numParts(), ctx.numRings());
  }
}
