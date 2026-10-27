package org.maplibre.mlt.research.cli;

import org.maplibre.mlt.research.extraction.ExtractionWriter.ManifestEntry;
import org.maplibre.mlt.research.pipeline.BufferFeatures;
import org.maplibre.mlt.research.pipeline.ByteWidth;
import org.maplibre.mlt.research.pipeline.PipelineConfig;
import org.maplibre.mlt.research.pipeline.PipelineRegistry;
import org.maplibre.mlt.research.pipeline.Prediction;
import org.maplibre.mlt.research.pipeline.Transpose;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * How far the choice of each stage can be predicted without compressing: per (tile, layer) buffer the
 * {@link BufferFeatures} beside what the reference tile selector (trial compression) chose, and per
 * stage the share of buffers on which a cheap rule picks the same value.
 *
 * <p>The rules are the per-layer default, the majority value per layer and per (layer, zoom), the
 * predictor with the lowest residual entropy, and every other tile selector (those sizing the tile
 * with an entropy estimate choose without compressing). A majority is fitted on one half of the tiles
 * and scored on the other ({@code (x + y)} parity, both ways), so it is not scored on what it was
 * fitted on. Agreement is a share of buffers, not of bytes: a disagreement on a near tie costs nothing.
 */
final class ChoiceCorrelation {

    /** One decision of the pipeline, and the value a composition takes for it. */
    enum Stage {
        REORDER(c -> c.reorder().reorders() ? "hilbert" : "none"),
        PREDICTOR(c -> c.predictor().name()),
        SEGMENTATION(c -> c.segmentation().name()),
        TERMINAL(c -> c.width() == ByteWidth.VARINT_SPLIT ? "split"
                : c.transpose() == Transpose.INTERLEAVED_PLANES ? "planes" : "escape");

        private final Function<PipelineConfig, String> value;

        Stage(Function<PipelineConfig, String> value) {
            this.value = value;
        }

        String of(PipelineConfig config) {
            return value.apply(config);
        }
    }

    private record Row(ManifestEntry entry, BufferFeatures features, PipelineConfig reference,
                       Map<String, PipelineConfig> others) {

        int fold() {
            return (entry.x() + entry.y()) & 1;
        }
    }

    private final String reference;
    private final List<Row> rows = new ArrayList<>();

    /** @param reference the name of the tile selector the rules are scored against */
    ChoiceCorrelation(String reference) {
        this.reference = reference;
    }

    /** One buffer: its features and what every tile selector chose for it, by selector name. */
    void record(ManifestEntry entry, BufferFeatures features, Map<String, PipelineConfig> choices) {
        final var others = new LinkedHashMap<>(choices);
        final var chosen = others.remove(reference);
        rows.add(new Row(entry, features, chosen, others));
    }

    boolean isEmpty() {
        return rows.isEmpty();
    }

    void print() {
        System.out.printf("%n--- choice prediction: share of buffers on which a rule picks what %s picked ---%n", reference);
        final var width = 40;
        final var header = new StringBuilder(String.format("%-" + width + "s", "rule"));
        for (final var stage : Stage.values()) {
            header.append(String.format(" %13s", stage.name().toLowerCase()));
        }
        header.append(String.format(" %13s", "all four"));
        System.out.println(header);

        agreement("per-layer default (forLayer)", width, (row, stage) -> stage.of(PipelineRegistry.forLayer(row.entry().layerName())));
        final var byLayer = majority(row -> row.entry().layerName());
        agreement("majority per layer (2-fold)", width, byLayer);
        final var byLayerZoom = majority(row -> row.entry().layerName() + "/" + row.entry().z());
        agreement("majority per layer+zoom (2-fold)", width, (row, stage) -> {
            final var value = byLayerZoom.predict(row, stage);
            return value != null ? value : byLayer.predict(row, stage);
        });
        agreement("lowest residual entropy (predictor only)", width,
                (row, stage) -> stage == Stage.PREDICTOR ? row.features().lowestEntropy().name() : null);
        if (!rows.isEmpty()) {
            for (final var other : rows.get(0).others().keySet()) {
                agreement(other, width, (row, stage) -> stage.of(row.others().get(other)));
            }
        }
        System.out.printf("(%,d buffers; a majority is fitted on the tiles of one (x + y) parity and scored on the other;%n"
                + " per layer+zoom falls back to per layer where the other half has no buffer of that layer and zoom)%n", rows.size());

        for (final var stage : Stage.values()) {
            featureTable(stage);
        }
    }

