package org.maplibre.mlt.research.encoder.stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.buffer.Encoding.BufferContext;
import org.maplibre.mlt.research.encoding.VertexTransform;
import org.maplibre.mlt.research.pipeline.PayloadHeader;
import org.maplibre.mlt.research.pipeline.PipelineConfig;
import org.maplibre.mlt.research.pipeline.PipelineEncoding;

/** S7 verification: every layout is a permutation of the bytes, and the anchors equal the old codecs. */
class TransposeMechanismTest {

  private static BufferContext buffer(int range) {
    var random = new Random(7);
    var vertices = new ArrayList<Vertex>();
    var x = 0;
    var y = 0;
    for (var i = 0; i < 64; i++) {
      x += random.nextInt(2 * range + 1) - range;
      y += random.nextInt(2 * range + 1) - range;
      vertices.add(new Vertex(x, y));
    }
    return BufferContext.of(vertices);
  }

  private static byte[] body(PipelineConfig config, BufferContext ctx) {
    var payload = new PipelineEncoding(config).encode(ctx).payload();
    return Arrays.copyOfRange(payload, PayloadHeader.HEADER_BYTES, payload.length);
  }

  private static PipelineConfig plain(ByteWidth width, Transpose transpose) {
    return PipelineConfig.IDENTITY.withWidth(width).withTranspose(transpose);
  }

  @ParameterizedTest
  @EnumSource(Transpose.class)
  void everyLayoutIsAPermutationOfThePositions(Transpose transpose) {
    for (var width : List.of(1, 2, 4)) {
      var n = 10;
      var seen = new boolean[n * width];
      for (var i = 0; i < n; i++) {
        for (var b = 0; b < width; b++) {
          var at = transpose.index(i, b, n, width);
          assertEquals(false, seen[at], transpose + " writes position " + at + " twice");
          seen[at] = true;
        }
      }
    }
  }

  @ParameterizedTest
  @EnumSource(Transpose.class)
  void writeThenReadIsTheIdentity(Transpose transpose) {
    var values = new int[] {0, 1, 255, 256, 65535, 65536, -1, Integer.MIN_VALUE, 7, 8};
    assertArrayEquals(values, transpose.read(transpose.write(values, 4), values.length, 4));
    var small = new int[] {0, 1, 254, 100, 7, 8};
    assertArrayEquals(small, transpose.read(transpose.write(small, 1), small.length, 1));
    var mid = new int[] {0, 1, 65535, 300, 7, 8};
    assertArrayEquals(mid, transpose.read(transpose.write(mid, 2), mid.length, 2));
  }

  @Test
  void theLayoutsAreWhatTheirNamesSay() {
    // two vertices (x0 y0) (x1 y1), values with distinct low/high bytes, width 2
    var values = new int[] {0x0A01, 0x0B02, 0x0C03, 0x0D04};
    assertArrayEquals(
        new byte[] {1, 0x0A, 2, 0x0B, 3, 0x0C, 4, 0x0D}, Transpose.NONE.write(values, 2));
    assertArrayEquals(
        new byte[] {1, 2, 3, 4, 0x0A, 0x0B, 0x0C, 0x0D}, Transpose.INTERLEAVED_PLANES.write(values, 2));
    // x_lo, y_lo, x_hi, y_hi: per plane, the x bytes of every vertex then the y bytes
    assertArrayEquals(
        new byte[] {1, 3, 2, 4, 0x0A, 0x0C, 0x0B, 0x0D}, Transpose.PLANE_MAJOR.write(values, 2));
    // x_lo, x_hi, y_lo, y_hi
    assertArrayEquals(
        new byte[] {1, 3, 0x0A, 0x0C, 2, 4, 0x0B, 0x0D}, Transpose.COMPONENT_MAJOR.write(values, 2));
  }

  @Test
  void force4WithPlanesIsTheOldByteSplitBody() {
    var ctx = buffer(40_000);
    assertArrayEquals(
        IntCodec.BYTESPLIT.encode(new VertexTransform.ZigZagDelta().apply(ctx)),
        body(plain(ByteWidth.FORCE_4, Transpose.INTERLEAVED_PLANES), ctx),
        "S7 anchor: DELTA / FLAT / ZIGZAG / FORCE_4 / INTERLEAVED_PLANES == BYTESPLIT");
  }

  @Test
  void widthTwoWithPlanesIsTheOldUint16PlanesBody() {
    var ctx = buffer(300);
    var values = new VertexTransform.ZigZagDelta().apply(ctx);
    assertEquals(
        2, ByteWidth.minWidth(Arrays.stream(values).mapToLong(v -> v & 0xFFFFFFFFL).max().orElse(0)));

    var planes = body(plain(ByteWidth.MIN_FIXED, Transpose.INTERLEAVED_PLANES), ctx);
    assertEquals(ByteWidth.codeOf(2), planes[0] & 3, "descriptor: 2-byte width, no escape");
    assertEquals(0, planes[0] >> 2, "no escape flag, no exception width");
    assertArrayEquals(
        IntCodec.UINT16_PLANES.encode(values), Arrays.copyOfRange(planes, 1, planes.length));

    var interleaved = body(plain(ByteWidth.MIN_FIXED, Transpose.NONE), ctx);
    assertArrayEquals(
        IntCodec.UINT16.encode(values), Arrays.copyOfRange(interleaved, 1, interleaved.length));
  }

  @ParameterizedTest
  @EnumSource(Transpose.class)
  void everyLayoutRoundTripsABuffer(Transpose transpose) {
    for (var width : List.of(ByteWidth.MIN_FIXED, ByteWidth.ESCAPE, ByteWidth.FORCE_4)) {
      for (var range : new int[] {3, 300, 70_000}) {
        var ctx = buffer(range);
        var encoding = new PipelineEncoding(plain(width, transpose));
        assertEquals(ctx.vertices(), encoding.decode(encoding.encode(ctx), ctx, ctx.vertices().size()));
      }
    }
  }

  @Test
  void aVarintTerminalRefusesATranspose() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new PipelineEncoding(plain(ByteWidth.VARINT_SPLIT, Transpose.INTERLEAVED_PLANES)));
  }
}