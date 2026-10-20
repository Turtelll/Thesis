package org.maplibre.mlt.research.cli;

import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.research.encoding.PartBoundaries;
import org.maplibre.mlt.research.extraction.ExtractionReader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Evaluation-protocol Stage 0 probe: does a second-order corner predictor say anything for the
 * {@code SEGMENT_QUAD_CORNER} population its parallelogram predictor ({@code p0+p2-p1}) does not
 * reach — the non-parallelogram four-vertex building rings (see {@code
 * docs/results/segment-quad-corner.md}, "What was left on the table")? This measures the residual
 * <b>distribution</b> only; it encodes nothing and is not an {@code Encoding}. Per the protocol
 * (this file's own existence is the worked example): an idea earns an encoder only once a probe
 * like this shows the distribution moves, because "raw bytes vs baseline" cannot distinguish a
 * predictor that helps from one that doesn't — see {@link
 * org.maplibre.mlt.research.encoding.PredictedTransform}.
 *
 * <p>Candidates, against the existing B (previous-vertex delta) and P0 (parallelogram, {@code
 * p0+p2-p1}) baselines already measured by {@code SEGMENT_QUAD_CORNER}:
 *
 * <ul>
 *   <li><b>M — midpoint-diagonal.</b> {@code pred = 2*p1 - p_opposite_midpoint}... no closed form
 *       exists without a fourth reference, so this predicts the corner as the reflection of {@code
 *       p1} through the diagonal midpoint {@code (p0+p2)/2}: {@code pred = p0 + p2 - p1}. This is
 *       algebraically identical to P0 — included only to confirm that identity numerically rather
 *       than assert it.
 *   <li><b>H — half-angle / kite.</b> {@code pred = p2 + (p2 - p1)}, i.e. continuing the last edge
 *       (ignores {@code p0} entirely) — the ordinary continuation predictor {@code ideen.md}
 *       already measured and found FastPFOR-invisible elsewhere; included as the "no second-order
 *       information helps" control.
 *   <li><b>D — diagonal-ratio.</b> {@code pred = p1 + (p1 - p0) * len(p2-p1)/len(p1-p0)}, scaling
 *       the incoming edge by the outgoing/incoming edge-length ratio — the only candidate that
 *       uses genuinely second-order (non-parallelogram) information. Reported in fixed-point
 *       (rounded) since the residual must be an integer.
 * </ul>
 *
 * <p>Usage: {@code ./gradlew quadCornerResidual --args="../data/extracted --layer building"}
 */
public final class QuadCornerResidualCli {
    private QuadCornerResidualCli() {
    }

    public static void main(String[] args) throws IOException {
        String inArg = null;
        var layer = "building";
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

        final var bitsB = new ArrayList<Integer>();
        final var bitsP0 = new ArrayList<Integer>();
        final var bitsH = new ArrayList<Integer>();
        final var bitsD = new ArrayList<Integer>();
        var parallelogramCount = 0;
        var corners = 0;
        var buffers = 0;

        for (final var entry : manifest) {
            if (!entry.layerName().equals(layer)) {
                continue;
            }
            final var ctx = ExtractionReader.readContext(inDir, entry);
            if (ctx.vertices().isEmpty()) {
                continue;
            }
            final var vertices = ctx.vertices();
            final var starts = PartBoundaries.of(ctx, vertices.size());
            var sawCorner = false;
            for (var i = 0; i < starts.length; i++) {
                final var start = starts[i];
                final var end = i + 1 < starts.length ? starts[i + 1] : vertices.size();
                if (end - start != 4) {
                    continue;
                }
                sawCorner = true;
                corners++;
                final var p0 = vertices.get(start);
                final var p1 = vertices.get(start + 1);
                final var p2 = vertices.get(start + 2);
                final var p3 = vertices.get(start + 3);

                final var predP0X = p0.x() + p2.x() - p1.x();
                final var predP0Y = p0.y() + p2.y() - p1.y();
                if (predP0X == p3.x() && predP0Y == p3.y()) {
                    parallelogramCount++;
                } else {
                    // Only the non-parallelogram population is what P0 leaves on the table.
                    bitsB.add(bits(EncodingUtils.encodeZigZag(p3.x() - p2.x())));
                    bitsB.add(bits(EncodingUtils.encodeZigZag(p3.y() - p2.y())));
                    bitsP0.add(bits(EncodingUtils.encodeZigZag(p3.x() - predP0X)));
                    bitsP0.add(bits(EncodingUtils.encodeZigZag(p3.y() - predP0Y)));

                    final var predHX = 2 * p2.x() - p1.x();
                    final var predHY = 2 * p2.y() - p1.y();
                    bitsH.add(bits(EncodingUtils.encodeZigZag(p3.x() - predHX)));
                    bitsH.add(bits(EncodingUtils.encodeZigZag(p3.y() - predHY)));

                    final var inLen = Math.hypot(p1.x() - p0.x(), p1.y() - p0.y());
                    final var outLen = Math.hypot(p2.x() - p1.x(), p2.y() - p1.y());
                    final var ratio = inLen < 1e-9 ? 1.0 : outLen / inLen;
                    final var predDX = p2.x() + Math.round((p2.x() - p1.x()) * ratio);
                    final var predDY = p2.y() + Math.round((p2.y() - p1.y()) * ratio);
                    bitsD.add(bits(EncodingUtils.encodeZigZag((int) (p3.x() - predDX))));
                    bitsD.add(bits(EncodingUtils.encodeZigZag((int) (p3.y() - predDY))));
                }
            }
            if (sawCorner) {
                buffers++;
            }
        }

        report(layer, buffers, corners, parallelogramCount, bitsB, bitsP0, bitsH, bitsD);
    }

