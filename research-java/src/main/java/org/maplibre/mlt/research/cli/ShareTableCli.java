package org.maplibre.mlt.research.cli;

import org.maplibre.mlt.research.encoding.Layout;
import org.maplibre.mlt.research.encoding.PredictedTransform;
import org.maplibre.mlt.research.encoding.research.SegmentQuadCorner;
import org.maplibre.mlt.research.extraction.ExtractionReader;
import org.maplibre.mlt.research.extraction.ExtractionWriter.ManifestEntry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Probe: does sharing an {@code RansCodec} frequency table across several buffers pay for itself?
 *
 * <p>{@code SegmentedRansCodec} ({@code QUAD_CORNER_SEG_RANSSEG}) gives each of
 * {@link SegmentQuadCorner}'s three residual regions its own 66-byte table — a fixed
 * ~152 bytes/buffer cost that {@code docs/results/rans-entropy-coder.md} finds sinks the corpus
 * (+9,62%) on the small-buffer majority. A table shared across buffers removes that fixed cost on
 * every buffer but the one that ships it, at the price of a <b>mixture penalty</b>: the shared
 * table prices every member buffer's distribution at their average instead of its own. This probe
 * measures that tradeoff directly, in ideal code length, without building a coder — per
 * {@code docs/evaluation-protocol.md} step 1.
 *
 * <p><b>Two candidate causes, one probe.</b> {@code --gzip}'s good ratio on {@code RANSSEG}
 * payloads could mean the per-buffer tables resemble each other (share them), or it could mean
 * each table is mostly zeros — {@code RansCodec.writeTable} emits all 33 symbols unconditionally,
 * so a region with 4 present buckets still pays 66 bytes, 58 of them zero (serialize sparsely,
 * no cross-buffer agreement needed). Both are measured so the confound is resolved rather than
 * assumed.
 *
 * <p>Reports five rows — per-buffer (today), sparse-table control, per-tile shared,
 * per-layer-across-tiles shared, corpus-static (a held-out ceiling) — bucketed by the same
 * 0-50/50-200/200-1000/1000-5000/5000+ vertex buckets {@code rans-entropy-coder.md} and
 * {@code decode-time.md} use, so this reads directly against the documented crossover.
 *
 * <p>Usage: {@code ./gradlew shareTable --args="../data/extractedBaseline [--layer <name>]"}
 */
public final class ShareTableCli {
    private ShareTableCli() {
    }

    private static final int MAX_SYMBOL = 33;

    /**
     * One buffer's three region histograms, plus enough identity to group it by tile/layer/bucket.
     */
    private record BufferHistograms(
            String layerName, long tileKeyHash, int vertexCount, long[][] regions) {
    }

    public static void main(String[] args) throws IOException {
        String inArg = null;
        String layer = null;
        for (var i = 0; i < args.length; i++) {
            if (args[i].equals("--layer") && i + 1 < args.length) {
                layer = args[++i];
            } else if (!args[i].startsWith("--")) {
                inArg = args[i];
            }
        }
        if (inArg == null) {
            throw new IllegalArgumentException("Usage: <extractionDir> [--layer <name>]");
        }
        final var inDir = Path.of(inArg);
        final var manifest = ExtractionReader.readManifest(inDir);

        final var transform = new PredictedTransform(new SegmentQuadCorner(), Layout.SEGMENTED);
        final var predictor = new SegmentQuadCorner();

        final var buffers = new ArrayList<BufferHistograms>();

        for (final var entry : manifest) {
            if (layer != null && !entry.layerName().equals(layer)) {
                continue;
            }
            final var ctx = ExtractionReader.readContext(inDir, entry);
            if (ctx.vertices().isEmpty()) {
                continue;
            }

            final var values = transform.apply(ctx);
            final var lengths = predictor.regionLengths(ctx, ctx.vertices().size());

            final var regions = new long[3][MAX_SYMBOL];
            var offset = 0;
            for (var r = 0; r < 3; r++) {
                for (var i = 0; i < lengths[r]; i++) {
                    regions[r][bucket(values[offset + i])]++;
                }
                offset += lengths[r];
            }

            buffers.add(
                    new BufferHistograms(
                            entry.layerName(), tileKey(entry), entry.vertexCount(), regions));
        }

        if (buffers.isEmpty()) {
            System.out.println("No buffers matched.");
            return;
        }

        report(buffers);
    }

    /**
     * Mirrors {@code RansCodec.bucket} (RansCodec.java:61-63): the residual's bit-length is the
     * entropy-coded symbol. Kept as a local mirror rather than a shared reference so a change to
     * the real coder's alphabet cannot silently drift this probe out of sync with what it measures —
     * the same convention {@code EntropyCli} uses for its {@code classify} mirror.
     */
    private static int bucket(int value) {
        return 32 - Integer.numberOfLeadingZeros(value);
    }

