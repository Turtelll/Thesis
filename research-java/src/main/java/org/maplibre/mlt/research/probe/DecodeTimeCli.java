package org.maplibre.mlt.research.probe;

import org.maplibre.mlt.research.baseline.Baselines;
import org.maplibre.mlt.research.buffer.Encoding;
import org.maplibre.mlt.research.encoder.stream.IntCodec;
import org.maplibre.mlt.research.baseline.VertexBufferCodec;
import org.maplibre.mlt.research.buffer.Encoding.BufferContext;
import org.maplibre.mlt.research.buffer.Encoding.EncodedBuffer;
import org.maplibre.mlt.research.baseline.VertexTransform;
import org.maplibre.mlt.research.registry.PipelineRegistry;
import org.maplibre.mlt.research.measurement.verification.Verification;
import org.maplibre.mlt.research.extraction.ExtractionReader;
import org.maplibre.mlt.research.extraction.ExtractionWriter.ManifestEntry;
import org.maplibre.mlt.research.extraction.Corpus;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Decode-time probe: how much slower (or faster) a research encoding decodes than the baseline it
 * would replace, isolated at both the {@link IntCodec} layer and the full {@link Encoding} layer.
 *
 * <p>Every size result in {@code docs/results/} is only half of the tradeoff: an encoding that
 * saves bytes but decodes several times slower than the baseline is not a win for a tile client.
 * {@code --encoding} picks any registered encoding by name; both {@link Baselines#baseline()} rows
 * are always measured beside it, and each row is compared against the baseline with the same
 * codec, so a codec difference is never credited to or blamed on the idea. Without {@code
 * --encoding} the baselines and the pipeline's default step (the rANS and segmented-codec grid of {@code
 * docs/results/decode-time.md} is history, reproducible at the {@code pre-consolidation} tag).
 *
 * <p>Method: in-process, single-threaded, steady-state JVM timing via {@code System.nanoTime()}.
 * No JMH (not a project dependency). A full warmup pass over the corpus runs before any timing is
 * recorded, and each buffer is decoded {@code --reps} times per row, keeping the minimum — the
 * standard robust estimator against GC/scheduler noise. This is <b>not</b> a cold-start,
 * client-side number: a browser decoder pays JIT warmup cost this harness deliberately excludes.
 *
 * <p>Usage: {@code ./gradlew research --args="decode-time ../data/extractedBaseline --encoding
 * PIPE_S8 --layer transportation --reps 5 --warmup 2 --max-ratio 1.5"}
 */
public final class DecodeTimeCli {
    private DecodeTimeCli() {
    }

    private static final int[] BUCKET_BOUNDARIES = {50, 200, 1000, 5000};
    private static final String[] BUCKET_LABELS = {
            "0-50", "50-200", "200-1000", "1000-5000", "5000-inf"
    };

    /** Without {@code --encoding}: the baselines and the rows of this pipeline step. */
    private static final String DEFAULT_STEP = "s8";

    /** Name prefix of the baseline transform's rows, e.g. {@code DELTA_ZIGZAG_VARINT_XY}. */
    private static final String REFERENCE_TRANSFORM = "DELTA_ZIGZAG_";

    private static final String USAGE =
            "Usage: [extractionDir] [--encoding <NAME>[,<NAME>...]] [--layer <name>] [--reps <n>]"
                    + " [--warmup <n>] [--max-ratio <x>] [--csv <file>]";

    /**
     * The part of a row's decode that is {@link IntCodec#decode} alone. Present only when the
     * payload is exactly that codec's output — a composite payload (a mode byte, a flag bitmap, a
     * reference stream) cannot be handed to the codec on its own.
     */
    private record CodecOnly(IntCodec codec, VertexTransform transform) {
        int valueCount(BufferContext ctx, int vertexCount) {
            return transform.valueCount(ctx, vertexCount);
        }
    }

    private record Row(String name, Encoding encoding, Optional<CodecOnly> codecOnly) {
        static Row of(Encoding encoding) {
            final Optional<CodecOnly> codecOnly;
            if (encoding instanceof VertexBufferCodec codec) {
                codecOnly = Optional.of(new CodecOnly(codec.codec(), codec.transform()));
            } else {
                codecOnly = Optional.empty();
            }
            return new Row(encoding.name(), encoding, codecOnly);
        }
    }

    /**
     * Every encoding selectable by name: {@link Baselines#all()} and the pipeline's rows.
     */
    private static Map<String, Encoding> candidates() {
        final var byName = new LinkedHashMap<String, Encoding>();
        for (final var encoding : Baselines.all()) {
            byName.put(encoding.name(), encoding);
        }
        // the OpenZL-style pipeline's rows (PIPE_*), measurable by name like any other encoding
        for (final var encoding : PipelineRegistry.all()) {
            byName.putIfAbsent(encoding.name(), encoding);
        }
        return byName;
    }

    /**
     * The baselines first, then each requested encoding once, in the order asked for.
     */
    private static List<Row> rows(List<String> requested) {
        final var byName = candidates();
        final var names = new ArrayList<String>();
        if (requested.isEmpty()) {
            for (final var baseline : Baselines.baseline()) {
                names.add(baseline.name());
            }
            for (final var encoding : PipelineRegistry.step(DEFAULT_STEP)) {
                names.add(encoding.name());
            }
        } else {
            for (final var baseline : Baselines.baseline()) {
                names.add(baseline.name());
            }
            for (final var name : requested) {
                // A row on a serializer neither baseline uses is timed against DELTA_ZIGZAG on that serializer.
                final var sameCodecReference = Optional.ofNullable(byName.get(name))
                        .flatMap(DecodeTimeCli::codecOf)
                        .map(codec -> REFERENCE_TRANSFORM + codec.name())
                        .filter(byName::containsKey);
                if (sameCodecReference.isPresent() && !names.contains(sameCodecReference.get())) {
                    names.add(sameCodecReference.get());
                }
                if (!names.contains(name)) {
                    names.add(name);
                }
            }
        }
        final var rows = new ArrayList<Row>(names.size());
        for (final var name : names) {
            final var encoding = byName.get(name);
            if (encoding == null) {
                throw new IllegalArgumentException(
                        "Unknown encoding " + name + ". Available: " + String.join(", ", byName.keySet()));
            }
            rows.add(Row.of(encoding));
        }
        return rows;
    }

    private static Optional<IntCodec> codecOf(Encoding encoding) {
        if (encoding instanceof VertexBufferCodec codec) {
            return Optional.of(codec.codec());
        }
        return Optional.empty();
    }

    /**
     * The baseline row a row is compared against: the one with the same codec if it was measured,
     * otherwise the first baseline present (FastPFOR), otherwise the first row.
     */
    private static Row referenceOf(Row row, List<Row> rows) {
        final var references = new ArrayList<>(Baselines.baseline().stream().map(Encoding::name).toList());
        final var codecName = codecOf(row.encoding()).map(IntCodec::name);
        Row fallback = null;
        for (final var candidate : rows) {
            if (!references.contains(candidate.name())) {
                continue;
            }
            if (fallback == null) {
                fallback = candidate;
            }
            if (codecName.isPresent() && codecName.equals(codecOf(candidate.encoding()).map(IntCodec::name))) {
                return candidate;
            }
        }
        return fallback != null ? fallback : rows.get(0);
    }

    private static boolean isBaseline(Row row) {
        return Baselines.baseline().stream().anyMatch(b -> b.name().equals(row.name()));
    }

    // ---------------------------------------------------------------------------
    // Per-row, per-bucket accumulators
    // ---------------------------------------------------------------------------

    private static final class Stats {
        long nanos;
        long values;
        int buffers;

        void add(long nanosMin, int valueCount) {
            nanos += nanosMin;
            values += valueCount;
            buffers++;
        }
    }

    /**
     * Sink for every decoded value, so the JIT cannot prove a decode call's result is unused and
     * eliminate it as dead code. Printed at the end; the value itself is meaningless.
     */
    private static long sink;

    public static void main(String[] args) throws IOException {
        String inArg = null;
        String layer = null;
        var reps = 5;
        var warmup = 2;
        var maxRatio = 1.5;
        String csvFile = null;
        final var requested = new ArrayList<String>();
        for (var i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--encoding" -> {
                    for (final var name : args[++i].split(",")) {
                        if (!name.isBlank()) {
                            requested.add(name.trim());
                        }
                    }
                }
                case "--layer" -> layer = args[++i];
                case "--reps" -> reps = Integer.parseInt(args[++i]);
                case "--warmup" -> warmup = Integer.parseInt(args[++i]);
                case "--max-ratio" -> maxRatio = Double.parseDouble(args[++i]);
                case "--csv" -> csvFile = args[++i];
                case "--help", "-h" -> throw new IllegalArgumentException(USAGE);
                default -> {
                    if (args[i].startsWith("--")) {
                        final var bare = args[i].substring(2);
                        final var hint = candidates().containsKey(bare)
                                ? " Did you mean --encoding " + bare + "?"
                                : "";
                        throw new IllegalArgumentException("Unknown option " + args[i] + "." + hint + " " + USAGE);
                    }
                    inArg = args[i];
                }
            }
        }
        final var inDir = Path.of(inArg != null ? inArg : "../data/extractedBaseline");
        final var manifest = ExtractionReader.readManifest(inDir);
        final var rows = rows(requested);

        // Load every in-scope buffer once; the same list is walked for warmup and for timing, so
        // both see an identical corpus and extraction I/O is never part of a timed measurement.
        final var buffers = new ArrayList<BufferContext>();
        final var entries = new ArrayList<ManifestEntry>();
        for (final var entry : manifest) {
            if (layer != null && !layer.equals(entry.layerName())) {
                continue;
            }
            final var ctx = ExtractionReader.readContext(inDir, entry);
            if (ctx.vertices().isEmpty()) {
                continue;
            }
            buffers.add(ctx);
            entries.add(entry);
        }
        if (buffers.isEmpty()) {
            throw new IllegalArgumentException(
                    "No buffers found under " + inDir + (layer != null ? " for layer " + layer : ""));
        }

        // Encode once per (row, buffer) up front and verify the round trip once — untimed, and a
        // timing number from a broken decoder is not a result.
        final var payloads = new EncodedBuffer[rows.size()][buffers.size()];
        for (var r = 0; r < rows.size(); r++) {
            final var row = rows.get(r);
            for (var b = 0; b < buffers.size(); b++) {
                final var ctx = buffers.get(b);
                final var encoded = row.encoding().encode(ctx);
                payloads[r][b] = encoded;
                Verification.roundTrip(Corpus.tileKey(entries.get(b)) + " " + entries.get(b).layerName(), ctx, row.encoding(), encoded);
            }
        }

        System.out.printf(
                "Decode-time probe: %,d buffers%s, %d row(s), %d warmup pass(es), %d timed rep(s) (min kept).%n",
                buffers.size(), layer != null ? " (layer=" + layer + ")" : "", rows.size(), warmup, reps);

        // Warmup: full passes over every (row, buffer), discarding all timings, so C2 has compiled
        // every decode path before anything is recorded. Without this the first-touched row absorbs
        // interpreter/C1 cost and the result is an artifact of row order.
        for (var w = 0; w < warmup; w++) {
            for (var r = 0; r < rows.size(); r++) {
                final var row = rows.get(r);
                for (var b = 0; b < buffers.size(); b++) {
                    final var ctx = buffers.get(b);
                    final var encoded = payloads[r][b];
                    final var vertexCount = ctx.vertices().size();
                    if (row.codecOnly().isPresent()) {
                        final var codecOnly = row.codecOnly().get();
                        sink += codecOnly.codec()
                                .decode(encoded.payload(), codecOnly.valueCount(ctx, vertexCount)).length;
                    }
                    sink += row.encoding().decode(encoded, ctx, vertexCount).size();
                }
            }
        }

        // Timed pass: per row, a bucket-indexed Stats array; bucket 0..3 by vertex-count boundary,
        // bucket 4 = 5000+ — mirrors docs/results/rans-entropy-coder.md's buffer-size table, so this
        // result reads directly against the existing size result.
        final var codecStats = new TreeMap<String, Stats[]>();
        final var encodingStats = new TreeMap<String, Stats[]>();
        for (final var row : rows) {
            codecStats.put(row.name(), newBucketStats());
            encodingStats.put(row.name(), newBucketStats());
        }

        for (var r = 0; r < rows.size(); r++) {
            final var row = rows.get(r);
            for (var b = 0; b < buffers.size(); b++) {
                final var ctx = buffers.get(b);
                final var encoded = payloads[r][b];
                final var payload = encoded.payload();
                final var vertexCount = ctx.vertices().size();
                final var bucket = bucketOf(vertexCount);

                if (row.codecOnly().isPresent()) {
                    final var codecOnly = row.codecOnly().get();
                    final var valueCount = codecOnly.valueCount(ctx, vertexCount);
                    var codecMin = Long.MAX_VALUE;
                    for (var i = 0; i < reps; i++) {
                        final var start = System.nanoTime();
                        sink += codecOnly.codec().decode(payload, valueCount).length;
                        final var elapsed = System.nanoTime() - start;
                        codecMin = Math.min(codecMin, elapsed);
                    }
                    codecStats.get(row.name())[bucket].add(codecMin, valueCount);
                }

                var encodingMin = Long.MAX_VALUE;
                for (var i = 0; i < reps; i++) {
                    final var start = System.nanoTime();
                    sink += row.encoding().decode(encoded, ctx, vertexCount).size();
                    final var elapsed = System.nanoTime() - start;
                    encodingMin = Math.min(encodingMin, elapsed);
                }
                encodingStats.get(row.name())[bucket].add(encodingMin, vertexCount * 2);
            }
        }

        report("Table A: IntCodec.decode (codec only)", rows, codecStats, true);
        report("Table B: Encoding.decode (end-to-end, transform + codec)", rows, encodingStats, false);
        summary(rows, encodingStats, maxRatio);
        System.out.printf("%n(sink=%d, ignore — keeps decode results observably used)%n", sink);

        if (csvFile != null) {
            writeCsv(Path.of(csvFile), rows, codecStats, encodingStats);
        }
    }

    private static Stats[] newBucketStats() {
        final var arr = new Stats[BUCKET_LABELS.length];
        for (var i = 0; i < arr.length; i++) {
            arr[i] = new Stats();
        }
        return arr;
    }

    private static int bucketOf(int vertexCount) {
        for (var i = 0; i < BUCKET_BOUNDARIES.length; i++) {
            if (vertexCount < BUCKET_BOUNDARIES[i]) {
                return i;
            }
        }
        return BUCKET_BOUNDARIES.length;
    }

    // ---------------------------------------------------------------------------
    // Reporting
    // ---------------------------------------------------------------------------

    private static int columnWidth(List<Row> rows) {
        return Math.max(20, rows.stream().mapToInt(r -> r.name().length()).max().orElse(0));
    }

    private static void report(String title, List<Row> rows, TreeMap<String, Stats[]> stats, boolean codecOnly) {
        System.out.println();
        System.out.println("--- " + title + " ---");
        final var width = columnWidth(rows);
        final var cell = " %" + width + "s";

        System.out.printf("%-14s %6s", "bucket", "bufs");
        for (final var row : rows) {
            System.out.printf(cell, row.name());
        }
        System.out.println(" (ns/value)");

        for (var bucket = 0; bucket < BUCKET_LABELS.length; bucket++) {
            var bufsInBucket = 0;
            for (final var row : rows) {
                bufsInBucket = Math.max(bufsInBucket, stats.get(row.name())[bucket].buffers);
            }
            System.out.printf("%-14s %6d", BUCKET_LABELS[bucket], bufsInBucket);
            for (final var row : rows) {
                if (codecOnly && row.codecOnly().isEmpty()) {
                    System.out.printf(cell, "-");
                    continue;
                }
                final var s = stats.get(row.name())[bucket];
                final var nsPerValue = s.values == 0 ? 0.0 : (double) s.nanos / s.values;
                System.out.printf(cell, String.format("%.1f", nsPerValue));
            }
            System.out.println();
        }

        System.out.printf("%-14s %6s", "corpus total", "");
        for (final var row : rows) {
            if (codecOnly && row.codecOnly().isEmpty()) {
                System.out.printf(cell, "-");
                continue;
            }
            System.out.printf(cell, String.format("%.3f ms", totalNanos(stats.get(row.name())) / 1_000_000.0));
        }
        System.out.println();

        System.out.printf("%-14s %6s", "x reference", "");
        for (final var row : rows) {
            final var reference = referenceOf(row, rows);
            if (codecOnly && (row.codecOnly().isEmpty() || reference.codecOnly().isEmpty())) {
                System.out.printf(cell, "-");
                continue;
            }
            final var referenceTotal = totalNanos(stats.get(reference.name()));
            final var ratio = referenceTotal == 0 ? 0.0 : (double) totalNanos(stats.get(row.name())) / referenceTotal;
            System.out.printf(cell, String.format("%.2fx", ratio));
        }
        System.out.println();
    }

    /**
     * One verdict line per non-baseline row, on end-to-end decode time — what a tile client
     * actually pays. Report only: a row over the limit is flagged, the run still succeeds.
     */
    private static void summary(List<Row> rows, TreeMap<String, Stats[]> stats, double maxRatio) {
        System.out.println();
        System.out.printf("--- Decode time vs same-codec baseline (end-to-end, limit %.2fx) ---%n", maxRatio);
        final var width = columnWidth(rows);
        System.out.printf("%-" + width + "s %-" + width + "s %12s %18s%n",
                "encoding", "reference", "corpus", "worst bucket");
        var flagged = 0;
        var compared = 0;
        for (final var row : rows) {
            if (isBaseline(row)) {
                continue;
            }
            compared++;
            final var reference = referenceOf(row, rows);
            final var rowStats = stats.get(row.name());
            final var refStats = stats.get(reference.name());
            final var corpus = ratio(totalNanos(rowStats), totalNanos(refStats));

            var worst = 0.0;
            var worstBucket = "-";
            for (var bucket = 0; bucket < BUCKET_LABELS.length; bucket++) {
                if (rowStats[bucket].buffers == 0 || refStats[bucket].nanos == 0) {
                    continue;
                }
                final var r = ratio(rowStats[bucket].nanos, refStats[bucket].nanos);
                if (r > worst) {
                    worst = r;
                    worstBucket = BUCKET_LABELS[bucket];
                }
            }
            final var over = corpus > maxRatio;
            if (over) {
                flagged++;
            }
            System.out.printf("%-" + width + "s %-" + width + "s %11.2fx %9.2fx %-8s%s%n",
                    row.name(), reference.name(), corpus, worst, worstBucket, over ? " OVER LIMIT" : "");
        }
        if (compared == 0) {
            System.out.println("(no research encoding selected — pass --encoding <NAME>)");
        } else {
            System.out.printf("%d of %d row(s) over the %.2fx limit.%n", flagged, compared, maxRatio);
        }
    }

    private static double ratio(long nanos, long referenceNanos) {
        return referenceNanos == 0 ? 0.0 : (double) nanos / referenceNanos;
    }

    private static long totalNanos(Stats[] buckets) {
        var total = 0L;
        for (final var s : buckets) {
            total += s.nanos;
        }
        return total;
    }

    private static void writeCsv(
            Path path, List<Row> rows, TreeMap<String, Stats[]> codecStats, TreeMap<String, Stats[]> encodingStats)
            throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        try (Writer w = Files.newBufferedWriter(path)) {
            w.write("encoding,bucket,buffers,values,codecNanos,encodingNanos,reference,ratioEndToEnd\n");
            for (final var row : rows) {
                final var reference = referenceOf(row, rows);
                final var codec = codecStats.get(row.name());
                final var end = encodingStats.get(row.name());
                final var refEnd = encodingStats.get(reference.name());
                for (var bucket = 0; bucket < BUCKET_LABELS.length; bucket++) {
                    final var c = codec[bucket];
                    final var e = end[bucket];
                    w.write(String.join(
                            ",",
                            row.name(),
                            BUCKET_LABELS[bucket],
                            String.valueOf(e.buffers),
                            String.valueOf(e.values),
                            row.codecOnly().isPresent() ? String.valueOf(c.nanos) : "",
                            String.valueOf(e.nanos),
                            reference.name(),
                            String.format(Locale.ROOT, "%.4f", ratio(e.nanos, refEnd[bucket].nanos))));
                    w.write("\n");
                }
            }
        }
    }
}
