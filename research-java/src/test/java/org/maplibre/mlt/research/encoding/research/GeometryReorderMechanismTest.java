package org.maplibre.mlt.research.encoding.research;

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
import org.maplibre.mlt.research.encoding.PartBoundaries;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;

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
}
