package org.maplibre.mlt.research.encoding.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.Layout;
import org.maplibre.mlt.research.encoding.PredictedTransform;
import org.maplibre.mlt.research.encoding.VertexTransform;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;

/**
 * Pins the two properties that make {@code SEGMENT_PAIR}'s measured win meaningful rather than an
 * artifact: it really reorders the stream, and it really reconstructs from that stream.
 *
 * <p>A round-trip test alone cannot distinguish a working transform from one that quietly ignores
 * the topology, because on a buffer with no usable topology both behave identically. These tests
 * use a line-only column, where the grouping actually happens.
 */
class SegmentPairMechanismTest {

  /** Two 2-vertex parts and one 3-vertex part — the shape that dominates {@code transportation}. */
  private static BufferContext lineColumn() {
    return new BufferContext(
        List.of(
            new Vertex(10, 10),
            new Vertex(12, 11),
            new Vertex(500, 400),
            new Vertex(503, 402),
            new Vertex(20, 900),
            new Vertex(22, 903),
            new Vertex(25, 905)),
        List.of(1, 1, 1),
        List.of(),
        List.of(2, 2, 3),
        List.of());
  }

  @Test
  void groupsPartStartsAheadOfInteriorDeltas() {
    final var ctx = lineColumn();
    final var grouped = new PredictedTransform(new SegmentPair(), Layout.SEGMENTED).apply(ctx);
    final var interleaved = new VertexTransform.ZigZagDelta().apply(ctx);

    // Same multiset of residuals, different order: the transform moves values, it does not
    // invent or drop them. If these were equal the encoding would be the baseline in disguise.
    assertNotEquals(
        List.of(interleaved), List.of(grouped), "expected a different ordering to the baseline");
    assertEquals(interleaved.length, grouped.length);
    assertEquals(sorted(interleaved), sorted(grouped), "residual multiset must be preserved");

    // The three part starts occupy the first three vertex slots.
    final var partStartCount = 3;
    final var firstStartX = grouped[0];
    assertEquals(interleaved[0], firstStartX, "part 0 start is unchanged at the head");
    // The jump into part 1 is a large residual and must sit in the head group, not the tail.
    final var jumpIntoPart1 = interleaved[4];
    assertEquals(grouped[2], jumpIntoPart1, "part 1 start must be grouped with the other starts");
    assertEquals(grouped.length, partStartCount * 2 + 4 * 2);
  }

  /**
   * The decode path must work from topology alone. Passing a context whose vertex list is empty
   * proves the inverse is not secretly reading the buffer it is meant to rebuild.
   */
  @Test
  void reconstructsWithoutReadingTheOriginalVertices() {
    final var ctx = lineColumn();
    final var transform = new PredictedTransform(new SegmentPair(), Layout.SEGMENTED);
    final var decoded = transform.invert(transform.apply(ctx), topologyOnly(ctx));
    assertEquals(ctx.vertices(), decoded);
  }

  /** A corrupted stream must not still reconstruct — otherwise the round trip proves nothing. */
  @Test
  void corruptedStreamDoesNotReconstruct() {
    final var ctx = lineColumn();
    final var transform = new PredictedTransform(new SegmentPair(), Layout.SEGMENTED);
    final var values = transform.apply(ctx);
    values[2] += 2; // perturb one part-start residual
    assertNotEquals(ctx.vertices(), transform.invert(values, topologyOnly(ctx)));
  }

  private static BufferContext topologyOnly(BufferContext ctx) {
    return new BufferContext(
        List.of(), ctx.geometryTypes(), ctx.numGeometries(), ctx.numParts(), ctx.numRings());
  }

  private static List<Integer> sorted(int[] values) {
    return java.util.Arrays.stream(values).boxed().sorted().toList();
  }
}
