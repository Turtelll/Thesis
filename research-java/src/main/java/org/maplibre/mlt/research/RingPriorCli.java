package org.maplibre.mlt.research;

import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.research.encoding.PartBoundaries;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.extraction.ExtractionReader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * Measures whether a model-based predictor could beat the baseline first-order delta on the interior
 * vertices of polygon rings with <em>more than four vertices</em> — the population {@link
 * org.maplibre.mlt.research.encoding.research.SegmentQuadCorner}'s four-vertex parallelogram corner
 * predictor does not reach.
 *
 * <p>This encodes nothing. It walks every ring of length {@code > 4} and, for each interior vertex
 * with two available predecessors, computes the zigzag bit width of the residual under three
 * predictors, accumulated per layer <em>and per ring vertex count</em> (4, 5, 6, 7, 8, {@code >8},
 * pooled). The per-size resolution is load-bearing: pooled, this measurement concluded that no
 * predictor beats a first-order delta, but rectilinearity is an even/odd property of a ring — a
 * closed rectilinear ring must have an even vertex count — so the pooled mean averages across two
 * populations that behave differently. See {@code docs/results/failed/ring-predictors.md}.
 *
 * <ul>
 *   <li><b>B — first-order delta</b> (the baseline): {@code pred = p[i-1]}.
 *   <li><b>C — edge continuation</b> (constant velocity, for collinear runs): {@code pred = p[i-1] +
 *       (p[i-1] - p[i-2])}.
 *   <li><b>R — right-angle turn</b> (rectilinear): {@code pred = p[i-1] + rot90(p[i-1] - p[i-2])},
 *       measured for both turn directions and a per-vertex best-of-the-two.
 * </ul>
 *
 * <p>Residuals are also counted <em>per component</em>, not only as a pair: the {@code %<=1bit}
 * metric ANDs x and y, so it structurally cannot see a predictor that zeroes exactly one of them —
 * which is what an axis-aligned edge does, and is worth a whole component's bits.
 *
 * <p>A predictor only earns an encoder if, on a population large enough to matter, its residual is
 * clearly narrower than B — bit width is the right currency because FastPFOR packs a block to the
 * width of its widest value. The prior negative results (uniform delta-of-delta, central-symmetry
 * reflection — see {@code docs/results/failed/}) are the bar: both were <em>wider</em> than a
 * short-edge delta.
 *
 * <p><b>Scope: pure-polygon columns only.</b> Columns that mix polygons and linestrings fold the
 * linestring vertex counts into the same {@code numRings} stream, so a run there is not necessarily a
 * true ring. Restricting to columns whose {@code geometryTypes} are all POLYGON/MULTIPOLYGON keeps
 * ~75% of the corpus's {@code >4}-vertex rings with zero contamination — the honest measurement
 * scope.
 *
 * <p>Usage: {@code ./gradlew ringPrior --args="../data/extracted"}
 */
public final class RingPriorCli {
    private RingPriorCli() {
    }

    private static final int POLYGON = 2;
    private static final int MULTIPOLYGON = 5;

    /**
     * Ring-size bucket collecting every ring with more than 8 vertices.
     */
    private static final int MANY = 9;

    /**
     * {@code >8} split by parity: rectilinearity is an even/odd property, so pooling them hides it.
     */
    private static final int MANY_EVEN = 10;
    private static final int MANY_ODD = 11;

    /**
     * Pseudo-bucket key for the pooled "every >4-vertex ring" stats; sorts after {@link #MANY}.
     */
    private static final int ALL = 99;

    /**
     * Per-layer running sums; one instance per predictor.
     */
    private static final class Stat {
        long vertices;
        long bitSum;
        long zeroCount; // residual exactly (0,0)
        long leCount; // both components' zigzag width <= 1 bit
        // Per-component zero counts. The paired leCount above ANDs the two components, so it cannot
        // see a predictor that zeroes exactly one of them -- which is what an axis-aligned edge does,
        // and is worth a whole component's bits.
        long zeroX;
        long zeroY;
        long componentBitsNonZero; // bits spent on the components that are not zero

        void add(int zzx, int zzy) {
            final var wx = width(zzx);
            final var wy = width(zzy);
            vertices++;
            bitSum += wx + wy;
            if (zzx == 0 && zzy == 0) {
                zeroCount++;
            }
            if (wx <= 1 && wy <= 1) {
                leCount++;
            }
            if (zzx == 0) {
                zeroX++;
            } else {
                componentBitsNonZero += wx;
            }
            if (zzy == 0) {
                zeroY++;
            } else {
                componentBitsNonZero += wy;
            }
        }