    private static long tileKey(ManifestEntry entry) {
        return ((long) entry.z() << 48) ^ ((long) entry.x() << 24) ^ entry.y();
    }

    // ---------------------------------------------------------------------------
    // Cost model
    // ---------------------------------------------------------------------------

    /**
     * Ideal code length in bits for histogram {@code c} priced under table {@code p} (not
     * necessarily its own): {@code -sum_s c[s] * log2(p[s] / sum(p))}. {@code bits(c, c)} is the
     * per-buffer optimum; {@code bits(c, shared)} is what a shared table actually costs, and the
     * gap between the two is the mixture penalty a real coder would pay.
     *
     * <p>If {@code c} uses a symbol absent from {@code p} (frequency 0), that symbol would be
     * unencodable under a real table; this charges it {@code log2(sum(p))} instead of failing, so
     * a missing-symbol case shows up in the reported penalty rather than as {@code NaN}.
     */
    private static double bits(long[] c, long[] p) {
        var total = 0L;
        for (final var v : p) {
            total += v;
        }
        if (total == 0) {
            return 0;
        }
        var result = 0.0;
        for (var s = 0; s < c.length; s++) {
            if (c[s] == 0) {
                continue;
            }
            if (p[s] <= 0) {
                // Unencodable under p: charge the same log2(total) worst case RansCodec's flatTable
                // fallback would, so a missing symbol shows up as a large penalty rather than NaN.
                result += c[s] * (Math.log(total) / Math.log(2));
                continue;
            }
            result -= c[s] * (Math.log((double) p[s] / total) / Math.log(2));
        }
        return result;
    }

    private static long[] sum(List<long[]> histograms) {
        final var total = new long[MAX_SYMBOL];
        for (final var h : histograms) {
            for (var s = 0; s < MAX_SYMBOL; s++) {
                total[s] += h[s];
            }
        }
        return total;
    }

    /**
     * Number of symbols with at least one occurrence — the sparse table's per-symbol entry count.
     */
    private static int presentSymbols(long[] histogram) {
        var present = 0;
        for (final var v : histogram) {
            if (v > 0) {
                present++;
            }
        }
        return present;
    }

    /**
     * Dense table bytes for one region: {@code RansCodec.writeTable} always writes all 33 symbols.
     */
    private static final int DENSE_TABLE_BYTES = MAX_SYMBOL * 2;

    /**
     * Sparse control format: {@code [1 B count][count x (1 B symbol, 2 B frequency)]}.
     */
    private static int sparseTableBytes(long[] histogram) {
        return 1 + presentSymbols(histogram) * 3;
    }

    // ---------------------------------------------------------------------------
    // Reporting
    // ---------------------------------------------------------------------------

    private static final int[] BUCKET_EDGES = {50, 200, 1000, 5000, Integer.MAX_VALUE};
    private static final String[] BUCKET_NAMES = {
        "0-50", "50-200", "200-1000", "1000-5000", "5000-inf"
    };

    private static int bucketOf(int vertexCount) {
        for (var i = 0; i < BUCKET_EDGES.length; i++) {
            if (vertexCount < BUCKET_EDGES[i]) {
                return i;
            }
        }
        return BUCKET_EDGES.length - 1;
    }

    private static void report(List<BufferHistograms> buffers) {
        System.out.printf(
                "Shared-table probe: %,d buffers, %,d bytes of dense per-buffer tables today.%n%n",
                buffers.size(), (long) buffers.size() * 3 * (DENSE_TABLE_BYTES + 8) + 4L * buffers.size());

        // Per-buffer optimum, sparse control, and sparsity/present-symbol stats, per bucket.
        reportPerBufferAndSparse(buffers);

        // Per-tile grouping: all layers in one tile share one table set per region.
        final Map<Long, List<BufferHistograms>> byTile = new LinkedHashMap<>();
        for (final var b : buffers) {
            byTile.computeIfAbsent(b.tileKeyHash(), k -> new ArrayList<>()).add(b);
        }
        reportGrouping("per-tile (all layers in a tile share one table set)", buffers, byTile.values());

        // Per-layer-across-tiles grouping: same MVT layer, every tile, shares one table set.
        final Map<String, List<BufferHistograms>> byLayer = new TreeMap<>();
        for (final var b : buffers) {
            byLayer.computeIfAbsent(b.layerName(), k -> new ArrayList<>()).add(b);
        }
        reportGrouping(
                "per-layer-across-tiles (one MVT layer, every tile, shares one table set)",
                buffers,
                byLayer.values());

        // Corpus-static: train on the first half of groups (by insertion order), price the second
        // half. A pooled single-pass number would repeat the exact error rans-entropy-coder.md
        // calls out (C4's 16,76% ideal never priced the per-buffer cost that decides small buffers).
        reportCorpusStatic(buffers);
    }

