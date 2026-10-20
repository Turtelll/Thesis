package org.maplibre.mlt.research.cli;

import org.maplibre.mlt.research.encoding.Baselines;
import org.maplibre.mlt.research.encoding.Encoding;
import org.maplibre.mlt.research.encoding.GeometryEquivalence;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.EncodedBuffer;
import org.maplibre.mlt.research.extraction.ExtractionReader;
import org.maplibre.mlt.research.extraction.ExtractionWriter.ManifestEntry;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;


/**
 * Measures research encodings under a general-purpose compressor: encodes every extracted (tile, layer) VertexBuffer with both baselines and every
 * {@link Baselines#research() research row}, and reports the sizes a client would be served — every
 * tile's buffers concatenated and compressed on their own, as one HTTP response.
 *
 * <p>The headline compares the two baselines under gzip with the per-buffer research oracle
 * (smallest research or Varint-baseline row per buffer) under gzip, Brotli and zstd. Every
 * percentage is against the smaller baseline under gzip.
 *
 * <p>Every encoding is round-tripped on every buffer — encode, decode, compare against the
 * extraction — and the run fails naming the encoding, tile and layer if any does not reconstruct the
 * buffer exactly. This is not optional and has no flag: an encoding that discards data reports a
 * small size and would look like the best result in the table <em>because</em> it is broken, so a
 * size from an unverified encoding is not a result.
 *
 * <p>Usage: {@code [extractionDir] [--csv <file>] [--layer <name>] [--verbose] [--by-zoom]
 * [--brotli-oracle] [--brotli-quality <0-11>]}. {@code extractionDir} defaults to {@code ../data/extractedBaseline} when omitted.
 */
public final class BenchmarkCli {
    private BenchmarkCli() {
    }

    /**
     * Layers shown individually in the breakdown; the rest are folded into one {@code (other)} row.
     */
    private static final int TOP_LAYERS = 6;

    /**
     * Encoding columns per table; the table repeats for the remaining encodings.
     */
    private static final int COLUMNS_PER_TABLE = 4;

    /**
     * Widest cell content: "13.399.028" + " " + "+15,6%". The winner star is written outside this
     * field, so it is not counted here.
     */
    private static final int CELL_WIDTH = 20;

    private static final List<String> BASELINE_NAMES =
            Baselines.baseline().stream().map(Encoding::name).toList();

    private static final String RESEARCH_ORACLE = "research oracle";

    /** Same candidates as {@link #RESEARCH_ORACLE}, chosen on each buffer's own Brotli size. */
    private static final String BROTLI_ORACLE = "brotli oracle";

    /**
     * Running gzip byte totals per encoding within one group (one layer, geometry type or zoom).
     */
    private static final class Totals {
        private final Map<String, Long> bytes = new LinkedHashMap<>();
        private int buffers;

        void add(String encoding, long encodedBytes) {
            bytes.merge(encoding, encodedBytes, Long::sum);
        }

        long get(String encoding) {
            return bytes.getOrDefault(encoding, 0L);
        }
    }

    /**
     * A per-buffer selector over one codec family: the smallest uncompressed size among the rows
     * offered for a buffer, i.e. the best any encoder choosing per stream could do.
     *
     * <p>It is an upper bound and deliberately optimistic — it charges nothing for signalling the
     * choice and assumes the encoder always guesses right. It selects on uncompressed bytes, so
     * under a compressor it can lose to a single row.
     */
    private static final class Oracle {
        private final String label;
        private final String unit;
        private final Map<String, Integer> wins = new TreeMap<>();
        private final Map<String, Long> bytes = new TreeMap<>();
        private String bestName;
        private EncodedBuffer best;
        private long bestCost;

        Oracle(String label) {
            this(label, "uncompressed bytes incl. headers");
        }

        /** {@code unit} describes what {@link #offer(String, EncodedBuffer, long)} costs measure. */
        Oracle(String label, String unit) {
            this.label = label;
            this.unit = unit;
        }