        /**
         * Share of the 2N components that are exactly zero.
         */
        double zeroComponentPct() {
            return vertices == 0 ? 0 : 100.0 * (zeroX + zeroY) / (2 * vertices);
        }

        double meanBits() {
            return vertices == 0 ? 0 : (double) bitSum / vertices;
        }
    }

    /**
     * All predictors' stats for one layer, plus the per-vertex best-of oracle.
     */
    private static final class LayerStats {
        final Stat baseline = new Stat();
        final Stat continuation = new Stat();
        final Stat rotCcw = new Stat();
        final Stat rotCw = new Stat();
        final Stat rotBest = new Stat(); // per-vertex min bits over the two turn directions
        final Stat overallBest = new Stat(); // per-vertex min bits over B, C, and both turns
        final Stat staircase = new Stat(); // rot90 forced OPPOSITE to the previous actual turn's sign
        // Lag-2 edge copy: in a rectilinear ring edge k is parallel to edge k-2, so predict the
        // current edge as the one two back. Copies a whole VECTOR, so it predicts both components
        // and is rotation-invariant -- the two properties rot90 lacked.
        final Stat lag2 = new Stat();
        // Axis deinterleaving: in a rectilinear ring consecutive edges alternate between two
        // perpendicular axes, so edges at even and odd ring positions belong to different axes.
        // These two stats are plain deltas, split by that parity -- no new predictor, just the
        // question of whether the two groups have different width characters (a block-width lever).
        final Stat evenPos = new Stat();
        final Stat oddPos = new Stat();
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: <extractionDir>");
        }
        final var inDir = Path.of(args[0]);

        final var manifest = ExtractionReader.readManifest(inDir);

        // Keyed by ring vertex count (MANY == ">8", ALL == pooled), each holding the per-layer stats.
        final var bySize = new TreeMap<Integer, TreeMap<String, LayerStats>>();

        for (final var entry : manifest) {
            final var ctx = ExtractionReader.readContext(inDir, entry);
            if (ctx.vertices().isEmpty() || !isPurePolygon(ctx.geometryTypes())) {
                continue;
            }
            measure(ctx, entry.layerName(), bySize);
        }

