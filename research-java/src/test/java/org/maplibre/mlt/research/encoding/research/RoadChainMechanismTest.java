package org.maplibre.mlt.research.encoding.research;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.GeometryEquivalence;
import org.maplibre.mlt.research.encoding.MergeEquivalence;
import org.maplibre.mlt.research.encoding.PartBoundaries;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.research.SharedVertexRef.Runs;

/**
 * Pins what road chaining claims: features that meet end to start are made adjacent without being
 * reversed, the join bit and the dropped junction vertex carry the join, a straight road costs
 * nothing across a join, and the lossy merge keeps every original line part cuttable.
 */
class RoadChainMechanismTest {

  private static final int LINESTRING = 1;
  private static final int POLYGON = 2;
  private static final int MULTILINESTRING = 4;

  private static Vertex v(int x, int y) {
    return new Vertex(x, y);
  }

  /** Vertices at x = 100 * k from {@code from} to {@code to} inclusive, y = 0. */
  private static List<Vertex> run(int from, int to) {
    final var vertices = new ArrayList<Vertex>();
    for (var k = from; k <= to; k++) {
      vertices.add(v(k * 100, 0));
    }
    return vertices;
  }

  @SafeVarargs
  private static BufferContext lines(List<Vertex>... parts) {
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

  /** One straight road of ten vertices, cut into three features that arrive scrambled. */
  private static BufferContext scrambledRoad() {
    return lines(run(6, 9), run(0, 3), run(3, 6));
  }

  @Test
  void chainsFeaturesEndToStartInRoadOrder() {
    final var chained = GeometryReorder.ROAD_CHAIN.apply(scrambledRoad());

    final var expected = new ArrayList<Vertex>();
    expected.addAll(run(0, 3));
    expected.addAll(run(3, 6));
    expected.addAll(run(6, 9));
    assertEquals(expected, chained.vertices());
    assertEquals(List.of(4, 4, 4), chained.numParts());
    assertTrue(GeometryEquivalence.holds(scrambledRoad(), chained));
  }

  @Test
  void aFeatureThatOnlyEndsAtTheExitIsNeitherJoinedNorReversed() {
    final var first = List.of(v(0, 0), v(100, 0));
    final var backwards = List.of(v(200, 0), v(100, 0));
    final var chained = GeometryReorder.ROAD_CHAIN.apply(lines(first, backwards));

    assertEquals(List.of(v(0, 0), v(100, 0), v(200, 0), v(100, 0)), chained.vertices());
    final var runs = Runs.of(chained, 4);
    assertEquals(0, RoadMerge.joinCount(RoadMerge.detectJoins(chained.vertices(), runs)));
  }

  @Test
  void atABranchTheStraighterContinuationFollows() {
    final var first = List.of(v(0, 0), v(100, 0));
    final var turns = List.of(v(100, 0), v(200, 100));
    final var straight = List.of(v(100, 0), v(200, 0));
    final var chained = GeometryReorder.ROAD_CHAIN.apply(lines(first, turns, straight));

    assertEquals(
        List.of(v(0, 0), v(100, 0), v(100, 0), v(200, 0), v(100, 0), v(200, 100)), chained.vertices());
  }

  /**
   * A MultiLineString joins only through its first part's start and its last part's end, and keeps
   * its part order; a feature that ends on an inner part is not joined.
   */
  @Test
  void aMultiLineStringJoinsOnlyAtItsOuterEnds() {
    final var d = List.of(v(200, 0), v(300, 0));
    final var c = List.of(v(400, 300), v(500, 300));
    final var a = List.of(v(0, 0), v(100, 0));
    final var p1 = List.of(v(100, 0), v(200, 0));
    final var p2 = List.of(v(300, 300), v(400, 300));
    final var vertices = new ArrayList<Vertex>();
    for (final var part : List.of(d, c, p1, p2, a)) {
      vertices.addAll(part);
    }
    final var ctx = new BufferContext(
        vertices,
        List.of(LINESTRING, LINESTRING, MULTILINESTRING, LINESTRING),
        List.of(2),
        List.of(2, 2, 2, 2, 2),
        List.of());

    final var chained = GeometryReorder.ROAD_CHAIN.apply(ctx);

    final var expected = new ArrayList<Vertex>();
    for (final var part : List.of(a, p1, p2, c, d)) {
      expected.addAll(part);
    }
    assertEquals(expected, chained.vertices());
    assertEquals(List.of(LINESTRING, MULTILINESTRING, LINESTRING, LINESTRING), chained.geometryTypes());
    assertEquals(List.of(2), chained.numGeometries());
    assertTrue(GeometryEquivalence.holds(ctx, chained));
    final var joined = RoadMerge.detectJoins(chained.vertices(), Runs.of(chained, 10));
    assertArrayEquals(new boolean[] {false, true, false, true, false}, joined);
  }

  @Test
  void polygonsFollowTheLinesAndPointsFallBack() {
    final var ring = List.of(v(0, 0), v(10, 0), v(10, 10), v(0, 0));
    final var second = List.of(v(100, 0), v(200, 0));
    final var first = List.of(v(0, 0), v(100, 0));
    final var vertices = new ArrayList<Vertex>();
    for (final var part : List.of(ring, second, first)) {
      vertices.addAll(part);
    }
    // In a polygon column run lengths are in numRings and numParts counts rings per polygon.
    final var ctx = new BufferContext(
        vertices, List.of(POLYGON, LINESTRING, LINESTRING), List.of(), List.of(1), List.of(4, 2, 2));

    final var chained = GeometryReorder.ROAD_CHAIN.apply(ctx);

    final var expected = new ArrayList<Vertex>();
    for (final var part : List.of(first, second, ring)) {
      expected.addAll(part);
    }
    assertEquals(expected, chained.vertices());
    assertEquals(List.of(LINESTRING, LINESTRING, POLYGON), chained.geometryTypes());
    assertEquals(List.of(1), chained.numParts());
    assertEquals(List.of(2, 2, 4), chained.numRings());
    assertTrue(GeometryEquivalence.holds(ctx, chained));

    final var points = BufferContext.of(List.of(v(90, 90), v(10, 10), v(50, 50)));
    assertSame(points, GeometryReorder.ROAD_CHAIN.apply(points));
  }

  // --- lossless -----------------------------------------------------------------

  /**
   * The join bit and the dropped junction carry the join: the road is ten vertices, and on a
   * straight road every continuation residual, across the joins included, is zero.
   */
  @Test
  void aStraightRoadCostsNothingAcrossItsJoins() {
    final var chained = GeometryReorder.ROAD_CHAIN.apply(scrambledRoad());
    final var runs = Runs.of(chained, 12);
    final var joined = RoadMerge.detectJoins(chained.vertices(), runs);
    assertArrayEquals(new boolean[] {false, true, true}, joined);

    final var reduced = RoadMerge.reduce(chained.vertices(), runs, joined);
    assertEquals(run(0, 9), reduced);
    assertEquals(chained.vertices(), RoadMerge.expand(reduced, runs, joined));

    final var merged = RoadMerge.merge(chained, runs, joined, reduced);
    assertNotNull(merged);
    assertEquals(10, merged.vertexCount());
    assertEquals(List.of(LINESTRING), merged.ctx().geometryTypes());
    assertEquals(List.of(10), merged.ctx().numParts());
    final var regions = ClassRegions.split(new LineContinuation(), merged.ctx());
    for (final var residual : regions[LineContinuation.LINE_INTERIOR]) {
      assertEquals(0, residual);
    }
    assertEquals(16, regions[LineContinuation.LINE_INTERIOR].length);
  }

  @Test
  void theJoinedPayloadIsChosenAndRoundTripsToTheEmittedBuffer() {
    final var ctx = scrambledRoad();
    final var joined = new RoadChainEncoding();
    final var encoded = joined.encode(ctx);

    assertEquals(1, encoded.payload()[0], "joined mode");
    assertEquals(joined.emitted(ctx).vertices(), joined.decode(encoded, ctx, ctx.vertices().size()));
    assertTrue(GeometryEquivalence.holds(ctx, joined.emitted(ctx)));

    final var ordered = new RoadChainEncoding(GeometryReorder.ROAD_CHAIN, false);
    final var orderedEncoded = ordered.encode(ctx);
    assertEquals(0, orderedEncoded.payload()[0], "no joins, plain mode");
    assertTrue(encoded.payload().length < orderedEncoded.payload().length);
    assertEquals(
        ordered.emitted(ctx).vertices(), ordered.decode(orderedEncoded, ctx, ctx.vertices().size()));
  }

  @Test
  void aBufferWithoutJoinsFallsBackToPlain() {
    final var ctx = lines(List.of(v(0, 0), v(100, 0)), List.of(v(5000, 5000), v(6000, 6000)));
    final var encoding = new RoadChainEncoding();
    final var encoded = encoding.encode(ctx);

    assertEquals(0, encoded.payload()[0]);
    assertEquals(encoding.emitted(ctx).vertices(), encoding.decode(encoded, ctx, 4));
  }

  @Test
  void multiPartAndPolygonColumnsRoundTrip() {
    final var ring = List.of(v(0, 0), v(10, 0), v(10, 10), v(0, 0));
    final var vertices = new ArrayList<Vertex>(ring);
    for (final var part : List.of(run(6, 9), run(0, 3), run(3, 6))) {
      vertices.addAll(part);
    }
    final var ctx = new BufferContext(
        vertices, List.of(POLYGON, LINESTRING, LINESTRING, LINESTRING), List.of(), List.of(1), List.of(4, 4, 4, 4));
    final var encoding = new RoadChainEncoding();
    final var encoded = encoding.encode(ctx);

    assertEquals(1, encoded.payload()[0]);
    assertEquals(encoding.emitted(ctx).vertices(), encoding.decode(encoded, ctx, vertices.size()));
  }

  // --- lossy merge -----------------------------------------------------------------

  @Test
  void theMergedRoadIsOneFeatureAndKeepsEveryPartCuttable() {
    final var ctx = scrambledRoad();
    final var encoding = new RoadMergeEncoding();
    final var merged = encoding.emitted(ctx);

    assertEquals(run(0, 9), merged.vertices());
    assertEquals(List.of(LINESTRING), merged.geometryTypes());
    assertEquals(List.of(10), merged.numParts());
    assertTrue(MergeEquivalence.holds(ctx, merged), MergeEquivalence.describe(ctx, merged));
    assertTrue(encoding.mergesFeatures());

    final var encoded = encoding.encode(ctx);
    assertEquals(merged.vertices(), encoding.decode(encoded, ctx, ctx.vertices().size()));
    assertEquals(
        PartBoundaries.of(merged, 10).length,
        1,
        "the decoder derives the merged topology, one run");
  }

  @Test
  void anUnmergeableBufferComesBackUnchanged() {
    final var ctx = lines(List.of(v(0, 0), v(100, 0)), List.of(v(5000, 5000), v(6000, 6000)));
    final var encoding = new RoadMergeEncoding();

    assertEquals(ctx.vertices(), encoding.emitted(ctx).vertices());
    assertTrue(MergeEquivalence.holds(ctx, encoding.emitted(ctx)));
    final var points = BufferContext.of(List.of(v(1, 1), v(2, 2)));
    assertTrue(MergeEquivalence.holds(points, encoding.emitted(points)));
    assertEquals(
        points.vertices(), encoding.decode(encoding.encode(points), points, 2));
  }

  @Test
  void aMergeThatKeepsTheJunctionVertexOrDropsAnEdgeIsRejected() {
    final var ctx = scrambledRoad();

    // Features concatenated with the junction vertices kept: no vertex was removed for the parts lost.
    final var concatenated = lines(
        List.of(v(0, 0), v(100, 0), v(200, 0), v(300, 0), v(300, 0), v(400, 0), v(500, 0), v(600, 0), v(600, 0),
            v(700, 0), v(800, 0), v(900, 0)));
    assertFalse(MergeEquivalence.holds(ctx, concatenated));

    // Ten vertices again, but the 600 to 700 edge is gone.
    final var droppedEdge = new ArrayList<Vertex>(run(0, 6));
    droppedEdge.addAll(List.of(v(800, 0), v(900, 0)));
    assertFalse(MergeEquivalence.holds(ctx, lines(droppedEdge)));
  }

  /**
   * P = a-b-c and Q = b-d may be fused as a-b-d plus b-c by an edge-preserving rewrite, but P is
   * then no slice of one merged part: it cannot be cut back.
   */
  @Test
  void crossLinkingAtABranchIsRejected() {
    final var a = v(0, 0);
    final var b = v(100, 0);
    final var c = v(200, 100);
    final var d = v(200, -100);
    final var original = lines(List.of(a, b, c), List.of(b, d));

    assertTrue(MergeEquivalence.holds(original, lines(List.of(a, b, c), List.of(b, d))));
    assertFalse(MergeEquivalence.holds(original, lines(List.of(a, b, d), List.of(b, c))));
  }
}
