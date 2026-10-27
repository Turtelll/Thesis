package org.maplibre.mlt.research.cli;

import org.maplibre.mlt.research.encoding.Baselines;
import org.maplibre.mlt.research.encoding.Encoding;
import org.maplibre.mlt.research.extraction.ExtractionReader;
import org.maplibre.mlt.research.extraction.ExtractionWriter.ManifestEntry;
import org.maplibre.mlt.research.pipeline.BufferFeatures;
import org.maplibre.mlt.research.pipeline.PipelineConfig;
import org.maplibre.mlt.research.pipeline.PipelineEncoding;
import org.maplibre.mlt.research.pipeline.PipelineRegistry;
import org.maplibre.mlt.research.pipeline.PipelineStats;
import org.maplibre.mlt.research.pipeline.Reorder;
import org.maplibre.mlt.research.pipeline.Selector;
import org.maplibre.mlt.research.pipeline.StageRules;
import org.maplibre.mlt.research.pipeline.TileSelector;
import org.maplibre.mlt.research.pipeline.Verification;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;

/**
 * The measurement harness: every extracted (tile, layer) buffer is encoded by the two baselines and
 * by the rows of one implementation step of the pipeline ({@code
 * org.maplibre.mlt.research.pipeline}), round-tripped, and sized as a browser receives it — every
 * tile's buffers concatenated and compressed on their own, per backend. Every percentage is against
 * the smaller of the two baselines under gzip.
 *
 * <p>It lives in {@code cli} only to reuse {@link TileCompression} and {@link Compressor}, which are
 * package-private.
 *
 * <p>{@code --oracle} (implied by {@code --step oracle}) replaces the per-row tables by the per-buffer
 * best of the rows: what it chose, how many times, and the result against the reference ({@link
 * OracleReport}).
 *
 * <p>{@code --step tile} adds one {@link TileSelector} per backend: a composition per (tile, layer),
 * chosen on the compressed tile, beside the fixed per-layer default.
 *
 * <p>Usage: {@code [extractionDir] [--step s0|s1|...|tile|ladder|oracle|all] [--oracle] [--rows a,b]
 * [--layer <name>] [--by-zoom] [--by-geometry] [--stats] [--with-old] [--csv <file>] [--brotli-quality <0-11>]
 * [--limit-tiles <n>]}.
 */
public final class PipelineCli {

    private PipelineCli() {
    }

    private static final String DEFAULT_IN_DIR = "../data/extractedBaseline";

    private static final int TOP_LAYERS = 8;

    /** Old-pipeline rows shown beside the new ones under {@code --with-old}; read-only references. */
    private static final List<String> OLD_ROWS = List.of(
            "QUAD_CORNER", "QUAD_CORNER_SHARED_RING_CANONICAL_REORDER", "LINE_CONTINUATION_SHARED_DICT");

