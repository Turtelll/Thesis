package org.maplibre.mlt.research.pipeline;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.PartBoundaries;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;

/**
 * Pins what makes a measured S1 result meaningful: the curve keys really are space-filling, the
 * order really changes, every geometry survives, rings keep their winding, lines are never
 * reversed, multi-part features stay whole, and what cannot be located is left alone.
 */
class ReorderMechanismTest {

  @Test
  void hilbertKeyVisitsEveryCellOnceAndMovesToANeighbourEachStep() {
    var order = 3;
    var side = 1 << order;
    var cellAt = new int[side * side][];
    for (var x = 0; x < side; x++) {
      for (var y = 0; y < side; y++) {
        var key = (int) Reorder.hilbertKey(order, x, y);
        assertTrue(key >= 0 && key < side * side);
        assertEquals(null, cellAt[key], "each key is used once");
        cellAt[key] = new int[] {x, y};
      }
    }
    for (var key = 1; key < cellAt.length; key++) {
      var step =
          Math.abs(cellAt[key][0] - cellAt[key - 1][0]) + Math.abs(cellAt[key][1] - cellAt[key - 1][1]);
      assertEquals(1, step, "consecutive Hilbert cells are adjacent");
    }
  }

  @Test
  void mortonKeyInterleavesBitsWithXInTheEvenPositions() {
    assertEquals(0, Reorder.mortonKey(0, 0));
    assertEquals(1, Reorder.mortonKey(1, 0));
    assertEquals(2, Reorder.mortonKey(0, 1));
    assertEquals(3, Reorder.mortonKey(1, 1));
    assertEquals(0b0101, Reorder.mortonKey(3, 0), "x=3 spreads to bits 0 and 2");
    assertEquals(0b1010, Reorder.mortonKey(0, 3), "y=3 spreads to bits 1 and 3");
  }

  @Test
  void orderIsStableAndHandlesNegativeCoordinates() {
    var order =
        Reorder.HILBERT_START.order(
            new int[] {-500, 4000, -500, 0}, new int[] {-500, 4000, -500, 0}, new int[] {1, 1, 1, 1});
    assertEquals(4, order.length);
    // The two features at (-500,-500) are tied: the earlier one stays first.
    var first = indexOf(order, 0);
    var second = indexOf(order, 2);
    assertTrue(first < second);
  }

  /** Three single-ring squares laid out far apart, near, far — a spatial sort must regroup them. */
  private static BufferContext squares() {
    var vertices = new ArrayList<Vertex>();
    vertices.addAll(square(0, 0, 10));
    vertices.addAll(square(3000, 3000, 10));
    vertices.addAll(square(20, 0, 10));
    return new BufferContext(
        vertices, List.of(2, 2, 2), List.of(), List.of(1, 1, 1), List.of(4, 4, 4));
  }

  private static List<Vertex> square(int x, int y, int size) {
    return List.of(
        new Vertex(x, y), new Vertex(x + size, y), new Vertex(x + size, y + size), new Vertex(x, y + size));
  }

  @Test
  void spatialSortRegroupsNearbyFeaturesAndKeepsEveryGeometry() {
    var ctx = squares();
    for (var mode :
        List.of(
            Reorder.HILBERT_START,
            Reorder.HILBERT_CENTROID,
            Reorder.MORTON_START,
            Reorder.MORTON_CENTROID,
            Reorder.HILBERT_START_ROT,
            Reorder.HILBERT_START_VC)) {
      var reordered = mode.apply(ctx);
      assertNotEquals(ctx.vertices(), reordered.vertices(), mode + " should change the order");
      assertTrue(Verification.holds(ctx, reordered), mode + " must preserve every geometry");
      // The far square must not sit between the two near ones.
      assertEquals(new Vertex(3000, 3000), reordered.vertices().get(8), mode.name());
    }
  }

  @Test
  void rebuiltTopologyStillDerivesTheSameRuns() {
    var ctx = squares();
    var reordered = Reorder.HILBERT_START.apply(ctx);
    assertArrayEquals(
        PartBoundaries.of(ctx, 12), PartBoundaries.of(reordered, 12), "equal-length rings tile identically");
    assertEquals(ctx.numParts(), reordered.numParts());
    assertEquals(ctx.numRings(), reordered.numRings());
    assertEquals(ctx.geometryTypes(), reordered.geometryTypes());
  }

