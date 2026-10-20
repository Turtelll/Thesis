package org.maplibre.mlt.research.encoding.research.failed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.IntCodec;
import org.maplibre.mlt.research.encoding.VertexBufferCodec;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.VertexTransform;
import org.maplibre.mlt.research.encoding.research.failed.StandardDelta.Chunk;
import org.maplibre.mlt.research.encoding.research.failed.StandardDelta.Decomposition;
import org.maplibre.mlt.research.encoding.research.failed.StandardDelta.Plain;
import org.maplibre.mlt.research.encoding.research.failed.StandardDelta.Pow2;
import org.maplibre.mlt.research.encoding.research.failed.StandardDelta.QuotRem;
import org.maplibre.mlt.research.encoding.research.failed.StandardDelta.Snap;

class StandardDeltaMechanismTest {

  /** 0, +-S, +-(S-1), multiples of S and part-start-sized jumps for every swept S, plus int extremes. */
  private static final int[] EDGE_DELTAS = {
    0, 1, -1, 15, -15, 16, -16, 17, -17, 32, -32, 63, -63, 64, -64, 65, 128, -128, 255, -255, 256,
    -256, 257, 512, -512, 4095, -4096, 8191, 40_000, -40_000, 70_000, -70_000, 1 << 20,
    Integer.MAX_VALUE, Integer.MIN_VALUE
  };

  static List<Decomposition> decompositions() {
    return Decomposition.all();
  }

  @ParameterizedTest
  @MethodSource("decompositions")
  void roundTripsEdgeDeltas(Decomposition decomposition) {
    int[] deltas = decomposition instanceof Chunk ? withoutHugeValues(EDGE_DELTAS) : EDGE_DELTAS;

    int[] tokens = decomposition.encode(deltas);
    int[] decoded =
        decomposition.decode(StandardDelta.unescape(StandardDelta.escape(tokens)), deltas.length);

    assertArrayEquals(deltas, decoded);
  }

  /** CHUNK emits |d| / S tokens, so the int extremes would allocate billions of them. */
  private static int[] withoutHugeValues(int[] deltas) {
    return java.util.Arrays.stream(deltas).filter(d -> Math.abs((long) d) <= 1 << 20).toArray();
  }

  @Test
  void chunk_largeJumpBecomesRunOfOneRepeatedToken() {
    assertArrayEquals(new int[] {128, 128, 44}, new Chunk(64).encode(new int[] {150}));
    assertArrayEquals(new int[] {127, 127, 43}, new Chunk(64).encode(new int[] {-150}));
    assertArrayEquals(new int[] {128, 0}, new Chunk(64).encode(new int[] {64}));
  }

  @Test
  void quotRem_writesQuotientsThenRemainders() {
    // -1 = -1 * 16 + 15, 33 = 2 * 16 + 1
    assertArrayEquals(new int[] {1, 4, 15, 1}, new QuotRem(16).encode(new int[] {-1, 33}));
  }

  @Test
  void pow2_writesSetBitsHighToLowThenTerminator() {
    // 37 = 32 + 4 + 1 -> exponents +6, +3, +1, zigzagged; -2 -> exponent -2.
    assertArrayEquals(new int[] {12, 6, 2, 0, 3, 0, 0}, new Pow2().encode(new int[] {37, -2, 0}));
  }

  @Test
  void snap_writesTableIndicesThenCorrections() {
    // table 0, 1, 2, 3, 4, 6, 8, 12, 16, ...: 13 -> index 7 (12) + 1; -6 -> index -5 + 0.
    assertArrayEquals(new int[] {14, 9, 1, 0}, new Snap().encode(new int[] {13, -6}));
  }

  @Test
  void escape_wrapsTokensAboveSixteenBitsAndTheEscapeWordItself() {
    int[] tokens = {5, 0xFFFF, 0x12345, 7};

    int[] words = StandardDelta.escape(tokens);

    assertArrayEquals(
        new int[] {5, 0xFFFF, 0, 0xFFFF, 0xFFFF, 1, 0x2345, 7}, words);
    assertArrayEquals(tokens, StandardDelta.unescape(words));
  }

  @Test
  void plain_matchesDeltaZigZagOnTheSameUint16Serializer() {
    var ctx =
        BufferContext.of(
            List.of(new Vertex(10, 20), new Vertex(4000, -30), new Vertex(3990, 7), new Vertex(0, 0)));
    for (var codec : List.of(IntCodec.UINT16, IntCodec.UINT16_PLANES)) {
      var reference = new VertexBufferCodec(new VertexTransform.ZigZagDelta(), codec).encode(ctx);
      var plain = new StandardDelta(new Plain(), codec).encode(ctx);

      assertArrayEquals(reference.payload(), plain.payload(), codec.name());
    }
  }

  @Test
  void roundTripsBufferNeedingEscapes() {
    var vertices = List.of(new Vertex(0, 0), new Vertex(100_000, -100_000), new Vertex(5, 5));
    var ctx = BufferContext.of(vertices);
    for (var decomposition : List.of(new Plain(), new QuotRem(16), new Snap())) {
      var encoding = new StandardDelta(decomposition, IntCodec.UINT16_PLANES);

      assertEquals(vertices, encoding.decode(encoding.encode(ctx), ctx, vertices.size()));
    }
  }

  @Test
  void rejectsCodecsThatAreNotFixedSixteenBit() {
    assertThrows(IllegalArgumentException.class, () -> new StandardDelta(new Plain(), IntCodec.VARINT));
  }

  @Test
  void namesComposeDecompositionAndCodec() {
    assertEquals(
        "STD_DELTA_CHUNK64_UINT16_PLANES",
        new StandardDelta(new Chunk(64), IntCodec.UINT16_PLANES).name());
  }
}
