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
 * Evaluation-protocol Stage 0 probe: re-run of the turn-scalar idea (see {@code
 * docs/results/failed/turn-scalar.md}). Winding fixes the turn direction — outer rings turn one
 * way, inner rings the other — and corners are mostly 90°, so the next edge is predicted as {@code
 * rot90(prevEdge)} in direction and only its length is stored as one scalar. Measures the residual
 * <b>distribution</b> only; encodes nothing and is not an {@code Encoding}.
 *
 * <p>Per turn vertex ({@code i - start >= 2}) with {@code e = v[i-1]-v[i-2]}, {@code w =
 * v[i]-v[i-1]} and {@code u = rot90(e)} (CCW for outer rings, CW for inner rings):
 *
 * <ul>
 *   <li><b>B</b> — previous-vertex delta {@code w} (2 values).
 *   <li><b>R2</b> — {@code Rot90Fixed}-style residual {@code w - u} (2 values).
 *   <li><b>S</b> — the decodable scalar form: {@code along = round(dot(w,u)/|u|)}, plus the 2D
 *       correction {@code corr = w - round(along*u/|u|)}, zero whenever one scalar suffices (3
 *       values).
 * </ul>
 *
 * <p>Outer/inner comes from {@code numParts} (first ring of each polygon is outer); buffers whose
 * topology does not allow that mapping are skipped rather than guessed at. Because S stores three
 * values per vertex against two, compare <b>bits per vertex</b>, not bits per value.
 *
 * <p>Usage: {@code ./gradlew turnScalar --args="../data/extractedBaseline --layer building"}
 */
public final class TurnScalarCli {
    private static final int POLYGON = 2;
    private static final int MULTIPOLYGON = 5;
    private static final String[] CLASS_NAMES = {"n=4", "even n>4", "odd n"};

    private TurnScalarCli() {
    }

    private static final class Stats {
        long rings;
        long windingHits;
        long vertices;
        long degenerate;
        long exact90;
        long corrZero;
        final List<Integer> bitsB = new ArrayList<>();
        final List<Integer> bitsR2 = new ArrayList<>();
        final List<Integer> bitsAlong = new ArrayList<>();
        final List<Integer> bitsCorr = new ArrayList<>();
        long vertexBitsB;
        long vertexBitsR2;
        long vertexBitsS;
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

        final var stats = new Stats[]{new Stats(), new Stats(), new Stats()};
        var buffers = 0;
        var skippedBuffers = 0;

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
            final var outer = outerRings(ctx.geometryTypes(), ctx.numParts(), starts.length);
            if (outer == null) {
                skippedBuffers++;
                continue;
            }
            buffers++;
            for (var r = 0; r < starts.length; r++) {
                final var start = starts[r];
                final var end = r + 1 < starts.length ? starts[r + 1] : vertices.size();
                final var n = end - start;
                final var s = stats[n == 4 ? 0 : n % 2 == 0 ? 1 : 2];
                s.rings++;

                // Shoelace sign: rot90ccw is the correct turn for a positive-area ring.
                var area2 = 0L;
                for (var i = start; i < end; i++) {
                    final var a = vertices.get(i);
                    final var b = vertices.get(i + 1 < end ? i + 1 : start);
                    area2 += (long) a.x() * b.y() - (long) b.x() * a.y();
                }
                final var ccw = outer[r];
                if (ccw ? area2 > 0 : area2 < 0) {
                    s.windingHits++;
                }

                for (var i = start + 2; i < end; i++) {
                    final var p2 = vertices.get(i - 2);
                    final var p1 = vertices.get(i - 1);
                    final var p = vertices.get(i);
                    final var ex = p1.x() - p2.x();
                    final var ey = p1.y() - p2.y();
                    final var wx = p.x() - p1.x();
                    final var wy = p.y() - p1.y();
                    if (ex == 0 && ey == 0) {
                        s.degenerate++;
                        continue;
                    }
                    s.vertices++;
                    // rot90ccw(e) = (-ey, ex); rot90cw(e) = (ey, -ex).
                    final var ux = ccw ? -ey : ey;
                    final var uy = ccw ? ex : -ex;
                    if ((long) ux * wy - (long) uy * wx == 0 && (long) ux * wx + (long) uy * wy >= 0) {
                        s.exact90++;
                    }

                    final var bx = bits(EncodingUtils.encodeZigZag(wx));
                    final var by = bits(EncodingUtils.encodeZigZag(wy));
                    s.bitsB.add(bx);
                    s.bitsB.add(by);
                    s.vertexBitsB += bx + by;

                    final var rx = bits(EncodingUtils.encodeZigZag(wx - ux));
                    final var ry = bits(EncodingUtils.encodeZigZag(wy - uy));
                    s.bitsR2.add(rx);
                    s.bitsR2.add(ry);
                    s.vertexBitsR2 += rx + ry;

                    final var len = Math.hypot(ux, uy);
                    final var along = Math.round(((double) ux * wx + (double) uy * wy) / len);
                    final var cx = (int) (wx - Math.round(along * ux / len));
                    final var cy = (int) (wy - Math.round(along * uy / len));
                    if (cx == 0 && cy == 0) {
                        s.corrZero++;
                    }
                    final var ba = bits(EncodingUtils.encodeZigZag((int) along));
                    final var bcx = bits(EncodingUtils.encodeZigZag(cx));
                    final var bcy = bits(EncodingUtils.encodeZigZag(cy));
                    s.bitsAlong.add(ba);
                    s.bitsCorr.add(bcx);
                    s.bitsCorr.add(bcy);
                    s.vertexBitsS += ba + bcx + bcy;
                }
            }
        }

