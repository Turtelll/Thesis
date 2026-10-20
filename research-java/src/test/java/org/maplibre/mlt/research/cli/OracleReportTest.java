package org.maplibre.mlt.research.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.maplibre.mlt.research.pipeline.PipelineRegistry;

/**
 * The per-buffer oracle of {@code pipeline --oracle}: it picks the smallest candidate per backend,
 * a tie goes to the first candidate, and the grid it chooses among is the documented one (the
 * default first, every row distinct, since the benchmark totals by row name).
 */
class OracleReportTest {

  private static final List<Compressor> TWO = List.of(Compressor.GZIP, Compressor.GZIP);

  @Test
  void picksTheSmallestPerBackendAndBreaksTiesTowardsTheFirst() {
    var grid = PipelineRegistry.oracle();
    var report = new OracleReport(grid.subList(0, 3), TWO);
    // candidate 2 is smallest under backend 0, candidate 1 under backend 1, backend 1 ties 0 and 1 first
    var sizes = new long[][] {{10, 5}, {9, 5}, {4, 7}};
    assertArrayEquals(new int[] {2, 0}, report.offer("building", sizes));
    // a second buffer where everything ties: the first candidate wins both
    assertArrayEquals(new int[] {0, 0}, report.offer("building", new long[][] {{3, 3}, {3, 3}, {3, 3}}));
  }

  @Test
  void gridStartsWithTheDefaultAndHasDistinctRows() {
    var grid = PipelineRegistry.oracle();
    assertEquals(48, grid.size());
    assertEquals(PipelineRegistry.DEFAULT.name(), grid.getFirst().name());
    var names = new HashSet<String>();
    for (var row : grid) {
      assertTrue(names.add(row.name()), "repeated row " + row.name());
    }
  }

  @Test
  void labelsAreDistinctPerBackend() {
    var report = new OracleReport(PipelineRegistry.oracle().subList(0, 2), Compressor.all(9));
    var labels = new HashSet<String>();
    for (var c = 0; c < Compressor.all(9).size(); c++) {
      assertTrue(labels.add(report.label(c)));
    }
  }
}
