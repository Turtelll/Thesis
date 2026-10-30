package org.maplibre.mlt.research.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.maplibre.mlt.research.buffer.Encoding;
import org.maplibre.mlt.research.encoder.format.Composition;
import org.maplibre.mlt.research.encoder.format.PipelineEncoding;

/**
 * {@code pipeline --step ladder} sizes the build-up of the pipeline without checking round trips, so
 * this is where it is covered: every stage is a row of some step (round-tripped by {@code
 * PipelineEncodingTest} over {@link PipelineRegistry#all()}), the stages are distinct (the benchmark
 * keys its totals by encoding name and would count a repeated one twice), and the ends are the
 * documented rows.
 */
class LadderTest {

  @Test
  void everyStageIsARowOfAStepSoItIsRoundTripped() {
    var covered =
        PipelineRegistry.all().stream().map(Encoding::name).collect(Collectors.toSet());
    for (var stage : PipelineRegistry.ladder()) {
      var name = new PipelineEncoding(stage.config()).name();
      assertTrue(covered.contains(name), stage.label() + " is not a row of any step: " + name);
    }
  }

  @Test
  void stagesAreDistinct() {
    var names = new HashSet<String>();
    for (var stage : PipelineRegistry.ladder()) {
      assertTrue(
          names.add(new PipelineEncoding(stage.config()).name()),
          stage.label() + " repeats an earlier stage");
    }
  }

  @Test
  void endsAreTheIdentityAndTheDefault() {
    var ladder = PipelineRegistry.ladder();
    assertEquals(Composition.IDENTITY, ladder.getFirst().config());
    assertEquals(PipelineRegistry.DEFAULT, ladder.getLast().config());
    assertTrue(
        ladder.stream().anyMatch(s -> s.config().equals(PipelineRegistry.GEOMETRY_BEST)),
        "the geometry-best row is a stage");
  }

  @Test
  void ladderStepHasOneRowPerStage() {
    assertEquals(PipelineRegistry.ladder().size(), PipelineRegistry.step("ladder").size());
  }
}
