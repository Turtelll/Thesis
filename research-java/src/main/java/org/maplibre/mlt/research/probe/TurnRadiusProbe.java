package org.maplibre.mlt.research.probe;

import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.buffer.VertexRuns;
import org.maplibre.mlt.research.extraction.ExtractionReader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

/**
 * Stage 0 probe: does curvature in the {@code transportation} layer actually vary with zoom, in a
 * way any predictor could exploit? This was asked as "higher zooms have a larger average turn
 * radius than lower zooms, optimize an encoding for low zooms" — a cached measurement
 * ({@code data-analysis/transportation_stats.py} on {@code munich-plantiler}) already shows median
 * turn angle flat and non-monotone across zoom (11,9°-20,7°), while delta magnitude (not curvature)
 * is what scales with zoom (5,50 to 11,58 mean bits). That measurement is one Python run on one
 * corpus, median-only, in raw tile units, using a different bit-width convention than the encoders
 * here — not enough to settle the question. This probe re-measures on the Java extraction, with
 * tail percentiles and a proper curvature/radius metric, before any {@code Encoding} is written.
 *
 * <p>Per the protocol ({@code docs/evaluation-protocol.md}), median and tail must be reported
 * separately — a candidate that narrows the median while widening the tail is invisible to
 * FastPFOR, and rANS is retired so no codec is left to rescue that case.
 *
 * <p>Metrics, per zoom bucket, over line-run interior vertices (index {@code start+2 .. end-1} of
 * each LineString/MultiLineString run — exactly the population {@code LINE_CONTINUATION} predicts):
 *
 * <ul>
 *   <li><b>Turn angle</b> at each vertex, between the incoming edge {@code p[i-1]-p[i-2]} and the
 *       outgoing edge {@code p[i]-p[i-1]}, via {@code atan2(|cross|, dot)}.
 *   <li><b>Curvature / turn radius</b> — the Menger curvature of the three points
 *       {@code p[i-2], p[i-1], p[i]}: {@code radius = |e1|*|e2|*|e1+e2| / (2*|cross|)}. This is the
 *       quantity "turn radius" actually names; turn angle alone conflates a sharp corner with a
 *       long smooth arc sampled coarsely. Also reported normalized by median edge length, since
 *       raw radius scales with edge length and edge length scales with zoom — normalization is
 *       what tells "geometry is smoother" apart from "coordinates are bigger".
 *   <li><b>Residual bits</b> for three predictors on the same population: B (previous-vertex
 *       delta), C (continuation, {@code 2*p[i-1]-p[i-2]}, what {@code LINE_CONTINUATION} does), and
 *       A (constant-curvature: rotate the incoming edge by the previous turn and extrapolate).
 *       Nothing in this repo has tried curvature magnitude as predictor state before — prior turn
 *       work ({@code RightAngleTurn}, {@code Rot90Fixed}, {@code EvenRingTurn}, turn-scalar) assumed
 *       a fixed 90°, and {@code CONTEXT_DELTA} used curvature only to switch predictors, not to
 *       extrapolate.
 *   <li><b>Part-length composition</b> — share of runs by vertex-count bucket. {@code
 *       docs/results/line-continuation.md} shows continuation gain tracks run length, and this is
 *       the most likely real explanation for any per-zoom difference, so it must be reported
 *       alongside curvature rather than left to confound it.
 * </ul>
 *
 * <p>Usage: {@code ./gradlew research --args="turn-radius ../data/extractedBaseline --layer transportation"}
 */
public final class TurnRadiusProbe {
    private TurnRadiusProbe() {
    }

    // Below this many columns, a zoom bucket is too thin to read as anything but anecdote (see
    // the per-zoom column counts in the class javadoc's cited probe run).
    private static final int MIN_LINE_RUN_LENGTH = 4;