    public static void main(String[] args) throws IOException {
        final var opts = Options.parse(args);
        final var inDir = Path.of(opts.inDir);
        final var manifest = Corpus.tileLimited(Corpus.groupedByTile(ExtractionReader.readManifest(inDir)), opts.limitTiles);

        final var baselines = Baselines.baseline();
        final var baselineNames = baselines.stream().map(Encoding::name).toList();
        final var rows = new ArrayList<Encoding>(rowsFor(opts));
        if (opts.step.equals("selector") && opts.rows == null) {
            rows.addAll(selectors(opts.brotliQuality));
        }
        if (opts.withOld) {
            for (final var name : OLD_ROWS) {
                Baselines.all().stream().filter(e -> e.name().equals(name)).findFirst().ifPresent(rows::add);
            }
        }
        final var encodings = new ArrayList<Encoding>(baselines);
        encodings.addAll(rows);
        final var compressors = Compressor.all(opts.brotliQuality);
        // --step layer: the previous default (one row for all layers) beside the per-layer default
        final var layerStep = opts.step.equals("layer");
        // --step tile: the per-layer default beside one choice per (tile, layer), trialled on the tile
        final var tileStep = opts.step.equals("tile");
        final var tileSelectors = tileStep ? tileSelectors(compressors, opts.trials) : List.<TileSelector>of();
        // what the first tile selector (trial compression under gzip unless --trials says otherwise) chose,
        // against what cheap rules and the compression-free selectors would have chosen
        final var correlation = tileSelectors.isEmpty() ? null : new ChoiceCorrelation(tileSelectors.get(0).name());
        final var names = new ArrayList<>(encodings.stream().map(Encoding::name).toList());
        if (layerStep || tileStep) {
            names.add(OracleReport.PER_LAYER);
        }
        // a tile selection is only known once the tile is complete, so it has no per-buffer CSV column
        final var csvNames = List.copyOf(names);
        tileSelectors.forEach(selector -> names.add(selector.name()));

        final var probes = opts.stats ? PipelineStats.probes(opts.step) : List.<PipelineStats.Probe>of();
        final var perTile = new TileCompression(compressors);
        final var breakdown = new Breakdown();
        final var isolated = new LinkedHashMap<String, long[]>();
        final var selection = new SelectionReport();
        // --oracle: the per-buffer best of the rows, instead of one table row per composition
        final var oracle = opts.oracle() ? new OracleReport(rows, compressors) : null;
        final var plain = oracle == null ? encodings : baselines;

        Writer csv = null;
        if (opts.csvFile != null) {
            final var csvPath = Path.of(opts.csvFile);
            if (csvPath.getParent() != null) {
                Files.createDirectories(csvPath.getParent());
            }
            csv = Files.newBufferedWriter(csvPath);
            csv.write("z,x,y,layerName,vertexCount," + String.join(",", csvNames) + "\n");
        }

        final var tileEntries = new ArrayList<ManifestEntry>();
        final var tileContexts = new ArrayList<BufferContext>();
        String currentTile = null;
        var currentZoom = 0;
        var buffers = 0;
        long rawTotal = 0;
        try {
            for (final ManifestEntry entry : manifest) {
                if (opts.layer != null && !opts.layer.equals(entry.layerName())) {
                    continue;
                }
                final var ctx = ExtractionReader.readContext(inDir, entry);
                if (ctx.vertices().isEmpty()) {
                    continue;
                }
                final var tileKey = Corpus.tileKey(entry);
                if (!tileKey.equals(currentTile)) {
                    selectOnTile(tileSelectors, tileEntries, tileContexts, opts.verify(), perTile, breakdown, selection,
                            correlation);
                    perTile.endTile(currentZoom);
                    currentTile = tileKey;
                    currentZoom = entry.z();
                }
                buffers++;
                rawTotal += (long) entry.vertexCount() * 2 * Integer.BYTES;
                for (final var probe : probes) {
                    probe.accept(entry, ctx);
                }

                final var geometry = dominantGeometryType(ctx.geometryTypes());
                breakdown.buffer(entry.layerName(), geometry);
                final var line = new StringBuilder();
                for (final var encoding : plain) {
                    final var encoded = encoding.encode(ctx);
                    if (opts.verify()) {
                        Verification.roundTrip(entry.z() + "/" + entry.x() + "/" + entry.y() + " " + entry.layerName(), ctx, encoding, encoded);
                    }
                    perTile.add(encoding.name(), encoded.payload());
                    if (encoding instanceof Selector) {
                        selection.record(encoding.name(), entry, Selector.choiceOf(encoded.payload()));
                    }
                    final var gzip = Compressor.GZIP.size(encoded.payload());
                    breakdown.add(entry.layerName(), geometry, encoding.name(), gzip);
                    if (opts.isolated) {
                        final var sums = isolated.computeIfAbsent(encoding.name(), k -> new long[compressors.size()]);
                        for (var c = 0; c < sums.length; c++) {
                            sums[c] += compressors.get(c).size(encoded.payload());
                        }
                    }
                    line.append(',').append(gzip);
                }
                if (tileStep) {
                    tileEntries.add(entry);
                    tileContexts.add(ctx);
                }
                if (layerStep || tileStep) {
                    final var payload = new PipelineEncoding(PipelineRegistry.forLayer(entry.layerName()))
                            .encode(ctx).payload();
                    perTile.add(OracleReport.PER_LAYER, payload);
                    final var gzip = Compressor.GZIP.size(payload);
                    breakdown.add(entry.layerName(), geometry, OracleReport.PER_LAYER, gzip);
                    line.append(',').append(gzip);
                }
                if (oracle != null) {
                    oracleBuffer(oracle, entry.layerName(), rows, ctx, compressors, perTile, line);
                }
                if (csv != null) {
                    csv.write(String.join(",",
                            String.valueOf(entry.z()), String.valueOf(entry.x()), String.valueOf(entry.y()),
                            entry.layerName(), String.valueOf(entry.vertexCount())) + line + "\n");
                }
            }
            selectOnTile(tileSelectors, tileEntries, tileContexts, opts.verify(), perTile, breakdown, selection,
                    correlation);
            perTile.endTile(currentZoom);
        } finally {
            if (csv != null) {
                csv.close();
            }
        }

        if (buffers == 0) {
            System.out.println("No buffers matched.");
            return;
        }
        System.out.printf("%d buffers in %d tiles, %s raw bytes; step %s, %s%n",
                buffers, perTile.tiles(), String.format("%,d", rawTotal), opts.step,
                opts.verify() ? "all buffers round-tripped"
                        : "NOT round-tripped here (sizes only; run ./gradlew test for the lossless check)");

        final var reference = baselineNames.stream()
                .mapToLong(name -> perTile.totals().get(name)[TileCompression.GZIP])
                .min().orElse(0);
        if (oracle != null) {
            // what combination compresses best, not what each composition does to the tileset: no
            // per-encoding, per-layer or ladder table
            oracle.printCompositions();
            oracle.printByLayer();
            oracle.printBestFixedPerLayer((layer, candidate) ->
                    !(candidate instanceof PipelineEncoding row)
                            || row.config().reorder() == Reorder.NONE
                            || PipelineRegistry.reorderPays(layer));
            oracle.printComparison(perTile, reference, baselineNames);
            if (opts.byZoom) {
                final var shown = new ArrayList<>(baselineNames);
                shown.add(OracleReport.PER_LAYER);
                for (var c = 0; c < compressors.size(); c++) {
                    shown.add(oracle.label(c));
                }
                zoomTable(shown, baselineNames, perTile);
            }
        } else {
            if (opts.step.equals("ladder") && opts.rows == null) {
                ladderTable(perTile, reference);
            }
            perEncoding(names, perTile, reference);
            groupTable("by layer", names, baselineNames, breakdown.byLayer, breakdown.layerBuffers, TOP_LAYERS);
            if (opts.byGeometry) {
                groupTable("by dominant geometry type", names, baselineNames, breakdown.byGeometry,
                        breakdown.geometryBuffers, Integer.MAX_VALUE);
            }
            if (opts.byZoom) {
                zoomTable(names, baselineNames, perTile);
            }
            if (opts.isolated) {
                isolatedTable(names, baselineNames, isolated, compressors, perTile);
            }
        }
        for (final var probe : probes) {
            probe.report();
        }
        if (!selection.isEmpty()) {
            selection.print();
        }
        if (correlation != null && !correlation.isEmpty()) {
            correlation.print();
            if (opts.featuresCsv != null) {
                correlation.writeCsv(Path.of(opts.featuresCsv));
                System.out.printf("%nWrote %s (features and choices per buffer)%n", opts.featuresCsv);
            }
        }
        if (opts.csvFile != null) {
            System.out.printf("%nWrote %s (gzip bytes per buffer)%n", opts.csvFile);
        }
    }

