package org.maplibre.mlt.research.measurement.stats;

import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.research.buffer.PartBoundaries;
import org.maplibre.mlt.research.buffer.Encoding.BufferContext;
import org.maplibre.mlt.research.extraction.ExtractionWriter.ManifestEntry;
import org.maplibre.mlt.research.encoder.stream.ByteWidth;
import org.maplibre.mlt.research.encoder.prediction.Prediction;
import org.maplibre.mlt.research.encoder.reorder.Reorder;
import org.maplibre.mlt.research.encoder.segmentation.Segmentation;
import org.maplibre.mlt.research.registry.PipelineRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;

/**
 * The "Measure" probes of the design document. A probe sees every extracted buffer once (the
 * original, unreordered context) and prints its table at the end; it encodes nothing and writes no
 * bytes. {@code ./gradlew research --args="measure --step sN --stats"} runs the probes of step N.
 */
public final class PipelineStats {

    private PipelineStats() {
    }

    /** One measurement over the corpus. */
    public interface Probe {

        void accept(ManifestEntry entry, BufferContext ctx);

        void report();
    }

    /** Topology streams of {@code ctx} with the vertices removed. */
    private static BufferContext topology(BufferContext ctx) {
        return new BufferContext(
                List.of(), ctx.geometryTypes(), ctx.numGeometries(), ctx.numParts(), ctx.numRings());
    }

    /** The probes of one step. */
    public static List<Probe> probes(String step) {
        return switch (step) {
            case "s1" -> List.of(new JumpProbe(PipelineRegistry.s1Orders()));
            case "s2" -> List.of(new PredictProbe());
            case "s3" -> List.of(new RoleProbe());
            case "s6" -> List.of(new WidthProbe());
            default -> List.of();
        };
    }

    // --- S1: inter-feature jump -------------------------------------------------

    /**
     * Mean Manhattan jump {@code |dx| + |dy|} from the last vertex of each run (ring, line part, or
     * point) to the first vertex of the next one — the residual the delta chain pays at a boundary
     * — per layer, before and after each reorder variant. A buffer whose runs cannot be located
     * contributes nothing to any column, so the columns stay comparable.
     */
    static final class JumpProbe implements Probe {
        private final List<Reorder> orders;
        // layer -> per order {sum of jumps, boundaries}
        private final Map<String, long[][]> byLayer = new TreeMap<>();

        JumpProbe(List<Reorder> orders) {
            this.orders = orders;
        }

        @Override
        public void accept(ManifestEntry entry, BufferContext ctx) {
            final var results = new long[orders.size()][];
            for (var i = 0; i < orders.size(); i++) {
                results[i] = jumps(orders.get(i).apply(ctx));
                if (results[i] == null) {
                    return;
                }
            }
            final var layer = byLayer.computeIfAbsent(entry.layerName(), k -> new long[orders.size()][2]);
            for (var i = 0; i < orders.size(); i++) {
                layer[i][0] += results[i][0];
                layer[i][1] += results[i][1];
            }
        }

        /** {@code {sum, boundaries}}, or {@code null} if the runs are not derivable. */
        private static long[] jumps(BufferContext ctx) {
            final var vertices = ctx.vertices();
            var starts = PartBoundaries.of(ctx, vertices.size());
            if (starts.length < 2) {
                if (!ctx.geometryTypes().isEmpty() && ctx.geometryTypes().stream().allMatch(t -> t == 0 || t == 3)) {
                    starts = IntStream.range(0, vertices.size()).toArray();
                } else {
                    return null;
                }
            }
            var sum = 0L;
            for (var r = 1; r < starts.length; r++) {
                final var from = vertices.get(starts[r] - 1);
                final var to = vertices.get(starts[r]);
                sum += Math.abs((long) to.x() - from.x()) + Math.abs((long) to.y() - from.y());
            }
            return new long[]{sum, starts.length - 1L};
        }

