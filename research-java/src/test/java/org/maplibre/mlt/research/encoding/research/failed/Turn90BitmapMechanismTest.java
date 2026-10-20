package org.maplibre.mlt.research.encoding.research.failed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.IntCodec;
import org.maplibre.mlt.research.encoding.VertexTransform;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;

/**
 * Pins {@code TURN90_BITMAP}: from the third vertex of a polygon ring on, the prediction is the
 * previous edge turned by 90°, the bit picks the direction, and the rotated edge keeps the previous
 * edge's length — so a rectangle leaves only the side-length difference and a reflex corner flips
 * the bit. Line vertices are untouched.
 */
class Turn90BitmapMechanismTest {

  private static BufferContext ring(List<Vertex> vertices) {
    return new BufferContext(vertices, List.of(2), List.of(), List.of(1), List.of(vertices.size()));
  }

  private static int zz(int value) {
    return EncodingUtils.encodeZigZag(value);
  }

  @Test
  void rectangleLeavesOnlyTheSideLengthDifference() {
    final var streams =
        Turn90Bitmap.streams(
            ring(
                List.of(
                    new Vertex(0, 0), new Vertex(100, 0), new Vertex(100, 50), new Vertex(0, 50))));
    assertArrayEquals(new boolean[] {false, false}, streams.turns());
    assertArrayEquals(
        new int[] {0, 0, zz(100), 0, 0, zz(-50), zz(-50), 0}, streams.residuals());
  }

  @Test
  void reflexCornerFlipsTheBitAndEveryOtherCornerIsExact() {
    final var ctx =
        ring(
            List.of(
                new Vertex(0, 0),
                new Vertex(200, 0),
                new Vertex(200, 100),
                new Vertex(100, 100),
                new Vertex(100, 200),
                new Vertex(0, 200)));
    final var streams = Turn90Bitmap.streams(ctx);
    assertArrayEquals(new boolean[] {false, false, true, false}, streams.turns());
    assertArrayEquals(
        new int[] {0, 0, zz(200), 0, 0, zz(-100), 0, 0, 0, 0, 0, 0}, streams.residuals());

    final var encoding = new Turn90Bitmap(IntCodec.VARINT);
    assertEquals(ctx.vertices(), encoding.decode(encoding.encode(ctx), ctx, ctx.vertices().size()));
  }

  @Test
  void lineVerticesArePlainDelta() {
    final var vertices = List.of(new Vertex(0, 0), new Vertex(100, 0), new Vertex(100, 50));
    final var ctx = new BufferContext(vertices, List.of(1), List.of(), List.of(3), List.of());
    final var streams = Turn90Bitmap.streams(ctx);
    assertEquals(0, streams.turns().length);
    assertArrayEquals(new VertexTransform.ZigZagDelta().apply(ctx), streams.residuals());
  }
}
