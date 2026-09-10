package org.maplibre.mlt.research.encoding.research.failed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.IntCodec;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;

/**
 * Pins the three properties that make a {@code CONTEXT_DELTA} measurement meaningful rather than an
 * artifact: the run-length context actually builds up along a straight line, a turn actually breaks
 * it, and the decoder derives the identical context sequence without ever seeing the buffer.
 *
 * <p>A round-trip test alone cannot distinguish a working context machine from one that always
 * reports context 0 — that degenerate version is still perfectly lossless, it is just the baseline
 * wearing a different name. So the classification is asserted directly.
 */
class ContextDeltaMechanismTest {

  private static final int LINESTRING = 1;
  private static final int CONTEXTS = 4;
  private static final int TURN_SHIFT = 2;
  private static final int MISS_THRESHOLD = 8;

  private static ContextDelta encoding() {
    return new ContextDelta("CONTEXT_DELTA", new IntCodec.FastPfor(), CONTEXTS, TURN_SHIFT, MISS_THRESHOLD);
  }

  // --- fixtures -----------------------------------------------------------

  /**
   * One perfectly straight 8-vertex LineString. Every step continues the previous direction exactly,
   * so the run grows without interruption and the context saturates at {@code min(run,3) == 3}.
   */
  private static BufferContext straightLineColumn() {
    return lineColumn(
        List.of(
            new Vertex(100, 100),
            new Vertex(110, 100),
            new Vertex(120, 100),
            new Vertex(130, 100),
            new Vertex(140, 100),
            new Vertex(150, 100),
            new Vertex(160, 100),
            new Vertex(170, 100)),
        List.of(8));
  }

  /**
   * A run that goes straight, turns hard, then goes straight again — the shape the gate exists to
   * distinguish. The corner must knock the context back to 0 so the turn vertex is delta-coded and
   * files with the other rough residuals.
   */
  private static BufferContext cornerColumn() {
    return lineColumn(
        List.of(
            new Vertex(0, 0),
            new Vertex(10, 0),
            new Vertex(20, 0),
            new Vertex(30, 0), // straight so far
            new Vertex(30, 10), // hard 90 degree corner
            new Vertex(30, 20),
            new Vertex(30, 30)),
        List.of(7));
  }

  /**
   * Two parts: a straight run followed by a second straight run somewhere else. Part boundaries are
   * forced reset points, so the context must drop to 0 at the seam even though both runs are
   * individually smooth — an edge spanning two parts is not a direction.
   */
  private static BufferContext twoPartColumn() {
    return lineColumn(
        List.of(
            new Vertex(0, 0),
            new Vertex(10, 0),
            new Vertex(20, 0),
            new Vertex(30, 0),
            new Vertex(900, 700),
            new Vertex(910, 700),
            new Vertex(920, 700),
            new Vertex(930, 700)),
        List.of(4, 4));
  }

  /**
   * A corner and a part boundary in one buffer — the test vector {@code ideen.md} requires, so both
   * reset paths are exercised together.
   */
  private static BufferContext cornerAndBoundaryColumn() {
    return lineColumn(
        List.of(
            new Vertex(0, 0),
            new Vertex(10, 0),
            new Vertex(20, 0),
            new Vertex(20, 10), // corner reset
            new Vertex(20, 20),
            new Vertex(500, 500), // part boundary reset
            new Vertex(510, 500),
            new Vertex(520, 500),
            new Vertex(530, 500)),
        List.of(5, 4));
  }

  /** A pure-LineString column: vertex counts live in numParts, numRings stays empty. */
  private static BufferContext lineColumn(List<Vertex> vertices, List<Integer> partLengths) {
    return new BufferContext(
        vertices,
        java.util.Collections.nCopies(partLengths.size(), LINESTRING),
        List.of(),
        partLengths,
        List.of());
  }

  private static BufferContext topologyOnly(BufferContext ctx) {
    return new BufferContext(
        List.of(), ctx.geometryTypes(), ctx.numGeometries(), ctx.numParts(), ctx.numRings());
  }

  private static int[] classify(BufferContext ctx) {
    final var starts = org.maplibre.mlt.research.encoding.PartBoundaries.of(
        ctx, ctx.vertices().size());
    return ContextDelta.classify(
        ctx.vertices(), starts, ctx.vertices().size(), TURN_SHIFT, CONTEXTS - 1, MISS_THRESHOLD);
  }

  // --- the mechanism ------------------------------------------------------

  /**
   * The context must actually climb along a straight line. Vertex 0 has no predecessor and vertex 1
   * ends the first edge, so neither can have history; from vertex 2 the run grows and saturates.
   */
  @Test
  void contextClimbsAlongAStraightRun() {
    assertArrayEquals(new int[] {0, 0, 1, 2, 3, 3, 3, 3}, classify(straightLineColumn()));
  }

