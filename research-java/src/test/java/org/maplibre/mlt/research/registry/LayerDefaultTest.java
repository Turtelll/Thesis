package org.maplibre.mlt.research.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.maplibre.mlt.research.buffer.Encoding;
import org.maplibre.mlt.research.encoder.prediction.Prediction;
import org.maplibre.mlt.research.encoder.reorder.Reorder;
import org.maplibre.mlt.research.encoder.format.PipelineEncoding;

/**
 * The per-layer default ({@link PipelineRegistry#forLayer}) is a fixed table, so what it must hold
 * is checked here: every composition in it is a row of {@link PipelineRegistry#all()} (round-tripped
 * by {@code PipelineEncodingTest}), every fitted layer uses the Hilbert reorder, and an unknown
 * layer gets {@link PipelineRegistry#DEFAULT}.
 */
class LayerDefaultTest {

  @Test
  void everyCompositionIsARoundTrippedRow() {
    var covered = PipelineRegistry.all().stream().map(Encoding::name).collect(Collectors.toSet());
    for (var layer : PipelineRegistry.fittedLayers()) {
      var name = new PipelineEncoding(PipelineRegistry.forLayer(layer)).name();
      assertTrue(covered.contains(name), layer + " is not a row of any step: " + name);
    }
  }

  @Test
  void everyFittedLayerReorders() {
    for (var layer : PipelineRegistry.fittedLayers()) {
      assertTrue(PipelineRegistry.reorderPays(layer), layer);
      assertEquals(
          PipelineRegistry.CHAIN_LAYERS.contains(layer) ? Reorder.CHAIN : Reorder.HILBERT_CENTROID_ROT_MP,
          PipelineRegistry.forLayer(layer).reorder(),
          layer);
    }
  }

  @Test
  void transportationChainsItsRoadsAndReferencesEarlierJunctions() {
    var config = PipelineRegistry.forLayer("transportation");
    assertEquals(Reorder.CHAIN, config.reorder());
    assertEquals(Prediction.LINE_CONTINUATION_SHARED_FRAME_HEADING, config.predictor());
  }

  @Test
  void theOracleGridHoldsEveryLayerDefault() {
    var grid = PipelineRegistry.bestGrid();
    assertEquals(252, grid.size());
    assertEquals(grid.size(), new java.util.HashSet<>(grid).size(), "the grid has duplicates");
    assertTrue(grid.contains(PipelineRegistry.DEFAULT));
    for (var layer : PipelineRegistry.fittedLayers()) {
      assertTrue(grid.contains(PipelineRegistry.forLayer(layer)), layer);
    }
  }

  @Test
  void unknownLayerFallsBackToDefault() {
    assertEquals(PipelineRegistry.DEFAULT, PipelineRegistry.forLayer("no-such-layer"));
  }
}