        report(layer, buffers, skippedBuffers, stats);
    }

    /**
     * Per ring, whether it is an outer ring (first ring of its polygon) — or {@code null} if the
     * buffer is not polygon-only or {@code numParts} does not cover the rings exactly.
     */
    private static boolean[] outerRings(List<Integer> geometryTypes, List<Integer> numParts, int ringCount) {
        for (final var type : geometryTypes) {
            if (type != POLYGON && type != MULTIPOLYGON) {
                return null;
            }
        }
        final var outer = new boolean[ringCount];
        var ring = 0;
        for (final var rings : numParts) {
            if (rings <= 0 || ring + rings > ringCount) {
                return null;
            }
            outer[ring] = true;
            ring += rings;
        }
        return ring == ringCount ? outer : null;
    }

    private static int bits(int zigzag) {
        return 32 - Integer.numberOfLeadingZeros(zigzag);
    }

    private static void report(String layer, int buffers, int skipped, Stats[] stats) {
        System.out.printf(
                "TurnScalarCli (Stage 0 probe) on layer '%s': %,d buffers (%,d skipped, not polygon-only).%n",
                layer, buffers, skipped);
        for (var c = 0; c < stats.length; c++) {
            final var s = stats[c];
            System.out.printf("%n=== rings %s: %,d rings, %,d turn vertices (%,d degenerate skipped) ===%n",
                    CLASS_NAMES[c], s.rings, s.vertices, s.degenerate);
            if (s.vertices == 0) {
                continue;
            }
            System.out.printf("winding rule holds (outer CCW / inner CW)  %6.2f%%%n", pct(s.windingHits, s.rings));
            System.out.printf("turn exactly 90 deg                         %6.2f%%%n", pct(s.exact90, s.vertices));
            System.out.printf("one scalar suffices (corr == 0)             %6.2f%%%n", pct(s.corrZero, s.vertices));
            System.out.printf("%n%-24s %8s %8s %8s %8s%n", "per value", "mean", "median", "p90", "p99");
            printRow("B  delta (2)", s.bitsB);
            printRow("R2 rot90 residual (2)", s.bitsR2);
            printRow("S  along (1)", s.bitsAlong);
            printRow("S  corr (2)", s.bitsCorr);
            System.out.printf("%nbits per vertex:  B %.2f   R2 %.2f   S %.2f%n",
                    (double) s.vertexBitsB / s.vertices,
                    (double) s.vertexBitsR2 / s.vertices,
                    (double) s.vertexBitsS / s.vertices);
        }
        System.out.println();
        System.out.println("Decision rule: S bits/vertex must be clearly below both B and R2, else the idea stays dead.");
    }

    private static double pct(long part, long whole) {
        return whole == 0 ? 0.0 : 100.0 * part / whole;
    }

    private static void printRow(String label, List<Integer> bits) {
        final var sorted = new ArrayList<>(bits);
        Collections.sort(sorted);
        var sum = 0L;
        for (final var b : sorted) {
            sum += b;
        }
        System.out.printf(
                "%-24s %8.2f %8d %8d %8d%n",
                label,
                (double) sum / sorted.size(),
                percentile(sorted, 0.50),
                percentile(sorted, 0.90),
                percentile(sorted, 0.99));
    }

    private static int percentile(List<Integer> sorted, double p) {
        final var idx = Math.min(sorted.size() - 1, (int) Math.ceil(p * sorted.size()) - 1);
        return sorted.get(Math.max(0, idx));
    }
}
