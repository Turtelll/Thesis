package org.maplibre.mlt.research.probe;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Pins how {@code ResidualBitsCli}'s per-vertex table pairs a transform against {@code DELTA_ZIGZAG}:
 * per vertex (x and y together), with gains and losses summed separately so a mixed result cannot
 * cancel out into "no change".
 */
class VertexShrinkTest {

  @Test
  void countsSmallerEqualAndLargerVerticesWithSeparateBitSums() {
    // Per-vertex cost bits(x)+bits(y): reference 3, 0, 6, 4; candidate 1, 0, 7, 4.
    var reference = new int[] {3, 1, 0, 0, 7, 7, 1, 7};
    var candidate = new int[] {0, 1, 0, 0, 15, 7, 7, 1};

    // {vertices, smaller, larger, bitsSaved, bitsAdded}
    assertArrayEquals(new long[] {4, 1, 1, 2, 1}, ResidualBitsCli.VertexShrink.compare(reference, candidate));
  }

  @Test
  void aVertexWhoseXShrinksAsMuchAsItsYGrowsIsEqual() {
    assertArrayEquals(
        new long[] {1, 0, 0, 0, 0},
        ResidualBitsCli.VertexShrink.compare(new int[] {1, 7}, new int[] {7, 1}));
  }

  @Test
  void rejectsStreamsThatAreNotVertexAligned() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ResidualBitsCli.VertexShrink.compare(new int[] {1, 2}, new int[] {1, 2, 3, 4}));
    assertThrows(
        IllegalArgumentException.class,
        () -> ResidualBitsCli.VertexShrink.compare(new int[] {1, 2, 3}, new int[] {1, 2, 3}));
  }
}