    /** One candidate's payload for the buffer at hand and its size under each compressor. */
    private record Candidate(byte[] payload, long[] sizes) {
    }

    /**
     * Encodes the buffer with every candidate (in parallel: the rows are stateless), sizes each payload
     * alone under every compressor, hands the sizes to the oracle and adds each oracle's chosen payload
     * to the per-tile totals under its own row. The candidates go into the totals too, which is what
     * names the best single composition. Appends the candidates' gzip sizes to the CSV line.
     */
    private static void oracleBuffer(
            OracleReport oracle,
            String layer,
            List<Encoding> rows,
            org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext ctx,
            List<Compressor> compressors,
            TileCompression perTile,
            StringBuilder line) {
        final var results = IntStream.range(0, rows.size()).parallel().mapToObj(i -> {
            final var payload = rows.get(i).encode(ctx).payload();
            final var sizes = new long[compressors.size()];
            for (var c = 0; c < sizes.length; c++) {
                sizes[c] = compressors.get(c).size(payload);
            }
            return new Candidate(payload, sizes);
        }).toList();
        final var sizes = results.stream().map(Candidate::sizes).toArray(long[][]::new);
        final var chosen = oracle.offer(layer, sizes);
        for (var i = 0; i < rows.size(); i++) {
            perTile.add(rows.get(i).name(), results.get(i).payload());
            line.append(',').append(sizes[i][0]);
        }
        for (var c = 0; c < chosen.length; c++) {
            perTile.add(oracle.label(c), results.get(chosen[c]).payload());
        }
        // the pipeline's default: the layer's fixed composition (not a choice made on this buffer)
        perTile.add(OracleReport.PER_LAYER,
                new PipelineEncoding(PipelineRegistry.forLayer(layer)).encode(ctx).payload());
    }

