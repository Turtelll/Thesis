package org.maplibre.mlt.research.encoding.research.failed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.IntCodec;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;

/**
 * Pins the parity rule that is {@code EVEN_RING_TURN}'s whole idea: the signalled 90°-turn predictor
 * fires on even-length rings and never on odd-length ones, and the encoding reconstructs from the
 * topology streams alone either way.
 *
 * <p>Kept with the encoding as a measured negative result — the predictor is genuinely narrower on
 * even rings (see {@code research-java/README.md}) but the signal bit costs more than it returns.
 */
class EvenRingTurnMechanismTest {

  private static final int POLYGON = 2;
  private static final int START = 1;
  private static final int TURN = 3;

  private static EvenRingTurn encoding() {
    return new EvenRingTurn("EVEN_RING_TURN_FASTPFOR", new IntCodec.FastPfor());
  }

  private static BufferContext ringColumn(List<Vertex> vertices) {
    return new BufferContext(
        vertices, List.of(POLYGON), List.of(), List.of(1), List.of(vertices.size()));
  }

  /** A unit-step staircase: every turn is an exact 90°, so a turn-predicted vertex costs nothing. */
  private static List<Vertex> staircase(int n) {
    final var vertices = new java.util.ArrayList<Vertex>(n);
    var x = 0;
    var y = 0;
    for (var i = 0; i < n; i++) {
      vertices.add(new Vertex(x, y));
      if (i % 2 == 0) {
        x += 10;
      } else {
        y += 10;
      }
    }
    return vertices;
  }

  @Test
  void classifiesInteriorVerticesOfAnEvenRingAsTurns() {
    final var classes = EvenRingTurn.classify(new int[] {0}, 6, true);
    assertArrayEquals(new int[] {START, 0, TURN, TURN, TURN, TURN}, classes);
  }

  @Test
  void leavesOddRingsOnPlainDelta() {
    // The parity law: a closed rectilinear ring must have an even vertex count, and the corpus
    // shows 0,0% of 5- and 7-vertex building rings are fully right-angled.
    final var classes = EvenRingTurn.classify(new int[] {0}, 7, true);
    for (var i = 2; i < classes.length; i++) {
      assertEquals(0, classes[i], "vertex " + i + " of an odd ring must not be turn-predicted");
    }
  }

  @Test
  void appliesTheTurnPredictorOnAnEvenRing() {
    final var ctx = ringColumn(staircase(6));
    final var transformed =
        EvenRingTurn.transform(ctx.vertices(), EvenRingTurn.classify(new int[] {0}, 6, true));
    // Four turn-predicted vertices, each an exact 90° turn -> zero residual, packed last.
    final var values = transformed.values();
    for (var slot = values.length - 8; slot < values.length; slot++) {
      assertEquals(0, values[slot], "turn residual at slot " + slot);
    }
    assertEquals(4, transformed.turns().size());
  }

  @Test
  void reconstructsWithoutReadingTheOriginalVertices() {
    final var ctx = ringColumn(staircase(6));
    final var encoded = encoding().encode(ctx);
    final var blind =
        new BufferContext(List.of(), ctx.geometryTypes(), ctx.numGeometries(), ctx.numParts(), ctx.numRings());
    assertEquals(ctx.vertices(), encoding().decode(encoded, blind, 6));
  }

  @Test
  void reconstructsAnOddRingToo() {
    final var ctx = ringColumn(staircase(7));
    final var encoded = encoding().encode(ctx);
    final var blind =
        new BufferContext(List.of(), ctx.geometryTypes(), ctx.numGeometries(), ctx.numParts(), ctx.numRings());
    assertEquals(ctx.vertices(), encoding().decode(encoded, blind, 7));
  }

  @Test
  void corruptedStreamDoesNotReconstruct() {
    final var ctx = ringColumn(staircase(6));
    final var encoded = encoding().encode(ctx);
    final var payload = encoded.payload().clone();
    payload[payload.length - 1] ^= 0x7F;
    final var corrupted =
        new org.maplibre.mlt.research.encoding.VertexBufferCodec.EncodedBuffer(
            payload, encoded.metadataBytes());
    final var blind =
        new BufferContext(List.of(), ctx.geometryTypes(), ctx.numGeometries(), ctx.numParts(), ctx.numRings());
    assertNotEquals(ctx.vertices(), encoding().decode(corrupted, blind, 6));
  }
}