        @Override
        public void report() {
            System.out.printf("%n--- S1: mean |inter-run jump| (|dx|+|dy| from a run's last vertex to the next run's first) ---%n");
            final var layers = new ArrayList<>(byLayer.keySet());
            layers.sort(Comparator.comparingLong((String l) -> -byLayer.get(l)[0][1]));
            final var header = new StringBuilder(String.format("%-20s %9s", "layer", "runs"));
            for (final var order : orders) {
                header.append(String.format(" %22s", order.name()));
            }
            System.out.println(header);
            final var total = new long[orders.size()][2];
            for (final var layer : layers) {
                final var cells = byLayer.get(layer);
                final var row = new StringBuilder(String.format("%-20s %,9d", layer, cells[0][1]));
                for (var i = 0; i < orders.size(); i++) {
                    total[i][0] += cells[i][0];
                    total[i][1] += cells[i][1];
                    row.append(String.format(" %22.1f", mean(cells[i])));
                }
                System.out.println(row);
            }
            final var row = new StringBuilder(String.format("%-20s %,9d", "(all)", total[0][1]));
            for (final var cell : total) {
                row.append(String.format(" %22.1f", mean(cell)));
            }
            System.out.println(row);
            System.out.println("columns are in the order " + Arrays.toString(orders.stream().map(Reorder::name).toArray()));
        }

        private static double mean(long[] cell) {
            return cell[1] == 0 ? 0 : (double) cell[0] / cell[1];
        }
    }

    // --- S2: residual entropy per predictor ------------------------------------

    /**
     * Order-0 entropy of the zigzag residuals of every predictor, per layer, over one histogram per
     * (layer, predictor) — a bound on what any entropy stage after the predictor can reach without
     * context. The buffers are first put in the order of {@link PipelineRegistry#GEOMETRY_BEST}, the
     * residuals are the {@code FLAT} ones (no segmentation: S3 regroups them).
     */
    static final class PredictProbe implements Probe {
        private final Prediction[] predictors = Prediction.values();
        // layer -> per predictor: histogram of zigzag values
        private final Map<String, List<Map<Integer, Long>>> histograms = new TreeMap<>();
        private final Map<String, long[]> vertices = new TreeMap<>();

        @Override
        public void accept(ManifestEntry entry, BufferContext original) {
            final var ctx = PipelineRegistry.GEOMETRY_BEST.reorder().apply(original);
            final var n = ctx.vertices().size();
            if (n == 0) {
                return;
            }
            final var topo = topology(ctx);
            final var layer = histograms.computeIfAbsent(entry.layerName(), k -> {
                final var list = new ArrayList<Map<Integer, Long>>();
                for (var i = 0; i < predictors.length; i++) {
                    list.add(new HashMap<>());
                }
                return list;
            });
            vertices.computeIfAbsent(entry.layerName(), k -> new long[1])[0] += n;
            for (var p = 0; p < predictors.length; p++) {
                final var predictor = predictors[p].predictor();
                final var layout = Segmentation.FLAT.layout(topo, predictor, n);
                for (final var stream : Segmentation.split(layout, predictor, ctx.vertices())) {
                    for (final var value : stream) {
                        layer.get(p).merge(EncodingUtils.encodeZigZag(value), 1L, Long::sum);
                    }
                }
            }
        }

        private static double entropyBits(Map<Integer, Long> histogram, long total) {
            var bits = 0.0;
            for (final var count : histogram.values()) {
                final var p = (double) count / total;
                bits -= p * Math.log(p) / Math.log(2);
            }
            return bits;
        }

