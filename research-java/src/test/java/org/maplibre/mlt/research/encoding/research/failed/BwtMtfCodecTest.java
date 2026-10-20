package org.maplibre.mlt.research.encoding.research.failed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.maplibre.mlt.research.encoding.research.failed.BwtMtfCodec.Domain;
import org.maplibre.mlt.research.encoding.research.failed.BwtMtfCodec.Stage;

class BwtMtfCodecTest {

  static List<BwtMtfCodec> codecs() {
    var codecs = new ArrayList<BwtMtfCodec>();
    for (var domain : Domain.values()) {
      for (var stage : Stage.values()) {
        codecs.add(new BwtMtfCodec(domain, stage));
      }
    }
    return codecs;
  }

  static Stream<int[]> inputs() {
    var random = new Random(42);
    int[] small = new int[2000];
    int[] wide = new int[700];
    for (int i = 0; i < small.length; i++) {
      small[i] = random.nextInt(40);
    }
    for (int i = 0; i < wide.length; i++) {
      wide[i] = random.nextInt();
    }
    int[] runs = new int[300];
    int[] alternating = new int[301];
    for (int i = 0; i < alternating.length; i++) {
      alternating[i] = i % 2 == 0 ? 4 : 7;
    }
    return Stream.of(
        new int[0],
        new int[] {5},
        runs,
        alternating,
        new int[] {0xFFFFFFFF, 0xFFFFFFFE, 0, 0xFFFFFFFF, 1, 0x80000000, 0xFFFFFFFF},
        small,
        wide);
  }

  @ParameterizedTest
  @MethodSource("codecs")
  void roundTripsEveryInput(BwtMtfCodec codec) {
    inputs()
        .forEach(
            values ->
                assertArrayEquals(
                    values, codec.decode(codec.encode(values), values.length), codec.name()));
  }

  /** More distinct symbols than the MTF front list holds, so symbols fall off and come back. */
  @Test
  void mtf_roundTripsBeyondFrontList() {
    var random = new Random(7);
    int[] values = new int[5000];
    for (int i = 0; i < values.length; i++) {
      values[i] = random.nextInt(BwtMtfCodec.MTF_FRONT * 3);
    }
    assertArrayEquals(values, BwtMtfCodec.inverseMtf(BwtMtfCodec.mtf(values)));
  }

  @Test
  void bwt_matchesTextbookBanana() {
    var bwt = BwtMtfCodec.bwt("banana".chars().toArray());
    // Sorted suffixes of banana$: $, a$, ana$, anana$, banana$, na$, nana$ -> annb$aa.
    assertArrayEquals("annbaa".chars().toArray(), bwt.symbols());
    assertEquals(4, bwt.primary());
    assertArrayEquals("banana".chars().toArray(), BwtMtfCodec.inverseBwt(bwt.symbols(), 4));
  }

  @Test
  void mtf_overBytesIsStandardMoveToFront() {
    var random = new Random(3);
    int[] bytes = new int[1000];
    for (int i = 0; i < bytes.length; i++) {
      bytes[i] = random.nextInt(256) & (random.nextBoolean() ? 0x0F : 0xFF);
    }
    var list = new ArrayList<Integer>();
    for (int s = 0; s < 256; s++) {
      list.add(s);
    }
    int[] expected = new int[bytes.length];
    for (int i = 0; i < bytes.length; i++) {
      int rank = list.indexOf(bytes[i]);
      expected[i] = rank;
      list.remove(rank);
      list.add(0, bytes[i]);
    }
    assertArrayEquals(expected, BwtMtfCodec.mtf(bytes));
  }

  @Test
  void mtf_keepsUnseenSmallValuesSmall() {
    assertArrayEquals(new int[] {3, 0, 1, 2}, BwtMtfCodec.mtf(new int[] {3, 3, 0, 1}));
  }

  @Test
  void namesCarryDomainAndStage() {
    assertEquals("INT_BWT_MTF", new BwtMtfCodec(Domain.INT, Stage.BWT_MTF).name());
    assertEquals("BYTE_MTF", new BwtMtfCodec(Domain.BYTE, Stage.MTF).name());
  }
}
