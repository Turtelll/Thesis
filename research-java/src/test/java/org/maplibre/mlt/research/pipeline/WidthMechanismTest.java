package org.maplibre.mlt.research.pipeline;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** S6 verification: widths, the escape boundary and the descriptor, through the stream codec alone. */
class WidthMechanismTest {

  private static PipelineConfig config(ByteWidth width) {
    return PipelineConfig.IDENTITY.withWidth(width);
  }

  /** The signed residual whose zigzag is {@code mapped}. */
  private static int signedOf(int mapped) {
    return org.maplibre.mlt.decoder.DecodingUtils.decodeZigZag(mapped);
  }

  private static int[] streamOf(int... mapped) {
    return IntStream.of(mapped).map(WidthMechanismTest::signedOf).toArray();
  }

  private static byte[] encode(int[] signed, PipelineConfig config) {
    var out = new ByteArrayOutputStream();
    StreamCodec.encode(out, signed, config.stream(), true);
    return out.toByteArray();
  }

  private static int[] roundTrip(int[] signed, PipelineConfig config) {
    var bytes = encode(signed, config);
    var in = new Bytes.Reader(bytes, 0);
    var decoded = StreamCodec.decode(in, signed.length, config.stream(), true);
    assertEquals(0, in.remaining(), "the decoder consumes exactly what was written");
    return decoded;
  }

  @ParameterizedTest
  @EnumSource(ByteWidth.class)
  void everyWidthRoundTripsEveryRange(ByteWidth width) {
    var cases =
        new int[][] {
          streamOf(0, 1, 2, 3),
          streamOf(254, 255, 256, 0),
          streamOf(255, 255, 255, 255),
          streamOf(0, 65534, 65535, 65536),
          streamOf(0, 1, 2, 3_000_000, 0, 0),
          streamOf(-1, -1, 7, 7),
        };
    for (var signed : cases) {
      assertArrayEquals(signed, roundTrip(signed, config(width)));
    }
  }

  @Test
  void minFixedPicksTheNarrowestWidthPerStream() {
    // one descriptor byte plus n * width bytes, n = 4 values
    var fixed = config(ByteWidth.MIN_FIXED);
    assertEquals(1 + 4, encode(streamOf(0, 1, 254, 3), fixed).length);
    assertEquals(1 + 4, encode(streamOf(0, 1, 255, 3), fixed).length);
    assertEquals(1 + 8, encode(streamOf(0, 1, 256, 3), fixed).length);
    assertEquals(1 + 8, encode(streamOf(0, 1, 65535, 3), fixed).length);
    assertEquals(1 + 16, encode(streamOf(0, 1, 65536, 3), fixed).length);
  }
  @Test
  void escapeBoundaryAt254And255And256() {
    var esc = config(ByteWidth.ESCAPE);
    // 254 is the largest literal; one byte, no escape, no exception stream
    assertEquals(1 + 4, encode(streamOf(254, 0, 0, 0), esc).length);
    // 255 alone is still width 1 (no escape needed: the descriptor says "no escape")
    assertEquals(1 + 4, encode(streamOf(255, 0, 0, 0), esc).length);
    // one 256 in 16 values: escape = 1 + 16 + 1 exception byte < 1 + 32 fixed 2-byte
    var one = new int[16];
    one[5] = 256;
    var oneStream = streamOf(one);
    assertEquals(1 + 16 + 1, encode(oneStream, esc).length);
    assertArrayEquals(oneStream, roundTrip(oneStream, esc));
    // many wide values: escape stops paying, falls back to the 2-byte fixed stream
    var many = new int[16];
    java.util.Arrays.fill(many, 1000);
    assertEquals(1 + 32, encode(streamOf(many), esc).length);
  }

  @Test
  void aLiteral255BesideRealExceptionsIsEscapedNotConfused() {
    var values = new int[20];
    values[3] = 255; // exactly the marker
    values[9] = 4000;
    var signed = streamOf(values);
    assertArrayEquals(signed, roundTrip(signed, config(ByteWidth.ESCAPE)));
  }

  @Test
  void exceptionsUseTheirOwnMinimalWidth() {
    var values = new int[20];
    values[2] = 255 + 255; // exception value 255 -> width 1
    values[7] = 255 + 256; // exception value 256 -> width 2
    var signed = streamOf(values);
    var bytes = encode(signed, config(ByteWidth.ESCAPE));
    // descriptor + 20 main + 2 exceptions at width 2
    assertEquals(1 + 20 + 2 * 2, bytes.length);
    assertArrayEquals(signed, roundTrip(signed, config(ByteWidth.ESCAPE)));
  }

  @Test
  void force4WritesNoDescriptorAndFourBytesEach() {
    var signed = streamOf(0, 1, 2, 3);
    assertEquals(16, encode(signed, config(ByteWidth.FORCE_4)).length);
  }
}