    private static void reportPerBufferAndSparse(List<BufferHistograms> buffers) {
        System.out.printf("--- per-buffer (today) vs sparse-table control, by vertex-count bucket ---%n");
        System.out.printf(
                "%-10s %8s %14s %14s %10s %10s %12s%n",
                "bucket", "bufs", "dense bytes", "sparse bytes", "saved", "saved%", "meanPresent");

        final var denseByBucket = new long[BUCKET_NAMES.length];
        final var sparseByBucket = new long[BUCKET_NAMES.length];
        final var bufsByBucket = new int[BUCKET_NAMES.length];
        final var presentSumByBucket = new long[BUCKET_NAMES.length];
        final var presentCountByBucket = new long[BUCKET_NAMES.length];

        for (final var b : buffers) {
            final var bucket = bucketOf(b.vertexCount());
            bufsByBucket[bucket]++;
            // Framing: SegmentedRansCodec.encode's 4 + 3*8 = 28 bytes, identical for dense and sparse.
            denseByBucket[bucket] += 28L + 3L * DENSE_TABLE_BYTES;
            for (var r = 0; r < 3; r++) {
                sparseByBucket[bucket] += sparseTableBytes(b.regions()[r]);
                presentSumByBucket[bucket] += presentSymbols(b.regions()[r]);
                presentCountByBucket[bucket]++;
            }
            sparseByBucket[bucket] += 28;
        }

        long denseTotal = 0;
        long sparseTotal = 0;
        for (var i = 0; i < BUCKET_NAMES.length; i++) {
            if (bufsByBucket[i] == 0) {
                continue;
            }
            final var saved = denseByBucket[i] - sparseByBucket[i];
            System.out.printf(
                    "%-10s %8d %,14d %,14d %,10d %9.1f%% %12.2f%n",
                    BUCKET_NAMES[i],
                    bufsByBucket[i],
                    denseByBucket[i],
                    sparseByBucket[i],
                    saved,
                    denseByBucket[i] == 0 ? 0 : 100.0 * saved / denseByBucket[i],
                    (double) presentSumByBucket[i] / presentCountByBucket[i]);
            denseTotal += denseByBucket[i];
            sparseTotal += sparseByBucket[i];
        }
        System.out.printf(
                "%-10s %8d %,14d %,14d %,10d %9.1f%%%n%n",
                "total",
                buffers.size(),
                denseTotal,
                sparseTotal,
                denseTotal - sparseTotal,
                denseTotal == 0 ? 0 : 100.0 * (denseTotal - sparseTotal) / denseTotal);
    }

    /**
     * One grouping's tradeoff: table bytes saved by sharing one table set per group, vs the mixture
     * penalty (in bytes) that pricing every member at the group's pooled distribution costs. Each
     * buffer is charged a flat 1-byte selector (which table set applies) — sufficient for the
     * <=16-buffer/tile, single-table-per-group case this measures; a scheme with several table sets
     * per group would cost more.
     */
    private static void reportGrouping(
            String label, List<BufferHistograms> allBuffers, Iterable<List<BufferHistograms>> groups) {
        System.out.printf("--- %s ---%n", label);
        System.out.printf(
                "%-10s %8s %14s %14s %14s %10s%n",
                "bucket", "bufs", "table bytes", "penalty bytes", "net vs dense", "net%");

        final var denseByBucket = new long[BUCKET_NAMES.length];
        final var sharedTableByBucket = new long[BUCKET_NAMES.length];
        final var penaltyBitsByBucket = new double[BUCKET_NAMES.length];
        final var bufsByBucket = new int[BUCKET_NAMES.length];

        for (final var group : groups) {
            final var pooled = new long[3][];
            for (var r = 0; r < 3; r++) {
                final var regionHistograms = new ArrayList<long[]>(group.size());
                for (final var b : group) {
                    regionHistograms.add(b.regions()[r]);
                }
                pooled[r] = sum(regionHistograms);
            }

            // One table set is charged once per group; selector cost is 1 byte/buffer.
            final var groupBucket = bucketOf(group.get(0).vertexCount());
            sharedTableByBucket[groupBucket] += 28L + 3L * DENSE_TABLE_BYTES;

            for (final var b : group) {
                final var bucket = bucketOf(b.vertexCount());
                bufsByBucket[bucket]++;
                denseByBucket[bucket] += 28L + 3L * DENSE_TABLE_BYTES;
                sharedTableByBucket[bucket] += 1; // selector byte, charged where the buffer's own bucket is
                for (var r = 0; r < 3; r++) {
                    final var ownBits = bits(b.regions()[r], b.regions()[r]);
                    final var sharedBits = bits(b.regions()[r], pooled[r]);
                    penaltyBitsByBucket[bucket] += Math.max(0, sharedBits - ownBits);
                }
            }
        }

        long denseTotal = 0;
        long sharedTotal = 0;
        double penaltyBitsTotal = 0;
        for (var i = 0; i < BUCKET_NAMES.length; i++) {
            if (bufsByBucket[i] == 0) {
                continue;
            }
            final var penaltyBytes = penaltyBitsByBucket[i] / 8.0;
            final var net = denseByBucket[i] - sharedTableByBucket[i] - penaltyBytes;
            System.out.printf(
                    "%-10s %8d %,14d %,14.0f %,14.0f %9.1f%%%n",
                    BUCKET_NAMES[i],
                    bufsByBucket[i],
                    sharedTableByBucket[i],
                    penaltyBytes,
                    net,
                    denseByBucket[i] == 0 ? 0 : 100.0 * net / denseByBucket[i]);
            denseTotal += denseByBucket[i];
            sharedTotal += sharedTableByBucket[i];
            penaltyBitsTotal += penaltyBitsByBucket[i];
        }
        final var penaltyBytesTotal = penaltyBitsTotal / 8.0;
        final var netTotal = denseTotal - sharedTotal - penaltyBytesTotal;
        System.out.printf(
                "%-10s %8d %,14d %,14.0f %,14.0f %9.1f%%%n%n",
                "total",
                allBuffers.size(),
                sharedTotal,
                penaltyBytesTotal,
                netTotal,
                denseTotal == 0 ? 0 : 100.0 * netTotal / denseTotal);
    }