    public static void main(String[] args) throws IOException {
        String inArg = null;
        var layer = "transportation";
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

        final var byBucket = new TreeMap<String, BucketStats>();
        var buffersUsed = 0;
        var buffersSkipped = 0;

        for (final var entry : manifest) {
            if (!entry.layerName().equals(layer)) {
                continue;
            }
            final var ctx = ExtractionReader.readContext(inDir, entry);
            final var vertices = ctx.vertices();
            if (vertices.isEmpty()) {
                continue;
            }
            final var starts = VertexRuns.of(ctx, vertices.size());
            final var isLineRun = VertexRuns.lineRuns(ctx, starts.length);
            if (isLineRun == null) {
                buffersSkipped++;
                continue;
            }
            buffersUsed++;
            final var bucket = byBucket.computeIfAbsent(zoomBucket(entry.z()), k -> new BucketStats());

            for (var r = 0; r < starts.length; r++) {
                final var start = starts[r];
                final var end = r + 1 < starts.length ? starts[r + 1] : vertices.size();
                final var length = end - start;
                bucket.runLength(length);
                if (!isLineRun[r]) {
                    continue;
                }
                if (length < MIN_LINE_RUN_LENGTH) {
                    continue;
                }
                for (var i = start + 2; i < end; i++) {
                    bucket.vertex(vertices, i, i - start >= 3);
                }
            }
        }

        report(layer, buffersUsed, buffersSkipped, byBucket);
    }

    private static String zoomBucket(int z) {
        if (z <= 11) {
            return "<=11";
        }
        return Integer.toString(z);
    }

    private static int bits(int zigzag) {
        return 32 - Integer.numberOfLeadingZeros(zigzag);
    }

    /** Per-vertex accumulation for one zoom bucket. */
    private static final class BucketStats {
        final List<Integer> bitsB = new ArrayList<>();
        final List<Integer> bitsC = new ArrayList<>();
        final List<Integer> bitsA = new ArrayList<>();
        final List<Double> turnDegrees = new ArrayList<>();
        final List<Double> radii = new ArrayList<>();
        final List<Double> normalizedRadii = new ArrayList<>();
        final List<Double> edgeLengths = new ArrayList<>();
        long straightVertices = 0;
        long turnedVertices = 0;
        final TreeMap<String, Long> runLengths = new TreeMap<>();

        void runLength(int length) {
            final String key;
            if (length == 2) {
                key = "2";
            } else if (length == 3) {
                key = "3";
            } else if (length <= 10) {
                key = "4-10";
            } else if (length <= 50) {
                key = "11-50";
            } else {
                key = "51+";
            }
            runLengths.merge(key, 1L, Long::sum);
        }

