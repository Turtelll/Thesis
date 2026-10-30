package org.maplibre.mlt.research.pipeline.mechanism;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.GeometryEquivalence;
import org.maplibre.mlt.research.buffer.PartBoundaries;
import org.maplibre.mlt.research.buffer.Encoding.BufferContext;

/**
 * Pins the properties that make a measured reordering win meaningful rather than an artifact: it
 * really reorders, it really preserves every geometry, and it leaves alone the buffers it cannot
 * reason about.
 *
 * <p>A round-trip test cannot cover this transform at all — reordering deliberately changes the
 * order, so {@code BenchmarkCli}'s exact-equality check does not apply to it. The safety property
 * is {@link GeometryEquivalence} instead, and these tests are what stop that weaker contract from
 * silently permitting a dropped or deformed geometry.
 */
class GeometryReorderMechanismTest {

  /**
   * Three 2-vertex line parts laid out so natural order is deliberately bad: part 0 ends at (12,11)
   * but part 1 starts far away at (500,400), while part 2 starts at (14,12) — right next to part 0.
   * A greedy chain should visit 0, 2, 1.
   */
  private static BufferContext lineColumn() {
    return new BufferContext(
        List.of(
            new Vertex(10, 10),
            new Vertex(12, 11),
            new Vertex(500, 400),
            new Vertex(503, 402),
            new Vertex(14, 12),
            new Vertex(16, 13)),
        List.of(1, 1, 1),
        List.of(),
        List.of(2, 2, 2),
        List.of());
  }

  @Test
  void chainsFeaturesByProximityRatherThanKeepingNaturalOrder() {
    final var ctx = lineColumn();
    final var reordered = GeometryReorder.FEATURE.apply(ctx);

    // If this were equal the pre-pass would be a no-op dressed up as an experiment.
    assertNotEquals(ctx.vertices(), reordered.vertices(), "expected a different emission order");

    // The near part (14,12) must follow part 0, displacing the far part (500,400) to the end.
    assertEquals(
        List.of(
            new Vertex(10, 10),
            new Vertex(12, 11),
            new Vertex(14, 12),
            new Vertex(16, 13),
            new Vertex(500, 400),
            new Vertex(503, 402)),
        reordered.vertices());
  }

  /** The permutation a caller applies to the property columns is the one {@code apply} applies. */
  @Test
  void featureOrderIsThePermutationApplyUses() {
    final var ctx = lineColumn();
    assertArrayEquals(new int[] {0, 2, 1}, GeometryReorder.FEATURE.featureOrder(ctx));
    assertEquals(null, GeometryReorder.FEATURE.featureOrder(new BufferContext(
        List.of(new Vertex(1, 1)), List.of(0), List.of(), List.of(), List.of())));
  }

  @Test
  void preservesEveryGeometry() {
    final var ctx = lineColumn();
    assertTrue(GeometryEquivalence.holds(ctx, GeometryReorder.FEATURE.apply(ctx)));
    assertTrue(GeometryEquivalence.holds(ctx, GeometryReorder.FEATURE_REV.apply(ctx)));
  }

  /**
   * The rebuilt topology streams must re-derive the same run lengths, only permuted. Getting this
   * wrong is silent — the buffer still decodes, into plausible but wrong geometry — which is why
   * {@code PartBoundaries} exists as its own class and why this is pinned rather than assumed.
   */
  @Test
  void rebuildsTopologyStreamsSoRunsStillDerive() {
    final var ctx = lineColumn();
    final var reordered = GeometryReorder.FEATURE.apply(ctx);

    assertArrayEquals(
        PartBoundaries.of(ctx, ctx.vertices().size()),
        PartBoundaries.of(reordered, reordered.vertices().size()),
        "equal-length parts must still tile the buffer identically");
    assertEquals(ctx.numParts(), reordered.numParts());
    assertEquals(ctx.geometryTypes(), reordered.geometryTypes());
  }

  /** A column whose runs are not derivable must be returned untouched, not guessed at. */
  @Test
  void leavesBuffersWithoutDerivableRunsUnchanged() {
    // Point-only: points contribute vertices but no run entry, so PartBoundaries falls back.
    final var points =
        BufferContext.of(List.of(new Vertex(90, 90), new Vertex(10, 10), new Vertex(50, 50)));
    assertSame(points, GeometryReorder.FEATURE_REV.apply(points));

    // Topology that does not tile the buffer: the streams are not what we assumed they are.
    final var mismatched =
        new BufferContext(
            List.of(new Vertex(0, 0), new Vertex(1, 1), new Vertex(2, 2)),
            List.of(1, 1),
            List.of(),
            List.of(2, 7),
            List.of());
    assertSame(mismatched, GeometryReorder.FEATURE_REV.apply(mismatched));
  }