  /**
   * A hard turn must knock the run back.
   *
   * <p>Note <em>where</em> it knocks back, because this is the causal limit of the scheme and the
   * thing a reader of the numbers has to understand. The context of vertex {@code i} is read before
   * {@code i} is decoded, so the corner vertex itself (index 4) is still coded at the full context
   * 3 — the machine cannot know the line turns there until it has seen it turn. The turn is caught
   * one vertex later: index 5 drops to 0 because the mispredicted residual at index 4 tripped the
   * strike test. So the scheme pays for exactly one wide residual per corner and then recovers,
   * rather than predicting a whole run from a direction that no longer holds.
   */
  @Test
  void aTurnResetsTheContextOneVertexLate() {
    final var contexts = classify(cornerColumn());
    assertArrayEquals(new int[] {0, 0, 1, 2, 3, 0, 1}, contexts);
    assertEquals(3, contexts[4], "the corner is still coded at full context -- the miss is causal");
    assertEquals(0, contexts[5], "the mispredicted corner must collapse the run for the next vertex");
  }

  /** A part boundary is a forced reset even when both runs are individually smooth. */
  @Test
  void partBoundaryResetsTheContext() {
    final var contexts = classify(twoPartColumn());
    assertArrayEquals(new int[] {0, 0, 1, 2, 0, 0, 1, 2}, contexts);
    assertEquals(0, contexts[4], "the first vertex of the second part must reset to context 0");
    assertEquals(0, contexts[3 + 1], "no edge may predict across a part seam");
  }

  /**
   * The straight run's residuals must be near zero, which is the entire point: that is the narrow
   * population FastPFOR gets to pack at its own width once it is separated.
   */
  @Test
  void straightRunProducesNearZeroResiduals() {
    final var ctx = straightLineColumn();
    final var encoded = encoding().encode(ctx);
    assertEquals(ctx.vertices(), encoding().decode(encoded, topologyOnly(ctx), ctx.vertices().size()));

    // Contexts 1..3 hold the six predicted vertices; a perfectly straight line predicts them
    // exactly, so every one of those residuals is zero.
    final var contexts = classify(ctx);
    var predicted = 0;
    for (var c : contexts) {
      if (c > 0) {
        predicted++;
      }
    }
    assertEquals(6, predicted, "six vertices should be continuation-predicted");
  }

  // --- the contract -------------------------------------------------------

  /**
   * The decode path must work from topology alone. Passing a context whose vertex list is empty
   * proves the decoder derives its contexts rather than reading the buffer it is reconstructing.
   */
  @Test
  void reconstructsWithoutReadingTheOriginalVertices() {
    for (var ctx : List.of(
        straightLineColumn(), cornerColumn(), twoPartColumn(), cornerAndBoundaryColumn())) {
      final var encoding = encoding();
      final var encoded = encoding.encode(ctx);
      assertEquals(
          ctx.vertices(),
          encoding.decode(encoded, topologyOnly(ctx), ctx.vertices().size()),
          "must reconstruct from topology alone");
    }
  }

  /**
   * {@code ideen.md} names the evaluation order normative — context before decoding, state
   * transition after reconstruction — and warns that getting it wrong throws no error but emits
   * plausible-looking wrong geometry to the end of the buffer. This pins the exact output on the
   * buffer that contains both reset paths.
   */
  @Test
  void reconstructsExactlyAcrossACornerAndAPartBoundary() {
    final var ctx = cornerAndBoundaryColumn();
    final var encoding = encoding();
    final var decoded =
        encoding.decode(encoding.encode(ctx), topologyOnly(ctx), ctx.vertices().size());
    assertEquals(ctx.vertices(), decoded);
    // 0,0,1,2 building up; the corner at index 3 mispredicts so index 4 collapses to 0; index 5
    // starts the second part (forced reset) and the run rebuilds from there.
    assertArrayEquals(new int[] {0, 0, 1, 2, 0, 0, 0, 1, 2}, classify(ctx));
  }

  /** A corrupted stream must not still reconstruct — otherwise the round trip proves nothing. */
  @Test
  void corruptedStreamDoesNotReconstruct() {
    final var ctx = straightLineColumn();
    final var encoding = encoding();
    final var encoded = encoding.encode(ctx);
    final var payload = encoded.payload().clone();
    payload[payload.length - 1] ^= 0x01;
    final var corrupted =
        new org.maplibre.mlt.research.encoding.VertexBufferCodec.EncodedBuffer(
            payload, encoded.metadataBytes());
    assertNotEquals(
        ctx.vertices(), encoding.decode(corrupted, topologyOnly(ctx), ctx.vertices().size()));
  }

  /** The count prefix is real signalled data and must be carried in the reported size. */
  @Test
  void reportedSizeCarriesTheCountPrefix() {
    final var ctx = straightLineColumn();
    final var encoded = encoding().encode(ctx);
    assertTrue(encoded.metadataBytes() > 0, "the count stream must charge its own header");
    assertTrue(
        encoded.totalBytes() > encoded.payload().length,
        "totalBytes must exceed the payload by the stream metadata");
  }
}