    /**
     * Runs every tile selector over the tile collected since the last call, round-trips each chosen
     * buffer and adds it to the per-tile totals, the per-layer gzip table and the selection report
     * under the selector's row, and each buffer's features with every selector's choice to the
     * correlation; then empties the collection.
     */
    private static void selectOnTile(
            List<TileSelector> selectors,
            List<ManifestEntry> entries,
            List<BufferContext> contexts,
            boolean verify,
            TileCompression perTile,
            Breakdown breakdown,
            SelectionReport selection,
            ChoiceCorrelation correlation) {
        if (entries.isEmpty()) {
            return;
        }
        final var layers = entries.stream().map(ManifestEntry::layerName).toList();
        final var chosen = new ArrayList<Map<String, PipelineConfig>>();
        entries.forEach(entry -> chosen.add(new LinkedHashMap<>()));
        for (final var selector : selectors) {
            final var choices = selector.select(layers, contexts);
            for (var b = 0; b < choices.size(); b++) {
                chosen.get(b).put(selector.name(), choices.get(b).config());
                final var entry = entries.get(b);
                final var choice = choices.get(b);
                if (verify) {
                    Verification.roundTrip(entry.z() + "/" + entry.x() + "/" + entry.y() + " " + entry.layerName(),
                            contexts.get(b), new PipelineEncoding(choice.config()), choice.encoded());
                }
                final var payload = choice.encoded().payload();
                perTile.add(selector.name(), payload);
                breakdown.add(entry.layerName(), dominantGeometryType(contexts.get(b).geometryTypes()), selector.name(),
                        Compressor.GZIP.size(payload));
                selection.record(selector.name(), entry, choice.config());
            }
        }
        if (correlation != null) {
            for (var b = 0; b < entries.size(); b++) {
                correlation.record(entries.get(b), BufferFeatures.of(contexts.get(b)), chosen.get(b));
            }
        }
        entries.clear();
        contexts.clear();
    }

