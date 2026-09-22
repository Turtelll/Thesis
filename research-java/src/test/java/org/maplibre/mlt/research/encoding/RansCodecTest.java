package org.maplibre.mlt.research.encoding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

    // Worst case for the sparse table: all 33 symbols present, so it costs 100 bytes vs the old
    // format's fixed 66 — accepted deliberately since this case is rare (docs/results/shared-tables.md).
    final var codec = new RansCodec();
    final var encoded = codec.encode(values);
    assertEquals(33, encoded[0] & 0xFF);
  }

  @Test
  void writesSparseTableHeader() {
    // Only buckets 0, 1, 2 occur (values 0..3), so the leading count byte should read 3, not the old
    // format's fixed 33 — pins the sparse header without depending on total payload size.
    final var values = new int[] {0, 1, 2, 3, 1, 2, 0, 3};
    final var codec = new RansCodec();
    final var encoded = codec.encode(values);
    assertEquals(3, encoded[0] & 0xFF);
  }
}
