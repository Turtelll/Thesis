package org.maplibre.mlt.research.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.maplibre.mlt.research.encoding.Encoding;

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
          Reorder.HILBERT_CENTROID_ROT_MP,
          PipelineRegistry.forLayer(layer).reorder(),
          layer);
    }
  }

  @Test
  void unknownLayerFallsBackToDefault() {
    assertEquals(PipelineRegistry.DEFAULT, PipelineRegistry.forLayer("no-such-layer"));
  }
}
