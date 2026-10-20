package org.maplibre.mlt.research.cli;

import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.PartBoundaries;
import org.maplibre.mlt.research.extraction.ExtractionReader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Evaluation-protocol Stage 0 probe: can {@code SEGMENT_QUAD_CORNER} gain by choosing, per
 * four-vertex ring, which vertex is decoded first (and optionally the traversal direction)?
 *
 * <p>The parallelogram relation {@code p0+p2 = p1+p3} is symmetric, so the corner residual is the
 * same up to sign under every rotation/reflection. What changes is the part-start delta (first
 * decoded vertex vs previous decoded vertex) and the two interior deltas. This measures the
 * zigzag bit width of those residuals under the fixed rotation 0 (today) versus the per-ring best
 * choice, which the decoder would have to be told: 2 bits (4 rotations) or 3 bits (+ reversal).
 *
 * <p>The choice is greedy in buffer order: {@code prev} for a ring is the last vertex actually
 * decoded under the choices already made. Per-value bits are a proxy only — FastPFOR prices block
 * max, not per value. Encodes nothing.
 *
 * <p>Usage: {@code ./gradlew quadRotation --args="../data/extractedBaseline --layer building"}
 */
public final class QuadRotationCli {
    private QuadRotationCli() {
    }

    /** Decode order of the ring's vertex indices: 4 rotations forward, then 4 reversed. */
    private static final int[][] ORDERS = {
            {0, 1, 2, 3}, {1, 2, 3, 0}, {2, 3, 0, 1}, {3, 0, 1, 2},
            {0, 3, 2, 1}, {1, 0, 3, 2}, {2, 1, 0, 3}, {3, 2, 1, 0},
    };

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

        // [0] = fixed rotation 0, [1] = best of 4 rotations, [2] = best of 8 (rotation + reversal)
        final var labels = new String[]{"fixed (today)", "best rotation", "best rot+reverse"};
        final var signalBits = new int[]{0, 2, 3};
        final List<List<Integer>> startBits = new ArrayList<>();
        final List<List<Integer>> interiorBits = new ArrayList<>();
        final List<List<Integer>> cornerBits = new ArrayList<>();
        final var totalBits = new long[3];
        for (var m = 0; m < 3; m++) {
            startBits.add(new ArrayList<>());
            interiorBits.add(new ArrayList<>());
            cornerBits.add(new ArrayList<>());
        }
        var quads = 0;

        for (final var entry : manifest) {
            if (!entry.layerName().equals(layer)) {
                continue;
            }
            final var ctx = ExtractionReader.readContext(inDir, entry);
            final var vertices = ctx.vertices();
            if (vertices.isEmpty()) {
                continue;
            }
            final var starts = PartBoundaries.of(ctx, vertices.size());
            // Independent prev cursor per mode, since each mode's choices change the decode order.
            final var prev = new Vertex[]{new Vertex(0, 0), new Vertex(0, 0), new Vertex(0, 0)};
            for (var i = 0; i < starts.length; i++) {
                final var start = starts[i];
                final var end = i + 1 < starts.length ? starts[i + 1] : vertices.size();
                if (end - start != 4) {
                    for (var m = 0; m < 3; m++) {
                        prev[m] = vertices.get(end - 1);
                    }
                    continue;
                }
                quads++;
                final var ring = vertices.subList(start, end);
                for (var m = 0; m < 3; m++) {
                    final var candidates = m == 0 ? 1 : m == 1 ? 4 : 8;
                    var best = 0;
                    var bestCost = Integer.MAX_VALUE;
                    for (var o = 0; o < candidates; o++) {
                        final var cost = sum(residualBits(ring, ORDERS[o], prev[m]));
                        if (cost < bestCost) {
                            bestCost = cost;
                            best = o;
                        }
                    }
                    final var r = residualBits(ring, ORDERS[best], prev[m]);
                    startBits.get(m).add(r[0]);
                    startBits.get(m).add(r[1]);
                    interiorBits.get(m).add(r[2]);
                    interiorBits.get(m).add(r[3]);
                    interiorBits.get(m).add(r[4]);
                    interiorBits.get(m).add(r[5]);
                    cornerBits.get(m).add(r[6]);
                    cornerBits.get(m).add(r[7]);
                    totalBits[m] += bestCost + signalBits[m];
                    prev[m] = ring.get(ORDERS[best][3]);
                }
            }
        }

        System.out.printf("QuadRotationCli (Stage 0 probe) on layer '%s': %,d four-vertex rings.%n%n", layer, quads);
        if (quads == 0) {
            return;
        }
        System.out.printf("%-18s %-9s %8s %8s %8s %8s%n", "mode", "region", "mean", "median", "p90", "p99");
        for (var m = 0; m < 3; m++) {
            printRow(labels[m], "start", startBits.get(m));
            printRow("", "interior", interiorBits.get(m));
            printRow("", "corner", cornerBits.get(m));
        }
        System.out.println();
        System.out.printf("%-18s %14s %14s%n", "mode", "bits/ring", "vs fixed");
        for (var m = 0; m < 3; m++) {
            final var perRing = (double) totalBits[m] / quads;
            System.out.printf("%-18s %14.2f %+13.2f%%%n", labels[m], perRing,
                    100.0 * (totalBits[m] - totalBits[0]) / totalBits[0]);
        }
        System.out.println("(bits/ring = zigzag bits of all 8 residual values + signalling bits)");
    }

    /** Zigzag bit widths: start x/y, interior 1 x/y, interior 2 x/y, corner residual x/y. */
    private static int[] residualBits(List<Vertex> ring, int[] order, Vertex prev) {
        final var a = ring.get(order[0]);
        final var b = ring.get(order[1]);
        final var c = ring.get(order[2]);
        final var d = ring.get(order[3]);
        return new int[]{
                bits(a.x() - prev.x()), bits(a.y() - prev.y()),
                bits(b.x() - a.x()), bits(b.y() - a.y()),
                bits(c.x() - b.x()), bits(c.y() - b.y()),
                bits(d.x() - (a.x() + c.x() - b.x())), bits(d.y() - (a.y() + c.y() - b.y())),
        };
    }

    private static int sum(int[] values) {
        var s = 0;
        for (final var v : values) {
            s += v;
        }
        return s;
    }

    private static int bits(int value) {
        return 32 - Integer.numberOfLeadingZeros(EncodingUtils.encodeZigZag(value));
    }

    private static void printRow(String mode, String region, List<Integer> bits) {
        final var sorted = new ArrayList<>(bits);
        Collections.sort(sorted);
        var sum = 0L;
        for (final var b : sorted) {
            sum += b;
        }
        System.out.printf("%-18s %-9s %8.2f %8d %8d %8d%n", mode, region, (double) sum / sorted.size(),
                percentile(sorted, 0.50), percentile(sorted, 0.90), percentile(sorted, 0.99));
    }

    private static int percentile(List<Integer> sorted, double p) {
        final var idx = Math.min(sorted.size() - 1, (int) Math.ceil(p * sorted.size()) - 1);
        return sorted.get(Math.max(0, idx));
    }
}