    /**
     * One tile selector per backend, trial-compressing the tile, then two that size it with a
     * byte-entropy estimate and compress nothing; {@code trials} (comma-separated label prefixes,
     * e.g. {@code gzip,order}) keeps a subset, in the order listed here. The first one kept is the
     * reference of the choice correlation.
     */
    private static List<TileSelector> tileSelectors(List<Compressor> compressors, String trials) {
        final var all = new ArrayList<TileSelector>();
        for (final var compressor : compressors) {
            all.add(new TileSelector(compressor.label(), compressor::size));
        }
        all.add(new TileSelector("order0", TileCompression::order0Bytes));
        all.add(new TileSelector("order1", TileCompression::order1Bytes));
        // reorder and class streams fixed by rule, predictor x terminal (12) searched
        all.add(new TileSelector("rules_order1", TileCompression::order1Bytes, StageRules::restrict));
        all.add(new TileSelector("rules_gzip", Compressor.GZIP::size, StageRules::restrict));
        if (trials == null) {
            return all;
        }
        final var wanted = List.of(trials.split(","));
        final var kept = all.stream()
                .filter(selector -> wanted.stream().anyMatch(selector.backend()::startsWith))
                .toList();
        if (kept.isEmpty()) {
            throw new IllegalArgumentException("--trials " + trials + " matches none of "
                    + all.stream().map(TileSelector::backend).toList());
        }
        return kept;
    }

    /**
     * The per-buffer selection, once per backend it trial-compresses with, without and with the
     * reorder; its trial sizes the buffer alone ({@link Selector}).
     */
    private static List<Encoding> selectors(int brotliQuality) {
        final var rows = new ArrayList<Encoding>();
        for (final var reorder : List.of(Reorder.NONE, Reorder.HILBERT_CENTROID_ROT)) {
            for (final var compressor : Compressor.all(brotliQuality)) {
                rows.add(new Selector(reorder, compressor.label(), compressor::size));
            }
        }
        return rows;
    }

    private static List<Encoding> rowsFor(Options opts) {
        if (opts.rows != null) {
            final var wanted = new LinkedHashSet<>(List.of(opts.rows.split(",")));
            final var found = PipelineRegistry.all().stream().filter(e -> wanted.contains(e.name())).toList();
            if (found.size() != wanted.size()) {
                throw new IllegalArgumentException("unknown row among " + wanted + "; known: "
                        + PipelineRegistry.all().stream().map(Encoding::name).toList());
            }
            return found;
        }
        if (opts.step.equals("all")) {
            return PipelineRegistry.all();
        }
        return PipelineRegistry.step(opts.step);
    }

    // --- reporting ----------------------------------------------------------

    /**
     * The build-up of the pipeline (--step ladder): per stage the bytes under each backend, the change
     * against the smaller baseline and the change this stage made against the stage before it.
     */
    private static void ladderTable(TileCompression perTile, long reference) {
        final var stages = PipelineRegistry.ladder();
        final var names = PipelineRegistry.step("ladder").stream().map(Encoding::name).toList();
        final var compressors = perTile.compressors();
        final var width = stages.stream().mapToInt(s -> s.label().length()).max().orElse(0);
        System.out.printf("%n--- ladder: the pipeline built up one decision at a time (per tile, payload bytes) ---%n");
        final var header = new StringBuilder(String.format("%-" + width + "s", "stage"));
        for (final var compressor : compressors) {
            header.append(String.format(" %12s %8s %8s", compressor.label(), "vs ref", "vs prev"));
        }
        System.out.println(header);
        for (var i = 0; i < stages.size(); i++) {
            final var row = new StringBuilder(String.format("%-" + width + "s", stages.get(i).label()));
            final var now = perTile.totals().get(names.get(i));
            final var before = i == 0 ? null : perTile.totals().get(names.get(i - 1));
            for (var c = 0; c < compressors.size(); c++) {
                final var column = TileCompression.GZIP + c;
                row.append(String.format(" %,12d %8s %8s", now[column], change(now[column], reference),
                        before == null ? "-" : change(now[column], before[column])));
            }
            System.out.println(row);
        }
        System.out.printf("ref = the smaller baseline under gzip (%,d). The ladder is the geometry buffers only;%n"
                + "the S8 stage drops the reorder because it costs more in the property columns than it saves here.%n",
                reference);
    }