        /** Selects on uncompressed size. A tie goes to the row offered first. */
        void offer(String name, EncodedBuffer encoded) {
            offer(name, encoded, encoded.totalBytes());
        }

        /** Selects on a caller-supplied cost, e.g. the buffer's compressed size. */
        void offer(String name, EncodedBuffer encoded, long cost) {
            if (best == null || cost < bestCost) {
                bestName = name;
                best = encoded;
                bestCost = cost;
            }
        }

        /** The payload of the smallest row offered since the last call. */
        byte[] take() {
            wins.merge(bestName, 1, Integer::sum);
            bytes.merge(bestName, bestCost, Long::sum);
            var payload = best.payload();
            best = null;
            return payload;
        }

        /**
         * Which rows the oracle is made of. A total dominated by a single encoding is one extra
         * codec to add, while a total spread thin across many is a signalling and decoder-complexity
         * bill.
         */
        void report() {
            var total = bytes.values().stream().mapToLong(Long::longValue).sum();
            var width = maxLength(wins.keySet().stream().map(BenchmarkCli::withBaselineTag), 8);

            System.out.printf("%n--- %s composition (%s) ---%n", label, unit);
            System.out.printf("%-" + width + "s %10s %7s %14s%n", "encoding", "buffers", "share", "bytes");

            var ranked = new ArrayList<>(wins.keySet());
            ranked.sort(Comparator.<String>comparingInt(wins::get).thenComparingLong(bytes::get).reversed());
            for (var name : ranked) {
                System.out.printf(
                        "%-" + width + "s %10d %6.1f%% %14s%n",
                        withBaselineTag(name),
                        wins.get(name),
                        total == 0 ? 0.0 : 100.0 * bytes.get(name) / total,
                        String.format("%,d", bytes.get(name)));
            }
        }
    }

