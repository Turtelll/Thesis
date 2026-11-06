package org.maplibre.mlt.research.measurement.report;

import org.maplibre.mlt.research.buffer.Encoding;
import org.maplibre.mlt.research.encoder.format.Composition;
import org.maplibre.mlt.research.encoder.format.PipelineEncoding;
import org.maplibre.mlt.research.encoder.stream.Transpose;
import org.maplibre.mlt.research.measurement.compression.Compressor;
import org.maplibre.mlt.research.measurement.compression.TileCompression;
import org.maplibre.mlt.research.registry.LayerCompositions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The oracle of {@code measure --oracle}: for every buffer, the composition (row) whose payload is
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
public final class OracleReport {

    /** The row name of the per-layer fixed composition in {@link TileCompression}. */
    public static final String PER_LAYER = "PER LAYER";

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

    /** The decisions a pipeline row is made of, in the order of {@link #optionsOf}. */
    public static final List<String> DECISIONS = List.of("reorder", "predictor", "segmentation", "byte terminal");

    /**
     * Every buffer's candidate sizes, flattened as {@code [candidate * compressors + compressor]};
     * {@code null} unless {@link #trackDropCost()} was called (it holds buffers x candidates longs).
     */
    private List<long[]> kept;
    /** Per compressor, per decision of {@link #DECISIONS}, the buffers whose tile choice took each option. */
    private final List<List<Map<String, Integer>>> tileChoices = new ArrayList<>();
    private final int[] tileBuffers;

    public OracleReport(List<Encoding> candidates, List<Compressor> compressors) {
        this.candidates = candidates;
        this.compressors = compressors;
        this.isolated = new long[candidates.size()][compressors.size()];
        this.tileBuffers = new int[compressors.size()];
        for (var c = 0; c < compressors.size(); c++) {
            wins.add(new LinkedHashMap<>());
            chosenBytes.add(new LinkedHashMap<>());
            components.add(new LinkedHashMap<>());
            byLayer.add(new TreeMap<>());
            final var perDecision = new ArrayList<Map<String, Integer>>();
            DECISIONS.forEach(d -> perDecision.add(new TreeMap<>()));
            tileChoices.add(perDecision);
        }
    }

    /**
     * The option of each decision a composition takes, in the order of {@link #DECISIONS}: the reorder,
     * the predictor, the segmentation and the byte terminal ({@code ESCAPE_INTERLEAVED_PLANES},
     * {@code ESCAPE} or {@code VARINT_SPLIT}). The names are those {@code --exclude} takes.
     */
    public static List<String> optionsOf(Composition config) {
        return List.of(config.reorder().name(), config.predictor().name(), config.segmentation().name(),
                config.width().name() + (config.transpose() == Transpose.NONE ? "" : "_" + config.transpose().name()));
    }

    /** Keeps every buffer's candidate sizes so {@link #printDropCost()} can be printed; call before the first {@link #offer}. */
    public void trackDropCost() {
        if (kept == null) {
            kept = new ArrayList<>();
        }
    }

    /** Records the composition the per-(tile, layer) oracle of compressor {@code c} chose for one buffer. */
    public void recordTileChoice(int c, Composition choice) {
        tileBuffers[c]++;
        final var options = optionsOf(choice);
        for (var d = 0; d < options.size(); d++) {
            tileChoices.get(c).get(d).merge(options.get(d), 1, Integer::sum);
        }
    }

    /** The row name the oracle of compressor {@code c} is recorded under in {@link TileCompression}. */
    public String label(int c) {
        return "ORACLE " + compressors.get(c).label();
    }