  // --- reversal -----------------------------------------------------------

  /**
   * Two parts where the second is cheaper entered backwards: it ends at (13,10), next to part 0's
   * end at (12,11), but starts far away at (600,600).
   */
  private static BufferContext reversibleColumn() {
    return new BufferContext(
        List.of(
            new Vertex(10, 10),
            new Vertex(12, 11),
            new Vertex(600, 600),
            new Vertex(13, 10)),
        List.of(1, 1),
        List.of(),
        List.of(2, 2),
        List.of());
  }

  @Test
  void reversesAPartOnlyWhenItsFarEndpointIsNearer() {
    final var ctx = reversibleColumn();

    // Without reversal the far start is paid in full.
    assertEquals(ctx.vertices(), GeometryReorder.FEATURE.apply(ctx).vertices());

    // With reversal the second part is emitted backwards: (13,10) then (600,600).
    assertEquals(
        List.of(
            new Vertex(10, 10),
            new Vertex(12, 11),
            new Vertex(13, 10),
            new Vertex(600, 600)),
        GeometryReorder.FEATURE_REV.apply(ctx).vertices());
  }

  /**
   * Reversal is the only rewrite of a part's interior that is legitimate, so the equivalence check
   * must accept it — and must still reject an arbitrary permutation of the same vertices, or it
   * would be no check at all.
   */
  @Test
  void equivalenceAcceptsReversalButRejectsOtherPermutations() {
    final var ctx = reversibleColumn();
    assertTrue(GeometryEquivalence.holds(ctx, GeometryReorder.FEATURE_REV.apply(ctx)));

    final var scrambled =
        new BufferContext(
            List.of(
                new Vertex(10, 10),
                new Vertex(600, 600),
                new Vertex(12, 11),
                new Vertex(13, 10)),
            ctx.geometryTypes(),
            ctx.numGeometries(),
            ctx.numParts(),
            ctx.numRings());
    assertFalse(
        GeometryEquivalence.holds(ctx, scrambled),
        "vertices moved between parts must not pass as equivalent");
  }

  @Test
  void equivalenceRejectsADroppedOrDuplicatedGeometry() {
    final var ctx = lineColumn();
    // Part 0 emitted twice in place of the far part: same vertex count, same part count.
    final var duplicated =
        new BufferContext(
            List.of(
                new Vertex(10, 10),
                new Vertex(12, 11),
                new Vertex(14, 12),
                new Vertex(16, 13),
                new Vertex(10, 10),
                new Vertex(12, 11)),
            ctx.geometryTypes(),
            ctx.numGeometries(),
            ctx.numParts(),
            ctx.numRings());
    assertFalse(GeometryEquivalence.holds(ctx, duplicated));
  }

  // --- multi-part ---------------------------------------------------------

  /**
   * A MULTILINESTRING of two parts, plus a single LineString that sits between them spatially. The
   * multi-part feature's parts must stay contiguous and in order even though splitting them would
   * chain more tightly — splitting changes what the feature <em>is</em>.
   */
  @Test
  void keepsMultiPartFeaturesContiguousAndInOrder() {
    final var ctx =
        new BufferContext(
            List.of(
                // feature 0: a lone line near the origin
                new Vertex(0, 0),
                new Vertex(1, 1),
                // feature 1: MULTILINESTRING, parts far apart from each other
                new Vertex(900, 900),
                new Vertex(901, 901),
                new Vertex(2, 2),
                new Vertex(3, 3)),
            List.of(1, 4),
            List.of(2),
            List.of(2, 2, 2),
            List.of());

    final var reordered = GeometryReorder.FEATURE.apply(ctx);
    assertTrue(GeometryEquivalence.holds(ctx, reordered));

    // The multi-part feature's two parts stay adjacent and in their original relative order,
    // so (900,900) is still immediately followed by its own second part (2,2).
    final var vertices = reordered.vertices();
    final var head = vertices.indexOf(new Vertex(900, 900));
    assertEquals(new Vertex(901, 901), vertices.get(head + 1));
    assertEquals(new Vertex(2, 2), vertices.get(head + 2));
    assertEquals(new Vertex(3, 3), vertices.get(head + 3));

    assertEquals(List.of(2), reordered.numGeometries(), "the multi count travels with its feature");
    assertEquals(ctx.geometryTypes().size(), reordered.geometryTypes().size());
  }