    /** A rule: the value it predicts for a stage of a buffer, {@code null} for no prediction. */
    private interface Rule {
        String predict(Row row, Stage stage);
    }

    private void agreement(String label, int width, Rule rule) {
        final var line = new StringBuilder(String.format("%-" + width + "s", label));
        var allFour = 0;
        var allFourScored = true;
        final var hits = new int[Stage.values().length];
        final var scored = new int[Stage.values().length];
        for (final var row : rows) {
            var all = true;
            for (final var stage : Stage.values()) {
                final var predicted = rule.predict(row, stage);
                if (predicted == null) {
                    all = false;
                    allFourScored = false;
                    continue;
                }
                scored[stage.ordinal()]++;
                if (predicted.equals(stage.of(row.reference()))) {
                    hits[stage.ordinal()]++;
                } else {
                    all = false;
                }
            }
            if (all) {
                allFour++;
            }
        }
        for (final var stage : Stage.values()) {
            final var s = stage.ordinal();
            line.append(scored[s] == 0 ? String.format(" %13s", "-") : String.format(" %12.1f%%", 100.0 * hits[s] / scored[s]));
        }
        line.append(allFourScored && !rows.isEmpty()
                ? String.format(" %12.1f%%", 100.0 * allFour / rows.size()) : String.format(" %13s", "-"));
        System.out.println(line);
    }

    /** The most frequent reference value per key and stage, fitted on the other fold. */
    private Rule majority(Function<Row, String> key) {
        // fold -> key -> stage -> value -> count
        final List<Map<String, Map<Stage, Map<String, int[]>>>> counts = List.of(new HashMap<>(), new HashMap<>());
        for (final var row : rows) {
            final var byStage = counts.get(row.fold()).computeIfAbsent(key.apply(row), k -> new HashMap<>());
            for (final var stage : Stage.values()) {
                byStage.computeIfAbsent(stage, k -> new HashMap<>())
                        .computeIfAbsent(stage.of(row.reference()), k -> new int[1])[0]++;
            }
        }
        return (row, stage) -> {
            final var byStage = counts.get(1 - row.fold()).get(key.apply(row));
            if (byStage == null) {
                return null;
            }
            return byStage.get(stage).entrySet().stream()
                    .max(Map.Entry.<String, int[]>comparingByValue((a, b) -> Integer.compare(a[0], b[0]))
                            .thenComparing(Map.Entry.comparingByKey()))
                    .map(Map.Entry::getKey)
                    .orElse(null);
        };
    }

    /** Per value the reference chose for this stage: how many buffers, and the mean of each feature. */
    private void featureTable(Stage stage) {
        final var groups = new TreeMap<String, List<BufferFeatures>>();
        for (final var row : rows) {
            groups.computeIfAbsent(stage.of(row.reference()), k -> new ArrayList<>()).add(row.features());
        }
        System.out.printf("%n%s: mean features of the buffers per value %s chose%n", stage.name().toLowerCase(), reference);
        final var header = new StringBuilder(String.format("  %-18s %8s %9s %8s %7s %7s %7s %9s %9s %7s",
                "value", "buffers", "vertices", "v/feat", "point", "line", "poly", "step", "jump", "jump×"));
        for (final var prediction : Prediction.values()) {
            header.append(String.format(" %9s", "H " + abbreviation(prediction)));
        }
        header.append(String.format(" %7s", ">=255"));
        System.out.println(header);
        for (final var group : groups.entrySet()) {
            final var list = group.getValue();
            final var line = new StringBuilder(String.format("  %-18s %8d %9.0f %8.1f %6.1f%% %6.1f%% %6.1f%% %9.1f %9.1f %7.2f",
                    group.getKey(), list.size(),
                    mean(list, f -> (double) f.vertices()),
                    mean(list, f -> f.features() == 0 ? Double.NaN : (double) f.vertices() / f.features()),
                    100 * mean(list, BufferFeatures::pointShare),
                    100 * mean(list, BufferFeatures::lineShare),
                    100 * mean(list, BufferFeatures::polygonShare),
                    mean(list, BufferFeatures::meanStep),
                    mean(list, BufferFeatures::meanJump),
                    mean(list, BufferFeatures::jumpRatio)));
            for (final var prediction : Prediction.values()) {
                final var p = prediction.ordinal();
                line.append(String.format(" %9.2f", mean(list, f -> f.entropy()[p])));
            }
            line.append(String.format(" %6.2f%%", 100 * mean(list, BufferFeatures::wideShare)));
            System.out.println(line);
        }
    }

