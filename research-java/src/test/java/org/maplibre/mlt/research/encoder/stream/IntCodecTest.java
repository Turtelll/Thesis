package org.maplibre.mlt.research.encoder.stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

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

  @ParameterizedTest
  @MethodSource("byteSerializers")
  void byteSerializer_roundTripsRandomValues(IntCodec codec) {
    assertRoundTrip(codec, randomValues(501));
  }

  @ParameterizedTest
  @MethodSource("byteSerializers")
  void byteSerializer_roundTripsEmptyAndOddLength(IntCodec codec) {
    assertRoundTrip(codec, new int[0]);
    assertRoundTrip(codec, new int[] {0, 127, 128, 16384, -1});
  }

  /** Same size as Varint before compression; only the byte order differs. */
  @Test
  void varintSplit_matchesVarintSize() {
    int[] values = randomValues(300);
    for (int i = 0; i < values.length; i++) {
      values[i] >>>= 1 + i % 25;
    }
    int varint = IntCodec.VARINT.encode(values).length;
    assertEquals(varint, IntCodec.VARINT_SPLIT.encode(values).length);
  }

  /** Every first byte comes before any continuation byte. */
  @Test
  void varintSplit_putsFirstBytesFirst() {
    byte[] encoded = IntCodec.VARINT_SPLIT.encode(new int[] {300, 1, 2});
    assertArrayEquals(new byte[] {(byte) 0xAC, 1, 2, 0x02}, encoded);
  }

  /** Byte planes: all low bytes, then all second bytes, and so on. */
  @Test
  void byteSplit_transposesIntoPlanes() {
    byte[] encoded = IntCodec.BYTESPLIT.encode(new int[] {0x0201, 0x0403});
    assertArrayEquals(new byte[] {1, 3, 2, 4, 0, 0, 0, 0}, encoded);
  }

  @ParameterizedTest
  @MethodSource("uint16Serializers")
  void uint16_roundTripsFullRangeAndEmpty(IntCodec codec) {
    var random = new Random(42);
    int[] values = new int[501];
    for (int i = 0; i < values.length; i++) {
      values[i] = random.nextInt(0x10000);
    }
    values[0] = 0;
    values[1] = 0xFFFF;
    assertRoundTrip(codec, values);
    assertRoundTrip(codec, new int[0]);
    assertEquals(values.length * 2, codec.encode(values).length);
  }

  @ParameterizedTest
  @MethodSource("uint16Serializers")
  void uint16_rejectsValuesOutsideSixteenBits(IntCodec codec) {
    assertThrows(IllegalArgumentException.class, () -> codec.encode(new int[] {0x10000}));
    assertThrows(IllegalArgumentException.class, () -> codec.encode(new int[] {-1}));
  }

  @Test
  void uint16_writesLittleEndianWords() {
    assertArrayEquals(
        new byte[] {1, 2, 3, 4}, IntCodec.UINT16.encode(new int[] {0x0201, 0x0403}));
  }

  @Test
  void uint16Planes_writesLowPlaneThenHighPlane() {
    assertArrayEquals(
        new byte[] {1, 3, 2, 4}, IntCodec.UINT16_PLANES.encode(new int[] {0x0201, 0x0403}));
  }

  static List<IntCodec> byteSerializers() {
    return List.of(IntCodec.VARINT_SPLIT, IntCodec.BYTESPLIT);
  }

  static List<IntCodec> uint16Serializers() {
    return List.of(IntCodec.UINT16, IntCodec.UINT16_PLANES);
  }
}