  // --- canonical ring start -----------------------------------------------

  /**
   * Two translated copies of one L-shaped ring, the second starting two corners later. Canonical
   * start must make both rings' internal deltas identical, which is the whole point for an LZ77
   * stage — and must stay a rotation, never a reversal, so winding is kept.
   */
  @Test
  void canonicalStartMakesTranslatedCopiesEmitTheSameDeltas() {
    final var ctx =
        new BufferContext(
            List.of(
                new Vertex(10, 0), new Vertex(10, 5), new Vertex(5, 5),
                new Vertex(5, 10), new Vertex(0, 10), new Vertex(0, 0),
                // the same ring shifted by (100, 100), starting at its third vertex
                new Vertex(105, 105), new Vertex(105, 110), new Vertex(100, 110),
                new Vertex(100, 100), new Vertex(110, 100), new Vertex(110, 105)),
            List.of(2, 2),
            List.of(),
            List.of(1, 1),
            List.of(6, 6));

    final var canonical = GeometryReorder.RING_CANONICAL.apply(ctx);
    assertTrue(GeometryEquivalence.holds(ctx, canonical));
    final var vertices = canonical.vertices();
    assertEquals(new Vertex(0, 0), vertices.get(0));
    assertEquals(new Vertex(100, 100), vertices.get(6));
    for (var i = 1; i < 6; i++) {
      assertEquals(vertices.get(i).x() - vertices.get(i - 1).x(), vertices.get(6 + i).x() - vertices.get(5 + i).x());
      assertEquals(vertices.get(i).y() - vertices.get(i - 1).y(), vertices.get(6 + i).y() - vertices.get(5 + i).y());
    }
    // A rotation keeps the successor of the start vertex: (0,0) was followed by (10,0) before.
    assertEquals(new Vertex(10, 0), vertices.get(1));
  }

  /**
   * Rotation runs first, so the chain sees the emitted ring starts: the far ring (starting at its
   * smallest vertex (100,100)) is placed after the near one, whose canonical start is (0,0), and
   * every ring in the result starts at its smallest vertex.
   */
  @Test
  void canonicalThenFeatureChainsOverTheRotatedStarts() {
    final var ctx =
        new BufferContext(
            List.of(
                // far ring first, starting at its third vertex
                new Vertex(105, 105), new Vertex(105, 110), new Vertex(100, 110),
                new Vertex(100, 100), new Vertex(110, 100), new Vertex(110, 105),
                // near ring
                new Vertex(10, 0), new Vertex(10, 5), new Vertex(5, 5),
                new Vertex(5, 10), new Vertex(0, 10), new Vertex(0, 0)),
            List.of(2, 2),
            List.of(),
            List.of(1, 1),
            List.of(6, 6));

    final var combined = GeometryReorder.RING_CANONICAL_THEN_FEATURE.apply(ctx);
    assertTrue(GeometryEquivalence.holds(ctx, combined));
    final var vertices = combined.vertices();
    assertEquals(new Vertex(0, 0), vertices.get(0), "nearest start to the origin goes first");
    assertEquals(new Vertex(100, 100), vertices.get(6));
    assertEquals("_RING_CANONICAL_REORDER", GeometryReorder.RING_CANONICAL_THEN_FEATURE.nameSuffix());
  }

  // --- part chaining inside a MultiLineString ------------------------------

  private static final int LINESTRING = 1;
  private static final int MULTILINESTRING = 4;