        @Override
        public void report() {
            System.out.printf("%n--- S2: order-0 entropy of the FLAT zigzag residuals, bits per vertex (x and y), "
                    + "and share of zero residuals ---%n");
            final var header = new StringBuilder(String.format("%-20s %10s", "layer", "vertices"));
            for (final var predictor : predictors) {
                header.append(String.format(" %24s", predictor.name()));
            }
            System.out.println(header);
            final var layers = new ArrayList<>(histograms.keySet());
            layers.sort(Comparator.comparingLong((String l) -> -vertices.get(l)[0]));
            final var totalBits = new double[predictors.length];
            var totalVertices = 0L;
            for (final var layer : layers) {
                final var n = vertices.get(layer)[0];
                totalVertices += n;
                final var row = new StringBuilder(String.format("%-20s %,10d", layer, n));
                var best = 0;
                var bestBits = Double.MAX_VALUE;
                for (var p = 0; p < predictors.length; p++) {
                    final var histogram = histograms.get(layer).get(p);
                    final var bits = 2.0 * entropyBits(histogram, 2 * n);
                    final var zeros = histogram.getOrDefault(0, 0L);
                    totalBits[p] += bits * n;
                    if (bits < bestBits) {
                        bestBits = bits;
                        best = p;
                    }
                    row.append(String.format(" %12.3f %5.1f%%zero", bits, 100.0 * zeros / (2 * n)));
                }
                System.out.println(row + "  best: " + predictors[best].name());
            }
            final var row = new StringBuilder(String.format("%-20s %,10d", "(all, weighted)", totalVertices));
            for (var p = 0; p < predictors.length; p++) {
                row.append(String.format(" %12.3f %12s", totalBits[p] / totalVertices, ""));
            }
            System.out.println(row);
        }
    }

    // --- S3: width of the role streams ---------------------------------------------

    /**
     * Per layer and role stream ({@code jump}, {@code edge}, {@code body}), over the default
     * reorder and predictor: how many residual values land there, their mean zigzag value, and the
     * share above 255 — what a one-byte stream cannot hold. This is what a separate width per
     * stream (S6) can save over one shared width.
     */
    static final class RoleProbe implements Probe {
        private static final String[] ROLES = {"jump", "edge", "body"};
        // layer -> per role {values, sum of zigzag, values > 255}
        private final Map<String, long[][]> byLayer = new TreeMap<>();

        @Override
        public void accept(ManifestEntry entry, BufferContext original) {
            final var ctx = PipelineRegistry.GEOMETRY_BEST.reorder().apply(original);
            final var n = ctx.vertices().size();
            if (n == 0) {
                return;
            }
            final var topo = topology(ctx);
            final var predictor = PipelineRegistry.GEOMETRY_BEST.predictor().predictor();
            final var layout = Segmentation.ROLE.layout(topo, predictor, n);
            final var streams = Segmentation.split(layout, predictor, ctx.vertices());
            final var cells = byLayer.computeIfAbsent(entry.layerName(), k -> new long[3][3]);
            for (var s = 0; s < streams.length; s++) {
                for (final var value : streams[s]) {
                    final var zigzag = EncodingUtils.encodeZigZag(value) & 0xFFFFFFFFL;
                    cells[s][0]++;
                    cells[s][1] += zigzag;
                    if (zigzag > 255) {
                        cells[s][2]++;
                    }
                }
            }
        }

        @Override
        public void report() {
            System.out.printf("%n--- S3: role streams (default reorder and predictor): values, mean zigzag, share > 255 ---%n");
            final var header = new StringBuilder(String.format("%-20s", "layer"));
            for (final var role : ROLES) {
                header.append(String.format(" %12s %10s %8s", role, "mean", ">255"));
            }
            System.out.println(header);
            final var layers = new ArrayList<>(byLayer.keySet());
            layers.sort(Comparator.comparingLong((String l) -> -(byLayer.get(l)[0][0] + byLayer.get(l)[1][0] + byLayer.get(l)[2][0])));
            final var total = new long[3][3];
            for (final var layer : layers) {
                final var cells = byLayer.get(layer);
                final var row = new StringBuilder(String.format("%-20s", layer));
                for (var s = 0; s < 3; s++) {
                    for (var k = 0; k < 3; k++) {
                        total[s][k] += cells[s][k];
                    }
                    row.append(cell(cells[s]));
                }
                System.out.println(row);
            }
            final var row = new StringBuilder(String.format("%-20s", "(all)"));
            for (var s = 0; s < 3; s++) {
                row.append(cell(total[s]));
            }
            System.out.println(row);
        }