    private static void perEncoding(List<String> names, TileCompression perTile, long reference) {
        final var totals = perTile.totals();
        final var compressors = perTile.compressors();
        final var width = Math.max("encoding".length(), names.stream().mapToInt(String::length).max().orElse(0));
        final var smallest = new long[compressors.size()];
        for (var c = 0; c < smallest.length; c++) {
            final var column = TileCompression.GZIP + c;
            smallest[c] = names.stream().mapToLong(name -> totals.get(name)[column]).min().orElse(0);
        }
        System.out.printf("%n--- per encoding, per tile (payload bytes; each tile compressed on its own) ---%n");
        final var header = new StringBuilder(String.format("%-" + width + "s %14s %14s", "encoding", "raw", "order-0"));
        for (final var compressor : compressors) {
            header.append(String.format(" %14s  %9s", compressor.label(), "change"));
        }
        System.out.println(header);
        for (final var name : names) {
            final var sizes = totals.get(name);
            final var row = new StringBuilder(String.format(
                    "%-" + width + "s %,14d %,14d", name, sizes[TileCompression.RAW], sizes[TileCompression.ORDER0]));
            for (var c = 0; c < compressors.size(); c++) {
                final var bytes = sizes[TileCompression.GZIP + c];
                row.append(String.format(" %,14d%s %9s", bytes, bytes == smallest[c] ? "*" : " ", change(bytes, reference)));
            }
            System.out.println(row.toString().stripTrailing());
        }
        System.out.printf("%nref = the smaller baseline under gzip; every change, in every column, is against it.%n"
                + "* = the smallest row in that column. order-0 = byte-entropy bound per tile.%n");
    }

    /** gzip of each buffer on its own, summed per layer and per dominant geometry type. */
    private static final class Breakdown {
        final Map<String, Map<String, Long>> byLayer = new TreeMap<>();
        final Map<String, Map<String, Long>> byGeometry = new TreeMap<>();
        final Map<String, Integer> layerBuffers = new TreeMap<>();
        final Map<String, Integer> geometryBuffers = new TreeMap<>();

        void buffer(String layer, String geometry) {
            layerBuffers.merge(layer, 1, Integer::sum);
            geometryBuffers.merge(geometry, 1, Integer::sum);
        }

        void add(String layer, String geometry, String name, long gzip) {
            byLayer.computeIfAbsent(layer, k -> new LinkedHashMap<>()).merge(name, gzip, Long::sum);
            byGeometry.computeIfAbsent(geometry, k -> new LinkedHashMap<>()).merge(name, gzip, Long::sum);
        }
    }

    /**
     * The most frequent geometry type in the column. Buffers with mixed geometry types are common
     * (~20% of this corpus), so this is a label, not a partition.
     */
    static String dominantGeometryType(List<Integer> geometryTypes) {
        if (geometryTypes == null || geometryTypes.isEmpty()) {
            return "UNKNOWN";
        }
        final var counts = new int[8];
        for (final var type : geometryTypes) {
            if (type >= 0 && type < counts.length) {
                counts[type]++;
            }
        }
        var best = 0;
        for (var i = 1; i < counts.length; i++) {
            if (counts[i] > counts[best]) {
                best = i;
            }
        }
        final var names = new String[] {"POINT", "LINESTRING", "POLYGON", "MULTIPOINT", "MULTILINESTRING", "MULTIPOLYGON"};
        return best < names.length ? names[best] : "UNKNOWN";
    }

