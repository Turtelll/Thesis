package org.maplibre.mlt.research.cli;

import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.PartBoundaries;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.research.LineContinuation;
import org.maplibre.mlt.research.extraction.ExtractionReader;
import org.maplibre.mlt.research.pipeline.Reorder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluation-protocol Stage 0 probe: does the ring frame prior ({@code RingFramePrior}, {@code
 * OrientationPriorCli}) carry over to line parts, i.e. to the {@code transportation} layer? Nothing
 * is kept; the streams below exist only to be sized.
 *
 * <p>The population is what {@code LINE_CONTINUATION} leaves to plain delta on line runs: every
 * non-start vertex that the predictor does not continue (the 2nd vertex of every run and all of the
 * runs shorter than its gate). Each such edge is re-expressed in a predicted frame by the same
 * integer rotation the ring prior uses ({@link OrientationPriorCli#rotate}, exactly invertible, two
 * integers out, nothing signalled). The start vertex of a run and every continued vertex keep their
 * residual, as in the pipeline.
 *
 * <p>Frame sources, directions modulo 90 deg (roads parallel or perpendicular to a decoded road vote
 * together):
 *
 * <ul>
 *   <li>{@code AXIS}: the tile axes, plain delta. The reference.
 *   <li>{@code OWN_EDGE}: the edge's own direction. A ceiling only, the decoder does not have it.
 *   <li>{@code PREV_PART}: the direction of the last edge of the previous line run.
 *   <li>{@code NEAREST_CENTROID}: the frame of the decoded run whose centroid is nearest the run's
 *       start vertex ({@code RingFramePrior}'s rule on open runs).
 *   <li>{@code NEAREST_EDGE}: the direction of the decoded edge whose midpoint is nearest the run's
 *       start vertex.
 *   <li>{@code NEAREST_EDGE_REFINED}: {@code NEAREST_EDGE}'s direction as a prior of {@link
 *       #PRIOR_WEIGHT} length units, then refined by the run's own decoded edges.
 * </ul>
 *
 * <p>The {@code +HEADING} rows additionally rotate the continuation residual of a continued vertex
 * into the direction of the incoming edge (an along-track and a sideways component), to see whether
 * the interior vertices are worth framing as well.
 *
 * <p>The sized stream is {@code LINE_CONTINUATION}'s layout, interleaved zigzag varints, and also
 * three class streams (unframed plain delta | framed edges | continuation residuals) concatenated,
 * the shape {@code Segmentation.CLASS} gives. Both are gzipped per buffer.
 *
 * <p>Usage: {@code ./gradlew research --args="line-frame-prior ../data/extractedBaseline [more dirs] --layer
 * transportation"}
 */
public final class LineFramePriorCli {
    private LineFramePriorCli() {
    }

    /** Weight of the prior against the run's own decoded edges, as an edge length (as in the ring prior). */
    private static final double PRIOR_WEIGHT = 50;

    private static final int CELL = 128;
    private static final int MAX_RADIUS = 64;

    private enum Source {
        AXIS, OWN_EDGE, PREV_PART, NEAREST_CENTROID, NEAREST_EDGE, NEAREST_EDGE_REFINED
    }

    private record Row(String name, Source source, boolean heading) {
    }

    private static final List<Row> ROWS = rows();

    private static List<Row> rows() {
        final var rows = new ArrayList<Row>();
        for (final var source : Source.values()) {
            rows.add(new Row(source.name(), source, false));
        }
        rows.add(new Row("AXIS+HEADING", Source.AXIS, true));
        rows.add(new Row("NEAREST_EDGE_REFINED+HEADING", Source.NEAREST_EDGE_REFINED, true));
        return rows;
    }

    private static final String[] LENGTHS = {"2", "3", "4-5", "6-10", "11+", "all"};
    private static final int ALL = LENGTHS.length - 1;
    private static final String[] ZOOMS = {"<=11", "12", "13", "14"};

    private static final List<Reorder> ORDERS = List.of(Reorder.NONE, Reorder.HILBERT_CENTROID_ROT_MP);

    /** Running sums for one row under one order. */
    private static final class Stats {
        final long[] bits = new long[LENGTHS.length];
        final long[] edges = new long[LENGTHS.length];
        final long[] oneZero = new long[LENGTHS.length];
        long contBits;
        long contCount;
        long varint;
        long gzipInterleaved;
        long gzipSplit;
        final long[] zoomInterleaved = new long[ZOOMS.length];
        final long[] zoomSplit = new long[ZOOMS.length];
        final List<Double> errors = new ArrayList<>();
    }

    public static void main(String[] args) throws IOException {
        var layer = "transportation";
        final var dirs = new ArrayList<String>();
        for (var i = 0; i < args.length; i++) {
            if (args[i].equals("--layer") && i + 1 < args.length) {
                layer = args[++i];
            } else if (!args[i].startsWith("--")) {
                dirs.add(args[i]);
            }
        }
        if (dirs.isEmpty()) {
            throw new IllegalArgumentException("Usage: <extractionDir>... [--layer <name>]");
        }
        for (final var dir : dirs) {
            run(Path.of(dir), layer);
        }
    }

    private static void run(Path inDir, String layer) throws IOException {
        final var manifest = ExtractionReader.readManifest(inDir);
        final var stats = new Stats[ORDERS.size()][ROWS.size()];
        for (final var perOrder : stats) {
            for (var r = 0; r < perOrder.length; r++) {
                perOrder[r] = new Stats();
            }
        }
        var buffers = 0;
        var vertices = 0L;
        for (final var entry : manifest) {
            if (!entry.layerName().equals(layer)) {
                continue;
            }
            final var original = ExtractionReader.readContext(inDir, entry);
            if (original.vertices().isEmpty()) {
                continue;
            }
            buffers++;
            vertices += original.vertices().size();
            final var zoom = entry.z() <= 11 ? 0 : entry.z() - 11 > 3 ? 3 : entry.z() - 11;
            for (var o = 0; o < ORDERS.size(); o++) {
                final var ctx = ORDERS.get(o).apply(original);
                final var n = ctx.vertices().size();
                final var starts = PartBoundaries.of(ctx, n);
                final var isLine = starts.length < 2 ? null : PartBoundaries.lineRuns(ctx, starts.length);
                final var predictor = new LineContinuation();
                final var classes = predictor.classify(ctx, n);
                for (var r = 0; r < ROWS.size(); r++) {
                    measure(ROWS.get(r), ctx, starts, isLine, predictor, classes, zoom, stats[o][r]);
                }
            }
        }
        System.out.printf("%n==== LineFramePriorCli on %s, layer '%s': %,d buffers, %,d vertices ====%n",
                inDir, layer, buffers, vertices);
        for (var o = 0; o < ORDERS.size(); o++) {
            System.out.printf("%n######## order: %s ########%n", ORDERS.get(o).name());
            report(stats[o]);
        }
    }

    // ---------------------------------------------------------------------------
    // Per buffer and row
    // ---------------------------------------------------------------------------

    private static void measure(
            Row row,
            BufferContext ctx,
            int[] starts,
            boolean[] isLine,
            LineContinuation predictor,
            int[] classes,
            int zoom,
            Stats st) {
        final var v = ctx.vertices();
        final var n = v.size();
        final var interleaved = new ByteArrayOutputStream();
        final var plain = new ByteArrayOutputStream();
        final var framed = new ByteArrayOutputStream();
        final var continued = new ByteArrayOutputStream();
        final var centroids = new Grid();
        final var edges = new Grid();
        var lastEdgeTheta = 0.0;

        for (var r = 0; r < starts.length; r++) {
            final var start = starts[r];
            final var end = r + 1 < starts.length ? starts[r + 1] : n;
            final var line = isLine != null && isLine[r];
            var sumC = 0.0;
            var sumS = 0.0;
            var runC = 0.0;
            var runS = 0.0;
            var prior = 0.0;
            if (line) {
                final var first = v.get(start);
                final var found = switch (row.source()) {
                    case NEAREST_CENTROID -> centroids.nearest(first.x(), first.y());
                    case NEAREST_EDGE, NEAREST_EDGE_REFINED -> edges.nearest(first.x(), first.y());
                    case PREV_PART -> lastEdgeTheta;
                    default -> 0.0;
                };
                prior = Double.isNaN(found) ? 0 : found;
                sumC = PRIOR_WEIGHT * Math.cos(Math.toRadians(4 * prior));
                sumS = PRIOR_WEIGHT * Math.sin(Math.toRadians(4 * prior));
            }
            final var bucket = lengthBucket(end - start);

            for (var i = start; i < end; i++) {
                final var cur = v.get(i);
                final var prev = i == 0 ? new Vertex(0, 0) : v.get(i - 1);
                final var dx = cur.x() - prev.x();
                final var dy = cur.y() - prev.y();
                final var cls = classes[i];
                if (predictor.hasPrediction(cls)) {
                    var rx = (long) cur.x() - predictor.predictX(cls, i, v);
                    var ry = (long) cur.y() - predictor.predictY(cls, i, v);
                    if (row.heading()) {
                        final var incoming = v.get(i - 1);
                        final var before = v.get(i - 2);
                        final var rotated = OrientationPriorCli.rotate(
                                rx, ry, own(incoming.x() - before.x(), incoming.y() - before.y()));
                        rx = rotated[0];
                        ry = rotated[1];
                    }
                    write(interleaved, rx, ry);
                    write(continued, rx, ry);
                    st.contBits += bits(rx) + bits(ry);
                    st.contCount++;
                } else if (line && i > start) {
                    final var frame = switch (row.source()) {
                        case AXIS -> 0.0;
                        case OWN_EDGE -> own(dx, dy);
                        case PREV_PART, NEAREST_CENTROID, NEAREST_EDGE -> quantize(prior);
                        case NEAREST_EDGE_REFINED ->
                                quantize(fold(Math.toDegrees(Math.atan2(sumS, sumC)) / 4));
                    };
                    final var rotated = OrientationPriorCli.rotate(dx, dy, frame);
                    write(interleaved, rotated[0], rotated[1]);
                    write(framed, rotated[0], rotated[1]);
                    final var cost = bits(rotated[0]) + bits(rotated[1]);
                    final var zero = rotated[0] == 0 || rotated[1] == 0 ? 1 : 0;
                    for (final var b : new int[]{bucket, ALL}) {
                        st.bits[b] += cost;
                        st.edges[b]++;
                        st.oneZero[b] += zero;
                    }
                    if (dx != 0 || dy != 0) {
                        st.errors.add(angleDiff(frame, own(dx, dy)));
                    }
                } else {
                    write(interleaved, dx, dy);
                    write(plain, dx, dy);
                }
                if (line && i > start && (dx != 0 || dy != 0)) {
                    final var len = Math.hypot(dx, dy);
                    final var phi = 4 * Math.atan2(dy, dx);
                    sumC += len * Math.cos(phi);
                    sumS += len * Math.sin(phi);
                    runC += len * Math.cos(phi);
                    runS += len * Math.sin(phi);
                }
            }

            if (line && end - start >= 2) {
                if (row.source() == Source.NEAREST_CENTROID) {
                    var cx = 0.0;
                    var cy = 0.0;
                    for (var k = start; k < end; k++) {
                        cx += v.get(k).x();
                        cy += v.get(k).y();
                    }
                    final var theta = runC == 0 && runS == 0 ? 0 : fold(Math.toDegrees(Math.atan2(runS, runC)) / 4);
                    centroids.add(cx / (end - start), cy / (end - start), theta);
                }
                for (var k = start + 1; k < end; k++) {
                    final var a = v.get(k - 1);
                    final var b = v.get(k);
                    final var ex = b.x() - a.x();
                    final var ey = b.y() - a.y();
                    if (ex == 0 && ey == 0) {
                        continue;
                    }
                    final var theta = own(ex, ey);
                    lastEdgeTheta = theta;
                    if (row.source() == Source.NEAREST_EDGE || row.source() == Source.NEAREST_EDGE_REFINED) {
                        edges.add((a.x() + b.x()) / 2.0, (a.y() + b.y()) / 2.0, theta);
                    }
                }
            }
        }

        final var inter = interleaved.toByteArray();
        final var split = new ByteArrayOutputStream();
        split.writeBytes(plain.toByteArray());
        split.writeBytes(framed.toByteArray());
        split.writeBytes(continued.toByteArray());
        final var gi = Compressor.GZIP.size(inter);
        final var gs = Compressor.GZIP.size(split.toByteArray());
        st.varint += inter.length;
        st.gzipInterleaved += gi;
        st.gzipSplit += gs;
        st.zoomInterleaved[zoom] += gi;
        st.zoomSplit[zoom] += gs;
    }

    private static int lengthBucket(int n) {
        if (n <= 2) {
            return 0;
        }
        if (n == 3) {
            return 1;
        }
        if (n <= 5) {
            return 2;
        }
        return n <= 10 ? 3 : 4;
    }

    // ---------------------------------------------------------------------------
    // Frames
    // ---------------------------------------------------------------------------

    /** An edge's own direction folded into [-45, 45); 0 for a zero edge. */
    private static double own(int dx, int dy) {
        return dx == 0 && dy == 0 ? 0 : fold(Math.toDegrees(Math.atan2(dy, dx)));
    }

    private static double fold(double deg) {
        return ((deg + 45) % 90 + 90) % 90 - 45;
    }

    private static double angleDiff(double a, double b) {
        return Math.abs(fold(a - b));
    }

    private static double quantize(double theta) {
        final var index = (int) Math.round((theta + 45) / 90 * 65536) & 0xFFFF;
        return index * 90.0 / 65536 - 45;
    }

    /** Uniform grid over points carrying a frame; only points added so far are candidates. */
    private static final class Grid {
        private final Map<Long, List<double[]>> cells = new HashMap<>();
        private int size;

        void add(double x, double y, double theta) {
            cells.computeIfAbsent(key(cell(x), cell(y)), k -> new ArrayList<>()).add(new double[]{x, y, theta});
            size++;
        }

        /** The frame of the nearest point, or NaN if there is none. */
        double nearest(double x, double y) {
            if (size == 0) {
                return Double.NaN;
            }
            final var cx = cell(x);
            final var cy = cell(y);
            var best = Double.NaN;
            var bestDist = Double.MAX_VALUE;
            for (var radius = 0; radius <= MAX_RADIUS; radius++) {
                for (var dx = -radius; dx <= radius; dx++) {
                    final var edge = Math.abs(dx) == radius;
                    for (var dy = -radius; dy <= radius; dy += edge ? 1 : Math.max(1, 2 * radius)) {
                        final var list = cells.get(key(cx + dx, cy + dy));
                        if (list == null) {
                            continue;
                        }
                        for (final var p : list) {
                            final var d = Math.hypot(p[0] - x, p[1] - y);
                            if (d < bestDist) {
                                bestDist = d;
                                best = p[2];
                            }
                        }
                    }
                }
                if (!Double.isNaN(best) && bestDist <= (double) radius * CELL) {
                    break;
                }
            }
            return best;
        }

        private static int cell(double coordinate) {
            return (int) Math.floor(coordinate / CELL);
        }

        private static long key(int x, int y) {
            return ((long) x << 32) ^ (y & 0xFFFFFFFFL);
        }
    }

    // ---------------------------------------------------------------------------
    // Bytes
    // ---------------------------------------------------------------------------

    private static long zigzag(long value) {
        return (value << 1) ^ (value >> 63);
    }

    private static int bits(long value) {
        return 64 - Long.numberOfLeadingZeros(zigzag(value));
    }

    private static void write(ByteArrayOutputStream out, long x, long y) {
        writeVarint(out, x);
        writeVarint(out, y);
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        var zz = zigzag(value);
        while ((zz & ~0x7FL) != 0) {
            out.write((int) (zz & 0x7F) | 0x80);
            zz >>>= 7;
        }
        out.write((int) zz);
    }

    // ---------------------------------------------------------------------------
    // Reporting
    // ---------------------------------------------------------------------------

    private static void report(Stats[] stats) {
        final var ref = stats[0];
        System.out.printf("framed edges: %,d (by run length:", ref.edges[ALL]);
        for (var b = 0; b < LENGTHS.length - 1; b++) {
            System.out.printf(" %s=%,d", LENGTHS[b], ref.edges[b]);
        }
        System.out.printf("); continued vertices: %,d%n%n", ref.contCount);

        System.out.println("--- Frame error against the edge's own direction, degrees mod 90 (AXIS = the angle itself) ---");
        System.out.printf("%-30s %8s %8s %8s %8s%n", "source", "<=1", "<=5", "<=15", "median");
        for (var r = 0; r < ROWS.size(); r++) {
            if (ROWS.get(r).heading()) {
                continue;
            }
            final var e = sorted(stats[r].errors);
            System.out.printf("%-30s %7.1f%% %7.1f%% %7.1f%% %8.2f%n", ROWS.get(r).name(),
                    within(e, 1), within(e, 5), within(e, 15), e.length == 0 ? 0 : e[e.length / 2]);
        }
        System.out.println();

        System.out.println("--- Mean zigzag bits per framed edge, by run length ---");
        lengthHeader();
        for (var r = 0; r < ROWS.size(); r++) {
            if (ROWS.get(r).heading()) {
                continue;
            }
            final var line = new StringBuilder(String.format("%-30s", ROWS.get(r).name()));
            for (var b = 0; b < LENGTHS.length; b++) {
                line.append(String.format("%9.2f", stats[r].edges[b] == 0
                        ? 0 : (double) stats[r].bits[b] / stats[r].edges[b]));
            }
            System.out.println(line);
        }
        System.out.println();

        System.out.println("--- Framed edges with a zero component, by run length ---");
        lengthHeader();
        for (var r = 0; r < ROWS.size(); r++) {
            if (ROWS.get(r).heading()) {
                continue;
            }
            final var line = new StringBuilder(String.format("%-30s", ROWS.get(r).name()));
            for (var b = 0; b < LENGTHS.length; b++) {
                line.append(String.format("%8.1f%%", stats[r].edges[b] == 0
                        ? 0 : 100.0 * stats[r].oneZero[b] / stats[r].edges[b]));
            }
            System.out.println(line);
        }
        System.out.println();

        System.out.println("--- Mean zigzag bits per continued vertex (HEADING rotates the continuation residual) ---");
        for (var r = 0; r < ROWS.size(); r++) {
            if (!ROWS.get(r).name().equals("AXIS") && !ROWS.get(r).heading()) {
                continue;
            }
            System.out.printf("%-30s %9.2f%n", ROWS.get(r).name(),
                    stats[r].contCount == 0 ? 0 : (double) stats[r].contBits / stats[r].contCount);
        }
        System.out.println();

        System.out.println("--- Whole buffer, LINE_CONTINUATION layout, summed over buffers (gzip per buffer) ---");
        System.out.printf("%-30s %12s %9s %12s %9s %12s %9s%n", "source",
                "varint", "vs AXIS", "gzip inter", "vs AXIS", "gzip split", "vs AXIS");
        for (var r = 0; r < ROWS.size(); r++) {
            final var s = stats[r];
            System.out.printf("%-30s %,12d %+8.2f%% %,12d %+8.2f%% %,12d %+8.2f%%%n", ROWS.get(r).name(),
                    s.varint, pct(s.varint, ref.varint),
                    s.gzipInterleaved, pct(s.gzipInterleaved, ref.gzipInterleaved),
                    s.gzipSplit, pct(s.gzipSplit, ref.gzipSplit));
        }
        System.out.println();

        System.out.println("--- gzip split vs AXIS, per zoom (gzip inter in brackets) ---");
        final var head = new StringBuilder(String.format("%-30s", "source"));
        for (final var z : ZOOMS) {
            head.append(String.format("%20s", z));
        }
        System.out.println(head);
        for (var r = 1; r < ROWS.size(); r++) {
            final var line = new StringBuilder(String.format("%-30s", ROWS.get(r).name()));
            for (var z = 0; z < ZOOMS.length; z++) {
                line.append(String.format("%11s (%6s)",
                        String.format("%+.2f%%", pct(stats[r].zoomSplit[z], ref.zoomSplit[z])),
                        String.format("%+.2f%%", pct(stats[r].zoomInterleaved[z], ref.zoomInterleaved[z]))));
            }
            System.out.println(line);
        }
        System.out.println("(OWN_EDGE is a ceiling: the decoder has no frame for it.)");
    }

    private static void lengthHeader() {
        final var line = new StringBuilder(String.format("%-30s", "source"));
        for (final var l : LENGTHS) {
            line.append(String.format("%9s", l));
        }
        System.out.println(line);
    }

    private static double[] sorted(List<Double> values) {
        final var a = values.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(a);
        return a;
    }

    private static double within(double[] sorted, double limit) {
        if (sorted.length == 0) {
            return 0;
        }
        var count = 0;
        for (final var x : sorted) {
            if (x <= limit) {
                count++;
            }
        }
        return 100.0 * count / sorted.length;
    }

    private static double pct(long value, long ref) {
        return ref == 0 ? 0 : 100.0 * (value - ref) / ref;
    }
}