    /**
     * Corpus-static ceiling: train one table set per region on the first half of tiles (by
     * insertion/manifest order), price the second half against it. A single pooled figure over the
     * whole corpus would be train==test and overstate what a shipped static table achieves on tiles
     * it never saw — the same error rans-entropy-coder.md's C4 pooled estimate was discounted for.
     */
    private static void reportCorpusStatic(List<BufferHistograms> buffers) {
        final Map<Long, List<BufferHistograms>> byTile = new LinkedHashMap<>();
        for (final var b : buffers) {
            byTile.computeIfAbsent(b.tileKeyHash(), k -> new ArrayList<>()).add(b);
        }
        final var tileGroups = new ArrayList<>(byTile.values());
        final var split = tileGroups.size() / 2;
        final var trainBuffers = new ArrayList<BufferHistograms>();
        final var testBuffers = new ArrayList<BufferHistograms>();
        for (var i = 0; i < tileGroups.size(); i++) {
            (i < split ? trainBuffers : testBuffers).addAll(tileGroups.get(i));
        }

        System.out.printf(
                "--- corpus-static (held out: trained on %,d tiles / %,d buffers, priced on %,d tiles / %,d buffers) ---%n",
                split, trainBuffers.size(), tileGroups.size() - split, testBuffers.size());

        if (trainBuffers.isEmpty() || testBuffers.isEmpty()) {
            System.out.printf("Not enough tiles to hold out a split.%n%n");
            return;
        }

        final var trained = new long[3][];
        for (var r = 0; r < 3; r++) {
            final var histograms = new ArrayList<long[]>(trainBuffers.size());
            for (final var b : trainBuffers) {
                histograms.add(b.regions()[r]);
            }
            trained[r] = sum(histograms);
        }

        System.out.printf(
                "%-10s %8s %14s %10s%n", "bucket", "bufs", "penalty bytes", "table bytes");
        final var penaltyBitsByBucket = new double[BUCKET_NAMES.length];
        final var bufsByBucket = new int[BUCKET_NAMES.length];
        for (final var b : testBuffers) {
            final var bucket = bucketOf(b.vertexCount());
            bufsByBucket[bucket]++;
            for (var r = 0; r < 3; r++) {
                final var ownBits = bits(b.regions()[r], b.regions()[r]);
                final var sharedBits = bits(b.regions()[r], trained[r]);
                penaltyBitsByBucket[bucket] += Math.max(0, sharedBits - ownBits);
            }
        }
        double penaltyBitsTotal = 0;
        for (var i = 0; i < BUCKET_NAMES.length; i++) {
            if (bufsByBucket[i] == 0) {
                continue;
            }
            System.out.printf(
                    "%-10s %8d %,14.0f %10s%n",
                    BUCKET_NAMES[i], bufsByBucket[i], penaltyBitsByBucket[i] / 8.0, i == 0 ? "0 (baked in)" : "");
            penaltyBitsTotal += penaltyBitsByBucket[i];
        }
        System.out.printf(
                "%-10s %8d %,14.0f %10s%n%n",
                "total", testBuffers.size(), penaltyBitsTotal / 8.0, "0 (baked in)");
        System.out.printf(
                "Held-out table bytes shipped: 0 (baked into codec, needs a spec constant).%n"
                        + "Held-out penalty is the honest ceiling cost of never adapting to the tile.%n%n");
    }
}