        void vertex(List<Vertex> vertices, int i, boolean hasP3) {
            final var p0 = vertices.get(i - 2);
            final var p1 = vertices.get(i - 1);
            final var p2 = vertices.get(i);

            final var e1x = p1.x() - p0.x();
            final var e1y = p1.y() - p0.y();
            final var e2x = p2.x() - p1.x();
            final var e2y = p2.y() - p1.y();
            final var cross = (long) e1x * e2y - (long) e1y * e2x;
            final var dot = (long) e1x * e2x + (long) e1y * e2y;

            final var e1Len = Math.hypot(e1x, e1y);
            final var e2Len = Math.hypot(e2x, e2y);
            edgeLengths.add(e1Len);

            if (cross == 0) {
                straightVertices++;
            } else {
                turnedVertices++;
                turnDegrees.add(Math.toDegrees(Math.atan2(Math.abs(cross), dot)));
                final var e12Len = Math.hypot(e1x + e2x, e1y + e2y);
                final var radius = e1Len * e2Len * e12Len / (2.0 * Math.abs(cross));
                radii.add(radius);
                final var medianEdge = e1Len; // per-vertex edge length used as local scale
                if (medianEdge > 1e-9) {
                    normalizedRadii.add(radius / medianEdge);
                }
            }

            // B: previous-vertex delta.
            bitsB.add(bits(EncodingUtils.encodeZigZag(p2.x() - p1.x())));
            bitsB.add(bits(EncodingUtils.encodeZigZag(p2.y() - p1.y())));

            // C: continuation, 2*p1 - p0.
            final var predCX = 2 * p1.x() - p0.x();
            final var predCY = 2 * p1.y() - p0.y();
            bitsC.add(bits(EncodingUtils.encodeZigZag(p2.x() - predCX)));
            bitsC.add(bits(EncodingUtils.encodeZigZag(p2.y() - predCY)));

            // A: constant-curvature continuation. Needs p[i-3] to know the previous turn; falls back
            // to C (zero-curvature) when unavailable, matching what a real predictor would have to do.
            if (hasP3) {
                final var pm1 = vertices.get(i - 3);
                final var f1x = p0.x() - pm1.x();
                final var f1y = p0.y() - pm1.y();
                final var prevCross = (long) f1x * e1y - (long) f1y * e1x;
                final var prevDot = (long) f1x * e1x + (long) f1y * e1y;
                final var f1Len = Math.hypot(f1x, f1y);
                if (f1Len > 1e-9) {
                    // Rotate e1 by the same angle that took f1 -> e1 (turn from the previous edge
                    // pair), then extrapolate from p1: pred = p1 + rotate(e1, theta).
                    final var cosTheta = prevDot / (f1Len * e1Len);
                    final var sinTheta = prevCross / (f1Len * e1Len);
                    final var predAX = p1.x() + Math.round(e1x * cosTheta - e1y * sinTheta);
                    final var predAY = p1.y() + Math.round(e1x * sinTheta + e1y * cosTheta);
                    bitsA.add(bits(EncodingUtils.encodeZigZag((int) (p2.x() - predAX))));
                    bitsA.add(bits(EncodingUtils.encodeZigZag((int) (p2.y() - predAY))));
                } else {
                    bitsA.add(bits(EncodingUtils.encodeZigZag(p2.x() - predCX)));
                    bitsA.add(bits(EncodingUtils.encodeZigZag(p2.y() - predCY)));
                }
            } else {
                bitsA.add(bits(EncodingUtils.encodeZigZag(p2.x() - predCX)));
                bitsA.add(bits(EncodingUtils.encodeZigZag(p2.y() - predCY)));
            }
        }
    }

    private static void report(
            String layer, int buffersUsed, int buffersSkipped, TreeMap<String, BucketStats> byBucket) {
        System.out.printf(
                "TurnRadiusCli (Stage 0 probe) on layer '%s': %,d buffers used, %,d skipped "
                        + "(topology did not resolve to line runs).%n%n",
                layer, buffersUsed, buffersSkipped);

        if (byBucket.isEmpty()) {
            System.out.println("No line-run interior vertices found on this layer.");
            return;
        }

        System.out.println("--- turn angle (degrees) ---");
        System.out.printf(
                "%-8s %10s %8s %8s %8s %8s%n", "zoom", "vertices", "mean", "median", "p90", "p99");
        for (final var e : byBucket.entrySet()) {
            final var s = e.getValue();
            printDoubleRow(e.getKey(), s.turnDegrees);
        }

        System.out.println();
        System.out.println("--- curvature: turn radius (tile units) ---");
        System.out.printf(
                "%-8s %10s %8s %10s %10s %10s %10s%n",
                "zoom", "turned", "straight%", "medianR", "p90R", "medianR/e", "p90R/e");
        for (final var e : byBucket.entrySet()) {
            final var s = e.getValue();
            final var total = s.straightVertices + s.turnedVertices;
            final var straightPct = total == 0 ? 0.0 : 100.0 * s.straightVertices / total;
            System.out.printf(
                    "%-8s %,10d %9.1f%% %10.1f %10.1f %10.2f %10.2f%n",
                    e.getKey(),
                    s.turnedVertices,
                    straightPct,
                    median(s.radii),
                    percentileD(s.radii, 0.90),
                    median(s.normalizedRadii),
                    percentileD(s.normalizedRadii, 0.90));
        }

        System.out.println();
        System.out.println("--- residual bits: B (delta) vs C (continuation) vs A (constant-curvature) ---");
        System.out.printf(
                "%-8s %10s %8s %8s %8s %8s %8s%n", "zoom", "values", "pred", "mean", "median", "p90", "p99.9");
        for (final var e : byBucket.entrySet()) {
            final var s = e.getValue();
            printBitsRow(e.getKey(), "B", s.bitsB);
            printBitsRow("", "C", s.bitsC);
            printBitsRow("", "A", s.bitsA);
        }

        System.out.println();
        System.out.println("--- part-length composition (all runs, not just qualifying line runs) ---");
        System.out.printf("%-8s %8s %8s %8s %8s %8s %10s%n", "zoom", "n=2", "n=3", "4-10", "11-50", "51+", "runs");
        for (final var e : byBucket.entrySet()) {
            final var lengths = e.getValue().runLengths;
            final var total = lengths.values().stream().mapToLong(Long::longValue).sum();
            System.out.printf(
                    "%-8s %7.1f%% %7.1f%% %7.1f%% %7.1f%% %7.1f%% %,10d%n",
                    e.getKey(),
                    pct(lengths, "2", total),
                    pct(lengths, "3", total),
                    pct(lengths, "4-10", total),
                    pct(lengths, "11-50", total),
                    pct(lengths, "51+", total),
                    total);
        }

        System.out.println();
        System.out.println(
                "Reading this per the protocol: if turn radius (normalized by edge length) does not move");
        System.out.println(
                "across zoom buckets, the premise that curvature varies with zoom is refuted on this");
        System.out.println(
                "corpus. If predictor A's MEDIAN beats C's without widening p99.9 on some bucket, that");
        System.out.println(
                "earns a ClassifiedPredictor; a median-only or tail-only move does not, since rANS is");
        System.out.println("retired and there is no codec left to price a median-only win.");
    }

