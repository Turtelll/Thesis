package org.maplibre.mlt.research.encoding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

class IntCodecTest {

  @Test
  void varint_roundTripsRandomValues() {
    var random = new Random(42);
    int[] values = new int[500];
    for (int i = 0; i < values.length; i++) {
      values[i] = random.nextInt();
    }

    var codec = new IntCodec.Varint();
    byte[] encoded = codec.encode(values);
    int[] decoded = codec.decode(encoded, values.length);

    assertArrayEquals(values, decoded);
  }

  @Test
  void varint_roundTripsEmpty() {
    var codec = new IntCodec.Varint();
    byte[] encoded = codec.encode(new int[0]);
    int[] decoded = codec.decode(encoded, 0);
    assertArrayEquals(new int[0], decoded);
  }

  @Test
  void fastPfor_roundTripsRandomValues() {
    var random = new Random(42);
    int[] values = new int[500];
    for (int i = 0; i < values.length; i++) {
      values[i] = random.nextInt();
    }

    var codec = new IntCodec.FastPfor();
    byte[] encoded = codec.encode(values);
    int[] decoded = codec.decode(encoded, values.length);

    assertArrayEquals(values, decoded);
  }

  @Test
  void fastPfor_roundTripsSmallNonBlockAlignedArray() {
    int[] values = {1, -2, 3, -4, 5};

    var codec = new IntCodec.FastPfor();
    byte[] encoded = codec.encode(values);
    int[] decoded = codec.decode(encoded, values.length);

    assertArrayEquals(values, decoded);
  }
}