  @Test
  void ringRotationKeepsWindingAndCyclicOrder() {
    // The second square starts at its far corner relative to where the first ended.
    var ctx = squares();
    var reordered = Reorder.HILBERT_START_ROT.apply(ctx);
    var ring0 = reordered.vertices().subList(0, 4);
    var ring1 = reordered.vertices().subList(4, 8);
    for (var ring : List.of(ring0, ring1)) {
      assertTrue(signedArea(ring) > 0 == signedArea(square(0, 0, 10)) > 0, "winding kept");
      assertTrue(isRotationOfASquare(ring), "a cyclic rotation, not a permutation");
    }
    // ring1 must start at the corner nearest where ring0 ended.
    var end = ring0.get(3);
    var start = ring1.get(0);
    for (var v : ring1) {
      assertTrue(distance(start, end) <= distance(v, end));
    }
  }

  private static boolean isRotationOfASquare(List<Vertex> ring) {
    for (var k = 0; k < 4; k++) {
      var a = ring.get(k);
      var b = ring.get((k + 1) % 4);
      if ((a.x() == b.x()) == (a.y() == b.y())) {
        return false;
      }
    }
    return true;
  }

  /** Lines: parts keep their vertex order, even when the end of one is nearer than its start. */
  @Test
  void linesAreNeverReversed() {
    var vertices =
        List.of(
            new Vertex(900, 900),
            new Vertex(10, 10), // line 0 runs far -> near
            new Vertex(20, 20),
            new Vertex(0, 0), // line 1 runs far from line 0's start but near its end
            new Vertex(5, 5));
    var ctx = new BufferContext(vertices, List.of(1, 1), List.of(), List.of(2, 3), List.of());
    for (var mode : List.of(Reorder.HILBERT_START, Reorder.MORTON_CENTROID, Reorder.HILBERT_START_ROT)) {
      var reordered = mode.apply(ctx);
      var starts = PartBoundaries.of(reordered, 5);
      var parts = new ArrayList<List<Vertex>>();
      for (var i = 0; i < starts.length; i++) {
        parts.add(reordered.vertices().subList(starts[i], i + 1 < starts.length ? starts[i + 1] : 5));
      }
      assertTrue(parts.contains(vertices.subList(0, 2)), mode + " kept part 0 in its direction");
      assertTrue(parts.contains(vertices.subList(2, 5)), mode + " kept part 1 in its direction");
    }
  }

  /** A MultiLineString's children are one feature: they move together and keep their order. */
  @Test
  void multiPartFeaturesStayContiguousAndInOrder() {
    var vertices =
        List.of(
            new Vertex(3000, 3000), new Vertex(3001, 3000), // feature 0, single line
            new Vertex(10, 10), new Vertex(11, 10), // feature 1, part a
            new Vertex(2000, 2000), new Vertex(2001, 2000), // feature 1, part b (far from a)
            new Vertex(12, 12), new Vertex(13, 12)); // feature 2, single line
    var ctx =
        new BufferContext(vertices, List.of(1, 4, 1), List.of(2), List.of(2, 2, 2, 2), List.of());
    var reordered = Reorder.HILBERT_START.apply(ctx);

    assertTrue(Verification.holds(ctx, reordered));
    var out = reordered.vertices();
    var a = out.indexOf(new Vertex(10, 10));
    assertEquals(new Vertex(2000, 2000), out.get(a + 2), "part b directly follows part a");
    // numGeometries travels with its feature, geometry types are permuted with the features.
    assertEquals(List.of(2), reordered.numGeometries());
    assertEquals(List.of(4, 1, 1), reordered.geometryTypes(), "types are permuted with their features");
  }

