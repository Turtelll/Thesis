package org.maplibre.mlt.research.encoding.research.failed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.Baselines;
import org.maplibre.mlt.research.encoding.IntCodec;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.research.failed.TileStandardDelta.Learned;
import org.maplibre.mlt.research.encoding.research.failed.TileStandardDelta.Unit;

class TileStandardDeltaTest {

  /** Deltas (0,0), (20,0), (20,0), then four (5,3) below MIN_LENGTH. */
  private static final BufferContext BUFFER_A =
      BufferContext.of(
          List.of(
              new Vertex(0, 0), new Vertex(20, 0), new Vertex(40, 0), new Vertex(45, 3),
              new Vertex(50, 6), new Vertex(55, 9), new Vertex(60, 12)));

  /** Deltas (20,0), (0,-30), (0,-30). */
  private static final BufferContext BUFFER_B =
      BufferContext.of(List.of(new Vertex(20, 0), new Vertex(20, -30), new Vertex(20, -60)));

  private static final List<BufferContext> TILE = List.of(BUFFER_A, BUFFER_B);

  static List<TileStandardDelta> rows() {
    return Baselines.tileStandardDelta();
  }

  @ParameterizedTest
  @MethodSource("rows")
  void roundTripsEveryBufferOfATile(TileStandardDelta row) {
    assertRoundTrips(row, TILE);
  }

  @Test
  void learn_countsAcrossBuffersAndSkipsShortDeltas() {
    assertArrayEquals(new int[] {20}, learn(Unit.SCALAR, 1));
    assertArrayEquals(new int[] {20, -30}, learn(Unit.SCALAR, 16));
    assertArrayEquals(new int[] {20, 0}, learn(Unit.VECTOR, 1));
    assertArrayEquals(new int[] {20, 0, 0, -30}, learn(Unit.VECTOR, 16));
  }

  private static int[] learn(Unit unit, int k) {
    return new TileStandardDelta(unit, k, false, IntCodec.VARINT_SPLIT).learn(TILE);
  }

  @Test
  void scalar_writesStepTokensThenRemainders() {
    // 45 = 20 + 20 + 5; -3 takes no step.
    var learned = new Learned(1, new int[] {20}, false);

    int[] tokens = learned.encode(new int[] {45, -3});

    assertArrayEquals(new int[] {1, 1, 0, 0, 10, 5}, tokens);
    assertArrayEquals(new int[] {45, -3}, learned.decode(tokens, 2));
  }

  @Test
  void vector_subtractsTheEntryThatShortensTheRestMost() {
    // (21,-17) = (20,0) + (0,-16) + (1,-1)
    var learned = new Learned(2, new int[] {20, 0, 0, -16}, false);

    int[] tokens = learned.encode(new int[] {21, -17});

    assertArrayEquals(new int[] {1, 2, 0, 2, 1}, tokens);
    assertArrayEquals(new int[] {21, -17}, learned.decode(tokens, 2));
  }

  @Test
  void single_writesOneStepTokenPerDeltaAndNoTerminator() {
    // 45 = 20 + 25; -3 takes no entry.
    var learned = new Learned(1, new int[] {20}, true);

    int[] tokens = learned.encode(new int[] {45, -3});

    assertArrayEquals(new int[] {1, 0, 50, 5}, tokens);
    assertArrayEquals(new int[] {45, -3}, learned.decode(tokens, 2));
  }

  @Test
  void emptyVocabulary_whenNoDeltaIsLongEnough() {
    var tile =
        List.of(BufferContext.of(List.of(new Vertex(1, 2), new Vertex(4, 6), new Vertex(3, 3))));
    var row = new TileStandardDelta(Unit.VECTOR, 4, false, IntCodec.VARINT_SPLIT);

    assertEquals(0, row.learn(tile).length);
    assertArrayEquals(new byte[] {0}, row.header(row.learn(tile)));
    assertRoundTrips(row, tile);
  }

  @Test
  void roundTripsBufferNeedingSixteenBitEscapes() {
    var tile =
        List.of(
            BufferContext.of(
                List.of(new Vertex(0, 0), new Vertex(100_000, -100_000), new Vertex(5, 5))));
    for (var unit : Unit.values()) {
      assertRoundTrips(new TileStandardDelta(unit, 1, false, IntCodec.UINT16_PLANES), tile);
      assertRoundTrips(new TileStandardDelta(unit, 1, true, IntCodec.UINT16_PLANES), tile);
    }
  }

  @Test
  void namesComposeUnitSizeAndCodec() {
    assertEquals(
        "TILE_STD_DELTA_VECTOR_K4_VARINT_SPLIT",
        new TileStandardDelta(Unit.VECTOR, 4, false, IntCodec.VARINT_SPLIT).name());
    assertEquals(
        "TILE_STD_DELTA_SCALAR_K16_SINGLE_UINT16_PLANES",
        new TileStandardDelta(Unit.SCALAR, 16, true, IntCodec.UINT16_PLANES).name());
  }

  private static void assertRoundTrips(TileStandardDelta row, List<BufferContext> tile) {
    var encoding = row.forTile(row.header(row.learn(tile)));
    for (var ctx : tile) {
      var decoded = encoding.decode(encoding.encode(ctx), ctx, ctx.vertices().size());

      assertEquals(ctx.vertices(), decoded, row.name());
    }
  }
}