    public static void main(String[] args) throws IOException {
        var opts = Options.parse(args);
        var inDir = Path.of(opts.inDir);

        var manifest = groupedByTile(ExtractionReader.readManifest(inDir));

        /* The FastPFOR baseline is a reported row only; the research oracle selects among the Varint
         * baseline and the research rows. */
        var fastPforBaseline = Baselines.baseline().get(0);
        var varint = new ArrayList<Encoding>(List.of(Baselines.baseline().get(1)));
        varint.addAll(Baselines.research());
        var names = Stream.concat(Stream.of(fastPforBaseline), varint.stream()).map(Encoding::name).toList();
        var rows = new ArrayList<>(names);
        rows.add(RESEARCH_ORACLE);

        var researchOracle = new Oracle(RESEARCH_ORACLE);
        var compressors = Compressor.all(opts.brotliQuality);
        var brotli = compressors.get(1);
        Oracle brotliOracle = null;
        if (opts.brotliOracle) {
            rows.add(BROTLI_ORACLE);
            brotliOracle = new Oracle(
                    BROTLI_ORACLE, brotli.label() + " bytes of each buffer compressed on its own");
        }
        var perTile = new TileCompression(compressors);
        var byLayer = new TreeMap<String, Totals>();
        var byGeometry = new TreeMap<String, Totals>();
        var byZoom = new TreeMap<Integer, Totals>();
        String currentTile = null;
        var currentZoom = 0;
        var buffers = 0;
        long rawTotal = 0;

        Writer csv = null;
        if (opts.csvFile != null) {
            var csvPath = Path.of(opts.csvFile);
            if (csvPath.getParent() != null) {
                Files.createDirectories(csvPath.getParent());
            }
            csv = Files.newBufferedWriter(csvPath);
            csv.write("z,x,y,layerName,vertexCount,rawBytes,dominantGeometryType," + String.join(",", rows));
            csv.write("\n");
        }

        try {
            for (ManifestEntry entry : manifest) {
                if (opts.layer != null && !opts.layer.equals(entry.layerName())) {
                    continue;
                }
                var ctx = ExtractionReader.readContext(inDir, entry);
                if (ctx.vertices().isEmpty()) {
                    continue;
                }
                var tileKey = tileKey(entry);
                if (!tileKey.equals(currentTile)) {
                    perTile.endTile(currentZoom);
                    currentTile = tileKey;
                    currentZoom = entry.z();
                }

                var geometry = dominantGeometryType(ctx.geometryTypes());
                var rawBytes = entry.vertexCount() * 2 * Integer.BYTES;
                rawTotal += rawBytes;
                buffers++;

                var payloads = new LinkedHashMap<String, byte[]>();
                var fastPforEncoded = fastPforBaseline.encode(ctx);
                verifyRoundTrip(entry, ctx, fastPforBaseline, fastPforEncoded);
                payloads.put(fastPforBaseline.name(), fastPforEncoded.payload());
                for (var encoding : varint) {
                    var encoded = encoding.encode(ctx);
                    verifyRoundTrip(entry, ctx, encoding, encoded);
                    researchOracle.offer(encoding.name(), encoded);
                    if (brotliOracle != null) {
                        brotliOracle.offer(encoding.name(), encoded, brotli.size(encoded.payload()));
                    }
                    payloads.put(encoding.name(), encoded.payload());
                }
                payloads.put(RESEARCH_ORACLE, researchOracle.take());
                if (brotliOracle != null) {
                    payloads.put(BROTLI_ORACLE, brotliOracle.take());
                }

                var groups = List.of(
                        byLayer.computeIfAbsent(entry.layerName(), k -> new Totals()),
                        byGeometry.computeIfAbsent(geometry, k -> new Totals()));
                for (var totals : groups) {
                    totals.buffers++;
                }
                byZoom.computeIfAbsent(entry.z(), k -> new Totals()).buffers++;

                var line = new StringBuilder();
                for (var row : payloads.entrySet()) {
                    perTile.add(row.getKey(), row.getValue());
                    var gzip = Compressor.GZIP.size(row.getValue());
                    for (var totals : groups) {
                        totals.add(row.getKey(), gzip);
                    }
                    line.append(',').append(gzip);
                }

                if (csv != null) {
                    csv.write(
                            String.join(
                                    ",",
                                    String.valueOf(entry.z()),
                                    String.valueOf(entry.x()),
                                    String.valueOf(entry.y()),
                                    entry.layerName(),
                                    String.valueOf(entry.vertexCount()),
                                    String.valueOf(rawBytes),
                                    geometry));
                    csv.write(line + "\n");
                }
            }
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

        System.out.printf(
                "%d buffers in %d tiles, %s raw bytes%n",
                buffers, perTile.tiles(), String.format("%,d", rawTotal));

        var reference = BASELINE_NAMES.stream()
                .mapToLong(name -> perTile.totals().get(name)[TileCompression.GZIP])
                .min()
                .orElse(0);
        perEncoding(names, perTile, reference);
        researchOracle.report();
        if (brotliOracle != null) {
            brotliOracle.report();
        }

        System.out.printf(
                "%n=== breakdowns: gzip bytes; ref = the smaller baseline in that group ===%n"
                        + "by layer / by geometry gzip each (tile, layer) buffer on its own - the tile restricted%n"
                        + "to that layer - so their rows do not sum to the per-tile tables.%n");
        segment("layer", names, byLayer, TOP_LAYERS, opts.verbose);
        segment("geometry", names, byGeometry, Integer.MAX_VALUE, opts.verbose);
        if (opts.byZoom) {
            var shown = new ArrayList<Map.Entry<String, Totals>>();
            for (var zoom : byZoom.entrySet()) {
                perTile.byZoom().get(zoom.getKey()).forEach(
                        (row, sizes) -> zoom.getValue().add(row, sizes[TileCompression.GZIP]));
                shown.add(Map.entry("z" + zoom.getKey(), zoom.getValue()));
            }
            tables("zoom (whole tiles)", "zoom", names, shown, opts.verbose);
        }
        headline(perTile, reference, opts.brotliOracle);
        if (opts.csvFile != null) {
            System.out.printf("%nWrote %s (gzip bytes per buffer)%n", opts.csvFile);
        }
    }

    /**
     * The manifest with every tile's buffers contiguous, tiles in first-seen order — {@link
     * TileCompression} compresses a tile when the next one starts. An extraction already grouped by
     * tile comes back in its original order.
     */
    static List<ManifestEntry> groupedByTile(List<ManifestEntry> manifest) {
        var byTile = new LinkedHashMap<String, List<ManifestEntry>>();
        for (var entry : manifest) {
            byTile.computeIfAbsent(tileKey(entry), k -> new ArrayList<>()).add(entry);
        }
        return byTile.values().stream().flatMap(List::stream).toList();
    }

    static String tileKey(ManifestEntry entry) {
        return entry.z() + "/" + entry.x() + "/" + entry.y();
    }

    // --- reporting ----------------------------------------------------------

    /**
     * Both baselines under gzip against the research oracle under every compressor.
     */
    private static void headline(TileCompression perTile, long reference, boolean brotliOracle) {
        var labels = new LinkedHashMap<String, String>();
        for (var name : BASELINE_NAMES) {
            labels.put(name, "baseline " + name);
        }
        labels.put(RESEARCH_ORACLE, RESEARCH_ORACLE);
        if (brotliOracle) {
            labels.put(BROTLI_ORACLE, BROTLI_ORACLE);
        }
        var width = maxLength(labels.values().stream(), "row".length());
        var compressors = perTile.compressors();

        System.out.printf(
                "%n--- baselines vs oracle, per tile (payload bytes; each of %d tiles compressed on its own) ---%n",
                perTile.tiles());
        var header = new StringBuilder(String.format("%-" + width + "s %14s", "row", "raw"));
        for (var compressor : compressors) {
            header.append(String.format(" %14s %9s", compressor.label(), "change"));
        }
        System.out.println(header);
        for (var entry : labels.entrySet()) {
            var sizes = perTile.totals().get(entry.getKey());
            var row = new StringBuilder(
                    String.format("%-" + width + "s %,14d", entry.getValue(), sizes[TileCompression.RAW]));
            for (var c = 0; c < compressors.size(); c++) {
                if (c > 0 && !entry.getKey().equals(RESEARCH_ORACLE) && !entry.getKey().equals(BROTLI_ORACLE)) {
                    row.append(String.format(" %14s %9s", "-", ""));
                    continue;
                }
                var bytes = sizes[TileCompression.GZIP + c];
                row.append(String.format(" %,14d %9s", bytes, change(bytes, reference)));
            }
            System.out.println(row.toString().stripTrailing());
        }
        System.out.printf(
                "%nref = the smaller baseline under gzip; every change, in every column, is against it.%n"
                        + "The research oracle picks per buffer on uncompressed bytes; the brotli oracle (--brotli-oracle)%n"
                        + "on each buffer's own Brotli size. Neither charges anything for signalling.%n");
    }

    /**
     * One row per reported encoding, every compressed column against the same gzip {@code reference}
     * as {@link #headline}.
     */
    private static void perEncoding(List<String> names, TileCompression perTile, long reference) {
        var totals = perTile.totals();
        var compressors = perTile.compressors();
        var width = maxLength(names.stream(), "encoding".length());
        var smallest = new long[compressors.size()];
        for (var c = 0; c < smallest.length; c++) {
            final var column = TileCompression.GZIP + c;
            smallest[c] = names.stream().mapToLong(name -> totals.get(name)[column]).min().orElse(0);
        }

        System.out.printf("%n--- per encoding, per tile (payload bytes) ---%n");
        var header = new StringBuilder(String.format("%-" + width + "s %14s %14s", "encoding", "raw", "order-0"));
        for (var compressor : compressors) {
            // The trailing blank matches the star slot each data cell reserves below.
            header.append(String.format(" %14s  %9s", compressor.label(), "change"));
        }
        System.out.println(header);

        for (var name : names) {
            var sizes = totals.get(name);
            var row = new StringBuilder(String.format(
                    "%-" + width + "s %,14d %,14d", name, sizes[TileCompression.RAW], sizes[TileCompression.ORDER0]));
            for (var c = 0; c < compressors.size(); c++) {
                var bytes = sizes[TileCompression.GZIP + c];
                row.append(String.format(
                        " %,14d%s %9s", bytes, bytes == smallest[c] ? "*" : " ", change(bytes, reference)));
            }
            System.out.println(row.toString().stripTrailing());
        }
        System.out.printf(
                "%nref = the smaller baseline under gzip; * = the smallest row in that column. order-0 =%n"
                        + "byte-entropy bound per tile; order-0 minus a compressed column is what LZ77 matching found.%n");
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

    /**
     * One breakdown: a row per group, sorted by group size and cut to {@code limit} rows. Everything
     * past the cut is folded into a single {@code (other)} row rather than dropped, so the column
     * totals still add up.
     */
    private static void segment(
            String label, List<String> names, Map<String, Totals> groups, int limit, boolean verbose) {

        /* Ranked by bytes, not by buffer count: a buffer is one (tile, layer) pair, so counting them
         * measures how many tiles a layer appears in, not how much data it is. Since the question is
         * where an encoding can save bytes, the big layers are the byte-heavy ones. */
        var sorted = new ArrayList<>(groups.entrySet());
        sorted.sort((a, b) -> Long.compare(bestBaseline(b.getValue()), bestBaseline(a.getValue())));

        var shown = new ArrayList<Map.Entry<String, Totals>>(sorted.subList(0, Math.min(limit, sorted.size())));
        if (sorted.size() > limit) {
            var rest = new Totals();
            for (var group : sorted.subList(limit, sorted.size())) {
                rest.buffers += group.getValue().buffers;
                for (var name : names) {
                    rest.add(name, group.getValue().get(name));
                }
            }
            shown.add(Map.entry("(other, " + (sorted.size() - limit) + ")", rest));
        }
        tables(label, label, names, shown, verbose);
    }

    /**
     * {@link #compactTable} by default; under {@code --verbose} every encoding as a column, printed
     * in chunks so a row fits a terminal, the whole table repeating per chunk with the same rows.
     * Nothing is dropped: the reference, the winner and the percentages are all computed over
     * <em>every</em> encoding, so a chunk that happens to exclude the winner still names it in the
     * {@code best} column and still measures against the same baseline.
     */
    private static void tables(
            String title, String label, List<String> names, List<Map.Entry<String, Totals>> shown, boolean verbose) {
        if (!verbose) {
            System.out.printf("%n--- by %s ---%n", title);
            compactTable(label, names, shown);
            return;
        }
        for (var from = 0; from < names.size(); from += COLUMNS_PER_TABLE) {
            var chunk = names.subList(from, Math.min(from + COLUMNS_PER_TABLE, names.size()));
            var part =
                    names.size() <= COLUMNS_PER_TABLE
                            ? ""
                            : String.format(
                            " (%d/%d)",
                            from / COLUMNS_PER_TABLE + 1,
                            (names.size() + COLUMNS_PER_TABLE - 1) / COLUMNS_PER_TABLE);
            System.out.printf("%n--- by %s%s ---%n", title, part);
            table(label, names, chunk, shown, from + COLUMNS_PER_TABLE >= names.size());
        }
    }

    /**
     * The default view: one row per group naming only the encoding that won it and what that win is
     * worth against the best baseline for the same group.
     *
     * <p>This is the question asked after every change to an encoding — did it win anything, and
     * where — and the per-encoding tables answer it only after reading sixteen columns spread over
     * four repetitions. A {@code saved} of {@code ref} means the winner <em>is</em> a baseline, i.e.
     * nothing under research helps on that group; {@code --verbose} still has the full field.
     */
    private static void compactTable(
            String label, List<String> names, List<Map.Entry<String, Totals>> shown) {

        var width = groupWidth(label, shown);
        /* Sized to the winners this table actually prints, not to the widest name in the registry:
         * with every add-on enabled the longest registered name is ~40 chars, which would pad this
         * column with ~25 blanks on every row and push the two numeric columns off to the right. */
        var nameWidth = maxLength(
                shown.stream()
                        .filter(g -> !g.getKey().startsWith("(other"))
                        .map(g -> winner(names, g.getValue())),
                "winner".length());
        var corpus = shown.stream().mapToLong(g -> bestBaseline(g.getValue())).sum();
        var rowFormat = "%-" + width + "s %8d %6.1f%% %14s   %-" + nameWidth + "s %14s %11s%n";

        System.out.printf(
                "%-" + width + "s %8s %7s %14s   %-" + nameWidth + "s %14s %11s%n",
                label, "buffers", "share", "baseline bytes", "winner", "winner bytes", "vs baseline");

        for (var group : shown) {
            var totals = group.getValue();
            var reference = bestBaseline(totals);
            var share = corpus == 0 ? 0.0 : 100.0 * reference / corpus;

            /* The folded row sums layers that share nothing but being small, so its "winner" would be
             * an encoding no tile could ever select. Its bytes still count toward the corpus, so the
             * row stays — without a winner. */
            if (group.getKey().startsWith("(other")) {
                System.out.printf(
                        rowFormat,
                        group.getKey(), totals.buffers, share, String.format("%,d", reference), "-", "-", "-");
                continue;
            }

            var winner = winner(names, totals);
            System.out.printf(
                    rowFormat,
                    group.getKey(),
                    totals.buffers,
                    share,
                    String.format("%,d", reference),
                    winner,
                    String.format("%,d", totals.get(winner)),
                    delta(totals.get(winner), reference));
        }
    }

    /**
     * One table: the given rows, but only {@code chunk}'s columns. {@code withBest} appends the
     * winner column, which is printed once on the last chunk rather than repeated in every one.
     */
    private static void table(
            String label,
            List<String> names,
            List<String> chunk,
            List<Map.Entry<String, Totals>> shown,
            boolean withBest) {

        var width = groupWidth(label, shown);
        // Sized to the winners actually printed, for the same reason as in compactTable.
        var nameWidth = maxLength(shown.stream().map(g -> winner(names, g.getValue())), "best".length());
        var corpus = shown.stream().mapToLong(g -> bestBaseline(g.getValue())).sum();
        var header =
                new StringBuilder(String.format("%-" + width + "s %8s %7s", label, "buffers", "share"));
        var column = columnWidth(chunk);
        for (var name : chunk) {
            // The trailing blank matches the star slot each data cell reserves below.
            header.append(String.format(" %" + column + "s ", name));
        }
        if (withBest) {
            header.append(String.format("  %-" + nameWidth + "s", "best"));
        }
        System.out.println(header.toString().stripTrailing());

        for (var group : shown) {
            var totals = group.getValue();
            var reference = bestBaseline(totals);
            var winner = winner(names, totals);
            var row =
                    new StringBuilder(
                            String.format(
                                    "%-" + width + "s %8d %6.1f%%",
                                    group.getKey(),
                                    totals.buffers,
                                    corpus == 0 ? 0.0 : 100.0 * reference / corpus));
            for (var name : chunk) {
                var bytes = totals.get(name);
                /* The winning cell is starred; the percentages are all against the baseline reference,
                 * which is a different question and can land on a different column.
                 *
                 * The star sits OUTSIDE the right-aligned field: inside it, a starred cell would be one
                 * character wider than its neighbours and shift every later column left by one. Every
                 * cell therefore reserves the same trailing character, blank when it did not win. */
                row.append(
                        String.format(
                                " %" + column + "s%s",
                                String.format("%s %s", String.format("%,d", bytes), delta(bytes, reference)),
                                name.equals(winner) ? "*" : " "));
            }
            if (withBest) {
                row.append(String.format("  %-" + nameWidth + "s", winner));
            }
            System.out.println(row.toString().stripTrailing());
        }
    }

    /**
     * The smallest encoding for this group — what you would actually ship for it.
     */
    private static String winner(List<String> names, Totals totals) {
        return smallest(names, totals);
    }

    /**
     * The name with the fewest bytes among those with any, the first one on a tie; {@code -} if none
     * has bytes in this group.
     */
    private static String smallest(List<String> candidates, Totals totals) {
        return candidates.stream()
                .filter(name -> totals.get(name) > 0)
                .min(Comparator.comparingLong(totals::get))
                .orElse("-");
    }

    /**
     * The smaller of the two baselines for this group — what a new encoding has to beat. Comparing
     * against the better baseline rather than a fixed one avoids crediting an encoding for beating an
     * opponent that was never the one to beat.
     */
    private static long bestBaseline(Totals totals) {
        return BASELINE_NAMES.stream()
                .mapToLong(totals::get)
                .filter(bytes -> bytes > 0)
                .min()
                .orElse(0);
    }

    /**
     * A signed percentage against the baseline reference; negative means smaller, which is the goal.
     * {@code ref} marks the reference cell itself — the best <em>baseline</em>, which is not
     * necessarily the smallest encoding in the row (that one is starred instead).
     */
    private static String delta(long bytes, long reference) {
        if (reference == 0) {
            return "-";
        }
        if (bytes == reference) {
            return "ref";
        }
        return String.format("%+.1f%%", 100.0 * (bytes - reference) / reference);
    }

    /**
     * Width of one encoding column: wide enough for the widest cell ({@code bytes} + {@code delta} +
     * the winner star) and for the longest name <em>in this chunk</em>. Sizing it to the longest
     * name in the whole registry instead would pad every column of every table to the widest name
     * anywhere, which no cell in the table needs.
     */
    private static int columnWidth(List<String> chunk) {
        return maxLength(chunk.stream(), CELL_WIDTH);
    }

    /**
     * Width of the group column of {@link #compactTable} and {@link #table}.
     */
    private static int groupWidth(String label, List<Map.Entry<String, Totals>> shown) {
        return Math.max(label.length(), shown.stream().mapToInt(g -> g.getKey().length()).max().orElse(8));
    }

    /**
     * The length of the longest string, but at least {@code min}.
     */
    private static int maxLength(Stream<String> strings, int min) {
        return Math.max(min, strings.mapToInt(String::length).max().orElse(0));
    }

    /**
     * An encoding's row label in the oracle-composition tables, which mark baselines.
     */
    private static String withBaselineTag(String name) {
        return BASELINE_NAMES.contains(name) ? name + " (baseline)" : name;
    }

    // --- per-buffer work ----------------------------------------------------

    /**
     * Decodes what was just encoded and fails the run if it does not reconstruct the buffer.
     *
     * <p>Runs unconditionally: a size from an encoding that loses data is not a result, and a lossy
     * encoder would otherwise report the smallest number in the table precisely <em>because</em> it
     * discards information.
     *
     * <p>An encoding declaring {@link Encoding#reordersGeometries()} is checked against the buffer
     * it actually emits rather than the extraction, and by geometry equivalence rather than exact
     * equality — it is allowed to change the order of geometries and nothing else. Every other
     * encoding is still held to exact reconstruction, so that declaration is the only way to obtain
     * the weaker check and a lossy encoder cannot slip through by claiming to reorder.
     */
    static void verifyRoundTrip(
            ManifestEntry entry, BufferContext ctx, Encoding encoding, EncodedBuffer encoded) {
        var decoded = encoding.decode(encoded, ctx, ctx.vertices().size());

        if (encoding.reordersGeometries()) {
            var emitted = encoding.emitted(ctx);
            // Two separate obligations: the decode must reproduce what was encoded, and the reordering
            // must not have altered the geometry. Checking only the first would pass an encoding that
            // silently dropped a feature before encoding it.
            if (!emitted.vertices().equals(decoded)) {
                throw new IllegalStateException(
                        String.format(
                                "%s does not decode to the buffer it emitted on %d/%d/%d %s (%d vertices)",
                                encoding.name(), entry.z(), entry.x(), entry.y(), entry.layerName(),
                                ctx.vertices().size()));
            }
            if (!GeometryEquivalence.holds(ctx, emitted)) {
                throw new IllegalStateException(
                        String.format(
                                "%s did not preserve the geometry of %d/%d/%d %s (%d vertices): %s",
                                encoding.name(), entry.z(), entry.x(), entry.y(), entry.layerName(),
                                ctx.vertices().size(), GeometryEquivalence.describe(ctx, emitted)));
            }
            return;
        }

        if (!ctx.vertices().equals(decoded)) {
            throw new IllegalStateException(
                    String.format(
                            "%s is not lossless on %d/%d/%d %s (%d vertices, %d payload bytes): decoded buffer "
                                    + "differs from the extraction",
                            encoding.name(),
                            entry.z(),
                            entry.x(),
                            entry.y(),
                            entry.layerName(),
                            ctx.vertices().size(),
                            encoded.payload().length));
        }
    }

    /**
     * The most frequent geometry type in the column. Buffers with mixed geometry types are common
     * (~20% of this corpus), so this is a label, not a partition.
     */
    private static String dominantGeometryType(List<Integer> geometryTypes) {
        if (geometryTypes == null || geometryTypes.isEmpty()) {
            return "UNKNOWN";
        }
        var counts = new int[8];
        for (var type : geometryTypes) {
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
        var names =
                new String[]{
                        "POINT", "LINESTRING", "POLYGON", "MULTIPOINT", "MULTILINESTRING", "MULTIPOLYGON"
                };
        return best < names.length ? names[best] : "UNKNOWN";
    }

    private record Options(
            String inDir,
            String csvFile,
            String layer,
            boolean verbose,
            boolean byZoom,
            boolean brotliOracle,
            int brotliQuality) {

        /**
         * The extraction directory used when none is given on the command line — the project's
         * current baseline corpus (planetiler-derived, see {@code docs/results/README.md}).
         */
        private static final String DEFAULT_IN_DIR = "../data/extractedBaseline";

        private static final String USAGE =
                "Usage: [extractionDir] [--csv <file>] [--layer <name>] [--verbose] [--by-zoom]"
                        + " [--brotli-oracle] [--brotli-quality <0-11>]";

        static Options parse(String[] args) {
            String in = null;
            String csv = null;
            String layer = null;
            var verbose = false;
            var byZoom = false;
            var brotliOracle = false;
            var brotliQuality = 11;
            for (var i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--csv" -> csv = args[++i];
                    case "--layer" -> layer = args[++i];
                    case "--verbose" -> verbose = true;
                    case "--by-zoom" -> byZoom = true;
                    case "--brotli-oracle" -> brotliOracle = true;
                    case "--brotli-quality" -> {
                        brotliQuality = Integer.parseInt(args[++i]);
                        if (brotliQuality < 0 || brotliQuality > 11) {
                            throw new IllegalArgumentException(
                                    "Brotli quality must be 0-11: " + brotliQuality + "\n" + USAGE);
                        }
                    }
                    case "--help", "-h" -> throw new IllegalArgumentException(USAGE);
                    default -> {
                        if (args[i].startsWith("-")) {
                            throw new IllegalArgumentException("Unknown argument: " + args[i] + "\n" + USAGE);
                        }
                        if (in != null) {
                            throw new IllegalArgumentException(
                                    "Unexpected second input directory: " + args[i] + "\n" + USAGE);
                        }
                        in = args[i];
                    }
                }
            }
            if (in == null) {
                in = DEFAULT_IN_DIR;
            }
            return new Options(in, csv, layer, verbose, byZoom, brotliOracle, brotliQuality);
        }
    }
}
