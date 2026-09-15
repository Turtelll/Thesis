package org.maplibre.mlt.research;

import org.maplibre.mlt.research.encoding.IntCodec;
import org.maplibre.mlt.research.encoding.Layout;
import org.maplibre.mlt.research.encoding.PartBoundaries;
import org.maplibre.mlt.research.encoding.PredictedTransform;
import org.maplibre.mlt.research.encoding.research.SegmentQuadCorner;
import org.maplibre.mlt.research.extraction.ExtractionReader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WP7: a <b>cost-model estimate</b> for whether an entropy coder (rANS/FSE) could ever beat FastPFOR
 * on the corpus's current best encoding, {@link SegmentQuadCorner}. This does not implement a coder —
 * no candidate entropy coder exists in the repo, and the design questions a real one would raise
 * (context plumbing through {@link IntCodec}, static vs per-buffer tables) are recorded in {@code
 * docs/results/rans-entropy-coder.md} rather than answered here. It only measures the two numbers a real coder
 * would need to beat: order-0 entropy of the residual stream, and what FastPFOR actually charges for
 * the same stream — the gap between them is the entire case for building one.
 *
 * <p>Reports, for {@code SEGMENT_QUAD_CORNER}'s three residual regions (starts | interior | corners):
 *
 * <ul>
 *   <li><b>E7.1 — the mixture penalty.</b> Order-0 entropy of the residual stream as one interleaved
 *       population vs the same values partitioned by region. The partitioned entropy is always
 *       &lt;= the interleaved one (mixing distributions cannot lower entropy); the gap is what
 *       {@code SEGMENT_QUAD_CORNER}'s region split already buys an entropy coder for free.
 *   <li><b>Real FastPFOR bytes for the same stream</b>, so "entropy says X bits" can be compared
 *       directly against what the corpus's actual bit-packed codec charges.
 *   <li><b>Static-table transmission cost</b> — the byte cost of shipping one frequency table per
 *       region, and whether the corpus is large enough (features per tile) for that to be worth
 *       partitioning tables per region rather than sharing one.
 * </ul>
 *
 * <p>Usage: {@code ./gradlew entropy --args="../data/extracted --layer building"}
 */
public final class EntropyCli {
    private EntropyCli() {
    }

    public static void main(String[] args) throws IOException {
        String inArg = null;
        String layer = "building";
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

        // Region 0 = starts, 1 = interior, 2 = corners — matches SegmentQuadCorner's own layout.
        final List<List<Integer>> regionValues =
                List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        final var allValues = new ArrayList<Integer>();
        var fastPforBytes = 0L;
        var buffers = 0;

        final var transform = new PredictedTransform(new SegmentQuadCorner(), Layout.SEGMENTED);
        final var codec = new IntCodec.FastPfor();

        for (final var entry : manifest) {
            if (!entry.layerName().equals(layer)) {
                continue;
            }
            final var ctx = ExtractionReader.readContext(inDir, entry);
            if (ctx.vertices().isEmpty()) {
                continue;
            }
            buffers++;

            final var values = transform.apply(ctx);
            fastPforBytes += codec.encode(values).length;
            for (final var v : values) {
                allValues.add(v);
            }

            // Recompute region boundaries independently of SegmentQuadCorner's private cursors() —
            // same convention the mechanism tests use, so a bug in the transform's own bookkeeping
            // cannot silently propagate into this measurement.
            final var starts = PartBoundaries.of(ctx, ctx.vertices().size());
            final var classes = classify(starts, ctx.vertices().size());
            var startCount = 0;
            var cornerCount = 0;
            for (final var c : classes) {
                if (c == 1) {
                    startCount++;
                } else if (c == 2) {
                    cornerCount++;
                }
            }
            final var interiorCount = classes.length - startCount - cornerCount;
            var slot = 0;
            for (var i = 0; i < startCount * 2; i++, slot++) {
                regionValues.get(0).add(values[slot]);
            }
            for (var i = 0; i < interiorCount * 2; i++, slot++) {
                regionValues.get(1).add(values[slot]);
            }
            for (var i = 0; i < cornerCount * 2; i++, slot++) {
                regionValues.get(2).add(values[slot]);
            }
        }

        report(layer, buffers, allValues, regionValues, fastPforBytes);
    }

