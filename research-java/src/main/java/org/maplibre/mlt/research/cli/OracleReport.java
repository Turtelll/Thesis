package org.maplibre.mlt.research.cli;

import org.maplibre.mlt.research.encoding.Encoding;
import org.maplibre.mlt.research.pipeline.PipelineEncoding;
import org.maplibre.mlt.research.pipeline.Transpose;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The oracle of {@code PipelineCli --oracle}: for every buffer, the composition (row) whose payload is
 * smallest under a backend, one oracle per backend, with a tally of what was chosen and how often.
 * Where the per-row tables say what one composition does to a tileset, this says which combination of
 * compositions compresses best.
 *
 * <p>It is an upper bound: it picks on the buffer compressed alone and charges nothing for the choice
 * (the pipeline's header already carries every choice but the reorder, which would cost one bit a
 * buffer). A tile compresses the concatenation of its buffers, so the chosen payloads can compress
 * worse together than the choice predicted; {@link #printComparison} therefore sizes the oracle the
 * way the other rows are sized, per tile, next to the best single composition.
 */
final class OracleReport {

    /** The row name of the per-layer fixed composition in {@link TileCompression}. */
    static final String PER_LAYER = "PER LAYER";

    private final List<Encoding> candidates;
    private final List<Compressor> compressors;
    private final List<Map<String, Integer>> wins = new ArrayList<>();
    private final List<Map<String, Long>> chosenBytes = new ArrayList<>();
    /** Per compressor, per decision of a pipeline row ("reorder", "predictor", ..), the buffers that chose it. */
    private final List<Map<String, Map<String, Integer>>> components = new ArrayList<>();
    /** The same per layer: compressor, layer, decision, value. */
    private final List<Map<String, Map<String, Map<String, Integer>>>> byLayer = new ArrayList<>();
    private final Map<String, Integer> layerBuffers = new TreeMap<>();
    /** Per layer, per candidate, per compressor: that layer's buffers compressed alone, summed. */
    private final Map<String, long[][]> layerIsolated = new TreeMap<>();
    /** Per candidate, per compressor: every buffer compressed alone, summed. */
    private final long[][] isolated;
    private int buffers;

    OracleReport(List<Encoding> candidates, List<Compressor> compressors) {
        this.candidates = candidates;
        this.compressors = compressors;
        this.isolated = new long[candidates.size()][compressors.size()];
        for (var c = 0; c < compressors.size(); c++) {
            wins.add(new LinkedHashMap<>());
            chosenBytes.add(new LinkedHashMap<>());
            components.add(new LinkedHashMap<>());
            byLayer.add(new TreeMap<>());
        }
    }

    /** The row name the oracle of compressor {@code c} is recorded under in {@link TileCompression}. */
    String label(int c) {
        return "ORACLE " + compressors.get(c).label();
    }

    /**
     * Records one buffer. {@code sizes[i][c]} is the size of candidate {@code i}'s payload under
     * compressor {@code c}; returns, per compressor, the index of the smallest (a tie goes to the
     * candidate listed first).
     */
    int[] offer(String layer, long[][] sizes) {
        buffers++;
        layerBuffers.merge(layer, 1, Integer::sum);
        final var chosen = new int[compressors.size()];
        for (var c = 0; c < chosen.length; c++) {
            var best = 0;
            for (var i = 1; i < sizes.length; i++) {
                if (sizes[i][c] < sizes[best][c]) {
                    best = i;
                }
            }
            chosen[c] = best;
            final var name = candidates.get(best).name();
            wins.get(c).merge(name, 1, Integer::sum);
            chosenBytes.get(c).merge(name, sizes[best][c], Long::sum);
            if (candidates.get(best) instanceof PipelineEncoding row) {
                final var config = row.config();
                tally(c, layer, "reorder", String.valueOf(config.reorder()));
                tally(c, layer, "predictor", String.valueOf(config.predictor()));
                tally(c, layer, "segmentation", String.valueOf(config.segmentation()));
                tally(c, layer, "byte terminal", config.width()
                        + (config.transpose() == Transpose.NONE ? "" : " + " + config.transpose()));
            } else {
                tally(c, layer, "reorder", "(not a pipeline row)");
            }
        }
        final var inLayer = layerIsolated.computeIfAbsent(
                layer, k -> new long[candidates.size()][compressors.size()]);
        for (var i = 0; i < sizes.length; i++) {
            for (var c = 0; c < chosen.length; c++) {
                isolated[i][c] += sizes[i][c];
                inLayer[i][c] += sizes[i][c];
            }
        }
        return chosen;
    }

    private void tally(int c, String layer, String component, String value) {
        components.get(c).computeIfAbsent(component, k -> new TreeMap<>()).merge(value, 1, Integer::sum);
        byLayer.get(c).computeIfAbsent(layer, k -> new LinkedHashMap<>())
                .computeIfAbsent(component, k -> new TreeMap<>()).merge(value, 1, Integer::sum);
    }

    /**
     * Per compressor and layer, the share of that layer's buffers choosing each value of each decision:
     * whether the oracle really picks the same predictor everywhere or follows the layer.
     */
    void printByLayer() {
        for (var c = 0; c < compressors.size(); c++) {
            System.out.printf("%n--- oracle choices by layer under %s: share of the layer's buffers per decision ---%n",
                    compressors.get(c).label());
            final var layers = new ArrayList<>(byLayer.get(c).keySet());
            layers.sort(Comparator.<String>comparingInt(layerBuffers::get).reversed());
            for (final var layer : layers) {
                final var n = layerBuffers.get(layer);
                System.out.printf("%s (%,d buffers)%n", layer, n);
                byLayer.get(c).get(layer).forEach((component, values) -> {
                    final var line = new StringBuilder(String.format("  %-14s", component));
                    values.entrySet().stream()
                            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                            .forEach(e -> line.append(String.format("  %s %.0f%%", e.getKey(), 100.0 * e.getValue() / n)));
                    System.out.println(line);
                });
            }
        }
    }

    /** Per compressor: what the oracle chose, how many buffers each, and the decisions it is made of. */
    void printCompositions() {
        for (var c = 0; c < compressors.size(); c++) {
            final var label = compressors.get(c).label();
            final var total = chosenBytes.get(c).values().stream().mapToLong(Long::longValue).sum();
            final var width = Math.max("composition".length(),
                    wins.get(c).keySet().stream().mapToInt(String::length).max().orElse(0));
            System.out.printf("%n--- oracle choices under %s: smallest of %d compositions per buffer, %d buffers"
                    + " (bytes = each buffer compressed alone) ---%n", label, candidates.size(), buffers);
            System.out.printf("%-" + width + "s %9s %7s %14s %7s%n", "composition", "buffers", "share", "bytes", "bytes%");
            final var ranked = new ArrayList<>(wins.get(c).keySet());
            ranked.sort(Comparator.<String>comparingInt(wins.get(c)::get)
                    .thenComparingLong(chosenBytes.get(c)::get).reversed());
            for (final var name : ranked) {
                System.out.printf("%-" + width + "s %,9d %6.1f%% %,14d %6.1f%%%n", name, wins.get(c).get(name),
                        100.0 * wins.get(c).get(name) / buffers, chosenBytes.get(c).get(name),
                        total == 0 ? 0.0 : 100.0 * chosenBytes.get(c).get(name) / total);
            }
            System.out.printf("%-" + width + "s %,9d %7s %,14d%n", "(" + ranked.size() + " of " + candidates.size()
                    + " compositions chosen at least once)", buffers, "", total);

            System.out.printf("by decision, share of buffers:%n");
            components.get(c).forEach((component, values) -> {
                final var line = new StringBuilder(String.format("  %-14s", component));
                values.entrySet().stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                        .forEach(e -> line.append(String.format("  %s %.1f%%", e.getKey(), 100.0 * e.getValue() / buffers)));
                System.out.println(line);
            });
        }
    }

    /**
     * Per backend and layer, the one fixed composition with the smallest total over the layer's buffers
     * (each compressed alone), among the candidates {@code allowed} for that layer, against the first
     * candidate (the previous default). This is how the per-layer composition is fitted; a table that
     * differs between backends is printed as it is.
     */
    void printBestFixedPerLayer(java.util.function.BiPredicate<String, Encoding> allowed) {
        for (var c = 0; c < compressors.size(); c++) {
            System.out.printf("%n--- best fixed composition per layer under %s (layer's buffers compressed alone;"
                    + " reorder only where it is allowed) ---%n", compressors.get(c).label());
            final var layers = new ArrayList<>(layerIsolated.keySet());
            layers.sort(Comparator.<String>comparingInt(layerBuffers::get).reversed());
            var sumBest = 0L;
            var sumFirst = 0L;
            for (final var layer : layers) {
                final var totals = layerIsolated.get(layer);
                var best = -1;
                for (var i = 0; i < candidates.size(); i++) {
                    if (allowed.test(layer, candidates.get(i))
                            && (best < 0 || totals[i][c] < totals[best][c])) {
                        best = i;
                    }
                }
                sumBest += totals[best][c];
                sumFirst += totals[0][c];
                System.out.printf("%-20s %5d buffers  %,12d vs %,12d first row %+7.2f%%  %s%n", layer,
                        layerBuffers.get(layer), totals[best][c], totals[0][c],
                        100.0 * (totals[best][c] - totals[0][c]) / totals[0][c], candidates.get(best).name());
            }
            System.out.printf("%-20s %5d buffers  %,12d vs %,12d first row %+7.2f%%%n", "all layers", buffers,
                    sumBest, sumFirst, 100.0 * (sumBest - sumFirst) / sumFirst);
        }
    }

    /**
     * The oracle against the reference, per tile under each backend: both baselines, the best single
     * composition (the row with the smallest total under that backend) and the oracle of that backend.
     */
    void printComparison(TileCompression perTile, long reference, List<String> baselineNames) {
        final var totals = perTile.totals();
        System.out.printf("%n--- oracle vs ref, per tile (payload bytes; each of %d tiles compressed on its own) ---%n",
                perTile.tiles());
        final var width = 34;
        final var header = new StringBuilder(String.format("%-" + width + "s", "row"));
        for (final var compressor : compressors) {
            header.append(String.format(" %14s %9s", compressor.label(), "change"));
        }
        System.out.println(header);
        for (final var name : baselineNames) {
            row(name + (totals.get(name)[TileCompression.GZIP] == reference ? " (ref)" : ""), width, reference,
                    c -> totals.get(name)[TileCompression.GZIP + c]);
        }

        final var best = new int[compressors.size()];
        for (var c = 0; c < best.length; c++) {
            final var column = TileCompression.GZIP + c;
            var arg = 0;
            for (var i = 1; i < candidates.size(); i++) {
                if (totals.get(candidates.get(i).name())[column] < totals.get(candidates.get(arg).name())[column]) {
                    arg = i;
                }
            }
            best[c] = arg;
        }
        row("best single composition", width, reference,
                c -> totals.get(candidates.get(best[c]).name())[TileCompression.GZIP + c]);
        if (totals.containsKey(PER_LAYER)) {
            row("fixed composition per layer", width, reference, c -> totals.get(PER_LAYER)[TileCompression.GZIP + c]);
        }
        row("ORACLE (best of " + candidates.size() + " per buffer)", width, reference,
                c -> totals.get(label(c))[TileCompression.GZIP + c]);

        final var gain = new StringBuilder(String.format("%-" + width + "s", "oracle over best single composition"));
        for (var c = 0; c < compressors.size(); c++) {
            final var single = totals.get(candidates.get(best[c]).name())[TileCompression.GZIP + c];
            final var oracle = totals.get(label(c))[TileCompression.GZIP + c];
            gain.append(String.format(" %14s %+8.2f%%", "", 100.0 * (oracle - single) / single));
        }
        System.out.println(gain);

        System.out.printf("%nbest single composition per backend:%n");
        for (var c = 0; c < best.length; c++) {
            System.out.printf("  %-9s %s%n", compressors.get(c).label(), candidates.get(best[c]).name());
        }
        System.out.printf("%nref = the smaller baseline under gzip (%,d); every change, in every column, is against it.%n"
                + "The oracle picks on each buffer compressed alone and is charged nothing for the choice, so it is an%n"
                + "upper bound. The per-tile figure is the one delivered: a tile compresses the concatenation of its%n"
                + "buffers, so the chosen payloads can compress worse together than the choice assumed.%n", reference);
    }

    private void row(String label, int width, long reference, java.util.function.IntToLongFunction bytes) {
        final var line = new StringBuilder(String.format("%-" + width + "s", label));
        for (var c = 0; c < compressors.size(); c++) {
            final var value = bytes.applyAsLong(c);
            line.append(String.format(" %,14d %9s", value, value == reference ? "ref"
                    : String.format("%+.2f%%", 100.0 * (value - reference) / reference)));
        }
        System.out.println(line);
    }
}
