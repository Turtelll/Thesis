package org.maplibre.mlt.research.measurement.selection;

import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.research.buffer.VertexRuns;
import org.maplibre.mlt.research.buffer.Encoding.BufferContext;
import org.maplibre.mlt.research.encoder.prediction.Prediction;
import org.maplibre.mlt.research.encoder.reorder.Reorder;
import org.maplibre.mlt.research.encoder.segmentation.Segmentation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * What can be read off one (tile, layer) buffer without compressing anything: its size and geometry
 * mix, how far consecutive vertices and consecutive runs are apart, what the Hilbert reorder does
 * to the jumps between runs, and, per predictor, the order-0 entropy and the zero share of its
 * {@code FLAT} zigzag residuals. These are the candidates for predicting a stage's choice from the
 * buffer instead of finding it by trial compression.
 *
 * @param vertices      vertex count
 * @param features      geometry count
 * @param pointShare    share of the geometries that are (multi)points
 * @param lineShare     share that are (multi)lines
 * @param polygonShare  share that are (multi)polygons
 * @param meanStep      mean {@code |dx| + |dy|} between consecutive vertices of the buffer
 * @param meanJump      mean {@code |dx| + |dy|} from the last vertex of a run to the first of the next
 *                      ({@code NaN} when the runs cannot be located)
 * @param jumpRatio     {@link #meanJump} after the Hilbert reorder over before ({@code NaN} likewise)
 * @param entropy       per {@link Prediction}, in ordinal order: order-0 entropy of the zigzag
 *                      residuals, bits per vertex (x and y)
 * @param zeroShare     per {@link Prediction}: share of the residuals that are zero
 * @param wideShare     share of the {@code QUAD_CORNER} zigzag residuals at or above 255, what
 *                      an escape byte has to catch
 */
public record BufferFeatures(
        int vertices,
        int features,
        double pointShare,
        double lineShare,
        double polygonShare,
        double meanStep,
        double meanJump,
        double jumpRatio,
        double[] entropy,
        double[] zeroShare,
        double wideShare) {

    public static BufferFeatures of(BufferContext ctx) {
        final var n = ctx.vertices().size();
        final var types = ctx.geometryTypes();
        var points = 0;
        var lines = 0;
        var polygons = 0;
        for (final var type : types) {
            switch (type) {
                case 0, 3 -> points++;
                case 1, 4 -> lines++;
                default -> polygons++;
            }
        }
        final var features = types.size();

        var step = 0L;
        for (var i = 1; i < n; i++) {
            step += manhattan(ctx, i - 1, i);
        }
        final var before = meanJump(ctx);
        final var after = meanJump(Reorder.HILBERT_CENTROID_ROT_MP.apply(ctx));

        final var predictions = Prediction.values();
        final var entropy = new double[predictions.length];
        final var zeroShare = new double[predictions.length];
        var wideShare = 0.0;
        final var topo = new BufferContext(
                List.of(), ctx.geometryTypes(), ctx.numGeometries(), ctx.numParts(), ctx.numRings());
        for (final var prediction : predictions) {
            final var predictor = prediction.predictor();
            final var layout = Segmentation.FLAT.layout(topo, predictor, n);
            final var histogram = new HashMap<Integer, Long>();
            var values = 0L;
            var wide = 0L;
            for (final var stream : Segmentation.split(layout, predictor, ctx.vertices())) {
                for (final var value : stream) {
                    final var zigzag = EncodingUtils.encodeZigZag(value);
                    histogram.merge(zigzag, 1L, Long::sum);
                    wide += (zigzag & 0xFFFFFFFFL) >= 255 ? 1 : 0;
                    values++;
                }
            }
            final var p = prediction.ordinal();
            entropy[p] = n == 0 ? 0 : 2.0 * entropyBits(histogram, values);
            zeroShare[p] = values == 0 ? 0 : (double) histogram.getOrDefault(0, 0L) / values;
            if (prediction == Prediction.QUAD_CORNER) {
                wideShare = values == 0 ? 0 : (double) wide / values;
            }
        }

        return new BufferFeatures(
                n,
                features,
                share(points, features),
                share(lines, features),
                share(polygons, features),
                n < 2 ? 0 : (double) step / (n - 1),
                before,
                before > 0 ? after / before : Double.NaN,
                entropy,
                zeroShare,
                wideShare);
    }

    /** The predictor whose residuals have the lowest order-0 entropy. */
    public Prediction lowestEntropy() {
        var best = 0;
        for (var p = 1; p < entropy.length; p++) {
            if (entropy[p] < entropy[best]) {
                best = p;
            }
        }
        return Prediction.values()[best];
    }

    private static double meanJump(BufferContext ctx) {
        final var n = ctx.vertices().size();
        var starts = VertexRuns.of(ctx, n);
        if (starts.length < 2) {
            if (n > 1 && !ctx.geometryTypes().isEmpty()
                    && ctx.geometryTypes().stream().allMatch(t -> t == 0 || t == 3)) {
                starts = IntStream.range(0, n).toArray();
            } else {
                return Double.NaN;
            }
        }
        var sum = 0L;
        for (var r = 1; r < starts.length; r++) {
            sum += manhattan(ctx, starts[r] - 1, starts[r]);
        }
        return (double) sum / (starts.length - 1);
    }

    private static long manhattan(BufferContext ctx, int from, int to) {
        final var a = ctx.vertices().get(from);
        final var b = ctx.vertices().get(to);
        return Math.abs((long) b.x() - a.x()) + Math.abs((long) b.y() - a.y());
    }

    private static double share(int count, int total) {
        return total == 0 ? 0 : (double) count / total;
    }

    private static double entropyBits(Map<Integer, Long> histogram, long total) {
        var bits = 0.0;
        for (final var count : histogram.values()) {
            final var p = (double) count / total;
            bits -= p * Math.log(p) / Math.log(2);
        }
        return bits;
    }
}