        private static String cell(long[] c) {
            return String.format(" %,12d %10.1f %7.1f%%", c[0], c[0] == 0 ? 0 : (double) c[1] / c[0],
                    c[0] == 0 ? 0 : 100.0 * c[2] / c[0]);
        }
    }

    // --- S6: how wide the values are ---------------------------------------------------

    /**
     * Per layer and per zoom, over the default reorder, predictor and class streams: the share of
     * values at or above 255 (what an escape marker catches) and above 65.535, and the stream
     * count per minimal width (1, 2, 4 bytes). This decides whether the escape variant can pay.
     */
    static final class WidthProbe implements Probe {
        // key -> {values, >= 255, > 65535, streams w1, w2, w4}
        private final Map<String, long[]> byLayer = new TreeMap<>();
        private final Map<Integer, long[]> byZoom = new TreeMap<>();

        @Override
        public void accept(ManifestEntry entry, BufferContext original) {
            final var ctx = PipelineRegistry.GEOMETRY_BEST.reorder().apply(original);
            final var n = ctx.vertices().size();
            if (n == 0) {
                return;
            }
            final var topo = topology(ctx);
            final var predictor = PipelineRegistry.GEOMETRY_BEST.predictor().predictor();
            final var layout = PipelineRegistry.GEOMETRY_BEST.segmentation().layout(topo, predictor, n);
            final var layer = byLayer.computeIfAbsent(entry.layerName(), k -> new long[6]);
            final var zoom = byZoom.computeIfAbsent(entry.z(), k -> new long[6]);
            for (final var stream : Segmentation.split(layout, predictor, ctx.vertices())) {
                if (stream.length == 0) {
                    continue;
                }
                var max = 0L;
                var wide = 0L;
                var huge = 0L;
                for (final var value : stream) {
                    final var u = EncodingUtils.encodeZigZag(value) & 0xFFFFFFFFL;
                    max = Math.max(max, u);
                    wide += u >= 255 ? 1 : 0;
                    huge += u > 65535 ? 1 : 0;
                }
                final var slot = 3 + ByteWidth.codeOf(ByteWidth.minWidth(max));
                for (final var cells : List.of(layer, zoom)) {
                    cells[0] += stream.length;
                    cells[1] += wide;
                    cells[2] += huge;
                    cells[slot]++;
                }
            }
        }

        @Override
        public void report() {
            System.out.printf("%n--- S6: values >= 255 and > 65.535, streams by minimal width (default pipeline) ---%n");
            System.out.printf("%-20s %12s %8s %8s %8s %8s %8s%n", "layer", "values", ">=255", ">65535", "w1", "w2", "w4");
            final var total = new long[6];
            byLayer.forEach((layer, c) -> {
                for (var i = 0; i < 6; i++) total[i] += c[i];
                System.out.println(row(layer, c));
            });
            System.out.println(row("(all)", total));
            System.out.printf("%n%-20s %12s %8s %8s %8s %8s %8s%n", "zoom", "values", ">=255", ">65535", "w1", "w2", "w4");
            byZoom.forEach((zoom, c) -> System.out.println(row("z" + zoom, c)));
        }

        private static String row(String label, long[] c) {
            return String.format("%-20s %,12d %7.2f%% %7.2f%% %,8d %,8d %,8d", label, c[0],
                    c[0] == 0 ? 0 : 100.0 * c[1] / c[0], c[0] == 0 ? 0 : 100.0 * c[2] / c[0], c[3], c[4], c[5]);
        }
    }
}