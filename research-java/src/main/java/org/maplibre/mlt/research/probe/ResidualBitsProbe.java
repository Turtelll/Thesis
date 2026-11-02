package org.maplibre.mlt.research.probe;

import org.maplibre.mlt.research.baseline.Baselines;
import org.maplibre.mlt.research.buffer.Encoding;
import org.maplibre.mlt.research.baseline.VertexBufferCodec;
import org.maplibre.mlt.research.buffer.Encoding.BufferContext;
import org.maplibre.mlt.research.baseline.VertexTransform;
import org.maplibre.mlt.research.extraction.ExtractionReader;
import org.maplibre.mlt.research.encoder.prediction.Prediction;
import org.maplibre.mlt.research.workshop.PredictionTransform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Measurement-only probe, no bytes written: mean residual bit width, zero-pair share and per-vertex
 * change against {@code DELTA_ZIGZAG} for the transform of every baseline row. A codec-independent headroom signal — whether it reaches
 * the tile is what {@code MeasureHarness} measures.
 *
 * <p>Usage: {@code [extractionDir] [--layer <name>] [--by-zoom]}. {@code extractionDir} defaults
 * to {@code ../data/extractedBaseline}.
 */
public final class ResidualBitsProbe {
    private ResidualBitsProbe() {
    }

    /**
     * Layers shown individually in the per-layer table.
     */
    private static final int TOP_LAYERS = 6;

    /**
     * Mean residual bit width per {@link VertexTransform}, corpus-wide — a codec-independent
     * headroom signal, separate from {@code MeasureHarness}'s byte tables.
     *
     * <p>Keyed on the transform's name rather than the encoding's: {@code QUAD_CORNER_VARINT},
     * {@code QUAD_CORNER_RANS} and {@code QUAD_CORNER_FASTPFOR} all wrap the same transform and so
     * produce byte-identical residuals, and printing the mean three times would wrongly imply the
     * codec affects it. A predictor that lowers mean bits has found real structure; whether that
     * structure survives into fewer bytes depends on layout and codec — {@code MeasureHarness}'s tables answer that
     * separately. {@code QUAD_CORNER} is the worked example: -9,6% mean bits, -0,0% bytes interleaved
     * under FastPFOR, -12,2% bytes once segmented (see {@code docs/evaluation-protocol.md}).
     *
     * <p>Beside the mean, the share of {@code (x, y)} residual pairs that are exactly {@code (0, 0)}:
     * the compressor-side signal. A general-purpose compressor prices byte frequencies and repeats,
     * not bit widths, so an exactly-right prediction (two identical zero bytes under Varint, the
     * same pair every time) is worth more to it than a near miss of the same width.
     *
     * <p>Only accumulates running sums per row, not the residual values themselves, so memory is
     * proportional to the number of distinct transforms, not corpus size.
     */
    private static final class Residuals {
        private final Map<String, Long> bitSum = new LinkedHashMap<>();
        private final Map<String, Long> count = new LinkedHashMap<>();
        private final Map<String, Long> zeroPairs = new LinkedHashMap<>();
        private final Map<String, Long> pairs = new LinkedHashMap<>();

        /**
         * Adds one buffer's {@code {bitSum, valueCount}}, optionally followed by {@code {zeroPairs,
         * pairs}} — all-in measurements ({@code *_SHARED}) have no per-pair residual stream and omit
         * them.
         */
        void add(String transformName, long[] measured) {
            bitSum.merge(transformName, measured[0], Long::sum);
            count.merge(transformName, measured[1], Long::sum);
            if (measured.length >= 4) {
                zeroPairs.merge(transformName, measured[2], Long::sum);
                pairs.merge(transformName, measured[3], Long::sum);
            }
        }

        static long[] measure(int[] zigzagValues) {
            var sum = 0L;
            for (var value : zigzagValues) {
                sum += bits(value);
            }
            var zero = 0L;
            for (var i = 0; i + 1 < zigzagValues.length; i += 2) {
                if (zigzagValues[i] == 0 && zigzagValues[i + 1] == 0) {
                    zero++;
                }
            }
            return new long[]{sum, zigzagValues.length, zero, zigzagValues.length / 2};
        }

