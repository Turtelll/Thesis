package org.maplibre.mlt.research.cli;

import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.IntCodec;
import org.maplibre.mlt.research.encoding.PartBoundaries;
import org.maplibre.mlt.research.encoding.StreamMetadata;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.VertexTransform;
import org.maplibre.mlt.research.extraction.ExtractionReader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Probe (encodes nothing): can the turn direction of a 90°-turn predictor be signalled relative to
 * the ring's winding, and what does each way of packing that signal cost?
 *
 * <p>Winding is not signalled: the MVT spec fixes exterior rings to positive and holes to negative
 * surveyor's-formula area in tile coordinates, and which ring is exterior (first ring of each
 * polygon) is in the topology streams. A turn with the winding is convex; against it, reflex. The
 * flag per predicted vertex (3rd+ of a polygon ring, as in {@code TURN90_BITMAP}) is "the turn at
 * {@code v[i-1]} is reflex", from the geometric cross product, not an encoder choice; a collinear
 * turn counts as convex. The prediction is {@code v[i-1] + d} rotated 90° in the flag's direction.
 *
 * <p>Reports: (1) winding compliance per ring role, (2) reflex probability given the previous two
 * turns in the ring — the "after a reflex, the next two turns are convex" rule — and the order-0
 * entropy of the flags the rule does not force, (3) the gap distribution between reflex corners,
 * all over all polygon rings and over rectilinear rings only (every corner within {@value
 * #RECTILINEAR_TOLERANCE_DEG}° of 90°), and (4) real FastPFOR/Varint bytes of three packings on the
 * same residuals: separate bitmap, flag in the x residual's low bit, and reflex gaps interleaved
 * into the residual stream.
 *
 * <p>Usage: {@code ./gradlew reflexSignal --args="../data/extractedBaseline --layer building"};
 * {@code --layer all} measures the whole corpus.
 */
public final class ReflexSignalCli {
    private ReflexSignalCli() {
    }

    private static final double RECTILINEAR_TOLERANCE_DEG = 10.0;
    private static final double MAX_ABS_COS = Math.cos(Math.toRadians(90.0 - RECTILINEAR_TOLERANCE_DEG));

    private static final IntCodec[] CODECS = {IntCodec.FASTPFOR, IntCodec.VARINT};

    private static final int LINE = 0;
    private static final int OUTER = 1;
    private static final int HOLE = 2;

    private static final int GAP_BUCKETS = 6; // 0..4, 5+

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
            throw new IllegalArgumentException("Usage: <extractionDir> [--layer <name>|all]");
        }
        final var inDir = Path.of(inArg);
        final var delta = new VertexTransform.ZigZagDelta();

        // [role][area sign: +, -, 0]
        final var winding = new long[3][3];
        final var all = new TurnStats();
        final var rectilinear = new TurnStats();
        var rectilinearRings = 0L;
        var polygonRings = 0L;
        var buffers = 0;
        var skipped = 0;

        final var baseBytes = new long[CODECS.length];
        final var bitmapBytes = new long[CODECS.length];
        final var lowBitBytes = new long[CODECS.length];
        final var gapBytes = new long[CODECS.length];
        final var bits = new long[4]; // baseline, residuals+bitmap, low bit, gaps
        final var values = new long[4];

        for (final var entry : ExtractionReader.readManifest(inDir)) {
            if (!layer.equals("all") && !entry.layerName().equals(layer)) {
                continue;
            }
            final var ctx = ExtractionReader.readContext(inDir, entry);
            final var vertices = ctx.vertices();
            buffers++;
            final var base = delta.apply(ctx);

            final var starts = PartBoundaries.of(ctx, vertices.size());
            final var roles = roles(ctx, starts.length);
            final var residuals = base.clone();
            final var flags = new ArrayList<Boolean>();
            final var lowBit = base.clone();
            final var gapStream = new ArrayList<Integer>();

            if (roles == null) {
                skipped++;
                for (final var v : base) {
                    gapStream.add(v);
                }
            } else {
                var copied = 0; // vertices of base already appended to gapStream
                for (var r = 0; r < starts.length; r++) {
                    final var start = starts[r];
                    final var end = r + 1 < starts.length ? starts[r + 1] : vertices.size();
                    if (roles[r] == LINE || end - start < 3) {
                        continue;
                    }
                    polygonRings++;
                    final var n = end - start;
                    final var area = area2(vertices, start, end);
                    winding[roles[r]][area > 0 ? 0 : area < 0 ? 1 : 2]++;
                    final var expected = roles[r] == OUTER ? 1 : -1;

                    // Flags in decode order: the turn at v[i-1] for i = start+2 .. end-1.
                    final var ringFlags = new boolean[n - 2];
                    for (var i = start + 2; i < end; i++) {
                        final var turn = Long.signum(cross(vertices.get(i - 2), vertices.get(i - 1), vertices.get(i)));
                        final var reflex = turn == -expected;
                        ringFlags[i - start - 2] = reflex;
                        flags.add(reflex);

                        final var prev = vertices.get(i - 1);
                        final var dx = prev.x() - vertices.get(i - 2).x();
                        final var dy = prev.y() - vertices.get(i - 2).y();
                        final var dir = reflex ? -expected : expected;
                        final var predX = prev.x() + (dir > 0 ? -dy : dy);
                        final var predY = prev.y() + (dir > 0 ? dx : -dx);
                        final var v = vertices.get(i);
                        residuals[2 * i] = EncodingUtils.encodeZigZag(v.x() - predX);
                        residuals[2 * i + 1] = EncodingUtils.encodeZigZag(v.y() - predY);
                        lowBit[2 * i] = residuals[2 * i] * 2 + (reflex ? 1 : 0);
                        lowBit[2 * i + 1] = residuals[2 * i + 1];
                    }
                    all.ring(ringFlags);
                    if (rectilinear(vertices, start, end)) {
                        rectilinearRings++;
                        rectilinear.ring(ringFlags);
                    }

                    // Gap stream: plain residuals up to the ring's first predicted vertex, then a gap
                    // before it, residuals, and a gap after each reflex while flags remain.
                    for (; copied < start + 2; copied++) {
                        gapStream.add(residuals[2 * copied]);
                        gapStream.add(residuals[2 * copied + 1]);
                    }
                    gapStream.add(gapFrom(ringFlags, 0));
                    for (var j = 0; j < ringFlags.length; j++) {
                        final var i = start + 2 + j;
                        gapStream.add(residuals[2 * i]);
                        gapStream.add(residuals[2 * i + 1]);
                        if (ringFlags[j] && j + 1 < ringFlags.length) {
                            gapStream.add(gapFrom(ringFlags, j + 1));
                        }
                    }
                    copied = end;
                }
                for (; copied < vertices.size(); copied++) {
                    gapStream.add(residuals[2 * copied]);
                    gapStream.add(residuals[2 * copied + 1]);
                }
            }

            final var gaps = gapStream.stream().mapToInt(Integer::intValue).toArray();
            final var k = flags.size();
            final var bitmap = (k + 7) / 8;
            for (var c = 0; c < CODECS.length; c++) {
                baseBytes[c] += sized(CODECS[c], base);
                bitmapBytes[c] += sized(CODECS[c], residuals) + bitmap + StreamMetadata.bytes(k, bitmap);
                lowBitBytes[c] += sized(CODECS[c], lowBit);
                gapBytes[c] += sized(CODECS[c], gaps);
            }
            bits[0] += bitSum(base);
            bits[1] += bitSum(residuals) + k;
            bits[2] += bitSum(lowBit);
            bits[3] += bitSum(gaps);
            values[0] += base.length;
            values[1] += residuals.length;
            values[2] += lowBit.length;
            values[3] += gaps.length;
        }

        System.out.printf("layer %s, %,d buffers (%,d without usable topology, plain delta), %,d polygon rings%n%n",
                layer, buffers, skipped, polygonRings);

        System.out.printf("--- winding vs MVT spec (outer +, hole -) ---%n");
        System.out.printf("%-6s %12s %12s %10s %9s%n", "role", "area +", "area -", "area 0", "per spec");
        for (final var role : new int[]{OUTER, HOLE}) {
            final var w = winding[role];
            final var ok = role == OUTER ? w[0] : w[1];
            System.out.printf("%-6s %,12d %,12d %,10d %8.2f%%%n",
                    role == OUTER ? "outer" : "hole", w[0], w[1], w[2], pctOf(ok, w[0] + w[1] + w[2]));
        }

        all.report("all polygon rings", polygonRings);
        rectilinear.report(String.format("rectilinear rings (every corner within %.0f deg of 90 deg)",
                RECTILINEAR_TOLERANCE_DEG), rectilinearRings);

        System.out.printf("%n--- packings (same residuals; bytes incl. stream headers) ---%n");
        final String[] names = {"DELTA_ZIGZAG", "residuals + bitmap", "flag in x low bit", "gaps interleaved"};
        final long[][] bytes = {baseBytes, bitmapBytes, lowBitBytes, gapBytes};
        System.out.printf("%-20s %12s %10s %14s %9s %14s %9s%n",
                "packing", "values", "mean bits", "FASTPFOR", "vs base", "VARINT", "vs base");
        for (var p = 0; p < names.length; p++) {
            System.out.printf("%-20s %,12d %10.3f %,14d %9s %,14d %9s%n",
                    names[p], values[p], (double) bits[p] / values[p],
                    bytes[p][0], pct(bytes[p][0], baseBytes[0]), bytes[p][1], pct(bytes[p][1], baseBytes[1]));
        }
        System.out.printf("%nmean bits for residuals + bitmap counts 1 bit per flag; gaps are counted at their own%n"
                + "width. Bitmap bytes are raw-packed, not entropy coded.%n");
    }

    /**
     * Turn-flag statistics over rings, in decode order.
     */
    private static final class TurnStats {
        long flags;
        long reflex;
        // Context: 0 = previous turn reflex, 1 = turn two back reflex (previous not), 2 = neither
        // (both exist), 3 = fewer than two previous turns in the ring and none reflex.
        final long[] ctxN = new long[4];
        final long[] ctxReflex = new long[4];
        final long[] gapHist = new long[GAP_BUCKETS];
        long gapNone;

        void ring(boolean[] f) {
            for (var j = 0; j < f.length; j++) {
                flags++;
                if (f[j]) {
                    reflex++;
                }
                final int ctx;
                if (j >= 1 && f[j - 1]) {
                    ctx = 0;
                } else if (j >= 2 && f[j - 2]) {
                    ctx = 1;
                } else if (j >= 2) {
                    ctx = 2;
                } else {
                    ctx = 3;
                }
                ctxN[ctx]++;
                if (f[j]) {
                    ctxReflex[ctx]++;
                    final var next = nextReflex(f, j + 1);
                    if (next < 0) {
                        gapNone++;
                    } else {
                        gapHist[Math.min(next - j - 1, GAP_BUCKETS - 1)]++;
                    }
                }
            }
        }

        void report(String title, long rings) {
            System.out.printf("%n--- turns: %s (%,d rings, %,d flags) ---%n", title, rings, flags);
            System.out.printf("reflex share %.2f%%, order-0 entropy %.3f bit/flag%n",
                    pctOf(reflex, flags), entropy(reflex, flags));
            final String[] labels = {"prev reflex", "2-back reflex", "prev 2 convex", "<2 prev, convex"};
            System.out.printf("%-16s %12s %10s%n", "context", "flags", "P(reflex)");
            for (var c = 0; c < 4; c++) {
                System.out.printf("%-16s %,12d %9.2f%%%n", labels[c], ctxN[c], pctOf(ctxReflex[c], ctxN[c]));
            }
            final var forced = ctxN[0] + ctxN[1];
            final var violations = ctxReflex[0] + ctxReflex[1];
            final var unforced = ctxN[2] + ctxN[3];
            final var unforcedReflex = ctxReflex[2] + ctxReflex[3];
            System.out.printf("rule \"2 convex after reflex\": forces %,d flags (%.2f%%), violated by %,d (%.2f%% of forced)%n",
                    forced, pctOf(forced, flags), violations, pctOf(violations, forced));
            System.out.printf("unforced flags %,d, reflex share %.2f%%, entropy %.3f bit/flag -> %,.0f bits total"
                            + " (all flags order-0: %,.0f bits)%n",
                    unforced, pctOf(unforcedReflex, unforced), entropy(unforcedReflex, unforced),
                    entropy(unforcedReflex, unforced) * unforced, entropy(reflex, flags) * flags);
            System.out.printf("convex turns until next reflex: ");
            for (var g = 0; g < GAP_BUCKETS; g++) {
                System.out.printf("%s%s=%,d  ", g, g == GAP_BUCKETS - 1 ? "+" : "", gapHist[g]);
            }
            System.out.printf("none=%,d%n", gapNone);
        }
    }

    /**
     * Number of non-reflex flags from {@code from} to the next reflex; the remaining count if none.
     */
    private static int gapFrom(boolean[] f, int from) {
        final var next = nextReflex(f, from);
        return (next < 0 ? f.length : next) - from;
    }

    private static int nextReflex(boolean[] f, int from) {
        for (var j = from; j < f.length; j++) {
            if (f[j]) {
                return j;
            }
        }
        return -1;
    }

    /**
     * Per run: {@link #LINE}, {@link #OUTER} (first ring of a polygon) or {@link #HOLE}; {@code
     * null} if the topology streams do not line up with the runs. Same walk as {@code
     * PartBoundaries.lineRuns}.
     */
    private static int[] roles(BufferContext ctx, int runCount) {
        final var types = ctx.geometryTypes();
        final var polygonColumn = types.contains(2) || types.contains(5);
        final var roles = new int[runCount];
        var run = 0;
        var geometryCursor = 0;
        var partCursor = 0;
        for (final int type : types) {
            if (type == 0 || type == 3) {
                return null;
            }
            final List<Integer> ringCounts = new ArrayList<>();
            if (polygonColumn && (type == 2 || type == 5)) {
                final var polygons = type == 5 ? multiCount(ctx, geometryCursor++) : 1;
                if (polygons < 0) {
                    return null;
                }
                for (var p = 0; p < polygons; p++) {
                    if (partCursor >= ctx.numParts().size()) {
                        return null;
                    }
                    ringCounts.add(ctx.numParts().get(partCursor++));
                }
                for (final var rings : ringCounts) {
                    for (var i = 0; i < rings; i++) {
                        if (run >= runCount) {
                            return null;
                        }
                        roles[run++] = i == 0 ? OUTER : HOLE;
                    }
                }
            } else {
                final var lines = type == 4 ? multiCount(ctx, geometryCursor++) : 1;
                if (lines <= 0 || run + lines > runCount) {
                    return null;
                }
                for (var i = 0; i < lines; i++) {
                    roles[run++] = LINE;
                }
            }
        }
        return run == runCount ? roles : null;
    }

    private static int multiCount(BufferContext ctx, int cursor) {
        return cursor < ctx.numGeometries().size() ? ctx.numGeometries().get(cursor) : -1;
    }

    /**
     * Twice the surveyor's-formula area of the ring {@code [start, end)}, closing edge included.
     */
    private static long area2(List<Vertex> v, int start, int end) {
        var sum = 0L;
        for (var i = start; i < end; i++) {
            final var a = v.get(i);
            final var b = v.get(i + 1 < end ? i + 1 : start);
            sum += (long) a.x() * b.y() - (long) b.x() * a.y();
        }
        return sum;
    }

    private static long cross(Vertex a, Vertex b, Vertex c) {
        return (long) (b.x() - a.x()) * (c.y() - b.y()) - (long) (b.y() - a.y()) * (c.x() - b.x());
    }

    /**
     * Every cyclic corner of the ring within the tolerance of 90° (a collinear or zero-length edge
     * fails it).
     */
    private static boolean rectilinear(List<Vertex> v, int start, int end) {
        final var n = end - start;
        for (var j = 0; j < n; j++) {
            final var a = v.get(start + (j + n - 1) % n);
            final var b = v.get(start + j);
            final var c = v.get(start + (j + 1) % n);
            final double ex = b.x() - a.x();
            final double ey = b.y() - a.y();
            final double fx = c.x() - b.x();
            final double fy = c.y() - b.y();
            final var len = Math.hypot(ex, ey) * Math.hypot(fx, fy);
            if (len == 0 || Math.abs(ex * fx + ey * fy) / len > MAX_ABS_COS) {
                return false;
            }
        }
        return true;
    }

    private static long sized(IntCodec codec, int[] values) {
        final var payload = codec.encode(values).length;
        return payload + StreamMetadata.bytes(values.length, payload);
    }

    private static long bitSum(int[] values) {
        var sum = 0L;
        for (final var value : values) {
            sum += 32 - Integer.numberOfLeadingZeros(value);
        }
        return sum;
    }

    private static double entropy(long ones, long n) {
        if (n == 0 || ones == 0 || ones == n) {
            return 0.0;
        }
        final var p = (double) ones / n;
        return -(p * Math.log(p) + (1 - p) * Math.log(1 - p)) / Math.log(2);
    }

    private static double pctOf(long part, long whole) {
        return whole == 0 ? 0.0 : 100.0 * part / whole;
    }

    private static String pct(long value, long reference) {
        return reference == 0 ? "-" : String.format("%+.1f%%", 100.0 * (value - reference) / reference);
    }
}