  /**
   * A LineString, then a MultiLineString stored as A, C, B although A ends where B starts and B
   * ends where C starts, then a MultiLineString whose part D starts where C ends. C has three
   * vertices, so the run lengths show whether the part-length stream was permuted with the parts.
   */
  @Test
  void chainsMultiLineStringPartsWithinTheirFeature() {
    final var ctx =
        new BufferContext(
            List.of(
                new Vertex(100, 100), new Vertex(101, 101),
                // feature 1: A, C, B
                new Vertex(0, 0), new Vertex(10, 0),
                new Vertex(20, 0), new Vertex(25, 0), new Vertex(30, 0),
                new Vertex(10, 0), new Vertex(20, 0),
                // feature 2: E, D
                new Vertex(500, 500), new Vertex(501, 501),
                new Vertex(30, 0), new Vertex(40, 0)),
            List.of(LINESTRING, MULTILINESTRING, MULTILINESTRING),
            List.of(3, 2),
            List.of(2, 2, 3, 2, 2, 2),
            List.of());

    final var chained = GeometryReorder.PART_CHAIN.apply(ctx);
    assertTrue(GeometryEquivalence.holds(ctx, chained));
    // D joins C's end but belongs to feature 2: it only moves to the front of its own feature.
    assertEquals(
        List.of(
            new Vertex(100, 100), new Vertex(101, 101),
            new Vertex(0, 0), new Vertex(10, 0),
            new Vertex(10, 0), new Vertex(20, 0),
            new Vertex(20, 0), new Vertex(25, 0), new Vertex(30, 0),
            new Vertex(30, 0), new Vertex(40, 0),
            new Vertex(500, 500), new Vertex(501, 501)),
        chained.vertices());
    assertEquals(List.of(2, 2, 2, 3, 2, 2), chained.numParts());
    assertEquals(ctx.geometryTypes(), chained.geometryTypes());
    assertEquals(ctx.numGeometries(), chained.numGeometries());
    assertEquals("_PART_CHAIN", GeometryReorder.PART_CHAIN.nameSuffix());
  }

  /** A part joinable only backwards: reversed under {@code PART_CHAIN_REV}, kept under {@code PART_CHAIN}. */
  @Test
  void reversesAPartToJoinItOnlyWhenAllowed() {
    final var ctx =
        new BufferContext(
            List.of(new Vertex(0, 0), new Vertex(10, 0), new Vertex(20, 0), new Vertex(10, 0)),
            List.of(MULTILINESTRING),
            List.of(2),
            List.of(2, 2),
            List.of());

    assertEquals(ctx.vertices(), GeometryReorder.PART_CHAIN.apply(ctx).vertices());

    final var reversed = GeometryReorder.PART_CHAIN_REV.apply(ctx);
    assertTrue(GeometryEquivalence.holds(ctx, reversed));
    assertEquals(
        List.of(new Vertex(0, 0), new Vertex(10, 0), new Vertex(10, 0), new Vertex(20, 0)),
        reversed.vertices());
  }

  /** At a junction the chain takes the part that continues straight, not the first one stored. */
  @Test
  void prefersTheStraightContinuationAtAJunction() {
    final var ctx =
        new BufferContext(
            List.of(
                new Vertex(0, 0), new Vertex(10, 0),
                // a turn off the junction
                new Vertex(10, 0), new Vertex(10, 10),
                // straight on
                new Vertex(10, 0), new Vertex(20, 0)),
            List.of(MULTILINESTRING),
            List.of(3),
            List.of(2, 2, 2),
            List.of());

    assertEquals(
        List.of(
            new Vertex(0, 0), new Vertex(10, 0),
            new Vertex(10, 0), new Vertex(20, 0),
            new Vertex(10, 0), new Vertex(10, 10)),
        GeometryReorder.PART_CHAIN.apply(ctx).vertices());
  }

  /** Polygon rings in the column are emitted as they were; only the line parts are chained. */
  @Test
  void leavesPolygonsInAMixedColumnUnchanged() {
    final var ctx =
        new BufferContext(
            List.of(
                new Vertex(0, 0), new Vertex(5, 0), new Vertex(5, 5), new Vertex(0, 5),
                new Vertex(20, 0), new Vertex(30, 0),
                new Vertex(10, 0), new Vertex(20, 0)),
            List.of(2, MULTILINESTRING),
            List.of(2),
            List.of(1),
            List.of(4, 2, 2));

    final var chained = GeometryReorder.PART_CHAIN.apply(ctx);
    assertTrue(GeometryEquivalence.holds(ctx, chained));
    assertEquals(ctx.vertices().subList(0, 4), chained.vertices().subList(0, 4));
    assertEquals(
        List.of(new Vertex(10, 0), new Vertex(20, 0), new Vertex(20, 0), new Vertex(30, 0)),
        chained.vertices().subList(4, 8));
    assertEquals(ctx.numParts(), chained.numParts());
    assertEquals(List.of(4, 2, 2), chained.numRings());
  }
}