        private String zeroShare(String transformName) {
            var n = pairs.getOrDefault(transformName, 0L);
            return n == 0 ? "-" : String.format("%.1f%%", 100.0 * zeroPairs.get(transformName) / n);
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
            var hasRef = bitSum.containsKey(reference);
            var refMean = hasRef ? meanBits(reference) : 0.0;

            var nameWidth = maxLength(bitSum.keySet().stream(), "transform".length());

            System.out.printf("%n--- residual bits ---%n");
            System.out.printf(
                    "%-" + nameWidth + "s %14s %10s %17s %11s%n",
                    "transform", "values", "mean bits", "vs DELTA_ZIGZAG", "zero pairs");
            for (var name : bitSum.keySet()) {
                var mean = meanBits(name);
                System.out.printf(
                        "%-" + nameWidth + "s %,14d %10.2f %17s %11s%n",
                        name,
                        count.get(name),
                        mean,
                        hasRef ? deltaFraction(mean, refMean) : "-",
                        zeroShare(name));
            }
            System.out.printf(
                    "%nA lower mean is a headroom signal, not a result: a transform that narrows the median%n"
                            + "while widening the tail can still cost the same or more once a codec prices a%n"
                            + "block's maximum (FastPFOR) rather than the mean. Whether the reduction above%n"
                            + "reaches the tile depends on layout and codec - see research measure. zero pairs =%n"
                            + "share of (x, y) residuals exactly (0, 0), what a general-purpose compressor rewards;%n"
                            + "'-' for all-in rows (*_SHARED) with no per-pair stream.%n");
        }
    }

    /**
     * Per-vertex change against {@code DELTA_ZIGZAG}: each vertex's {@code bits(x) + bits(y)} under
     * a transform, compared with the same vertex's cost under {@code DELTA_ZIGZAG}.
     *
     * <p>{@link Residuals} keeps only a mean, which cannot tell "most vertices shrank a little" from
     * "a few shrank a lot while others grew" — and the vertices that grow are the ones FastPFOR's
     * block maximum charges for. Pairing by position answers both whether and by how much the
     * vertices got smaller, with the gains and the losses summed separately so they cannot cancel.
     *
     * <p>Only transforms whose slot {@code 2i} is vertex {@code i} can be paired ({@link
     * VertexTransform#vertexAligned()}): the ring-frame predictors rotate the residual but keep one pair per
     * vertex, so they pair too.
     */
    static final class VertexShrink {
        private static final int VERTICES = 0;
        private static final int SMALLER = 1;
        private static final int LARGER = 2;
        private static final int BITS_SAVED = 3;
        private static final int BITS_ADDED = 4;

        private final Map<String, long[]> stats = new LinkedHashMap<>();

        void add(String rowKey, long[] measured) {
            var into = stats.computeIfAbsent(rowKey, k -> new long[measured.length]);
            for (var i = 0; i < measured.length; i++) {
                into[i] += measured[i];
            }
        }

        /**
         * {@code {vertices, smaller, larger, bitsSaved, bitsAdded}} of {@code candidate} against
         * {@code reference}, both interleaved {@code x, y} streams over the same vertices.
         */
        static long[] compare(int[] reference, int[] candidate) {
            if (reference.length != candidate.length || reference.length % 2 != 0) {
                throw new IllegalArgumentException(
                        "streams are not vertex-aligned: " + reference.length + " vs " + candidate.length + " values");
            }
            var result = new long[5];
            for (var i = 0; i < reference.length; i += 2) {
                var change = Residuals.bits(candidate[i]) + Residuals.bits(candidate[i + 1])
                        - Residuals.bits(reference[i]) - Residuals.bits(reference[i + 1]);
                if (change < 0) {
                    result[SMALLER]++;
                    result[BITS_SAVED] -= change;
                } else if (change > 0) {
                    result[LARGER]++;
                    result[BITS_ADDED] += change;
                }
            }
            result[VERTICES] = reference.length / 2;
            return result;
        }

        long vertices() {
            return stats.values().stream().mapToLong(s -> s[VERTICES]).max().orElse(0);
        }

        boolean isEmpty() {
            return stats.isEmpty();
        }

        /**
         * One table, a row per transform per group. {@code groupLabel} is {@code null} for the
         * corpus table, which has a single unnamed group and so no group column.
         */
        static void print(String title, String groupLabel, List<Map.Entry<String, VertexShrink>> groups) {
            if (groups.stream().allMatch(g -> g.getValue().isEmpty())) {
                return;
            }
            var nameWidth = maxLength(
                    groups.stream().flatMap(g -> g.getValue().stats.keySet().stream()), "transform".length());
            var groupFormat = groupLabel == null
                    ? "%s"
                    : "%-" + maxLength(groups.stream().map(Map.Entry::getKey), groupLabel.length()) + "s ";
            var rowFormat = groupFormat + "%-" + nameWidth + "s %14s %8s %8s %8s %11s %11s %16s%n";

            System.out.printf("%n--- %s ---%n", title);
            System.out.printf(
                    rowFormat, groupLabel == null ? "" : groupLabel, "transform", "vertices", "smaller", "equal",
                    "larger", "avg shrink", "avg growth", "net bits/vertex");
            for (var group : groups) {
                var first = true;
                for (var row : group.getValue().stats.entrySet()) {
                    var s = row.getValue();
                    var n = s[VERTICES];
                    System.out.printf(
                            rowFormat,
                            groupLabel == null || !first ? "" : group.getKey(),
                            row.getKey(),
                            String.format("%,d", n),
                            share(s[SMALLER], n),
                            share(n - s[SMALLER] - s[LARGER], n),
                            share(s[LARGER], n),
                            s[SMALLER] == 0 ? "-" : String.format("%+.2f", -(double) s[BITS_SAVED] / s[SMALLER]),
                            s[LARGER] == 0 ? "-" : String.format("%+.2f", (double) s[BITS_ADDED] / s[LARGER]),
                            n == 0 ? "-" : String.format("%+.3f", (double) (s[BITS_ADDED] - s[BITS_SAVED]) / n));
                    first = false;
                }
            }
        }