    /** gzip of each (tile, group) buffer on its own, as a change against the smaller baseline there. */
    private static void groupTable(
            String title,
            List<String> names,
            List<String> baselineNames,
            Map<String, Map<String, Long>> byLayer,
            Map<String, Integer> layerBuffers,
            int limit) {
        final var layers = new ArrayList<>(byLayer.keySet());
        layers.sort(Comparator.comparingLong((String l) -> reference(byLayer.get(l), baselineNames)).reversed());
        final var shown = new ArrayList<>(layers.subList(0, Math.min(limit, layers.size())));

        System.out.printf("%n--- %s: gzip of each buffer on its own, change vs the smaller baseline in that group ---%n",
                title);
        final var width = Math.max("encoding".length(), names.stream().mapToInt(String::length).max().orElse(0));
        final var header = new StringBuilder(String.format("%-" + width + "s", "encoding"));
        final var refLine = new StringBuilder(String.format("%-" + width + "s", "(ref bytes)"));
        for (final var layer : shown) {
            header.append(String.format(" %14s", layer + "(" + layerBuffers.get(layer) + ")"));
            refLine.append(String.format(" %,14d", reference(byLayer.get(layer), baselineNames)));
        }
        System.out.println(header);
        System.out.println(refLine);
        for (final var name : names) {
            final var row = new StringBuilder(String.format("%-" + width + "s", name));
            for (final var layer : shown) {
                row.append(String.format(" %14s", change(byLayer.get(layer).getOrDefault(name, 0L),
                        reference(byLayer.get(layer), baselineNames))));
            }
            System.out.println(row);
        }
    }

    /**
     * Every buffer compressed on its own and summed, beside the per-tile figure (a tile's buffers
     * concatenated, then compressed). The gap is what sharing a window between the buffers of a tile
     * buys; the change column is against the smaller baseline of the isolated figures.
     */
    private static void isolatedTable(
            List<String> names,
            List<String> baselineNames,
            Map<String, long[]> isolated,
            List<Compressor> compressors,
            TileCompression perTile) {
        System.out.printf("%n--- isolated: every buffer compressed on its own, summed (and the per-tile figure) ---%n");
        final var width = Math.max("encoding".length(), names.stream().mapToInt(String::length).max().orElse(0));
        final var header = new StringBuilder(String.format("%-" + width + "s", "encoding"));
        for (final var compressor : compressors) {
            header.append(String.format(" %14s %9s %14s %9s", compressor.label() + " isol.", "change",
                    compressor.label() + " tile", "isol/tile"));
        }
        System.out.println(header);
        final var references = new long[compressors.size()];
        for (var c = 0; c < references.length; c++) {
            final var column = c;
            references[c] = baselineNames.stream().mapToLong(b -> isolated.get(b)[column]).min().orElse(0);
        }
        for (final var name : names) {
            final var row = new StringBuilder(String.format("%-" + width + "s", name));
            for (var c = 0; c < compressors.size(); c++) {
                final var alone = isolated.get(name)[c];
                final var tile = perTile.totals().get(name)[TileCompression.GZIP + c];
                row.append(String.format(" %,14d %9s %,14d %+8.2f%%", alone, change(alone, references[c]), tile,
                        100.0 * (alone - tile) / tile));
            }
            System.out.println(row);
        }
    }

    private static void zoomTable(List<String> names, List<String> baselineNames, TileCompression perTile) {
        System.out.printf("%n--- by zoom: whole tiles, gzip, change vs the smaller baseline at that zoom ---%n");
        final var width = Math.max("encoding".length(), names.stream().mapToInt(String::length).max().orElse(0));
        final var header = new StringBuilder(String.format("%-" + width + "s", "encoding"));
        for (final var zoom : perTile.byZoom().keySet()) {
            header.append(String.format(" %9s", "z" + zoom));
        }
        System.out.println(header);
        for (final var name : names) {
            final var row = new StringBuilder(String.format("%-" + width + "s", name));
            for (final var zoom : perTile.byZoom().values()) {
                final var ref = baselineNames.stream().mapToLong(b -> zoom.get(b)[TileCompression.GZIP]).min().orElse(0);
                row.append(String.format(" %9s", change(zoom.get(name)[TileCompression.GZIP], ref)));
            }
            System.out.println(row);
        }
    }