    private static int bits(int zigzag) {
        return 32 - Integer.numberOfLeadingZeros(zigzag);
    }

    private static void report(
            String layer,
            int buffers,
            int corners,
            int parallelograms,
            List<Integer> bitsB,
            List<Integer> bitsP0,
            List<Integer> bitsH,
            List<Integer> bitsD) {
        System.out.printf(
                "QuadCornerResidualCli (Stage 0 probe) on layer '%s': %,d buffers, %,d four-vertex rings, "
                        + "%,d (%.1f%%) exact parallelograms.%n",
                layer, buffers, corners, parallelograms, corners == 0 ? 0 : 100.0 * parallelograms / corners);
        final var nonParallelogram = corners - parallelograms;
        System.out.printf(
                "Non-parallelogram population: %,d rings, %,d residual values per candidate.%n%n",
                nonParallelogram, bitsB.size());

        if (bitsB.isEmpty()) {
            System.out.println("No non-parallelogram four-vertex rings found on this layer.");
            return;
        }

        System.out.printf(
                "%-32s %8s %8s %8s %8s %8s%n", "predictor", "mean", "median", "p90", "p99", "p99.9");
        printRow("B  previous-vertex delta", bitsB);
        printRow("P0 parallelogram (p0+p2-p1)", bitsP0);
        printRow("H  edge-continuation (2p2-p1)", bitsH);
        printRow("D  diagonal-ratio (2nd order)", bitsD);

        System.out.println();
        System.out.println(
                "Reading this per the protocol (Stage 0): compare each candidate's MEDIAN to B's median —");
        System.out.println(
                "a lower median with a WIDER tail is a rANS candidate, invisible to FastPFOR block-max");
        System.out.println(
                "pricing (the CONTEXT_DELTA result). A candidate that does not move the median is dead;");
        System.out.println("do not write an Encoding for it.");
    }

    private static void printRow(String label, List<Integer> bits) {
        final var sorted = new ArrayList<>(bits);
        Collections.sort(sorted);
        final var n = sorted.size();
        var sum = 0L;
        for (final var b : sorted) {
            sum += b;
        }
        System.out.printf(
                "%-32s %8.2f %8d %8d %8d %8d%n",
                label,
                (double) sum / n,
                percentile(sorted, 0.50),
                percentile(sorted, 0.90),
                percentile(sorted, 0.99),
                percentile(sorted, 0.999));
    }

    private static int percentile(List<Integer> sorted, double p) {
        final var idx = Math.min(sorted.size() - 1, (int) Math.ceil(p * sorted.size()) - 1);
        return sorted.get(Math.max(0, idx));
    }

}