        private static String share(long part, long whole) {
            return whole == 0 ? "-" : String.format("%.1f%%", 100.0 * part / whole);
        }

        void report() {
            if (stats.isEmpty()) {
                return;
            }
            print("per-vertex change vs DELTA_ZIGZAG (bits(x)+bits(y), same vertex)", null,
                    List.of(Map.entry("", this)));
            System.out.printf(
                    "%nsmaller / avg shrink is what the predictor found; larger / avg growth is what it broke,%n"
                            + "and those grown vertices are what FastPFOR's block maximum charges for. net bits/vertex%n"
                            + "is twice the mean-bits change above. Not paired: *_SHARED rows (rings rotated,%n"
                            + "referenced vertices carry no residual).%n");
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
     * A transform reachable for residual-bits reporting, plus the row key it should accumulate under.
     *
     * <p>The row key is the transform's own name for a baseline row, so both baselines collapse onto
     * one {@code DELTA_ZIGZAG} row (same transform, byte-identical residuals), and the encoding's
     * name for a research row.
     *
     * <p>{@code alignedStream} is the transform's stream when it can be paired vertex-by-vertex for
     * {@link VertexShrink}, else {@code null}; {@code reference} is the {@code DELTA_ZIGZAG} stream of
     * the same buffer, shared by every row with the same {@code referenceKey}.
     */
    private record ResidualSource(
            String rowKey,
            Function<BufferContext, long[]> measure,
            Function<BufferContext, int[]> alignedStream,
            String referenceKey,
            Function<BufferContext, int[]> reference) {

        ResidualSource(String rowKey, Function<BufferContext, long[]> measure) {
            this(rowKey, measure, null, "", DELTA_ZIGZAG::apply);
        }
    }

    private static final VertexTransform DELTA_ZIGZAG = new VertexTransform.ZigZagDelta();

    private static ResidualSource residualSourceOf(Encoding encoding) {
        if (encoding instanceof VertexBufferCodec codec) {
            var transform = codec.transform();
            var paired = transform.vertexAligned() && !(transform instanceof VertexTransform.ZigZagDelta);
            return new ResidualSource(
                    transform.name(),
                    ctx -> Residuals.measure(transform.apply(ctx)),
                    paired ? transform::apply : null,
                    "",
                    DELTA_ZIGZAG::apply);
        }
        return null;
    }

    /** A pipeline predictor through its {@code FLAT} split: one residual pair per vertex, in order. */
    private static ResidualSource predicted(Prediction prediction) {
        var transform = new PredictionTransform(prediction);
        return new ResidualSource(
                transform.name(),
                ctx -> Residuals.measure(transform.apply(ctx)),
                transform::apply,
                "",
                DELTA_ZIGZAG::apply);
    }
    public static void run(String inDir, String layer, boolean byZoom) throws IOException {
        var sources = new ArrayList<ResidualSource>();
        for (var encoding : Baselines.baseline()) {
            sources.add(residualSourceOf(encoding));
        }
        for (var prediction : List.of(Prediction.QUAD_CORNER, Prediction.LINE_CONTINUATION,
                Prediction.QUAD_CORNER_LINE, Prediction.QUAD_CORNER_FRAME, Prediction.QUAD_CORNER_LINE_FRAME)) {
            sources.add(predicted(prediction));
        }

        var residuals = new Residuals();
        var shrink = new VertexShrink();
        var shrinkByLayer = new TreeMap<String, VertexShrink>();
        var residualsByZoom = new TreeMap<Integer, Residuals>();
        var shrinkByZoom = new TreeMap<Integer, VertexShrink>();
        var buffersByZoom = new TreeMap<Integer, Integer>();

        for (var entry : ExtractionReader.readManifest(Path.of(inDir))) {
            if (layer != null && !layer.equals(entry.layerName())) {
                continue;
            }
            var ctx = ExtractionReader.readContext(Path.of(inDir), entry);
            if (ctx.vertices().isEmpty()) {
                continue;
            }
            var groupResiduals = new ArrayList<>(List.of(residuals));
            var groupShrinks = new ArrayList<>(List.of(
                    shrink, shrinkByLayer.computeIfAbsent(entry.layerName(), k -> new VertexShrink())));
            if (byZoom) {
                groupResiduals.add(residualsByZoom.computeIfAbsent(entry.z(), z -> new Residuals()));
                groupShrinks.add(shrinkByZoom.computeIfAbsent(entry.z(), z -> new VertexShrink()));
                buffersByZoom.merge(entry.z(), 1, Integer::sum);
            }

            var seenTransforms = new HashSet<String>();
            var referenceStreams = new HashMap<String, int[]>();
            for (var residualSource : sources) {
                if (residualSource == null || !seenTransforms.add(residualSource.rowKey())) {
                    continue;
                }
                var rowKey = residualSource.rowKey();
                var stream = residualSource.alignedStream() == null
                        ? null
                        : residualSource.alignedStream().apply(ctx);
                var measured = stream == null
                        ? residualSource.measure().apply(ctx)
                        : Residuals.measure(stream);
                for (var sink : groupResiduals) {
                    sink.add(rowKey, measured);
                }

                if (stream != null) {
                    var reference = referenceStreams.computeIfAbsent(
                            residualSource.referenceKey(), k -> residualSource.reference().apply(ctx));
                    if (stream.length == reference.length) {
                        var change = VertexShrink.compare(reference, stream);
                        for (var sink : groupShrinks) {
                            sink.add(rowKey, change);
                        }
                    }
                }
            }
        }

        if (residuals.bitSum.isEmpty()) {
            System.out.println("No buffers matched.");
            return;
        }
        residuals.report();
        shrink.report();
        shrinkByLayer(shrinkByLayer);
        if (byZoom) {
            reportByZoom(residualsByZoom, shrinkByZoom, buffersByZoom);
        }
    }

    /**
     * The per-vertex change split by layer, for the {@code TOP_LAYERS} layers with the most vertices.
     * Ranked by vertices rather than bytes, since that is what this table counts; the rest are left
     * out rather than folded, because a summed {@code (other)} row describes no geometry in particular.
     */
    private static void shrinkByLayer(Map<String, VertexShrink> byLayer) {
        var ranked = byLayer.entrySet().stream()
                .filter(e -> !e.getValue().isEmpty())
                .sorted(Comparator.comparingLong((Map.Entry<String, VertexShrink> e) -> e.getValue().vertices())
                        .reversed())
                .limit(TOP_LAYERS)
                .toList();
        VertexShrink.print("per-vertex change vs DELTA_ZIGZAG by layer", "layer", ranked);
    }

    /**
     * The same tables split by tile zoom ({@code --by-zoom}).
     *
     * <p>Generalization changes what a layer's geometry looks like from zoom to zoom — fewer, longer,
     * coarser edges at low zoom, dense short edges at z14 — so a predictor's corpus figure can average
     * a win at one zoom against a loss at another. Each zoom is measured against its own {@code
     * DELTA_ZIGZAG} row.
     */
    private static void reportByZoom(
            Map<Integer, Residuals> residuals, Map<Integer, VertexShrink> shrink, Map<Integer, Integer> buffers) {
            var reference = "DELTA_ZIGZAG";
            var nameWidth = maxLength(
                    residuals.values().stream().flatMap(r -> r.bitSum.keySet().stream()), "transform".length());

            System.out.printf("%n--- residual bits by zoom ---%n");
            System.out.printf(
                    "%4s %8s %-" + nameWidth + "s %14s %10s %17s%n",
                    "zoom", "buffers", "transform", "values", "mean bits", "vs DELTA_ZIGZAG");
            for (var zoomEntry : residuals.entrySet()) {
                var zoom = zoomEntry.getKey();
                var r = zoomEntry.getValue();
                var hasRef = r.bitSum.containsKey(reference);
                var refMean = hasRef ? r.meanBits(reference) : 0.0;
                var first = true;
                for (var name : r.bitSum.keySet()) {
                    var mean = r.meanBits(name);
                    System.out.printf(
                            "%4s %8s %-" + nameWidth + "s %,14d %10.2f %17s%n",
                            first ? "z" + zoom : "",
                            first ? String.valueOf(buffers.get(zoom)) : "",
                            name,
                            r.count.get(name),
                            mean,
                            hasRef ? deltaFraction(mean, refMean) : "-");
                    first = false;
                }
            }

            VertexShrink.print(
                    "per-vertex change vs DELTA_ZIGZAG by zoom",
                    "zoom",
                    shrink.entrySet().stream()
                            .map(e -> Map.entry("z" + e.getKey(), e.getValue()))
                            .toList());
    }

    /**
     * The length of the longest string, but at least {@code min}.
     */
    private static int maxLength(Stream<String> strings, int min) {
        return Math.max(min, strings.mapToInt(String::length).max().orElse(0));
    }
}
