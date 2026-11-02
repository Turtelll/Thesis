package org.maplibre.mlt.research.probe;

import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.buffer.VertexRuns;
import org.maplibre.mlt.research.buffer.Encoding.BufferContext;
import org.maplibre.mlt.research.extraction.ExtractionReader;
import org.maplibre.mlt.research.measurement.compression.Compressor;

import static org.maplibre.mlt.research.probe.ProbeMath.angleDiff;
import static org.maplibre.mlt.research.probe.ProbeMath.bits;
import static org.maplibre.mlt.research.probe.ProbeMath.fold;
import static org.maplibre.mlt.research.probe.ProbeMath.writeVarint;
import static org.maplibre.mlt.research.probe.ProbeMath.zigzag;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluation-protocol Stage 0 probe: can a building ring's own frame (the rotation that makes its
 * walls axis-parallel) be predicted from context the decoder already has, or from a few angles a
 * layer pre-pass writes into the buffer? Nothing is kept; the streams below exist only to be sized.
 *
 * <p>Buildings are right-angled in their own frame but rarely in the tile's ({@code BuildingShapeCli}:
 * about a quarter within 5 deg of the tile axes), so plain delta pays two full components per wall.
 * Every edge {@code QUAD_CORNER} codes as a delta is re-expressed in a predicted frame by an integer
 * rotation made of three lifting shears. That map is a bijection on Z^2: the edge stays two integers
 * (no extra residual stream, unlike {@code TURN_SCALAR}'s along/corr triple) and the decoder inverts
 * it exactly. A wall aligned with the predicted frame then has one component near zero.
 *
 * <p>Orientation sources, angles modulo 90 deg:
 *
 * <ul>
 *   <li>{@code AXIS}: the tile axes, i.e. plain delta. The reference.
 *   <li>{@code OWN}: the ring's own frame. A ceiling only, the decoder does not have it.
 *   <li>{@code PREV_RING}: the frame of the previous ring in buffer order.
 *   <li>{@code NEAREST}: the frame of the decoded ring whose centroid is nearest the current ring's
 *       start vertex (decoded before the ring's first edge).
 *   <li>{@code NEAREST_RECT}: the same over fully right-angled decoded rings only.
 *   <li>{@code TOP1}/{@code TOP4}/{@code TOP16}: the buffer's k dominant frames from a pre-pass,
 *       2 bytes each in front of the buffer; per ring the encoder takes the cheapest and writes its
 *       index (one byte per ring in the sized stream, {@code ceil(log2 k)} bits in the bit tables).
 *   <li>{@code TOP4_SNAP}: {@code NEAREST_RECT}'s angle snapped to the closest {@code TOP4} frame
 *       within 2 deg; costs the header only.
 *   <li>{@code PREV_EDGE}: the ring's previous edge, {@code TURN_SCALAR}'s direction prior; the first
 *       edge of a ring stays a plain delta.
 *   <li>{@code NEAREST_REFINED}: {@code NEAREST}'s frame for the first edge, then the length-weighted
 *       frame of that prior (weighted as an edge of {@link #PRIOR_WEIGHT} units) and the ring's
 *       already decoded edges.
 * </ul>
 *
 * <p>The sized stream per buffer is {@code QUAD_CORNER}'s layout, interleaved zigzag varints: per ring
 * the start delta from the previous ring's last vertex, the coded edges, and on 4-vertex rings the
 * parallelogram corner residual. Only the edges change between sources. The {@code gated} rows apply
 * a source to even rings of 4+ vertices only (derivable from {@code numRings}), plain delta elsewhere.
 *
 * <p>Usage: {@code ./gradlew research --args="orientation-prior ../data/extractedBaseline --layer building"}
 */
public final class OrientationPriorProbe {
    private OrientationPriorProbe() {
    }

    private static final int POLYGON = 2;
    private static final int MULTIPOLYGON = 5;

    /** Neighbour-search grid cell, in tile units. */
    private static final int CELL = 128;

    /** Chebyshev search radius cap, in cells; covers a 4096 extent plus buffer. */
    private static final int MAX_RADIUS = 64;

    /** Pre-pass histogram bin width and the window a peak is refined over / suppresses, in degrees. */
    private static final double BIN_DEG = 0.5;
    private static final double REFINE_DEG = 1.0;
    private static final double SUPPRESS_DEG = 2.0;

    /**
     * Weight of the neighbour's frame against the ring's own decoded edges, as an edge length: the
     * neighbour is off by about 1 deg (median), the direction of an edge of length L by about 57/L deg.
     */
    private static final double PRIOR_WEIGHT = 50;

    /** Bytes one header frame angle takes (16-bit fixed point over 90 deg). */
    private static final int ANGLE_BYTES = 2;

    private enum Source {
        AXIS, OWN, PREV_RING, NEAREST, NEAREST_RECT, TOP1, TOP4, TOP16, TOP4_SNAP, PREV_EDGE, NEAREST_REFINED;

        int topK() {
            return switch (this) {
                case TOP1 -> 1;
                case TOP4, TOP4_SNAP -> 4;
                case TOP16 -> 16;
                default -> 0;
            };
        }

        /** Whether the encoder writes a per-ring frame index. */
        boolean signalled() {
            return this == TOP1 || this == TOP4 || this == TOP16;
        }
    }

    private static final Source[] SOURCES = Source.values();

    /** Ring-size columns. {@code OTHER} holds odd rings and rings under 4 vertices. */
    private static final String[] BUCKETS = {"4", "6", "8", ">8even", "other", "all"};
    private static final int ALL = BUCKETS.length - 1;

    private record Ring(int start, int end, double theta, boolean rect, double cx, double cy) {
        int n() {
            return end - start;
        }

        boolean even() {
            return n() >= 4 && n() % 2 == 0;
        }

        int bucket() {
            final var n = n();
            if (n == 4) {
                return 0;
            }
            if (n == 6) {
                return 1;
            }
            if (n == 8) {
                return 2;
            }
            return n > 8 && n % 2 == 0 ? 3 : 4;
        }
    }

    /** Running sums for one source, ungated. */
    private static final class Stats {
        final long[] bits = new long[BUCKETS.length];
        final long[] edges = new long[BUCKETS.length];
        final long[] oneZero = new long[BUCKETS.length];
        long signalBits;
        final List<Double> angleErrAll = new ArrayList<>();
        final List<Double> angleErrRect = new ArrayList<>();
    }

    public static void run(Path inDir, String layer) throws IOException {
        final var manifest = ExtractionReader.readManifest(inDir);

        final var stats = new Stats[SOURCES.length];
        for (var s = 0; s < SOURCES.length; s++) {
            stats[s] = new Stats();
        }
        // [source][0 = every ring, 1 = gated to even rings]
        final var varintBytes = new long[SOURCES.length][2];
        final var gzipBytes = new long[SOURCES.length][2];
        var buffers = 0;
        var ringCount = 0L;
        var rectCount = 0L;

        for (final var entry : manifest) {
            if (!entry.layerName().equals(layer)) {
                continue;
            }
            final var ctx = ExtractionReader.readContext(inDir, entry);
            if (ctx.vertices().isEmpty() || !isPurePolygon(ctx.geometryTypes())) {
                continue;
            }
            buffers++;
            final var rings = rings(ctx);
            ringCount += rings.size();
            rectCount += rings.stream().filter(Ring::rect).count();
            measureBuffer(ctx.vertices(), rings, stats, varintBytes, gzipBytes);
        }

        report(layer, buffers, ringCount, rectCount, stats, varintBytes, gzipBytes);
    }

    // ---------------------------------------------------------------------------
    // Per buffer
    // ---------------------------------------------------------------------------

    private static void measureBuffer(
            List<Vertex> v, List<Ring> rings, Stats[] stats, long[][] varintBytes, long[][] gzipBytes) {
        final var top16 = peaks(rings, 16);
        final var top4 = Arrays.copyOf(top16, Math.min(4, top16.length));
        final var top1 = Arrays.copyOf(top16, Math.min(1, top16.length));

        final var main = new ByteArrayOutputStream[SOURCES.length][2];
        final var side = new ByteArrayOutputStream[SOURCES.length][2];
        for (var s = 0; s < SOURCES.length; s++) {
            for (var g = 0; g < 2; g++) {
                main[s][g] = new ByteArrayOutputStream();
                side[s][g] = new ByteArrayOutputStream();
                final var header = switch (SOURCES[s]) {
                    case TOP1 -> top1;
                    case TOP4, TOP4_SNAP -> top4;
                    case TOP16 -> top16;
                    default -> new double[0];
                };
                for (final var angle : header) {
                    writeAngle(main[s][g], angle);
                }
            }
        }

        final var all = new Grid(rings);
        final var rect = new Grid(rings);
        var cursor = new Vertex(0, 0);
        for (var i = 0; i < rings.size(); i++) {
            final var ring = rings.get(i);
            final var first = v.get(ring.start());
            final var nearest = all.nearest(first.x(), first.y());
            final var nearestRect = rect.nearest(first.x(), first.y());
            final var nearestRectTheta = nearestRect < 0 ? 0 : rings.get(nearestRect).theta();

            for (var s = 0; s < SOURCES.length; s++) {
                final var source = SOURCES[s];
                var index = -1;
                final double angle = switch (source) {
                    case AXIS -> 0;
                    case OWN -> ring.theta();
                    case PREV_RING -> i == 0 ? 0 : rings.get(i - 1).theta();
                    case NEAREST, NEAREST_REFINED -> nearest < 0 ? 0 : rings.get(nearest).theta();
                    case NEAREST_RECT -> nearestRectTheta;
                    case TOP4_SNAP -> snap(nearestRectTheta, top4);
                    case PREV_EDGE -> Double.NaN;
                    case TOP1, TOP4, TOP16 -> {
                        final var peaks = source == Source.TOP1 ? top1 : source == Source.TOP4 ? top4 : top16;
                        if (peaks.length == 0) {
                            yield 0;
                        }
                        index = 0;
                        var best = Long.MAX_VALUE;
                        for (var p = 0; p < peaks.length; p++) {
                            final var cost = ringBits(v, ring, peaks[p]);
                            if (cost < best) {
                                best = cost;
                                index = p;
                            }
                        }
                        yield peaks[index];
                    }
                };

                for (var g = 0; g < 2; g++) {
                    final var gatedOff = g == 1 && !ring.even();
                    final var effective = gatedOff ? 0 : angle;
                    final var refine = source == Source.NEAREST_REFINED && !gatedOff;
                    encodeRing(v, ring, cursor, effective, refine, main[s][g], g == 0 ? stats[s] : null);
                    if (source.signalled() && index >= 0 && !gatedOff) {
                        side[s][g].write(index);
                    }
                }
                if (source.signalled() && index >= 0) {
                    stats[s].signalBits += ceilLog2(source.topK());
                }
                if (!Double.isNaN(angle)) {
                    final var err = angleDiff(angle, ring.theta());
                    stats[s].angleErrAll.add(err);
                    if (ring.rect()) {
                        stats[s].angleErrRect.add(err);
                    }
                }
            }

            all.add(i);
            if (ring.rect()) {
                rect.add(i);
            }
            cursor = v.get(ring.end() - 1);
        }

        for (var s = 0; s < SOURCES.length; s++) {
            for (var g = 0; g < 2; g++) {
                final var bytes = new ByteArrayOutputStream();
                bytes.writeBytes(main[s][g].toByteArray());
                bytes.writeBytes(side[s][g].toByteArray());
                final var data = bytes.toByteArray();
                varintBytes[s][g] += data.length;
                gzipBytes[s][g] += Compressor.GZIP.size(data);
            }
        }
    }

    /**
     * Writes one ring in {@code QUAD_CORNER}'s layout. {@code angle} is the frame every coded edge is
     * rotated into, or NaN for the previous edge's frame ({@code PREV_EDGE}); with {@code refine} it
     * is only the prior, updated by each decoded edge.
     */
    private static void encodeRing(
            List<Vertex> v,
            Ring ring,
            Vertex cursor,
            double angle,
            boolean refine,
            ByteArrayOutputStream out,
            Stats stats) {
        final var start = ring.start();
        final var n = ring.n();
        final var p0 = v.get(start);
        writeVarint(out, p0.x() - cursor.x());
        writeVarint(out, p0.y() - cursor.y());

        var sumCos = PRIOR_WEIGHT * Math.cos(Math.toRadians(4 * angle));
        var sumSin = PRIOR_WEIGHT * Math.sin(Math.toRadians(4 * angle));
        final var coded = n == 4 ? 2 : n - 1;
        for (var j = 1; j <= coded; j++) {
            final var a = v.get(start + j - 1);
            final var b = v.get(start + j);
            final double frame;
            if (Double.isNaN(angle)) {
                frame = j == 1 ? 0 : edgeAngle(v.get(start + j - 2), a);
            } else if (refine) {
                frame = quantize(fold(Math.toDegrees(Math.atan2(sumSin, sumCos)) / 4));
            } else {
                frame = angle;
            }
            final var dx = b.x() - a.x();
            final var dy = b.y() - a.y();
            if (refine && (dx != 0 || dy != 0)) {
                final var len = Math.hypot(dx, dy);
                final var phi = 4 * Math.atan2(dy, dx);
                sumCos += len * Math.cos(phi);
                sumSin += len * Math.sin(phi);
            }
            final var r = rotate(dx, dy, frame);
            writeVarint(out, r[0]);
            writeVarint(out, r[1]);
            if (stats != null) {
                final var bits = bits(r[0]) + bits(r[1]);
                final var zero = r[0] == 0 || r[1] == 0 ? 1 : 0;
                for (final var bucket : new int[]{ring.bucket(), ALL}) {
                    stats.bits[bucket] += bits;
                    stats.edges[bucket]++;
                    stats.oneZero[bucket] += zero;
                }
            }
        }
        if (n == 4) {
            final var p1 = v.get(start + 1);
            final var p2 = v.get(start + 2);
            final var p3 = v.get(start + 3);
            writeVarint(out, p3.x() - (p0.x() + p2.x() - p1.x()));
            writeVarint(out, p3.y() - (p0.y() + p2.y() - p1.y()));
        }
    }

    /** Zigzag bits of a ring's coded edges under one frame, for the encoder's per-ring frame choice. */
    private static long ringBits(List<Vertex> v, Ring ring, double angle) {
        final var coded = ring.n() == 4 ? 2 : ring.n() - 1;
        var sum = 0L;
        for (var j = 1; j <= coded; j++) {
            final var a = v.get(ring.start() + j - 1);
            final var b = v.get(ring.start() + j);
            final var r = rotate(b.x() - a.x(), b.y() - a.y(), angle);
            sum += bits(r[0]) + bits(r[1]);
        }
        return sum;
    }

    // ---------------------------------------------------------------------------
    // Integer rotation
    // ---------------------------------------------------------------------------

    /**
     * Rotates {@code (x, y)} by {@code -frameDeg} with three lifting shears, so an edge along the frame
     * lands on the x axis. Exactly invertible on integers; checked on every call.
     */
    static long[] rotate(long x, long y, double frameDeg) {
        if (frameDeg == 0) {
            return new long[]{x, y};
        }
        final var alpha = Math.toRadians(-frameDeg);
        final var t = Math.tan(alpha / 2);
        final var s = Math.sin(alpha);
        final var x1 = x - Math.round(t * y);
        final var y1 = y + Math.round(s * x1);
        final var x2 = x1 - Math.round(t * y1);

        final var ix1 = x2 + Math.round(t * y1);
        final var iy = y1 - Math.round(s * ix1);
        final var ix = ix1 + Math.round(t * iy);
        if (ix != x || iy != y) {
            throw new IllegalStateException("lifting rotation not invertible at " + x + "," + y);
        }
        return new long[]{x2, y1};
    }

    // ---------------------------------------------------------------------------
    // Ring frames and the pre-pass
    // ---------------------------------------------------------------------------

    private static List<Ring> rings(BufferContext ctx) {
        final var v = ctx.vertices();
        final var starts = VertexRuns.of(ctx, v.size());
        final var rings = new ArrayList<Ring>(starts.length);
        for (var s = 0; s < starts.length; s++) {
            final var start = starts[s];
            final var end = s + 1 < starts.length ? starts[s + 1] : v.size();
            if (end <= start) {
                continue;
            }
            var cx = 0.0;
            var cy = 0.0;
            for (var k = start; k < end; k++) {
                cx += v.get(k).x();
                cy += v.get(k).y();
            }
            final var n = end - start;
            rings.add(new Ring(start, end, frame(v, start, end), isRightAngled(v, start, end), cx / n, cy / n));
        }
        return rings;
    }

    /**
     * Length-weighted mean edge direction modulo 90 deg (directions quadrupled so walls at right angles
     * vote together), folded into [-45, 45).
     */
    private static double frame(List<Vertex> v, int start, int end) {
        final var n = end - start;
        var c = 0.0;
        var s = 0.0;
        for (var k = 0; k < n; k++) {
            final var p = v.get(start + k);
            final var q = v.get(start + (k + 1) % n);
            final var dx = q.x() - p.x();
            final var dy = q.y() - p.y();
            final var len = Math.hypot(dx, dy);
            if (len == 0) {
                continue;
            }
            final var phi = 4 * Math.atan2(dy, dx);
            c += len * Math.cos(phi);
            s += len * Math.sin(phi);
        }
        return c == 0 && s == 0 ? 0 : fold(Math.toDegrees(Math.atan2(s, c) / 4));
    }

    /** Every turn within 80-100 deg, the {@code BuildingShapeCli} definition. */
    private static boolean isRightAngled(List<Vertex> v, int start, int end) {
        final var n = end - start;
        if (n < 4) {
            return false;
        }
        for (var k = 0; k < n; k++) {
            final var p0 = v.get(start + k);
            final var p1 = v.get(start + (k + 1) % n);
            final var p2 = v.get(start + (k + 2) % n);
            final double e1x = p1.x() - p0.x();
            final double e1y = p1.y() - p0.y();
            final double e2x = p2.x() - p1.x();
            final double e2y = p2.y() - p1.y();
            final var deg = Math.toDegrees(Math.abs(Math.atan2(e1x * e2y - e1y * e2x, e1x * e2x + e1y * e2y)));
            if (deg < 80 || deg > 100) {
                return false;
            }
        }
        return true;
    }

    /**
     * Up to {@code k} dominant frames of a buffer: a histogram of right-angled ring frames (all rings
     * if there are none), each peak refined to the circular mean of the frames near it, quantized to
     * the header's 16 bits, and its neighbourhood suppressed before the next.
     */
    private static double[] peaks(List<Ring> rings, int k) {
        final var pool = rings.stream().filter(Ring::rect).toList();
        final var source = pool.isEmpty() ? rings : pool;
        final var bins = (int) Math.round(90 / BIN_DEG);
        final var hist = new double[bins];
        for (final var ring : source) {
            hist[bin(ring.theta(), bins)]++;
        }
        final var peaks = new ArrayList<Double>();
        while (peaks.size() < k) {
            var best = -1;
            for (var b = 0; b < bins; b++) {
                if (hist[b] > 0 && (best < 0 || hist[b] > hist[best])) {
                    best = b;
                }
            }
            if (best < 0) {
                break;
            }
            final var centre = -45 + (best + 0.5) * BIN_DEG;
            var c = 0.0;
            var s = 0.0;
            for (final var ring : source) {
                if (angleDiff(ring.theta(), centre) <= REFINE_DEG) {
                    c += Math.cos(Math.toRadians(4 * ring.theta()));
                    s += Math.sin(Math.toRadians(4 * ring.theta()));
                }
            }
            final var refined = c == 0 && s == 0 ? centre : fold(Math.toDegrees(Math.atan2(s, c)) / 4);
            peaks.add(quantize(refined));
            for (var b = 0; b < bins; b++) {
                if (angleDiff(-45 + (b + 0.5) * BIN_DEG, centre) <= SUPPRESS_DEG) {
                    hist[b] = 0;
                }
            }
        }
        return peaks.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private static double snap(double angle, double[] peaks) {
        var best = angle;
        var bestErr = SUPPRESS_DEG;
        for (final var p : peaks) {
            final var err = angleDiff(angle, p);
            if (err <= bestErr) {
                bestErr = err;
                best = p;
            }
        }
        return best;
    }

    private static double edgeAngle(Vertex a, Vertex b) {
        final var dx = b.x() - a.x();
        final var dy = b.y() - a.y();
        return dx == 0 && dy == 0 ? 0 : fold(Math.toDegrees(Math.atan2(dy, dx)));
    }

    private static int bin(double theta, int bins) {
        return Math.min(bins - 1, Math.max(0, (int) Math.floor((theta + 45) / BIN_DEG)));
    }

    private static int quantizeBits(double theta) {
        return (int) Math.round((theta + 45) / 90 * 65536) & 0xFFFF;
    }

    private static double quantize(double theta) {
        return quantizeBits(theta) * 90.0 / 65536 - 45;
    }

    private static void writeAngle(ByteArrayOutputStream out, double theta) {
        final var q = quantizeBits(theta);
        out.write(q & 0xFF);
        out.write(q >>> 8);
    }

    // ---------------------------------------------------------------------------
    // Nearest decoded ring
    // ---------------------------------------------------------------------------

    /** Uniform grid over ring centroids; only rings added so far (already decoded) are candidates. */
    private static final class Grid {
        private final List<Ring> rings;
        private final Map<Long, List<Integer>> cells = new HashMap<>();
        private int size;

        Grid(List<Ring> rings) {
            this.rings = rings;
        }

        void add(int index) {
            final var r = rings.get(index);
            cells.computeIfAbsent(key(cell(r.cx()), cell(r.cy())), k -> new ArrayList<>()).add(index);
            size++;
        }

        int nearest(double x, double y) {
            if (size == 0) {
                return -1;
            }
            final var cx = cell(x);
            final var cy = cell(y);
            var best = -1;
            var bestDist = Double.MAX_VALUE;
            for (var radius = 0; radius <= MAX_RADIUS; radius++) {
                for (var dx = -radius; dx <= radius; dx++) {
                    final var edge = Math.abs(dx) == radius;
                    for (var dy = -radius; dy <= radius; dy += edge ? 1 : Math.max(1, 2 * radius)) {
                        final var list = cells.get(key(cx + dx, cy + dy));
                        if (list == null) {
                            continue;
                        }
                        for (final var index : list) {
                            final var r = rings.get(index);
                            final var d = Math.hypot(r.cx() - x, r.cy() - y);
                            if (d < bestDist) {
                                bestDist = d;
                                best = index;
                            }
                        }
                    }
                }
                // Anything not yet visited lies at least radius cells away.
                if (best >= 0 && bestDist <= (double) radius * CELL) {
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
    // Helpers
    // ---------------------------------------------------------------------------

    private static int ceilLog2(int k) {
        return k <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(k - 1);
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

    // ---------------------------------------------------------------------------
    // Reporting
    // ---------------------------------------------------------------------------

    private static void report(
            String layer,
            int buffers,
            long rings,
            long rect,
            Stats[] stats,
            long[][] varintBytes,
            long[][] gzipBytes) {
        if (rings == 0) {
            System.out.printf("No pure-polygon rings in layer '%s'.%n", layer);
            return;
        }
        System.out.printf(
                "OrientationPriorCli (Stage 0 probe) on layer '%s': %,d buffers, %,d rings, %,d fully right-angled (%.1f%%).%n%n",
                layer, buffers, rings, rect, 100.0 * rect / rings);

        System.out.println("--- Frame error against the ring's own frame, degrees mod 90 ---");
        System.out.printf("%-14s %9s %9s %9s %9s %9s   %9s %9s %9s%n", "source",
                "rect<=0.5", "rect<=1", "rect<=2", "rect<=5", "rect med", "all<=1", "all<=5", "all med");
        for (var s = 0; s < SOURCES.length; s++) {
            final var r = sorted(stats[s].angleErrRect);
            final var a = sorted(stats[s].angleErrAll);
            if (a.length == 0) {
                System.out.printf("%-14s %9s%n", SOURCES[s], "per edge");
                continue;
            }
            System.out.printf("%-14s %8.1f%% %8.1f%% %8.1f%% %8.1f%% %9.2f   %8.1f%% %8.1f%% %9.2f%n", SOURCES[s],
                    within(r, 0.5), within(r, 1), within(r, 2), within(r, 5), median(r),
                    within(a, 1), within(a, 5), median(a));
        }
        System.out.println();

        System.out.println("--- Mean zigzag bits per coded edge (AXIS = plain delta) ---");
        bucketHeader();
        for (var s = 0; s < SOURCES.length; s++) {
            final var st = stats[s];
            final var line = new StringBuilder(String.format("%-14s", SOURCES[s]));
            for (var b = 0; b < BUCKETS.length; b++) {
                line.append(String.format("%10.2f", st.edges[b] == 0 ? 0 : (double) st.bits[b] / st.edges[b]));
            }
            System.out.println(line);
        }
        System.out.println();

        System.out.println("--- Coded edges with a zero component ---");
        bucketHeader();
        for (var s = 0; s < SOURCES.length; s++) {
            final var st = stats[s];
            final var line = new StringBuilder(String.format("%-14s", SOURCES[s]));
            for (var b = 0; b < BUCKETS.length; b++) {
                line.append(String.format("%9.1f%%", st.edges[b] == 0 ? 0 : 100.0 * st.oneZero[b] / st.edges[b]));
            }
            System.out.println(line);
        }
        System.out.println();

        final var refBits = stats[0].bits[ALL];
        System.out.println("--- Totals: edge bits (+ index bits), and the whole QUAD_CORNER stream per buffer ---");
        System.out.printf("%-14s %14s %9s %12s %9s %12s %9s %12s %9s %12s %9s%n", "source",
                "edge+idx bits", "vs AXIS", "varint", "vs AXIS", "gzip", "vs AXIS",
                "varint even", "vs AXIS", "gzip even", "vs AXIS");
        for (var s = 0; s < SOURCES.length; s++) {
            final var totalBits = stats[s].bits[ALL] + stats[s].signalBits;
            System.out.printf("%-14s %,14d %+8.2f%% %,12d %+8.2f%% %,12d %+8.2f%% %,12d %+8.2f%% %,12d %+8.2f%%%n",
                    SOURCES[s], totalBits, pctChange(totalBits, refBits),
                    varintBytes[s][0], pctChange(varintBytes[s][0], varintBytes[0][0]),
                    gzipBytes[s][0], pctChange(gzipBytes[s][0], gzipBytes[0][0]),
                    varintBytes[s][1], pctChange(varintBytes[s][1], varintBytes[0][1]),
                    gzipBytes[s][1], pctChange(gzipBytes[s][1], gzipBytes[0][1]));
        }
        System.out.println("(OWN is a ceiling: the decoder has no frame for it. 'even' = source applied to even rings of 4+ vertices only.)");
    }

    private static void bucketHeader() {
        final var line = new StringBuilder(String.format("%-14s", "source"));
        for (final var b : BUCKETS) {
            line.append(String.format("%10s", b));
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

    private static double median(double[] sorted) {
        return sorted.length == 0 ? 0 : sorted[sorted.length / 2];
    }

    private static double pctChange(long value, long ref) {
        return ref == 0 ? 0 : 100.0 * (value - ref) / ref;
    }
}
