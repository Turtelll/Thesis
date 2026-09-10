package org.maplibre.mlt.research.encoding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Round-trip coverage for {@link RansCodec}. An entropy coder fails in a specific way — a state or
 * renormalisation bug corrupts the tail of a stream while leaving its head intact — so these cases
 * lean on long streams and skewed distributions rather than a handful of hand-picked values.
 *
 * <p>{@link RansCodec} codes zigzag residuals, which are non-negative, so the inputs here are too.
 */
class RansCodecTest {

  private static void assertRoundTrip(int[] values) {
    final var codec = new RansCodec();
    final var encoded = codec.encode(values);
    final var decoded = codec.decode(encoded, values.length);
    assertArrayEquals(values, decoded);
  }

  @Test
  void roundTripsSkewedResiduals() {
    // The shape the coder actually sees: mostly small, occasional large.
    final var random = new Random(42);
    final var values = new int[20_000];
    for (var i = 0; i < values.length; i++) {
      values[i] = random.nextInt(100) < 80 ? random.nextInt(4) : random.nextInt(1 << 14);
    }
    assertRoundTrip(values);
  }

  @Test
  void roundTripsUniformWideValues() {
    // The adversarial case for a bucket alphabet: nearly all the entropy in the raw bits.
    final var random = new Random(7);
    final var values = new int[20_000];
    for (var i = 0; i < values.length; i++) {
      values[i] = random.nextInt(Integer.MAX_VALUE);
    }
    assertRoundTrip(values);
  }

  @Test
  void roundTripsAllZeros() {
    // A single-symbol alphabet: the normalisation must still produce an encodable table.
    assertRoundTrip(new int[5_000]);
  }

  @Test
  void roundTripsSingleValue() {
    assertRoundTrip(new int[] {12345});
  }

  @Test
  void roundTripsEmpty() {
    assertRoundTrip(new int[0]);
  }

  @Test
  void roundTripsExtremeValues() {
    // Bucket 32 is the top of the alphabet; a rawBitCount off-by-one shows up here first.
    assertRoundTrip(new int[] {0, 1, 2, Integer.MAX_VALUE, Integer.MAX_VALUE - 1, 0, 1});
  }

  @Test
  void roundTripsManyDistinctBuckets() {
    // Every bucket present at least once, which stresses the rounding-drift fixup.
    final var values = new int[64];
    for (var i = 0; i < values.length; i++) {
      values[i] = i < 32 ? (1 << i) - 1 : (1 << (i - 32));
    }
    assertRoundTrip(values);
  }

  @Test
  void compressesSkewedStreamBelowRawSize() {
    // Not a tuning assertion, just a floor: a heavily skewed stream must beat 4 bytes/value,
    // or the coder is not doing its job regardless of round-trip correctness.
    final var random = new Random(11);
    final var values = new int[50_000];
    for (var i = 0; i < values.length; i++) {
      values[i] = random.nextInt(100) < 95 ? random.nextInt(2) : random.nextInt(256);
    }
    final var encoded = new RansCodec().encode(values);
    assertTrue(
        encoded.length < values.length,
        "expected < 1 byte/value on a heavily skewed stream, got " + encoded.length);
  }
}
