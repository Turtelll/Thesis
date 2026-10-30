package org.maplibre.mlt.research.encoder.reference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.buffer.Encoding.BufferContext;

/**
 * Pins what the shared-reference mechanisms decide, on small fixtures: which vertices are
 * references (no residual), which rings or parts are rotated or reversed, and that a decoder with
 * the topology and the flag and index bits alone walks to the same references.
 */
class SharedReferenceMechanismTest {

  private static final int LINESTRING = 1;

  // --- rings ---

  /** Four houses in a row, each sharing a wall with the one before. */
  private static BufferContext terrace() {
    final var vertices = new ArrayList<Vertex>();
    for (var house = 0; house < 4; house++) {
      final var x = house * 1000;
      vertices.addAll(
          List.of(
              new Vertex(x, 0), new Vertex(x + 1000, 0), new Vertex(x + 1000, 1000), new Vertex(x, 1000)));
    }
    return new BufferContext(
        vertices, List.of(2, 2, 2, 2), List.of(), List.of(1, 1, 1, 1), List.of(4, 4, 4, 4));
  }

  private static BufferContext detachedHouses() {
    return new BufferContext(
        List.of(
            new Vertex(0, 0),
            new Vertex(1000, 0),
            new Vertex(1000, 1000),
            new Vertex(0, 1000),
            new Vertex(3000, 0),
            new Vertex(4000, 0),
            new Vertex(4000, 1000),
            new Vertex(3000, 1000)),
        List.of(2, 2),
        List.of(),
        List.of(1, 1),
        List.of(4, 4));
  }

  @Test
  void secondRingIsRotatedToStartWhereTheSharedWallBegins() {
    final var scan = SharedVertexRef.scan(terrace());
    assertEquals(
        List.of(
            new Vertex(0, 0),
            new Vertex(1000, 0),
            new Vertex(1000, 1000),
            new Vertex(0, 1000),
            // B rotated by 3: a cyclic shift, so its winding is unchanged. It starts on the shared
            // vertex whose predecessor is not shared, so the whole wall follows as one run.
            new Vertex(1000, 1000),
            new Vertex(1000, 0),
            new Vertex(2000, 0),
            new Vertex(2000, 1000)),
        scan.vertices().subList(0, 8));
  }

  @Test
  void bothVerticesOfASharedWallAreReferences() {
    final var referenced = SharedVertexRef.scan(terrace()).referenced();
    for (var i = 0; i < 4; i++) {
      assertFalse(referenced[i], "the first ring has nothing to reference: " + i);
    }
    assertTrue(referenced[4], "the wall's start is an entry into ring A");
    assertTrue(referenced[5], "the wall's second vertex follows ring A backwards");
    assertFalse(referenced[6]);
  }

  @Test
  void detachedBuildingsReferenceNothingAndAreNotRotated() {
    final var ctx = detachedHouses();
    final var scan = SharedVertexRef.scan(ctx);
    assertEquals(ctx.vertices(), scan.vertices());
    for (final var flag : scan.referenced()) {
      assertFalse(flag);
    }
  }

  @Test
  void ringWalkDecodesTheSameReferencesFromTopologyAlone() {
    for (final var ctx : List.of(terrace(), detachedHouses())) {
      final var scan = SharedVertexRef.scan(ctx);
      final var walk =
          SharedVertexRef.Walk.forDecoding(
              topologyOnly(ctx), scan.vertices().size(), scan.flags(), scan.indices());
      assertWalk(scan, walk);
    }
  }

  // --- line parts ---

  /** A road, then a road branching off its middle vertex: a T-junction. */
  private static BufferContext tJunction() {
    return lines(
        List.of(new Vertex(0, 0), new Vertex(1000, 0), new Vertex(2000, 0)),
        List.of(new Vertex(1000, 0), new Vertex(1000, 1000), new Vertex(1000, 2000)));
  }

  /** A road, then a road that only ends on it. */
  private static BufferContext endsOnEarlierRoad() {
    return lines(
        List.of(new Vertex(0, 0), new Vertex(1000, 0)),
        List.of(new Vertex(5000, 5000), new Vertex(3000, 3000), new Vertex(1000, 0)));
  }

  private static BufferContext detachedRoads() {
    return lines(
        List.of(new Vertex(0, 0), new Vertex(1000, 0)),
        List.of(new Vertex(5000, 5000), new Vertex(6000, 6000)));
  }

  /** A closed road (its end repeats its own start) and a one-vertex part on that start. */
  private static BufferContext closedLoopAndSinglePoint() {
    return lines(
        List.of(new Vertex(0, 0), new Vertex(1000, 0), new Vertex(1000, 1000), new Vertex(0, 0)),
        List.of(new Vertex(0, 0)));
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

  @Test
  void aJunctionStartIsAReference() {
    final var referenced = LineSharedVertexRef.scan(tJunction()).referenced();
    assertTrue(referenced[3], "the branch starts on a position road A holds");
    assertFalse(referenced[0]);
    assertFalse(referenced[4]);
    assertFalse(referenced[5], "the branch's end is on no earlier road");
  }

  @Test
  void aPartEndingOnAnEarlierRoadIsReversedSoItsStartCarriesTheReference() {
    final var scan = LineSharedVertexRef.scan(endsOnEarlierRoad());
    assertEquals(
        List.of(new Vertex(1000, 0), new Vertex(3000, 3000), new Vertex(5000, 5000)),
        scan.vertices().subList(2, 5));
    assertTrue(scan.referenced()[2]);
  }

  @Test
  void detachedRoadsReferenceNothingAndAreNotReversed() {
    final var ctx = detachedRoads();
    final var scan = LineSharedVertexRef.scan(ctx);
    assertEquals(ctx.vertices(), scan.vertices());
    for (final var flag : scan.referenced()) {
      assertFalse(flag);
    }
  }

  @Test
  void lineWalkDecodesTheSameReferencesFromTopologyAlone() {
    for (final var ctx :
        List.of(
            tJunction(), endsOnEarlierRoad(), detachedRoads(), closedLoopAndSinglePoint())) {
      final var scan = LineSharedVertexRef.scan(ctx);
      final var walk =
          LineSharedVertexRef.walkForDecoding(
              topologyOnly(ctx), scan.vertices().size(), scan.flags(), scan.indices());
      assertWalk(scan, walk);
    }
  }

  /** Walking the emitted vertices in order, a decoder finds a reference exactly where the encoder put one. */
  private static void assertWalk(SharedVertexRef.Scan scan, SharedVertexRef.ReferenceWalk walk) {
    final var decoded = new ArrayList<Vertex>();
    for (var i = 0; i < scan.vertices().size(); i++) {
      final var reference = walk.decode(i, decoded);
      if (scan.referenced()[i]) {
        assertNotNull(reference, "vertex " + i + " is a reference");
        assertEquals(scan.vertices().get(i), reference, "vertex " + i);
      } else {
        assertEquals(null, reference, "vertex " + i + " carries a residual");
      }
      decoded.add(scan.vertices().get(i));
      walk.accept(i, decoded);
    }
    assertArrayEquals(scan.vertices().toArray(), decoded.toArray());
  }

  private static BufferContext topologyOnly(BufferContext ctx) {
    return new BufferContext(
        List.of(), ctx.geometryTypes(), ctx.numGeometries(), ctx.numParts(), ctx.numRings());
  }
}