        report(bySize);
    }

    /**
     * Accumulates every {@code >4}-vertex ring's interior-vertex residuals, into both its own
     * ring-size bucket and the pooled {@link #ALL} bucket.
     */
    private static void measure(
            BufferContext ctx, String layer, TreeMap<Integer, TreeMap<String, LayerStats>> bySize) {
        final var vertices = ctx.vertices();
        final var starts = PartBoundaries.of(ctx, vertices.size());
        for (var s = 0; s < starts.length; s++) {
            final var start = starts[s];
            final var end = s + 1 < starts.length ? starts[s + 1] : vertices.size();
            if (end - start <= 4) {
                continue;
            }
            final var n = end - start;
            final var bucket = n <= 8 ? n : (n % 2 == 0 ? MANY_EVEN : MANY_ODD);
            final var stats = statsFor(bySize, bucket, layer);
            final var pooled = statsFor(bySize, ALL, layer);
            // Interior vertices: i-2 and i-1 both exist within the ring, so a predecessor edge is defined.
            for (var i = start + 2; i < end; i++) {
                final var p0 = vertices.get(i - 2);
                final var p1 = vertices.get(i - 1);
                final var p = vertices.get(i);
                final var edgeX = p1.x() - p0.x();
                final var edgeY = p1.y() - p0.y();

                // Per-vertex best-of: pick the residual with the smallest total bit width, count it there.
                final var bRes = new int[]{p.x() - p1.x(), p.y() - p1.y()};
                final var cRes = new int[]{p.x() - (p1.x() + edgeX), p.y() - (p1.y() + edgeY)};
                // rot90 ccw of (edgeX,edgeY) is (-edgeY, edgeX); cw is (edgeY, -edgeX).
                final var ccwRes = new int[]{p.x() - (p1.x() - edgeY), p.y() - (p1.y() + edgeX)};
                final var cwRes = new int[]{p.x() - (p1.x() + edgeY), p.y() - (p1.y() - edgeX)};

                // Lag-2: pred = p[i-1] + (p[i-2] - p[i-3]). Needs three in-ring predecessors.
                if (i >= start + 3) {
                    final var pm = vertices.get(i - 3);
                    final var lagX = p0.x() - pm.x();
                    final var lagY = p0.y() - pm.y();
                    addAt(
                            stats.lag2,
                            pooled.lag2,
                            new int[]{p.x() - (p1.x() + lagX), p.y() - (p1.y() + lagY)});
                }

                if ((i - start) % 2 == 0) {
                    addAt(stats.evenPos, pooled.evenPos, bRes);
                } else {
                    addAt(stats.oddPos, pooled.oddPos, bRes);
                }

                addAt(stats.baseline, pooled.baseline, bRes);
                addAt(stats.continuation, pooled.continuation, cRes);
                addAt(stats.rotCcw, pooled.rotCcw, ccwRes);
                addAt(stats.rotCw, pooled.rotCw, cwRes);
                addAt(stats.rotBest, pooled.rotBest, minBits(ccwRes, cwRes));
                addAt(
                        stats.overallBest,
                        pooled.overallBest,
                        minBits(minBits(bRes, cRes), minBits(ccwRes, cwRes)));

                // Staircase: force the current turn OPPOSITE to the previous actual turn. Needs a previous
                // edge (p[i-3]->p[i-2]) to read the previous turn's sign, so only from the 2nd interior
                // vertex on. The sign is the cross product of the two prior edges; derivable at decode
                // (reads only p[i-3..i-1]), so nothing is signalled.
                if (i >= start + 3) {
                    final var pm = vertices.get(i - 3);
                    final var prevEdgeX = p0.x() - pm.x();
                    final var prevEdgeY = p0.y() - pm.y();
                    final var prevTurn = prevEdgeX * edgeY - prevEdgeY * edgeX; // >0 ccw, <0 cw
                    // Force opposite: if previous turn was ccw, this one is cw, and vice versa. Tie -> ccw.
                    addAt(stats.staircase, pooled.staircase, prevTurn > 0 ? cwRes : ccwRes);
                }
            }
        }
    }

    /**
     * The residual of the two with the smaller total zigzag bit width.
     */
    private static int[] minBits(int[] a, int[] b) {
        return bits(a[0], a[1]) <= bits(b[0], b[1]) ? a : b;
    }

    /**
     * Adds one residual to a ring-size bucket's stat and to the pooled stat at once.
     */
    private static void addAt(Stat sized, Stat pooled, int[] res) {
        final var zzx = EncodingUtils.encodeZigZag(res[0]);
        final var zzy = EncodingUtils.encodeZigZag(res[1]);
        sized.add(zzx, zzy);
        pooled.add(zzx, zzy);
    }

    /**
     * The {@code (size, layer)} cell, created on demand.
     */
    private static LayerStats statsFor(
            TreeMap<Integer, TreeMap<String, LayerStats>> bySize, int size, String layer) {
        return bySize
                .computeIfAbsent(size, k -> new TreeMap<>())
                .computeIfAbsent(layer, k -> new LayerStats());
    }

    /**
     * Total zigzag bit width of a residual — the currency FastPFOR prices a block in.
     */
    private static int bits(int dx, int dy) {
        return width(EncodingUtils.encodeZigZag(dx)) + width(EncodingUtils.encodeZigZag(dy));
    }

    private static int width(int zigzag) {
        return 32 - Integer.numberOfLeadingZeros(zigzag);
    }

    private static boolean isPurePolygon(List<Integer> geometryTypes) {
        if (geometryTypes.isEmpty()) {
            return false;
        }
        for (final var t : geometryTypes) {
            if (t != POLYGON && t != MULTIPOLYGON) {
                return false;
            }
        }
        return true;
    }

    private static void report(TreeMap<Integer, TreeMap<String, LayerStats>> bySize) {
        System.out.printf(
                "Residual bit width on interior vertices of >4-vertex polygon rings (pure-polygon columns),%n"
                        + "resolved per ring vertex count (%d == '>8', 'all' == pooled).%n"
                        + "Lower mean bits is better; %%<=1bit is the separable near-zero share.%n%n",
                MANY);

        // Every layer that appears in any bucket, plus a CORPUS roll-up across layers.
        final var layers = new TreeSet<String>();
        for (final var byLayer : bySize.values()) {
            layers.addAll(byLayer.keySet());
        }

        for (final var layer : layers) {
            printLayer(layer, bySize, l -> l.get(layer));
        }
        printLayer("CORPUS", bySize, RingPriorCli::rollUp);
    }

    /**
     * Every predictor's mean bits across the ring-size buckets, for one layer.
     */
    private static void printLayer(
            String layer,
            TreeMap<Integer, TreeMap<String, LayerStats>> bySize,
            Function<TreeMap<String, LayerStats>, LayerStats> select) {
        final var columns = new LinkedHashMap<Integer, LayerStats>();
        for (final var e : bySize.entrySet()) {
            final var stats = select.apply(e.getValue());
            if (stats != null && stats.baseline.vertices > 0) {
                columns.put(e.getKey(), stats);
            }
        }
        if (columns.isEmpty()) {
            return;
        }

        System.out.printf("=== %s ===%n", layer);
        final var header = new StringBuilder(String.format("%-16s", "predictor"));
        for (final var k : columns.keySet()) {
            header.append(String.format("%10s", columnLabel(k)));
        }
        System.out.println(header);

        printRow("vertices", columns, s -> (double) s.baseline.vertices, "%,.0f");
        printRow("B baseline", columns, s -> s.baseline.meanBits(), "%.2f");
        printRow("C continuation", columns, s -> s.continuation.meanBits(), "%.2f");
        printRow("R rot90-ccw", columns, s -> s.rotCcw.meanBits(), "%.2f");
        printRow("R rot90-cw", columns, s -> s.rotCw.meanBits(), "%.2f");
        printRow("R rot-best", columns, s -> s.rotBest.meanBits(), "%.2f");
        printRow("staircase-alt", columns, s -> s.staircase.meanBits(), "%.2f");
        printRow("L lag2-edge", columns, s -> s.lag2.meanBits(), "%.2f");
        printRow("B @even pos", columns, s -> s.evenPos.meanBits(), "%.2f");
        printRow("B @odd pos", columns, s -> s.oddPos.meanBits(), "%.2f");
        printRow("best{B,C,R}", columns, s -> s.overallBest.meanBits(), "%.2f");
        printRow("B %<=1bit", columns, s -> pctLe(s.baseline), "%.2f%%");
        printRow("rot-best %<=1bit", columns, s -> pctLe(s.rotBest), "%.2f%%");
        printRow("B %zero-comp", columns, s -> s.baseline.zeroComponentPct(), "%.2f%%");
        printRow("ccw %zero-comp", columns, s -> s.rotCcw.zeroComponentPct(), "%.2f%%");
        printRow("rot-best %zero-comp", columns, s -> s.rotBest.zeroComponentPct(), "%.2f%%");
        printRow("lag2 %<=1bit", columns, s -> pctLe(s.lag2), "%.2f%%");
        printRow("lag2 %zero-comp", columns, s -> s.lag2.zeroComponentPct(), "%.2f%%");
        printRow("lag2 %zero-pair", columns, s -> pctZero(s.lag2), "%.2f%%");
        System.out.println();
    }

    private static void printRow(
            String label,
            LinkedHashMap<Integer, LayerStats> columns,
            ToDoubleFunction<LayerStats> metric,
            String format) {
        final var line = new StringBuilder(String.format("%-16s", label));
        for (final var stats : columns.values()) {
            line.append(String.format("%10s", String.format(format, metric.applyAsDouble(stats))));
        }
        System.out.println(line);
    }

    private static String columnLabel(int bucket) {
        if (bucket == ALL) {
            return "all";
        }
        if (bucket == MANY_EVEN) {
            return ">8even";
        }
        if (bucket == MANY_ODD) {
            return ">8odd";
        }
        return Integer.toString(bucket);
    }

    private static double pctZero(Stat stat) {
        return stat.vertices == 0 ? 0 : 100.0 * stat.zeroCount / stat.vertices;
    }

    private static double pctLe(Stat stat) {
        return stat.vertices == 0 ? 0 : 100.0 * stat.leCount / stat.vertices;
    }

    /**
     * Sums every layer's stats in one ring-size bucket into a single corpus-wide LayerStats.
     */
    private static LayerStats rollUp(TreeMap<String, LayerStats> byLayer) {
        final var corpus = new LayerStats();
        for (final var stats : byLayer.values()) {
            accumulate(corpus, stats);
        }
        return corpus;
    }

    private static void accumulate(LayerStats into, LayerStats from) {
        add(into.baseline, from.baseline);
        add(into.continuation, from.continuation);
        add(into.rotCcw, from.rotCcw);
        add(into.rotCw, from.rotCw);
        add(into.rotBest, from.rotBest);
        add(into.overallBest, from.overallBest);
        add(into.staircase, from.staircase);
        add(into.lag2, from.lag2);
        add(into.evenPos, from.evenPos);
        add(into.oddPos, from.oddPos);
    }

    private static void add(Stat into, Stat from) {
        into.vertices += from.vertices;
        into.bitSum += from.bitSum;
        into.zeroCount += from.zeroCount;
        into.leCount += from.leCount;
        into.zeroX += from.zeroX;
        into.zeroY += from.zeroY;
        into.componentBitsNonZero += from.componentBitsNonZero;
    }

}
