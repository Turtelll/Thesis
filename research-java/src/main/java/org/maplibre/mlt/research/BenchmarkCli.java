package org.maplibre.mlt.research;

import org.maplibre.mlt.research.encoding.Baselines;
import org.maplibre.mlt.research.encoding.Encoding;
import org.maplibre.mlt.research.encoding.GeometryEquivalence;
import org.maplibre.mlt.research.encoding.RansCodec;
import org.maplibre.mlt.research.encoding.SegmentedEncoding;
import org.maplibre.mlt.research.encoding.SegmentedRansCodec;
import org.maplibre.mlt.research.encoding.VertexBufferCodec;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.EncodedBuffer;
import org.maplibre.mlt.research.encoding.VertexTransform;
import org.maplibre.mlt.research.encoding.research.GeometryReorder;
import org.maplibre.mlt.research.encoding.research.ReorderedEncoding;
import org.maplibre.mlt.research.extraction.ExtractionReader;
import org.maplibre.mlt.research.extraction.ExtractionWriter.ManifestEntry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;


/**
 * Encodes every extracted (tile, layer) VertexBuffer with every registered encoding and reports the
 * resulting sizes, overall and per layer.
 *
 * <p>The per-layer breakdown is the point. A corpus total hides the case that decides whether an
 * encoding is useful: one that is much better on {@code transportation} (long smooth polylines) and
 * much worse on {@code building} can be net-negative overall and still worth shipping on
 * {@code transportation} alone. A single corpus number cannot express that; a size per (encoding,
 * layer) can.
 *
 * <p>Every encoding is round-tripped on every buffer — encode, decode, compare against the
 * extraction — and the run fails naming the encoding, tile and layer if any does not reconstruct the
 * buffer exactly. This is not optional and has no flag: an encoding that discards data reports a
 * small size and would look like the best result in the table <em>because</em> it is broken, so a
 * size from an unverified encoding is not a result.
 *
 * <p>Usage: {@code [extractionDir] [--csv <file>] [--layer <name>] [--verbose] [--segmented]
 * [--reorder <feature|feature-rev>]}. {@code extractionDir} defaults to {@code
 * ../data/extractedBaseline} when omitted.
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

    /**
     * Running byte totals for one encoding within one group (the whole corpus, or one layer).
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
     * Mean residual bit width per {@link VertexTransform}, corpus-wide — a codec-independent
     * headroom signal, separate from the byte tables above.
     *
     * <p>Keyed on the transform's name rather than the encoding's: {@code QUAD_CORNER_VARINT},
     * {@code QUAD_CORNER_RANS} and {@code QUAD_CORNER_FASTPFOR} all wrap the same transform and so
     * produce byte-identical residuals, and printing the mean three times would wrongly imply the
     * codec affects it. A predictor that lowers mean bits has found real structure; whether that
     * structure survives into fewer bytes depends on layout and codec — the tables above answer that
     * separately. {@code QUAD_CORNER} is the worked example: -9,6% mean bits, -0,0% bytes interleaved
     * under FastPFOR, -12,2% bytes once segmented (see {@code docs/evaluation-protocol.md}).
     *
     * <p>Only accumulates a running {@code (bitSum, count)} per row, not the residual values
     * themselves, so memory is proportional to the number of distinct transforms, not corpus size.
     */
    private static final class Residuals {
        private final Map<String, Long> bitSum = new LinkedHashMap<>();
        private final Map<String, Long> count = new LinkedHashMap<>();

        void add(String transformName, int[] zigzagValues) {
            var sum = 0L;
            for (var value : zigzagValues) {
                sum += bits(value);
            }
            bitSum.merge(transformName, sum, Long::sum);
            count.merge(transformName, (long) zigzagValues.length, Long::sum);
        }

        /**
         * Bit width of one zigzag-encoded residual, matching {@code QuadCornerResidualCli.bits()} and
         * {@code RansCodec}'s bucket symbol: the position of the leading one, zero for a zero value.
         */
        private static int bits(int zigzag) {
            return 32 - Integer.numberOfLeadingZeros(zigzag);
        }

        private double meanBits(String transformName) {
            var n = count.getOrDefault(transformName, 0L);
            return n == 0 ? 0.0 : (double) bitSum.get(transformName) / n;
        }

        void report() {
            if (bitSum.isEmpty()) {
                return;
            }
            var reference = "DELTA_ZIGZAG";
            var refMean = bitSum.containsKey(reference) ? meanBits(reference) : 0.0;
            var hasRef = bitSum.containsKey(reference);

            var nameWidth =
                    Math.max("transform".length(), bitSum.keySet().stream().mapToInt(String::length).max().orElse(0));

            System.out.printf("%n--- residual bits ---%n");
            System.out.printf(
                    "%-" + nameWidth + "s %14s %10s %17s%n", "transform", "values", "mean bits", "vs DELTA_ZIGZAG");
            for (var name : bitSum.keySet()) {
                var mean = meanBits(name);
                System.out.printf(
                        "%-" + nameWidth + "s %,14d %10.2f %17s%n",
                        name,
                        count.get(name),
                        mean,
                        hasRef ? deltaFraction(mean, refMean) : "-");
            }
            System.out.printf(
                    "%nA lower mean is a headroom signal, not a result: a transform that narrows the median%n"
                            + "while widening the tail can still cost the same or more once a codec prices a%n"
                            + "block's maximum (FastPFOR) rather than the mean. Whether the reduction above%n"
                            + "reaches the tile depends on layout and codec - see the byte tables.%n");
        }
    }

    /**
     * A signed percentage of a {@code double} mean against a reference mean, mirroring {@link
     * #delta(long, long)}'s sign convention (negative = smaller = the goal) for the residual-bits
     * table, which averages fractional bits rather than whole bytes.
     */
    private static String deltaFraction(double value, double reference) {
        if (reference == 0.0) {
            return "-";
        }
        if (value == reference) {
            return "ref";
        }
        return String.format("%+.1f%%", 100.0 * (value - reference) / reference);
    }

    /**
     * Answers the question the totals cannot: <em>would a tile ever choose one of these encodings?</em>
     *
     * <p>The tables above pick one encoding for a whole corpus or layer, but a real tile selects per
     * stream, and {@code StreamMetadata} already carries the technique per stream — so an encoder may
     * legitimately encode each buffer with whichever candidate is smallest. This accumulates the
     * <em>oracle</em>: the sum of the per-buffer minimum, i.e. the best any such selector could do.
     *
     * <p>It is an upper bound and deliberately optimistic — it charges nothing for signalling the
     * choice and assumes the encoder always guesses right. If the oracle barely beats simply always
     * using the best single encoding, then per-buffer selection cannot pay for itself and the
     * candidate encodings do not belong in a tile, however many individual buffers they win. Win
     * counts alone are misleading: an encoding can win many buffers by a byte each.
     */
    private static final class Selection {

        private long oracleBytes;
        private final Map<String, Integer> wins = new TreeMap<>();
        private final Map<String, Long> oracleBytesByName = new TreeMap<>();
        private final List<List<Integer>> perBufferSizes = new ArrayList<>();

        void add(List<String> names, List<Integer> sizes) {
            var best = 0;
            for (var i = 0; i < names.size(); i++) {
                if (sizes.get(i) < sizes.get(best)) {
                    best = i;
                }
            }
            oracleBytes += sizes.get(best);
            wins.merge(names.get(best), 1, Integer::sum);
            oracleBytesByName.merge(names.get(best), (long) sizes.get(best), Long::sum);
            perBufferSizes.add(sizes);
        }

        /**
         * How many buffers (FeatureTable instances) each encoding is smallest on, corpus-wide — the
         * "does this have potential on some geometry?" question, answered without reading a full
         * per-encoding table or exporting the CSV and pivoting it by hand.
         *
         * <p>One row per registered encoding, including the ones that win nothing: a {@code 0} row is
         * the kill signal this table exists to produce, so it is not filtered out. Win <em>count</em>
         * alone is misleading — {@code docs/results/selection-oracle.md}: an encoding can win hundreds
         * of buffers by a byte each — so each row also carries the bytes saved against that buffer's
         * best baseline, summed over every buffer it won.
         */
        void winCounts(List<String> names) {
            var baselineNames = Baselines.baseline().stream().map(Encoding::name).collect(Collectors.toSet());
            var bytesSaved = new HashMap<String, Long>();
            for (var sizes : perBufferSizes) {
                var best = 0;
                for (var i = 0; i < sizes.size(); i++) {
                    if (sizes.get(i) < sizes.get(best)) {
                        best = i;
                    }
                }
                var baselineBest = names.stream()
                        .filter(baselineNames::contains)
                        .mapToInt(n -> sizes.get(names.indexOf(n)))
                        .min()
                        .orElse(sizes.get(best));
                bytesSaved.merge(names.get(best), (long) (baselineBest - sizes.get(best)), Long::sum);
            }

            /* Measured over the rendered labels, not the raw names: a baseline row carries a
             * " (baseline)" suffix, so sizing this to the names alone would let those rows overflow
             * the field and push their two numeric columns out of line with every other row. */
            var labels = new LinkedHashMap<String, String>();
            for (var name : names) {
                labels.put(name, baselineNames.contains(name) ? name + " (baseline)" : name);
            }
            var nameWidth =
                    Math.max(8, labels.values().stream().mapToInt(String::length).max().orElse(0));

            System.out.printf("%n--- encoding potential (FeatureTable wins) ---%n");
            System.out.printf("%-" + nameWidth + "s %10s %14s%n", "encoding", "wins", "bytes saved");

            var ranked = new ArrayList<>(names);
            ranked.sort(
                    Comparator.<String>comparingInt(n -> wins.getOrDefault(n, 0))
                            .thenComparingLong(n -> bytesSaved.getOrDefault(n, 0L))
                            .reversed());

            for (var name : ranked) {
                System.out.printf(
                        "%-" + nameWidth + "s %10d %14s%n",
                        labels.get(name),
                        wins.getOrDefault(name, 0),
                        String.format("%,d", bytesSaved.getOrDefault(name, 0L)));
            }
        }

        void report(List<String> names, Totals overall) {
            var fixedBest = names.stream().mapToLong(overall::get).min().orElse(0);
            var fixedName = winner(names, overall);
            var fixedBaselineBest = bestBaseline(overall);
            var fixedBaselineName = referenceName(overall);
            var fixedBaselineIndex = names.indexOf(fixedBaselineName);

            var buffersImproved = 0;
            for (var sizes : perBufferSizes) {
                var best = 0;
                for (var i = 0; i < sizes.size(); i++) {
                    if (sizes.get(i) < sizes.get(best)) {
                        best = i;
                    }
                }
                if (sizes.get(best) < sizes.get(fixedBaselineIndex)) {
                    buffersImproved++;
                }
            }

            /* Every row here is "<label><star> <bytes> <delta>", and the label is built from an
             * encoding name whose length is unbounded — so the width is measured over the labels this
             * block will actually print rather than hardcoded. With add-ons enabled the names reach
             * ~40 characters, which any fixed width would overflow, pushing that row's numbers out of
             * line with the rest of the block. */
            /* Every row here is "<label> <bytes> <delta>", and the label is built from an encoding
             * name whose length is unbounded — so the width is measured over the labels this block
             * will actually print rather than hardcoded. With add-ons enabled the names reach ~40
             * characters, which any fixed width would overflow, pushing that row's numbers out of
             * line with the rest of the block. The reference star is part of the label so it stays
             * beside its name instead of floating in the padding. */
            var oracleLabels = new ArrayList<String>();
            oracleLabels.add("always " + fixedName);
            for (var baseline : Baselines.baseline()) {
                var name = baseline.name();
                if (overall.get(name) != 0) {
                    oracleLabels.add(
                            "baseline " + name + (name.equals(fixedBaselineName) ? " *" : ""));
                }
            }
            oracleLabels.add("research oracle");
            var labelWidth = oracleLabels.stream().mapToInt(String::length).max().orElse(15);

            System.out.printf("%n--- per-buffer selection (oracle) ---%n");
            System.out.printf(
                    "%-" + labelWidth + "s %14s%n",
                    "always " + fixedName, String.format("%,d", fixedBest));
            /* Both baselines are printed, not just the smaller one: the encoder picks FastPFOR or
             * Varint per stream, so a gain that only exists against the weaker baseline is not real.
             * The starred row is the reference every percentage here is measured against. */
            for (var baseline : Baselines.baseline()) {
                var name = baseline.name();
                var bytes = overall.get(name);
                if (bytes == 0) {
                    continue;
                }
                System.out.printf(
                        "%-" + labelWidth + "s %14s   %s%n",
                        "baseline " + name + (name.equals(fixedBaselineName) ? " *" : ""),
                        String.format("%,d", bytes),
                        delta(bytes, fixedBaselineBest));
            }
            System.out.printf(
                    "%-" + labelWidth + "s %14s   %s%n",
                    "research oracle",
                    String.format("%,d", oracleBytes),
                    delta(oracleBytes, fixedBaselineBest));

            var headroom = fixedBaselineBest - oracleBytes;
            if (headroom == 0) {
                System.out.printf(
                        "%nNo research encoding is smallest on any buffer, so a per-buffer selector gains%n"
                                + "nothing over the baseline reference.%n");
            } else {
                System.out.printf(
                        "%nAdding every research encoding to a per-buffer selector is worth %s (%.2f%%) over%n"
                                + "the baseline reference, across %d of %d buffers.%n",
                        String.format("%,d", headroom),
                        100.0 * headroom / fixedBaselineBest,
                        buffersImproved,
                        overall.buffers);
            }
            System.out.printf(
                    "%nThe oracle pays nothing to signal its choice and never guesses wrong, so this is the%n"
                            + "ceiling. A real selector costs signalling per stream plus decoder complexity; if the%n"
                            + "headroom above does not clearly exceed that, per-buffer selection cannot pay for%n"
                            + "itself.%n");

            /* The oracle total alone does not say whether it is carried by one encoding or spread
             * across many, and that distinction decides whether a per-buffer selector is shippable:
             * a total dominated by a single encoding is one extra codec to add, while a total spread
             * thin across many is a signalling and decoder-complexity bill the headroom may not cover. */
            var baselineNames = Baselines.baseline().stream().map(Encoding::name).collect(Collectors.toSet());
            var compositionLabels = new LinkedHashMap<String, String>();
            for (var name : wins.keySet()) {
                compositionLabels.put(name, baselineNames.contains(name) ? name + " (baseline)" : name);
            }
            var compositionWidth = Math.max(
                    8, compositionLabels.values().stream().mapToInt(String::length).max().orElse(0));

            System.out.printf("%n--- oracle composition ---%n");
            System.out.printf(
                    "%-" + compositionWidth + "s %10s %7s %14s%n", "encoding", "buffers", "share", "bytes");

            var ranked = new ArrayList<>(wins.keySet());
            ranked.sort(
                    Comparator.<String>comparingInt(wins::get)
                            .thenComparingLong(oracleBytesByName::get)
                            .reversed());

            for (var name : ranked) {
                var bytes = oracleBytesByName.get(name);
                var share = oracleBytes == 0 ? 0.0 : 100.0 * bytes / oracleBytes;
                System.out.printf(
                        "%-" + compositionWidth + "s %10d %6.1f%% %14s%n",
                        compositionLabels.get(name),
                        wins.get(name),
                        share,
                        String.format("%,d", bytes));
            }
        }
    }

    /**
     * How much of what an encoding saves survives gzip — the general-purpose compressor a browser
     * actually applies on top, so this is the realistic delivery size, not an optional check.
     *
     * <p>Every other number in this report is the raw encoded size. This answers a different
     * question: an encoding that wins by shrinking redundancy gzip would have removed anyway has not
     * found real structure. Corpus-scale only — buffered per encoding and gzipped once at report
     * time, rather than per buffer, so the result reflects cross-tile redundancy rather than each
     * gzip stream's own ~18-byte framing overhead. Payload bytes only: {@link StreamMetadata} never
     * materializes header bytes, only a size, so the header cannot enter the stream.
     *
     * <p>Buffers the whole corpus in memory once per selected encoding; with every add-on flag on at
     * once this multiplies, but stays well within reach for this project's tilesets.
     */
    private static final class GzipTotals {

        private final Map<String, ByteArrayOutputStream> streams = new LinkedHashMap<>();
        private final ByteArrayOutputStream oracle = new ByteArrayOutputStream();

        void add(String encoding, byte[] payload) {
            streams.computeIfAbsent(encoding, k -> new ByteArrayOutputStream()).writeBytes(payload);
        }

        void addOracle(byte[] payload) {
            oracle.writeBytes(payload);
        }

        void report(List<String> names) {
            var labels = new ArrayList<>(names);
            labels.add("research oracle");
            var labelWidth = labels.stream().mapToInt(String::length).max().orElse(15);

            System.out.printf(
                    "%n--- corpus gzip (payload bytes only, concatenated then gzipped once) ---%n");
            System.out.printf(
                    "%-" + labelWidth + "s %14s %14s %8s%n", "encoding", "raw", "gzip", "ratio");
            for (var name : names) {
                var raw = streams.containsKey(name) ? streams.get(name).size() : 0;
                printRow(name, labelWidth, raw, gzip(streams.get(name)));
            }
            printRow("research oracle", labelWidth, oracle.size(), gzip(oracle));
        }

        private static void printRow(String label, int labelWidth, int raw, int gzipped) {
            System.out.printf(
                    "%-" + labelWidth + "s %,14d %,14d %8.3f%n",
                    label, raw, gzipped, raw == 0 ? 0.0 : (double) gzipped / raw);
        }

        private static int gzip(ByteArrayOutputStream buffer) {
            if (buffer == null || buffer.size() == 0) {
                return 0;
            }
            var out = new ByteArrayOutputStream();
            try (var gzipOut = new GZIPOutputStream(out)) {
                buffer.writeTo(gzipOut);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return out.size();
        }
    }

    public static void main(String[] args) throws IOException {
        var opts = Options.parse(args);
        var inDir = Path.of(opts.inDir);

        var manifest = ExtractionReader.readManifest(inDir);

        var encodings = selectEncodings(opts);
        var names = encodings.stream().map(Encoding::name).toList();

        var overall = new Totals();
        var byLayer = new TreeMap<String, Totals>();
        var byGeometry = new TreeMap<String, Totals>();
        var selection = new Selection();
        var gzipTotals = new GzipTotals();
        var residuals = new Residuals();
        long rawTotal = 0;

        Writer csv = null;
        if (opts.csvFile != null) {
            var csvPath = Path.of(opts.csvFile);
            if (csvPath.getParent() != null) {
                Files.createDirectories(csvPath.getParent());
            }
            csv = Files.newBufferedWriter(csvPath);
            csv.write("z,x,y,layerName,vertexCount,rawBytes,dominantGeometryType," + String.join(",", names));
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

                var geometry = dominantGeometryType(ctx.geometryTypes());
                var rawBytes = entry.vertexCount() * 2 * Integer.BYTES;
                rawTotal += rawBytes;

                var layerTotals = byLayer.computeIfAbsent(entry.layerName(), k -> new Totals());
                var geometryTotals = byGeometry.computeIfAbsent(geometry, k -> new Totals());
                overall.buffers++;
                layerTotals.buffers++;
                geometryTotals.buffers++;

                var sizes = new ArrayList<Integer>(encodings.size());
                byte[] bestPayload = null;
                var bestTotal = Integer.MAX_VALUE;
                var seenTransforms = new HashSet<String>();
                for (var encoding : encodings) {
                    var encoded = encoding.encode(ctx);
                    verifyRoundTrip(entry, ctx, encoding, encoded);

                    var total = encoded.totalBytes();
                    sizes.add(total);
                    overall.add(encoding.name(), total);
                    layerTotals.add(encoding.name(), total);
                    geometryTotals.add(encoding.name(), total);

                    gzipTotals.add(encoding.name(), encoded.payload());
                    if (total < bestTotal) {
                        bestTotal = total;
                        bestPayload = encoded.payload();
                    }

                    var residualSource = residualSourceOf(encoding);
                    if (residualSource != null && seenTransforms.add(residualSource.rowKey())) {
                        residuals.add(
                                residualSource.rowKey(), residualSource.transform().apply(encoding.emitted(ctx)));
                    }
                }

                selection.add(names, sizes);
                gzipTotals.addOracle(bestPayload);

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
                    for (var size : sizes) {
                        csv.write("," + size);
                    }
                    csv.write("\n");
                }
            }
        } finally {
            if (csv != null) {
                csv.close();
            }
        }

        if (overall.buffers == 0) {
            System.out.println("No buffers matched.");
            return;
        }

        report(
                names, overall, byLayer, byGeometry, selection, residuals, gzipTotals, rawTotal,
                opts.verbose, describeSet(opts));
        if (opts.csvFile != null) {
            System.out.printf("%nWrote %s%n", opts.csvFile);
        }
    }

    /**
     * The encodings this run measures: always the baselines and {@link Baselines#core()}, plus the
     * add-ons the flags asked for.
     *
     * <p>The add-ons are deliberately additive rather than alternative. {@code --reorder} appends a
     * reordered copy of every row already selected instead of replacing it, so a reordered row and
     * its unreordered original always appear in the same table — which is what makes the add-on's
     * contribution readable separately from the encoding's, per {@code docs/evaluation-protocol.md}.
     * The baselines are reordered too, since "reordering alone, on the baseline transform" is itself
     * one of the numbers being compared; the percentage reference stays the unreordered baseline,
     * because {@link #bestBaseline} keys on {@link Baselines#baseline()}'s own names.
     */
    private static List<Encoding> selectEncodings(Options opts) {
        var selected = new ArrayList<Encoding>(Baselines.baseline());
        selected.addAll(Baselines.core());
        if (opts.segmented) {
            selected.addAll(Baselines.segmented());
        }
        selected.removeIf(BenchmarkCli::isRans);
        if (opts.reorder != null) {
            var reordered = new ArrayList<Encoding>(selected.size());
            for (var encoding : selected) {
                reordered.add(new ReorderedEncoding(encoding, opts.reorder));
            }
            selected.addAll(reordered);
        }
        return selected;
    }

    /**
     * rANS is retired as a research direction (not spec-compatible, does not combine with gzip) and
     * is no longer reported by default. {@link Baselines} keeps every rANS row registered so {@link
     * org.maplibre.mlt.research.encoding.VertexBufferCodecTest} still round-trips them; this filter
     * only removes them from what the benchmark measures and prints. Matched on codec identity, not
     * name, so a future rANS variant is not missed by a stale substring check.
     */
    private static boolean isRans(Encoding encoding) {
        if (encoding instanceof VertexBufferCodec codec) {
            return codec.codec() instanceof RansCodec;
        }
        return encoding instanceof SegmentedEncoding;
    }

    /**
     * A transform reachable for residual-bits reporting, plus the row key it should accumulate under.
     *
     * <p>The row key is the transform's own name for a plain row, so {@code QUAD_CORNER_VARINT},
     * {@code QUAD_CORNER_RANS} and {@code QUAD_CORNER_FASTPFOR} collapse onto one {@code QUAD_CORNER}
     * row (same transform, byte-identical residuals). A {@link ReorderedEncoding} gets its own key
     * instead — {@code GeometryReorder#apply} genuinely changes the vertex order and therefore the
     * residual stream, so it is measured as a distinct row, named like every reordered byte column
     * (transform name plus the reorder suffix) rather than collapsed into the unreordered transform's.
     */
    private record ResidualSource(String rowKey, VertexTransform transform) {
    }

    private static ResidualSource residualSourceOf(Encoding encoding) {
        if (encoding instanceof ReorderedEncoding reordered) {
            var inner = residualSourceOf(reordered.inner());
            return inner == null
                    ? null
                    : new ResidualSource(
                            inner.transform().name() + reordered.reorder().nameSuffix(), inner.transform());
        }
        if (encoding instanceof VertexBufferCodec codec) {
            return new ResidualSource(codec.transform().name(), codec.transform());
        }
        if (encoding instanceof SegmentedEncoding segmented) {
            return new ResidualSource(segmented.transform().name(), segmented.transform());
        }
        return null;
    }

    /**
     * Names which add-ons are in play, so a pasted table cannot be mistaken for a default run.
     */
    private static String describeSet(Options opts) {
        var set = new StringBuilder("core -rans");
        if (opts.segmented) {
            set.append(" +segmented");
        }
        if (opts.reorder != null) {
            set.append(" +reorder ").append(opts.reorderName);
        }
        return set.toString();
    }

    // --- reporting ----------------------------------------------------------

    /**
     * Prints the breakdown per layer and per dominant geometry type, then the per-buffer selection
     * oracle.
     *
     * <p>Two different questions are answered per row, and they are deliberately separate columns.
     * The percentages are measured against {@code ref}, the best <em>baseline</em> for that group —
     * that is the honest yardstick, since crediting an encoding for beating a baseline that was not
     * the one to beat would overstate it. The starred cell and the {@code best} column name the
     * smallest encoding overall, which is what you would actually ship for that group and may well be
     * a research encoding rather than a baseline.
     */
    private static void report(
            List<String> names,
            Totals overall,
            Map<String, Totals> byLayer,
            Map<String, Totals> byGeometry,
            Selection selection,
            Residuals residuals,
            GzipTotals gzipTotals,
            long rawTotal,
            boolean verbose,
            String set) {

        System.out.printf(
                "%d buffers, %s raw bytes, set: %s%n%n",
                overall.buffers, String.format("%,d", rawTotal), set);

        segment("layer", names, byLayer, TOP_LAYERS, verbose);
        segment("geometry", names, byGeometry, Integer.MAX_VALUE, verbose);
        residuals.report();
        selection.winCounts(names);
        selection.report(names, overall);
        gzipTotals.report(names);
    }

    /**
     * One breakdown table: a row per group, a column per encoding, sorted by group size and cut to
     * {@code limit} rows. Everything past the cut is folded into a single {@code (other)} row rather
     * than dropped, so the column totals still add up to the corpus figures above.
     */
    private static void segment(
            String label, List<String> names, Map<String, Totals> groups, int limit, boolean verbose) {

        /* Ranked by bytes, not by buffer count: a buffer is one (tile, layer) pair, so counting them
         * measures how many tiles a layer appears in, not how much data it is. `building` is 311
         * buffers and 3.2 MB; `place` is 1090 buffers and 0.24 MB. Since the question is where an
         * encoding can save bytes, the big layers are the byte-heavy ones. */
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

        if (!verbose) {
            System.out.printf("%n--- by %s ---%n", label);
            compactTable(label, names, shown);
            return;
        }

        /* Encodings are printed in chunks so a row fits a terminal, the whole table repeating per
         * chunk with the same rows. Nothing is dropped: the reference, the winner and the percentages
         * are all computed over *every* encoding, so a chunk that happens to exclude the winner still
         * names it in the `best` column and still measures against the same baseline. */
        for (var from = 0; from < names.size(); from += COLUMNS_PER_TABLE) {
            var chunk = names.subList(from, Math.min(from + COLUMNS_PER_TABLE, names.size()));
            var part =
                    names.size() <= COLUMNS_PER_TABLE
                            ? ""
                            : String.format(
                            " (%d/%d)",
                            from / COLUMNS_PER_TABLE + 1,
                            (names.size() + COLUMNS_PER_TABLE - 1) / COLUMNS_PER_TABLE);
            System.out.printf("%n--- by %s%s ---%n", label, part);
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

        var width =
                Math.max(label.length(), shown.stream().mapToInt(g -> g.getKey().length()).max().orElse(8));
        /* Sized to the winners this table actually prints, not to the widest name in the registry:
         * with every add-on enabled the longest registered name is ~40 chars, which would pad this
         * column with ~25 blanks on every row and push the two numeric columns off to the right. */
        var nameWidth =
                Math.max(
                        "winner".length(),
                        shown.stream()
                                .filter(g -> !g.getKey().startsWith("(other"))
                                .map(g -> winner(names, g.getValue()))
                                .mapToInt(String::length)
                                .max()
                                .orElse("winner".length()));
        var corpus = shown.stream().mapToLong(g -> bestBaseline(g.getValue())).sum();

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
                        "%-" + width + "s %8d %6.1f%% %14s   %-" + nameWidth + "s %14s %11s%n",
                        group.getKey(), totals.buffers, share, String.format("%,d", reference), "-", "-", "-");
                continue;
            }

            var winner = winner(names, totals);
            System.out.printf(
                    "%-" + width + "s %8d %6.1f%% %14s   %-" + nameWidth + "s %14s %11s%n",
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

        var width =
                Math.max(label.length(), shown.stream().mapToInt(g -> g.getKey().length()).max().orElse(8));
        // Sized to the winners actually printed, for the same reason as in compactTable.
        var nameWidth =
                Math.max(
                        "best".length(),
                        shown.stream()
                                .map(g -> winner(names, g.getValue()))
                                .mapToInt(String::length)
                                .max()
                                .orElse("best".length()));
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
        return names.stream()
                .filter(name -> totals.get(name) > 0)
                .min((a, b) -> Long.compare(totals.get(a), totals.get(b)))
                .orElse("-");
    }

    /**
     * Which baseline the percentages in this group are measured against.
     */
    private static String referenceName(Totals totals) {
        return Baselines.baseline().stream()
                .map(Encoding::name)
                .filter(name -> totals.get(name) > 0)
                .min((a, b) -> Long.compare(totals.get(a), totals.get(b)))
                .orElse("-");
    }

    /**
     * The smaller of the two baselines for this group — what a new encoding has to beat. Comparing
     * against the better baseline rather than a fixed one avoids crediting an encoding for beating an
     * opponent that was never the one to beat.
     */
    private static long bestBaseline(Totals totals) {
        return Baselines.baseline().stream()
                .map(Encoding::name)
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
        var widestName = chunk.stream().mapToInt(String::length).max().orElse(0);
        return Math.max(CELL_WIDTH, widestName);
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
    private static void verifyRoundTrip(
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
            boolean segmented,
            GeometryReorder reorder,
            String reorderName) {

        /**
         * The extraction directory used when none is given on the command line — the project's
         * current baseline corpus (planetiler-derived, see {@code docs/results/README.md}).
         */
        private static final String DEFAULT_IN_DIR = "../data/extractedBaseline";

        private static final String USAGE =
                "Usage: [extractionDir] [--csv <file>] [--layer <name>] [--verbose] [--segmented]"
                        + " [--reorder <feature|feature-rev>]";

        static Options parse(String[] args) {
            String in = null;
            String csv = null;
            String layer = null;
            var verbose = false;
            var segmented = false;
            GeometryReorder reorder = null;
            String reorderName = null;
            for (var i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--csv" -> csv = args[++i];
                    case "--layer" -> layer = args[++i];
                    case "--verbose" -> verbose = true;
                    case "--segmented" -> segmented = true;
                    case "--reorder" -> {
                        reorderName = args[++i];
                        reorder = switch (reorderName) {
                            case "feature" -> GeometryReorder.FEATURE;
                            case "feature-rev" -> GeometryReorder.FEATURE_REV;
                            default -> throw new IllegalArgumentException(
                                    "Unknown reorder mode: " + reorderName + "\n" + USAGE);
                        };
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
            return new Options(in, csv, layer, verbose, segmented, reorder, reorderName);
        }
    }
}