    private static long reference(Map<String, Long> totals, List<String> baselineNames) {
        return baselineNames.stream().mapToLong(n -> totals.getOrDefault(n, 0L)).filter(b -> b > 0).min().orElse(0);
    }

    private static String change(long bytes, long reference) {
        if (reference == 0) {
            return "-";
        }
        if (bytes == reference) {
            return "ref";
        }
        return String.format("%+.2f%%", 100.0 * (bytes - reference) / reference);
    }

    private record Options(
            String inDir,
            String step,
            String rows,
            String layer,
            boolean byZoom,
            boolean byGeometry,
            boolean stats,
            boolean withOld,
            String csvFile,
            int brotliQuality,
            int limitTiles,
            boolean isolated,
            boolean skipVerification,
            boolean oracleFlag,
            String trials,
            String featuresCsv) {

        private static final String USAGE = "Usage: [extractionDir] [--step s0|s1|...|selector|tile|ladder|oracle|all]"
                + " [--rows a,b] [--layer <name>] [--by-zoom] [--by-geometry] [--stats] [--with-old] [--csv <file>]"
                + " [--brotli-quality <0-11>] [--limit-tiles <n>] [--isolated] [--no-verify] [--oracle]"
                + " [--trials gzip,brotli,zstd,order0,order1] [--features <file>]";

        /**
         * The per-buffer best of the step's rows instead of a table row per composition; always on for
         * {@code --step oracle}, whose rows are the grid it chooses among.
         */
        boolean oracle() {
            return oracleFlag || step.equals("oracle") || step.startsWith("fit");
        }

        /** Round trips are checked in every run except the ladder and the oracle, which only size (the tests check them). */
        boolean verify() {
            return !skipVerification && !step.equals("ladder") && !step.equals("layer") && !oracle();
        }

        static Options parse(String[] args) {
            String in = null;
            var step = "s0";
            String rows = null;
            String layer = null;
            var byZoom = false;
            var byGeometry = false;
            var stats = false;
            var withOld = false;
            String csv = null;
            var brotliQuality = 11;
            var limitTiles = 0;
            var isolated = false;
            var skipVerification = false;
            var oracle = false;
            String trials = null;
            String features = null;
            for (var i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--oracle" -> oracle = true;
                    case "--trials" -> trials = args[++i];
                    case "--features" -> features = args[++i];
                    case "--no-verify" -> skipVerification = true;
                    case "--step" -> step = args[++i];
                    case "--rows" -> rows = args[++i];
                    case "--layer" -> layer = args[++i];
                    case "--by-zoom" -> byZoom = true;
                    case "--by-geometry" -> byGeometry = true;
                    case "--stats" -> stats = true;
                    case "--with-old" -> withOld = true;
                    case "--csv" -> csv = args[++i];
                    case "--brotli-quality" -> brotliQuality = Integer.parseInt(args[++i]);
                    case "--limit-tiles" -> limitTiles = Integer.parseInt(args[++i]);
                    case "--isolated" -> isolated = true;
                    case "--help", "-h" -> throw new IllegalArgumentException(USAGE);
                    default -> {
                        if (args[i].startsWith("-") || in != null) {
                            throw new IllegalArgumentException("Unexpected argument: " + args[i] + "\n" + USAGE);
                        }
                        in = args[i];
                    }
                }
            }
            return new Options(in == null ? DEFAULT_IN_DIR : in, step, rows, layer, byZoom, byGeometry, stats, withOld, csv,
                    brotliQuality, limitTiles, isolated, skipVerification, oracle, trials, features);
        }
    }
}
