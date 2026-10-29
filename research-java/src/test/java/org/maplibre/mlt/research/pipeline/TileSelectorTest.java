package org.maplibre.mlt.research.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.extraction.VertexBufferExtractor;

/**
 * The selection on the tile: every chosen buffer round-trips, the tile is never larger than the
 * per-layer default under the compressor it trials with, and the descent starts from that default.
 */
class TileSelectorTest {

  private static final GeometryFactory GF = new GeometryFactory();

  private static BufferContext polygons(int seed) {
    var geometries = new ArrayList<org.locationtech.jts.geom.Geometry>();
    for (var i = 0; i < 30; i++) {
      var x = ((i * 7919 + seed) % 40) * 90 - 2000;
      var y = ((i * 104729 + seed) % 37) * 70;
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

  private static BufferContext smoothLines() {
    var lines = new ArrayList<org.locationtech.jts.geom.Geometry>();
    for (var l = 0; l < 5; l++) {
      var points = new Coordinate[60];
      for (var i = 0; i < points.length; i++) {
        points[i] = new Coordinate(100 + l * 500 + i * 13, 50 + l * 300 + (i * i) / 8);
      }
      lines.add(GF.createLineString(points));
    }
    return PipelineEncodingTest.contextOf(VertexBufferExtractor.extract(lines));
  }

  private static final List<String> LAYERS = List.of("building", "transportation", "no-such-layer");

  private static List<BufferContext> tile() {
    return List.of(polygons(1), smoothLines(), polygons(5));
  }

  private static long tileSize(List<byte[]> payloads) {
    var out = new ByteArrayOutputStream();
    payloads.forEach(out::writeBytes);
    return SelectorTest.gzipSize(out.toByteArray());
  }

  @Test
  void everyChosenBufferRoundTrips() {
    var buffers = tile();
    var choices = new TileSelector("gzip", SelectorTest::gzipSize).select(LAYERS, buffers);
    assertEquals(buffers.size(), choices.size());
    for (var b = 0; b < buffers.size(); b++) {
      var choice = choices.get(b);
      PipelineEncodingTest.assertReconstructs(
          new PipelineEncoding(choice.config()), buffers.get(b), choice.encoded());
    }
  }

  @Test
  void isNeverLargerThanThePerLayerDefaultOnTheTile() {
    var buffers = tile();
    var selected =
        new TileSelector("gzip", SelectorTest::gzipSize)
            .select(LAYERS, buffers).stream()
                .map(choice -> choice.encoded().payload())
                .toList();
    var fixed = new ArrayList<byte[]>();
    for (var b = 0; b < buffers.size(); b++) {
      fixed.add(
          new PipelineEncoding(PipelineRegistry.forLayer(LAYERS.get(b)))
              .encode(buffers.get(b))
              .payload());
    }
    assertTrue(tileSize(selected) <= tileSize(fixed));
  }

  @Test
  void theOracleRoundTripsAndBeatsBothStartsOnTheTile() {
    var buffers = tile();
    var oracle = TileSelector.oracle("gzip", SelectorTest::gzipSize, PipelineRegistry.bestGrid());
    var search = oracle.search(LAYERS, oracle.encode(buffers));
    assertEquals(buffers.size(), search.tile().size());
    assertEquals(buffers.size(), search.alone().size());
    var fixed = new ArrayList<byte[]>();
    for (var b = 0; b < buffers.size(); b++) {
      for (var choice : List.of(search.tile().get(b), search.alone().get(b))) {
        PipelineEncodingTest.assertReconstructs(
            new PipelineEncoding(choice.config()), buffers.get(b), choice.encoded());
      }
      // the alone-best is the smallest candidate alone
      var aloneSize = SelectorTest.gzipSize(search.alone().get(b).encoded().payload());
      for (var size : search.aloneSizes()[b]) {
        assertTrue(aloneSize <= size);
      }
      fixed.add(
          new PipelineEncoding(PipelineRegistry.forLayer(LAYERS.get(b)))
              .encode(buffers.get(b))
              .payload());
    }
    var tile = tileSize(search.tile().stream().map(choice -> choice.encoded().payload()).toList());
    var alone = tileSize(search.alone().stream().map(choice -> choice.encoded().payload()).toList());
    assertTrue(tile <= tileSize(fixed), "never larger than the per-layer default");
    assertTrue(tile <= alone, "never larger than the alone-best tile");
  }

  @Test
  void aSearchRejectsATrialOfOtherCandidates() {
    var buffers = tile();
    var oracle = TileSelector.oracle("gzip", SelectorTest::gzipSize, PipelineRegistry.bestGrid());
    var trial = new TileSelector("gzip", SelectorTest::gzipSize).encode(buffers);
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> oracle.search(LAYERS, trial));
  }

  @Test
  void aRestrictionFixesTheRuledStages() {
    var buffers = tile();
    var choices = new TileSelector("gzip", SelectorTest::gzipSize, StageRules::restrict).select(LAYERS, buffers);
    for (var b = 0; b < buffers.size(); b++) {
      var features = BufferFeatures.of(buffers.get(b));
      var config = choices.get(b).config();
      assertEquals(StageRules.reorder(features), config.reorder());
      assertEquals(StageRules.segmentation(features), config.segmentation());
      PipelineEncodingTest.assertReconstructs(
          new PipelineEncoding(config), buffers.get(b), choices.get(b).encoded());
    }
  }

  @Test
  void startsFromThePerLayerDefault() {
    var candidates = new TileSelector("gzip", SelectorTest::gzipSize).candidates();
    // the 48-row oracle grid first, then each distinct layer default it lacks (the ring-frame rows)
    var grid = Selector.oracleConfigs();
    assertEquals(48, grid.size());
    assertEquals(grid, candidates.subList(0, 48));
    var extras =
        PipelineRegistry.fittedLayers().stream()
            .map(PipelineRegistry::forLayer)
            .filter(config -> !grid.contains(config))
            .distinct()
            .count();
    assertTrue(extras > 0, "the ring-frame defaults are outside the grid");
    assertEquals(48 + extras, candidates.size());
    for (var layer : PipelineRegistry.fittedLayers()) {
      assertTrue(candidates.contains(PipelineRegistry.forLayer(layer)), layer);
    }
    assertTrue(candidates.contains(PipelineRegistry.forLayer("no-such-layer")));
  }
}