  @Test
  void pointColumnsAreReorderedPerFeatureAndVerifiedAsAMultiset() {
    var vertices =
        List.of(
            new Vertex(900, 900),
            new Vertex(1, 1),
            new Vertex(902, 903), // multipoint of two
            new Vertex(900, 902),
            new Vertex(3, 2));
    var ctx = new BufferContext(vertices, List.of(0, 0, 3, 0), List.of(2), List.of(), List.of());
    var reordered = Reorder.HILBERT_START.apply(ctx);

    assertNotEquals(vertices, reordered.vertices());
    assertTrue(Verification.holds(ctx, reordered));
    var at = reordered.vertices().indexOf(new Vertex(902, 903));
    assertEquals(new Vertex(900, 902), reordered.vertices().get(at + 1), "multipoint keeps its two points together");
    assertFalse(
        Verification.holds(
            ctx,
            new BufferContext(
                List.of(new Vertex(900, 900), new Vertex(1, 1), new Vertex(902, 903), new Vertex(900, 902), new Vertex(4, 2)),
                ctx.geometryTypes(),
                ctx.numGeometries(),
                ctx.numParts(),
                ctx.numRings())),
        "a changed point is caught");
  }

  /** MultiPoint members are sorted along the curve, and the verification treats them as a set. */
  @Test
  void multiPointMembersAreSortedWhenAskedAndOtherwiseKeptInOrder() {
    var vertices =
        List.of(
            new Vertex(3000, 3000), new Vertex(5, 5), new Vertex(1500, 1500), new Vertex(10, 10), new Vertex(4000, 20));
    var ctx = new BufferContext(vertices, List.of(3, 0, 0, 0), List.of(2), List.of(), List.of());
    // Feature 0 is a MultiPoint of (3000,3000), (5,5); features 1 to 3 are points.
    var sorted = Reorder.HILBERT_CENTROID_ROT_MP.apply(ctx);
    var kept = Reorder.HILBERT_CENTROID_ROT.apply(ctx);

    assertTrue(Verification.holds(ctx, sorted));
    assertTrue(Verification.holds(ctx, kept));
    var at = sorted.vertices().indexOf(new Vertex(5, 5));
    assertEquals(new Vertex(3000, 3000), sorted.vertices().get(at + 1), "the near point leads its MultiPoint");
    var keptAt = kept.vertices().indexOf(new Vertex(3000, 3000));
    assertEquals(new Vertex(5, 5), kept.vertices().get(keptAt + 1), "without the flag the member order is kept");
    assertEquals(vertices.get(0), new Vertex(3000, 3000), "the input list is never modified");
  }

  @Test
  void unlocatableBuffersAreReturnedUnchanged() {
    var vertices = List.of(new Vertex(5, 5), new Vertex(1, 1), new Vertex(9, 9));
    // Mixed point + line: points have no run, so the features cannot be located.
    var mixed = new BufferContext(vertices, List.of(0, 1), List.of(), List.of(2), List.of());
    assertSame(mixed, Reorder.HILBERT_START.apply(mixed));
    // No topology at all.
    var bare = BufferContext.of(vertices);
    assertSame(bare, Reorder.HILBERT_START.apply(bare));
    // A single feature has nothing to reorder.
    var single = new BufferContext(vertices, List.of(1), List.of(), List.of(3), List.of());
    assertSame(single, Reorder.HILBERT_START.apply(single));
  }

  @Test
  void decoderTopologyCarriesNoVertices() {
    var topology = Reorder.HILBERT_START.topology(squares());
    assertTrue(topology.vertices().isEmpty());
    assertEquals(3, topology.geometryTypes().size());
  }

  private static int indexOf(int[] array, int value) {
    for (var i = 0; i < array.length; i++) {
      if (array[i] == value) {
        return i;
      }
    }
    return -1;
  }

  private static long signedArea(List<Vertex> ring) {
    var area = 0L;
    for (var i = 0; i < ring.size(); i++) {
      var a = ring.get(i);
      var b = ring.get((i + 1) % ring.size());
      area += (long) a.x() * b.y() - (long) b.x() * a.y();
    }
    return area;
  }

  private static long distance(Vertex a, Vertex b) {
    return Math.abs((long) a.x() - b.x()) + Math.abs((long) a.y() - b.y());
  }
}