    /**
     * Records one buffer. {@code sizes[i][c]} is the size of candidate {@code i}'s payload under
     * compressor {@code c}; returns, per compressor, the index of the smallest (a tie goes to the
     * candidate listed first).
     */
    public int[] offer(String layer, long[][] sizes) {
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
        if (kept != null) {
            final var flat = new long[sizes.length * compressors.size()];
            for (var i = 0; i < sizes.length; i++) {
                for (var c = 0; c < compressors.size(); c++) {
                    flat[i * compressors.size() + c] = sizes[i][c];
                }
            }
            kept.add(flat);
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
    public void printByLayer() {
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
    public void printCompositions() {
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
     * Per compressor, which options of the grid can go: for every option of every decision, how often
     * the alone oracle and (where {@link #recordTileChoice} was called) the tile oracle pick it, the
     * share of the chosen bytes it carries and what the alone oracle loses when every composition using
     * it is removed; then a greedy backward elimination, always removing the option whose removal costs
     * the least (one option per decision always stays), with the cumulative loss and the compositions
     * left. Needs {@link #trackDropCost()}; the figures are on buffers compressed alone, an estimate of
     * what the tile oracle loses, which a run with {@code --exclude} confirms.
     */
    public void printDropCost() {
        if (kept == null || kept.isEmpty()) {
            return;
        }
        final var configs = new ArrayList<Composition>();
        final var options = new ArrayList<List<String>>();
        for (final var candidate : candidates) {
            if (!(candidate instanceof PipelineEncoding row)) {
                return;
            }
            configs.add(row.config());
            options.add(optionsOf(row.config()));
        }
        for (var c = 0; c < compressors.size(); c++) {
            printDropCost(c, configs, options);
        }
    }

    private void printDropCost(int c, List<Composition> configs, List<List<String>> options) {
        final var n = candidates.size();
        final var nc = compressors.size();
        final var all = new boolean[n];
        Arrays.fill(all, true);
        final var full = smallestTotal(c, all);

        final var values = new ArrayList<Set<String>>();
        final var chosen = new ArrayList<Map<String, long[]>>();
        for (var d = 0; d < DECISIONS.size(); d++) {
            final var seen = new LinkedHashSet<String>();
            for (final var option : options) {
                seen.add(option.get(d));
            }
            values.add(seen);
            chosen.add(new LinkedHashMap<>());
        }
        for (final var sizes : kept) {
            var best = 0;
            for (var i = 1; i < n; i++) {
                if (sizes[i * nc + c] < sizes[best * nc + c]) {
                    best = i;
                }
            }
            for (var d = 0; d < DECISIONS.size(); d++) {
                final var slot = chosen.get(d).computeIfAbsent(options.get(best).get(d), k -> new long[2]);
                slot[0]++;
                slot[1] += sizes[best * nc + c];
            }
        }

        System.out.printf("%n--- cost of dropping one option under %s: the alone oracle's bytes (%,d, %d buffers, %d"
                        + " compositions) with every composition using the option removed ---%n",
                compressors.get(c).label(), full, kept.size(), n);
        System.out.printf("%-14s %-40s %8s %8s %8s %12s %9s%n", "decision", "option", "alone", "tile", "bytes",
                "drop cost", "drop %");
        for (var d = 0; d < DECISIONS.size(); d++) {
            final var decision = d;
            final var ranked = new ArrayList<>(values.get(d));
            ranked.sort(Comparator.<String>comparingLong(v -> -chosen.get(decision).getOrDefault(v, new long[2])[0])
                    .thenComparing(Comparator.naturalOrder()));
            final var chosenBytes = chosen.get(d).values().stream().mapToLong(slot -> slot[1]).sum();
            for (final var value : ranked) {
                final var slot = chosen.get(d).getOrDefault(value, new long[2]);
                final var without = all.clone();
                var remaining = 0;
                for (var i = 0; i < n; i++) {
                    without[i] = !options.get(i).get(d).equals(value);
                    if (without[i]) {
                        remaining++;
                    }
                }
                final var tile = tileBuffers[c] == 0 ? "-" : String.format("%.1f%%",
                        100.0 * tileChoices.get(c).get(d).getOrDefault(value, 0) / tileBuffers[c]);
                final var total = remaining == 0 ? Long.MAX_VALUE : smallestTotal(c, without);
                System.out.printf("%-14s %-40s %7.1f%% %8s %7.1f%% %12s %9s%n", DECISIONS.get(d), value,
                        100.0 * slot[0] / kept.size(), tile, chosenBytes == 0 ? 0.0 : 100.0 * slot[1] / chosenBytes,
                        total == Long.MAX_VALUE ? "-" : String.format("%,d", total - full),
                        total == Long.MAX_VALUE ? "-" : String.format("%+.3f%%", 100.0 * (total - full) / full));
            }
        }

        System.out.printf("%n--- greedy elimination under %s: each step removes the option that costs the least"
                + " (alone-oracle bytes, one option per decision stays) ---%n", compressors.get(c).label());
        System.out.printf("%4s  %-14s %-40s %12s %12s %9s %7s %s%n", "step", "decision", "removed", "step cost",
                "cumulative", "cum %", "comps", "layer defaults lost");
        final var alive = new ArrayList<Set<String>>();
        values.forEach(set -> alive.add(new LinkedHashSet<>(set)));
        final var aliveCandidates = all.clone();
        var previous = full;
        for (var step = 1; ; step++) {
            var bestDecision = -1;
            String bestValue = null;
            var bestTotal = Long.MAX_VALUE;
            for (var d = 0; d < DECISIONS.size(); d++) {
                if (alive.get(d).size() < 2) {
                    continue;
                }
                for (final var value : alive.get(d)) {
                    final var without = aliveCandidates.clone();
                    for (var i = 0; i < n; i++) {
                        if (options.get(i).get(d).equals(value)) {
                            without[i] = false;
                        }
                    }
                    final var total = smallestTotal(c, without);
                    if (total < bestTotal) {
                        bestTotal = total;
                        bestDecision = d;
                        bestValue = value;
                    }
                }
            }
            if (bestDecision < 0) {
                break;
            }
            alive.get(bestDecision).remove(bestValue);
            var remaining = 0;
            for (var i = 0; i < n; i++) {
                if (options.get(i).get(bestDecision).equals(bestValue)) {
                    aliveCandidates[i] = false;
                }
                if (aliveCandidates[i]) {
                    remaining++;
                }
            }
            var lost = 0;
            for (final var layer : LayerCompositions.fittedLayers()) {
                final var index = configs.indexOf(LayerCompositions.forLayer(layer));
                if (index >= 0 && !aliveCandidates[index]) {
                    lost++;
                }
            }
            System.out.printf("%4d  %-14s %-40s %12s %12s %+8.3f%% %7d %d of %d%n", step, DECISIONS.get(bestDecision),
                    bestValue, String.format("%,d", bestTotal - previous), String.format("%,d", bestTotal - full),
                    100.0 * (bestTotal - full) / full, remaining, lost, LayerCompositions.fittedLayers().size());
            previous = bestTotal;
        }
    }

    /** The bytes of every buffer's smallest alive candidate under compressor {@code c}, summed. */
    private long smallestTotal(int c, boolean[] alive) {
        final var nc = compressors.size();
        var total = 0L;
        for (final var sizes : kept) {
            var best = Long.MAX_VALUE;
            for (var i = 0; i < alive.length; i++) {
                if (alive[i] && sizes[i * nc + c] < best) {
                    best = sizes[i * nc + c];
                }
            }
            if (best == Long.MAX_VALUE) {
                return Long.MAX_VALUE;
            }
            total += best;
        }
        return total;
    }

    /**
     * Per backend and layer, the one fixed composition with the smallest total over the layer's buffers
     * (each compressed alone), among the candidates {@code allowed} for that layer, against the first
     * candidate (the previous default). This is how the per-layer composition is fitted; a table that
     * differs between backends is printed as it is.
     */
    public void printBestFixedPerLayer(java.util.function.BiPredicate<String, Encoding> allowed) {
        printBestFixedPerLayer(allowed, layer -> 0, "first row");
    }

    /**
     * {@link #printBestFixedPerLayer(java.util.function.BiPredicate)} against the candidate
     * {@code reference} names per layer (e.g. the layer's own fixed composition), labelled {@code
     * referenceLabel}; a winner equal to it is marked {@code (=)}.
     */
    public void printBestFixedPerLayer(java.util.function.BiPredicate<String, Encoding> allowed,
                                java.util.function.ToIntFunction<String> reference, String referenceLabel) {
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
                final var first = reference.applyAsInt(layer);
                sumBest += totals[best][c];
                sumFirst += totals[first][c];
                System.out.printf("%-20s %5d buffers  %,12d vs %,12d %s %+7.2f%%  %s%s%n", layer,
                        layerBuffers.get(layer), totals[best][c], totals[first][c], referenceLabel,
                        100.0 * (totals[best][c] - totals[first][c]) / totals[first][c], candidates.get(best).name(),
                        best == first ? " (=)" : "");
            }
            System.out.printf("%-20s %5d buffers  %,12d vs %,12d %s %+7.2f%%%n", "all layers", buffers,
                    sumBest, sumFirst, referenceLabel, 100.0 * (sumBest - sumFirst) / sumFirst);
        }
    }

    /**
     * The oracle against the reference, per tile under each backend: both baselines, the best single
     * composition (the row with the smallest total under that backend) and the oracle of that backend.
     */
    public void printComparison(TileCompression perTile, long reference, List<String> baselineNames) {
        printComparison(perTile, reference, baselineNames, List.of());
    }

    /**
     * {@link #printComparison(TileCompression, long, List)} plus the per-(tile, layer) oracles chosen on the
     * compressed tile, {@code tileOracles} holding one row name per compressor ({@code --step best}), and
     * each oracle's change against the fixed composition per layer.
     */
    public void printComparison(TileCompression perTile, long reference, List<String> baselineNames,
                         List<String> tileOracles) {
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
        if (!tileOracles.isEmpty()) {
            row("ORACLE TILE (descent on the tile)", width, reference,
                    c -> totals.get(tileOracles.get(c))[TileCompression.GZIP + c]);
        }

        gainLine("oracle over best single composition", width,
                c -> totals.get(label(c))[TileCompression.GZIP + c],
                c -> totals.get(candidates.get(best[c]).name())[TileCompression.GZIP + c]);
        if (totals.containsKey(PER_LAYER)) {
            gainLine("oracle over per layer", width, c -> totals.get(label(c))[TileCompression.GZIP + c],
                    c -> totals.get(PER_LAYER)[TileCompression.GZIP + c]);
        }
        if (!tileOracles.isEmpty()) {
            gainLine("oracle tile over best single", width,
                    c -> totals.get(tileOracles.get(c))[TileCompression.GZIP + c],
                    c -> totals.get(candidates.get(best[c]).name())[TileCompression.GZIP + c]);
            if (totals.containsKey(PER_LAYER)) {
                gainLine("oracle tile over per layer", width,
                        c -> totals.get(tileOracles.get(c))[TileCompression.GZIP + c],
                        c -> totals.get(PER_LAYER)[TileCompression.GZIP + c]);
            }
        }

        System.out.printf("%nbest single composition per backend:%n");
        for (var c = 0; c < best.length; c++) {
            System.out.printf("  %-9s %s%n", compressors.get(c).label(), candidates.get(best[c]).name());
        }
        System.out.printf("%nref = the smaller baseline under gzip (%,d); every change, in every column, is against it.%n"
                + "The oracle picks on each buffer compressed alone and is charged nothing for the choice, so it is an%n"
                + "upper bound. The per-tile figure is the one delivered: a tile compresses the concatenation of its%n"
                + "buffers, so the chosen payloads can compress worse together than the choice assumed.%n", reference);
    }

    private void gainLine(String label, int width, java.util.function.IntToLongFunction bytes,
                          java.util.function.IntToLongFunction against) {
        final var line = new StringBuilder(String.format("%-" + width + "s", label));
        for (var c = 0; c < compressors.size(); c++) {
            final var base = against.applyAsLong(c);
            line.append(String.format(" %14s %+8.2f%%", "", 100.0 * (bytes.applyAsLong(c) - base) / base));
        }
        System.out.println(line);
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