    /**
     * Mirrors {@code SegmentQuadCorner.classify}: 0=interior, 1=start, 2=corner.
     */
    private static int[] classify(int[] starts, int vertexCount) {
        final var classes = new int[vertexCount];
        for (var s = 0; s < starts.length; s++) {
            final var start = starts[s];
            final var end = s + 1 < starts.length ? starts[s + 1] : vertexCount;
            if (start < vertexCount) {
                classes[start] = 1;
            }
            if (end - start == 4) {
                classes[start + 3] = 2;
            }
        }
        return classes;
    }

    // ---------------------------------------------------------------------------
    // Entropy / reporting
    // ---------------------------------------------------------------------------

    /**
     * Order-0 Shannon entropy, in bits per symbol, over the given values.
     */
    private static double entropyBitsPerSymbol(List<Integer> values) {
        if (values.isEmpty()) {
            return 0;
        }
        final Map<Integer, Long> freq = new HashMap<>();
        for (final var v : values) {
            freq.merge(v, 1L, Long::sum);
        }
        final var n = (double) values.size();
        var bits = 0.0;
        for (final var count : freq.values()) {
            final var p = count / n;
            bits -= p * (Math.log(p) / Math.log(2));
        }
        return bits;
    }

    /**
     * Distinct symbol count, for static-table size estimation.
     */
    private static int alphabetSize(List<Integer> values) {
        return (int) values.stream().distinct().count();
    }

    private static void report(
            String layer, int buffers, List<Integer> all, List<List<Integer>> regions, long fastPforBytes) {
        System.out.printf(
                "Entropy cost-model estimate for SEGMENT_QUAD_CORNER on layer '%s' (%,d buffers, %,d values).%n%n",
                layer, buffers, all.size());

        final var interleavedBits = entropyBitsPerSymbol(all);
        final var interleavedBytes = interleavedBits * all.size() / 8.0;
        System.out.printf("--- E7.1: mixture penalty ---%n");
        System.out.printf(
                "interleaved (one population)   %8.3f bits/value  -> %,12.0f bytes (order-0 ideal, no table cost)%n",
                interleavedBits, interleavedBytes);

        final var names = new String[]{"starts", "interior", "corners"};
        var partitionedBytes = 0.0;
        var tableBytes = 0L;
        for (var r = 0; r < 3; r++) {
            final List<Integer> region = regions.get(r);
            final var bits = entropyBitsPerSymbol(region);
            final var bytes = bits * region.size() / 8.0;
            partitionedBytes += bytes;
            // Static-table transmission cost estimate: alphabet size * (symbol width + frequency width),
            // a rough per-region table — the real cost depends on the coder's table format, which does
            // not exist in this repo; this is only a ceiling check, not a design.
            final var alphabet = alphabetSize(region);
            final var tableCostBytes = (long) alphabet * 6; // ~2 bytes symbol id + ~4 bytes frequency
            tableBytes += tableCostBytes;
            System.out.printf(
                    "  %-10s %,10d values  %8.3f bits/value  -> %,12.0f bytes  (alphabet %,d, table ~%,d bytes)%n",
                    names[r], region.size(), bits, bytes, alphabet, tableCostBytes);
        }
        System.out.printf(
                "partitioned (3 regions)        %8s               -> %,12.0f bytes  (+ ~%,d bytes static tables)%n",
                "", partitionedBytes, tableBytes);
        System.out.printf(
                "mixture penalty (interleaved - partitioned): %,.0f bytes (%.2f%%)%n%n",
                interleavedBytes - partitionedBytes,
                interleavedBytes == 0 ? 0 : 100.0 * (interleavedBytes - partitionedBytes) / interleavedBytes);

        System.out.printf("--- Real FastPFOR bytes for the same stream (SEGMENT_QUAD_CORNER_FASTPFOR) ---%n");
        System.out.printf("%,d bytes%n%n", fastPforBytes);

        final var entropyGapVsFastPfor = fastPforBytes - partitionedBytes;
        System.out.printf(
                "Gap: FastPFOR - partitioned order-0 entropy = %,.0f bytes (%.2f%% of FastPFOR).%n",
                entropyGapVsFastPfor, fastPforBytes == 0 ? 0 : 100.0 * entropyGapVsFastPfor / fastPforBytes);
        System.out.printf(
                "That gap, minus the ~%,d bytes of static-table cost, is the ceiling on what a real%n"
                        + "rANS/FSE coder could ever save here — before decoder complexity, and before the%n"
                        + "context-plumbing redesign IntCodec would need (see research-java/README.md).%n",
                tableBytes);
    }

}
