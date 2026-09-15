package org.maplibre.mlt.research.encoding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

class IntCodecTest {

  private static void assertRoundTrip(IntCodec codec, int[] values) {
    byte[] encoded = codec.encode(values);
    int[] decoded = codec.decode(encoded, values.length);

    assertArrayEquals(values, decoded);
  }

  private static int[] randomValues(int count) {
    var random = new Random(42);
    int[] values = new int[count];
    for (int i = 0; i < values.length; i++) {
      values[i] = random.nextInt();
    }
    return values;
  }

  @Test
  void varint_roundTripsRandomValues() {
    assertRoundTrip(new IntCodec.Varint(), randomValues(500));
  }

  @Test
  void varint_roundTripsEmpty() {
    assertRoundTrip(new IntCodec.Varint(), new int[0]);
  }

  @Test
  void fastPfor_roundTripsRandomValues() {
    assertRoundTrip(new IntCodec.FastPfor(), randomValues(500));
  }

  @Test
  void fastPfor_roundTripsSmallNonBlockAlignedArray() {
    assertRoundTrip(new IntCodec.FastPfor(), new int[] {1, -2, 3, -4, 5});
  }
}
