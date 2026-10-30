package org.maplibre.mlt.research.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.buffer.Encoding.BufferContext;
import org.maplibre.mlt.research.extraction.VertexBufferExtractor;

/**
 * The selection loop: it round-trips, it is never larger than the default pipeline under the
 * compressor it trials with, and it does choose by content (a straight line takes a line predictor).
 */
class SelectorTest {

  private static final GeometryFactory GF = new GeometryFactory();

  static long gzipSize(byte[] data) {
    var out = new ByteArrayOutputStream();
    try (var gzip = new GZIPOutputStream(out)) {
      gzip.write(data);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.size();
  }

  private static BufferContext polygons() {
    var geometries = new ArrayList<org.locationtech.jts.geom.Geometry>();
    for (var i = 0; i < 20; i++) {
      var x = i * 300 - 2000;
      var y = (i * 7919) % 1500;
      geometries.add(
          GF.createPolygon(
              new Coordinate[] {
                new Coordinate(x, y),
                new Coordinate(x + 40 + i, y),
                new Coordinate(x + 40 + i, y + 30),
                new Coordinate(x, y + 30),
                new Coordinate(x, y),
              }));
    }
    return PipelineEncodingTest.contextOf(VertexBufferExtractor.extract(geometries));
  }

  private static BufferContext smoothLine() {
    var points = new Coordinate[200];
    for (var i = 0; i < points.length; i++) {
      points[i] = new Coordinate(100 + i * 13, 50 + (i * i) / 8);
    }
    return PipelineEncodingTest.contextOf(
        VertexBufferExtractor.extract(List.of(GF.createLineString(points))));
  }

  @ParameterizedTest
  @EnumSource(
      value = Reorder.class,
      names = {"NONE", "HILBERT_CENTROID_ROT"})
  void roundTripsEveryBufferShape(Reorder reorder) {
    var selector = new Selector(reorder, "gzip", SelectorTest::gzipSize);
    for (var ctx : List.of(polygons(), smoothLine())) {
      PipelineEncodingTest.assertReconstructs(selector, ctx, selector.encode(ctx));
    }
    var single =
        BufferContext.of(
            VertexBufferExtractor.extract(List.of(GF.createPoint(new Coordinate(7, 9))))
                .vertexBuffer());
    assertEquals(single.vertices(), selector.decode(selector.encode(single), single, 1));
    var extreme =
        BufferContext.of(
            List.of(new Vertex(Integer.MAX_VALUE, Integer.MIN_VALUE), new Vertex(Integer.MIN_VALUE, 0)));
    assertEquals(extreme.vertices(), selector.decode(selector.encode(extreme), extreme, 2));
  }

  @Test
  void isNeverLargerThanTheDefaultUnderItsOwnCompressor() {
    var selector = new Selector(Reorder.NONE, "gzip", SelectorTest::gzipSize);
    var fixed = new PipelineEncoding(PipelineRegistry.DEFAULT);
    for (var ctx : List.of(polygons(), smoothLine())) {
      assertTrue(gzipSize(selector.encode(ctx).payload()) <= gzipSize(fixed.encode(ctx).payload()));
    }
  }

  @Test
  void choosesByContent() {
    var selector = new Selector(Reorder.NONE, "gzip", SelectorTest::gzipSize);
    var line = smoothLine();
    var choice = Selector.choiceOf(selector.encode(line).payload());
    assertNotEquals(Prediction.DELTA, choice.predictor(), "a smooth curve has second differences near zero");
    assertTrue(
        gzipSize(selector.encode(line).payload())
            < gzipSize(
                new PipelineEncoding(PipelineRegistry.DEFAULT.withPredictor(Prediction.DELTA))
                    .encode(line)
                    .payload()));
  }
}
