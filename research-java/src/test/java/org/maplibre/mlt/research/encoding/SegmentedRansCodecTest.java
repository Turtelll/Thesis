package org.maplibre.mlt.research.encoding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Round-trip coverage for {@link SegmentedRansCodec}. The failure this targets is a region-boundary
 * bug: lengths that do not tile the stream, or a decoder that reads one region's bytes as another's,
 * corrupt values at a region seam while leaving the first region intact — so the cases here check
 * seams specifically, not just whole-stream equality.
 */
class SegmentedRansCodecTest {

  private static void assertRoundTrip(int[] values, int[] regions) {
    final var codec = new SegmentedRansCodec("RANS_SEG");
    codec.setPendingRegions(regions);
    final var encoded = codec.encode(values);
    final var decoded = codec.decode(encoded, values.length);
    assertArrayEquals(values, decoded);
  }

  @Test
  void roundTripsThreeRegionsWithDifferentDistributions() {
    // The real shape: a wide region, a narrow one, and a near-constant one.
    final var random = new Random(3);
    final var values = new int[3_000];
    for (var i = 0; i < 1_000; i++) {
      values[i] = random.nextInt(1 << 16);
    }
    for (var i = 1_000; i < 2_400; i++) {
      values[i] = random.nextInt(8);
    }
    for (var i = 2_400; i < 3_000; i++) {
      values[i] = random.nextInt(2);
    }
    assertRoundTrip(values, new int[] {1_000, 1_400, 600});
  }

  @Test
  void roundTripsWithEmptyRegions() {
    // A buffer with no quad corners produces a zero-length region; it must still tile the stream.
    final var values = new int[] {5, 6, 7, 8};
    assertRoundTrip(values, new int[] {0, 4, 0});
  }

  @Test
  void fallsBackWhenRegionsDoNotTileTheStream() {
    // A splitter that disagrees with the stream must degrade to one region, not corrupt it.
    final var values = new int[] {1, 2, 3, 4, 5};
    assertRoundTrip(values, new int[] {2, 2});
  }

  @Test
  void fallsBackWhenNoRegionsSupplied() {
    final var codec = new SegmentedRansCodec("RANS_SEG");
    final var values = new int[] {9, 8, 7, 6};
    final var encoded = codec.encode(values);
    assertArrayEquals(values, codec.decode(encoded, values.length));
  }

  @Test
  void roundTripsEmpty() {
    assertRoundTrip(new int[0], new int[] {0, 0, 0});
  }
}
