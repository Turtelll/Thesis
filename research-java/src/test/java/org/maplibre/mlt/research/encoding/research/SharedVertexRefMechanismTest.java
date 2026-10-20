package org.maplibre.mlt.research.encoding.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.Layout;
import org.maplibre.mlt.research.encoding.PredictedTransform;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;

/**
 * Pins how {@code QUAD_CORNER_SHARED} uses a wall two buildings share: the second ring is rotated
 * (winding kept) to start on the shared wall, its start is an index into the previous ring, and the
 * next vertex follows the previous ring backwards — so neither shared vertex carries a residual.
 */
class SharedVertexRefMechanismTest {

  /**
   * Two row houses sharing the wall x = 1000, both wound the same way, so house B walks the shared
   * wall opposite to house A.
   */
  private static BufferContext rowHouses() {
    return polygons(
        List.of(
            // House A.
            new Vertex(0, 0),
            new Vertex(1000, 0),
            new Vertex(1000, 1000),
            new Vertex(0, 1000),
            // House B: shares (1000, 0) and (1000, 1000) with A.
            new Vertex(1000, 0),
            new Vertex(2000, 0),
            new Vertex(2000, 1000),
            new Vertex(1000, 1000)));
  }

  /**
   * The row houses continued to four in a row, each sharing a wall with the one before — enough
   * sharing for the shared mode to pay for its region lengths and beat the plain fallback.
   */
  private static BufferContext terrace() {
    final var vertices = new java.util.ArrayList<Vertex>();
    for (var house = 0; house < 4; house++) {
      final var x = house * 1000;
      vertices.addAll(
          List.of(
              new Vertex(x, 0),
              new Vertex(x + 1000, 0),
              new Vertex(x + 1000, 1000),
              new Vertex(x, 1000)));
    }
    return new BufferContext(
        vertices, List.of(2, 2, 2, 2), List.of(), List.of(1, 1, 1, 1), List.of(4, 4, 4, 4));
  }

  /** Two squares that touch nowhere. */
  private static BufferContext detachedHouses() {
    return polygons(
        List.of(
            new Vertex(0, 0),
            new Vertex(1000, 0),
            new Vertex(1000, 1000),
            new Vertex(0, 1000),
            new Vertex(3000, 0),
            new Vertex(4000, 0),
            new Vertex(4000, 1000),
            new Vertex(3000, 1000)));
  }

  private static BufferContext polygons(List<Vertex> vertices) {
    return new BufferContext(
        vertices,
        List.of(2, 2), // POLYGON, POLYGON
        List.of(),
        List.of(1, 1), // one ring each
        List.of(4, 4));
  }

  @Test
  void secondRingIsRotatedToStartWhereTheSharedWallBegins() {
    final var emitted = new SharedVertexRef().emitted(terrace());
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
        emitted.vertices().subList(0, 8));
  }

  @Test
  void sharedWallChoosesTheSharedMode() {
    final var payload =
        new SharedVertexRef().encode(terrace()).payload();
    assertEquals(1, payload[0], "mode byte: SHARED");
  }

  /**
   * Side bits for the fixture: B's start is an entry (flag + 2-bit index into A's 4 positions), its
   * second vertex follows A backwards (flag), its third does not (flag), its fourth is offered no
   * flag because its predecessor is not shared. The two referenced vertices drop four residuals.
   */
  @Test
  void referencedVerticesPayOnlyFlagAndIndexBits() {
    final var ctx = rowHouses();
    final var shared = new SharedVertexRef();
    final var allIn = shared.allInBits(ctx);
    final var plain =
        residualBits(new PredictedTransform(new SegmentQuadCorner(), Layout.INTERLEAVED).apply(shared.emitted(ctx)));
    assertEquals(16, allIn[1], "two values per vertex, referenced ones included");
    assertEquals(plain - bitsOfReferencedResiduals(shared.emitted(ctx)) + 3 + 2, allIn[0]);
  }

  @Test
  void detachedBuildingsFallBackToPlainAndAreNotRotated() {
    final var ctx = detachedHouses();
    final var encoding = new SharedVertexRef();
    assertEquals(0, encoding.encode(ctx).payload()[0], "mode byte: PLAIN");
    assertSame(ctx, encoding.emitted(ctx));
  }

  @Test
  void reconstructsFromTopologyAlone() {
    final var ctx = rowHouses();
    final var encoding = new SharedVertexRef();
    final var decoded = encoding.decode(encoding.encode(ctx), topologyOnly(ctx), ctx.vertices().size());
    assertEquals(encoding.emitted(ctx).vertices(), decoded);
  }

  /**
   * Residual bits of emitted B's first two vertices — the ones the encoding references instead.
   */
  private static long bitsOfReferencedResiduals(BufferContext emitted) {
    final var values = new PredictedTransform(new SegmentQuadCorner(), Layout.INTERLEAVED).apply(emitted);
    return residualBits(java.util.Arrays.copyOfRange(values, 8, 12));
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