    private static double pct(TreeMap<String, Long> lengths, String key, long total) {
        return total == 0 ? 0.0 : 100.0 * lengths.getOrDefault(key, 0L) / total;
    }

    private static void printDoubleRow(String label, List<Double> values) {
        if (values.isEmpty()) {
            System.out.printf("%-8s %10d %8s %8s %8s %8s%n", label, 0, "-", "-", "-", "-");
            return;
        }
        final var sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        var sum = 0.0;
        for (final var v : sorted) {
            sum += v;
        }
        System.out.printf(
                "%-8s %,10d %8.2f %8.2f %8.2f %8.2f%n",
                label,
                sorted.size(),
                sum / sorted.size(),
                percentileD(sorted, 0.50),
                percentileD(sorted, 0.90),
                percentileD(sorted, 0.99));
    }

    private static void printBitsRow(String zoomLabel, String predictorLabel, List<Integer> bits) {
        if (bits.isEmpty()) {
            System.out.printf("%-8s %10s %8s %8s %8s %8s %8s%n", zoomLabel, "0", predictorLabel, "-", "-", "-", "-");
            return;
        }
        final var sorted = new ArrayList<>(bits);
        Collections.sort(sorted);
        var sum = 0L;
        for (final var b : sorted) {
            sum += b;
        }
        System.out.printf(
                "%-8s %,10d %8s %8.2f %8d %8d %8d%n",
                zoomLabel,
                sorted.size(),
                predictorLabel,
                (double) sum / sorted.size(),
                percentile(sorted, 0.50),
                percentile(sorted, 0.90),
                percentile(sorted, 0.999));
    }

    private static int percentile(List<Integer> sorted, double p) {
        final var idx = Math.min(sorted.size() - 1, (int) Math.ceil(p * sorted.size()) - 1);
        return sorted.get(Math.max(0, idx));
    }

    private static double median(List<Double> values) {
        return percentileD(values, 0.50);
    }

    private static double percentileD(List<Double> values, double p) {
        if (values.isEmpty()) {
            return 0.0;
        }
        final var sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        final var idx = Math.min(sorted.size() - 1, (int) Math.ceil(p * sorted.size()) - 1);
        return sorted.get(Math.max(0, idx));
    }
}
