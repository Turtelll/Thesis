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
    for (var layer : LayerCompositions.fittedLayers()) {
      var name = new PipelineEncoding(LayerCompositions.forLayer(layer)).name();
      assertTrue(covered.contains(name), layer + " is not a row of any step: " + name);
    }
  }

  @Test
  void everyFittedLayerReorders() {
    for (var layer : LayerCompositions.fittedLayers()) {
      assertTrue(LayerCompositions.reorderPays(layer), layer);
      assertEquals(
          LayerCompositions.CHAIN_LAYERS.contains(layer) ? Reorder.CHAIN : Reorder.HILBERT_CENTROID_ROT_MP,
          LayerCompositions.forLayer(layer).reorder(),
          layer);
    }
  }

  @Test
  void transportationChainsItsRoadsAndReferencesEarlierJunctions() {
    var config = LayerCompositions.forLayer("transportation");
    assertEquals(Reorder.CHAIN, config.reorder());
    assertEquals(Prediction.LINE_CONTINUATION_SHARED_IFRAME_HEADING, config.predictor());
  }

  @Test
  void ringFramesAreTheOneVoteFramesAndTheRetiredOnesAreOutOfEveryDefaultAndGrid() {
    assertEquals(Prediction.QUAD_CORNER_SHARED_PREVFRAME_FIRST_ONLY, PipelineRegistry.DEFAULT.predictor());
    assertEquals(
        Prediction.QUAD_CORNER_SHARED_PREVFRAME_FIRST_ONLY, LayerCompositions.forLayer("building").predictor());
    assertEquals(Prediction.QUAD_CORNER_PREVFRAME_FIRST_ONLY, LayerCompositions.forLayer("place").predictor());
    assertTrue(Prediction.grid().contains(Prediction.QUAD_CORNER_PREVFRAME_FIRST_ONLY));
    assertTrue(Prediction.grid().contains(Prediction.QUAD_CORNER_SHARED_PREVFRAME_FIRST_ONLY));
    for (var predictor : Prediction.values()) {
      if (predictor.retiredRingFrame()) {
        assertTrue(!Prediction.grid().contains(predictor), predictor + " is retired but in the grid");
        for (var layer : LayerCompositions.fittedLayers()) {
          assertTrue(LayerCompositions.forLayer(layer).predictor() != predictor, predictor + " in " + layer);
        }
        assertTrue(CompositionGrids.bestGrid().stream().noneMatch(c -> c.predictor() == predictor), predictor.name());
      }
    }
  }

  @Test
  void theOracleGridHoldsEveryLayerDefault() {
    var grid = CompositionGrids.bestGrid();
    assertEquals(96, grid.size());
    assertEquals(grid.size(), new java.util.HashSet<>(grid).size(), "the grid has duplicates");
    assertTrue(grid.contains(PipelineRegistry.DEFAULT));
    for (var layer : LayerCompositions.fittedLayers()) {
      assertTrue(grid.contains(LayerCompositions.forLayer(layer)), layer);
    }
  }

  @Test
  void unknownLayerFallsBackToDefault() {
    assertEquals(PipelineRegistry.DEFAULT, LayerCompositions.forLayer("no-such-layer"));
  }
}