    /** Mean over the buffers where the feature is defined. */
    private static double mean(List<BufferFeatures> list, java.util.function.ToDoubleFunction<BufferFeatures> feature) {
        var sum = 0.0;
        var count = 0;
        for (final var f : list) {
            final var value = feature.applyAsDouble(f);
            if (!Double.isNaN(value)) {
                sum += value;
                count++;
            }
        }
        return count == 0 ? Double.NaN : sum / count;
    }

    /** One line per buffer: tile, layer, every feature, the reference's stage values, every selector's choice. */
    void writeCsv(Path path) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        try (var out = Files.newBufferedWriter(path)) {
            final var header = new StringBuilder("z,x,y,layer,vertices,features,pointShare,lineShare,polygonShare,"
                    + "meanStep,meanJump,jumpRatio");
            for (final var prediction : Prediction.values()) {
                header.append(",entropy_").append(prediction).append(",zero_").append(prediction);
            }
            header.append(",wideShare");
            for (final var stage : Stage.values()) {
                header.append(",ref_").append(stage.name().toLowerCase());
            }
            final var others = rows.isEmpty() ? List.<String>of() : List.copyOf(rows.get(0).others().keySet());
            header.append(",ref_config");
            others.forEach(other -> header.append(',').append(other));
            out.write(header + "\n");
            for (final var row : rows) {
                final var f = row.features();
                final var e = row.entry();
                final var line = new StringBuilder(String.format(java.util.Locale.ROOT,
                        "%d,%d,%d,%s,%d,%d,%.4f,%.4f,%.4f,%.3f,%.3f,%.4f",
                        e.z(), e.x(), e.y(), e.layerName(), f.vertices(), f.features(), f.pointShare(),
                        f.lineShare(), f.polygonShare(), f.meanStep(), f.meanJump(), f.jumpRatio()));
                for (final var prediction : Prediction.values()) {
                    final var p = prediction.ordinal();
                    line.append(String.format(java.util.Locale.ROOT, ",%.4f,%.4f", f.entropy()[p], f.zeroShare()[p]));
                }
                line.append(String.format(java.util.Locale.ROOT, ",%.5f", f.wideShare()));
                for (final var stage : Stage.values()) {
                    line.append(',').append(stage.of(row.reference()));
                }
                line.append(',').append(row.reference().name());
                others.forEach(other -> line.append(',').append(row.others().get(other).name()));
                out.write(line + "\n");
            }
        }
    }

    private static String abbreviation(Prediction predictor) {
        return switch (predictor) {
            case DELTA -> "delta";
            case QUAD_CORNER -> "qc";
            case LINE_CONTINUATION -> "lc";
            case QUAD_CORNER_LINE -> "qcl";
            case TURN_SCALAR -> "turn";
            case QUAD_CORNER_FRAME -> "qcf";
            case QUAD_CORNER_LINE_FRAME -> "qclf";
            case QUAD_CORNER_SHARED -> "qcs";
            case QUAD_CORNER_SHARED_FRAME -> "qcsf";
            case LINE_CONTINUATION_FRAME -> "lcf";
            case LINE_CONTINUATION_HEADING -> "lch";
            case LINE_CONTINUATION_FRAME_HEADING -> "lcfh";
            case LINE_CONTINUATION_SHARED -> "lcs";
            case LINE_CONTINUATION_SHARED_FRAME_HEADING -> "lcsfh";
        };
    }
}
