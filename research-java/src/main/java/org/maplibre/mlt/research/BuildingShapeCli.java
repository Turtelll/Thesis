package org.maplibre.mlt.research;

import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.PartBoundaries;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.extraction.ExtractionReader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.ToDoubleFunction;

/**
 * Characterizes the <em>geometry</em> of polygon rings <strong>resolved per vertex count</strong>
 * — 4, 5, 6, 7, 8, {@code >8}, and pooled. Encodes nothing and reconstructs nothing: walks every
 * {@code >4}-vertex ring in the pure-polygon columns of one layer (default {@code building}) and
 * accumulates, per corpus:
 *
 * <ul>
 *   <li><b>Shape/regularity</b>: vertex-count distribution; rectilinearity (share of interior turns
 *       in 80°–100°) and the share of rings that are <em>entirely</em> right-angled; collinear-run
 *       lengths (near-0° turns); convex vs concave (turn-sign consistency); the per-ring
 *       edge-direction alphabet size (distinct 16-way quantized edge directions).
 *   <li><b>Size/extent</b>: bounding-box aspect ratio; the share of edges axis-parallel to the tile
 *       grid; the edge-length distribution.
 *   <li><b>Compression-relevant</b>: within-ring repeated edge vectors; the share of interior
 *       first-order residuals that are near-zero; and exact-shape repetition — a
 *       rotation-canonical, translation-invariant ring signature hashed across the corpus, split
 *       into same-tile vs cross-tile recurrence.
 *   <li><b>Structural</b>: share of these rings whose owning polygon has {@code numParts > 1}
 *       (holes/multipart); winding-order (signed-area sign) consistency; and whether the stored
 *       first vertex is the ring's min-(x,y) corner (a derivable start ⇒ no seed to signal).
 * </ul>
 *
 * <p><b>Scope: pure-polygon columns only</b> (same discipline as {@code RingPriorCli}). Columns that
 * mix polygons and linestrings fold linestring vertex counts into {@code numRings}, so a run there
 * is not necessarily a true ring.
 *
 * <p>Usage: {@code ./gradlew buildingShape --args="../data/extracted"} or with a different layer
 * {@code --args="../data/extracted --layer landcover"}.
 *
 * <p>Measured: {@code docs/results/failed/ring-predictors.md}, {@code docs/results/failed/shape-reuse.md}.
 */
public final class BuildingShapeCli {
    private BuildingShapeCli() {
    }

    private static final int POLYGON = 2;
    private static final int MULTIPOLYGON = 5;

    /**
     * 16-way direction quantization for the edge-direction alphabet, in radians per bucket.
     */
    private static final int DIR_BUCKETS = 16;

    /** Ring-size bucket for {@code >8} vertices. */
    private static final int MANY = 9;

    /** Pooled "every ring" accumulator; sorts after {@link #MANY}. */
    private static final int ALL = 99;

    /**
     * Window sizes for the WP5-E5.1 shape-reuse feasibility scan.
     */
    private static final int[] K_VALUES = {1, 4, 16};

    public static void main(String[] args) throws IOException {
        String inArg = null;
        String layer = "building";
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

        // One accumulator per ring-size bucket (4,5,6,7,8,9==">8"), plus ALL pooled. The shape
        // index is deliberately shared: shape recurrence is a corpus-wide question, not a per-bucket
        // one, and splitting it would report a shape as "distinct" once per bucket it appears in.
        final var buckets = new TreeMap<Integer, Accumulator>();
        final var shapeIndex = new HashMap<String, Signature>();
        // WP5-E5.1: recent-signature window shared across the whole manifest, in extraction order —
        // approximates a codec whose back-window is small rather than corpus-wide.
        final Deque<String> recentSignatures = new ArrayDeque<>();
        for (final var entry : manifest) {
            if (!entry.layerName().equals(layer)) {
                continue;
            }
            final var ctx = ExtractionReader.readContext(inDir, entry);
            if (ctx.vertices().isEmpty() || !isPurePolygon(ctx.geometryTypes())) {
                continue;
            }
            measure(
                    ctx, hasHoles(ctx), entry.z(), entry.x(), entry.y(), buckets, shapeIndex,
                    recentSignatures);
        }

        report(layer, buckets, shapeIndex);
    }

    // ---------------------------------------------------------------------------
    // Accumulation
    // ---------------------------------------------------------------------------

    /**
     * All corpus running sums for the measured population of {@code >4}-vertex rings.
     */
    private static final class Accumulator {
        long rings;

        // Shape / regularity
        final Map<Integer, Long> vertexCountHist = new HashMap<>(); // vertex count -> #rings (capped bucket >8)
        long interiorTurns; // total interior turns examined
        long rightAngleTurns; // turns in 80°-100°
        long allRightAngleRings; // rings whose every interior turn is ~90°
        long straightTurns; // turns ~0° (collinear)
        final Map<Integer, Long> collinearRunHist = new HashMap<>(); // run length -> count
        long convexRings; // all turn-signs equal
        final Map<Integer, Long> dirAlphabetHist = new HashMap<>(); // distinct-direction count -> #rings

        // Size / extent
        long edges; // total edges examined
        long axisParallelEdges; // dx==0 or dy==0
        long edgeLenSum;
        long edgeLenMax;
        final Map<Integer, Long> aspectHist = new HashMap<>(); // floor(max/min bbox side) -> #rings

        // Compression-relevant
        long ringsWithRepeatedEdge; // ring contains two identical (dx,dy) edge vectors
        // Within-ring dictionary viability. A back-reference costs ceil(log2(k)) bits to name one of
        // k earlier edges; it only pays if the edge it replaces would have cost more than that.
        long edgesRepeatingEarlier; // edge exactly equals some earlier edge in the SAME ring
        long edgesRepeatBitsSaved; // zigzag bits those repeated edges would have cost
        long edgesRepeatRefBits; // bits to name the back-reference instead
        long ringEdgesTotal; // denominator: edges examined for the above
        long interiorResiduals; // interior first-order residuals examined
        long nearZeroResiduals; // both components' zigzag width <= 1 bit
        long shapeRecurs; // rings whose shape has been seen before
        long shapeRecursCrossTile; // ...and the prior sighting was on a different tile
        // WP5-E5.1 feasibility scan: does this ring's shape match one of the k most recently seen
        // rings, in buffer (extraction) order? Unlike the corpus-wide index above, this bounds what a
        // codec with only a small back-window (not a corpus-wide dictionary) could actually exploit.
        final long[] kWindowHits = new long[K_VALUES.length]; // exact match, translation-invariant
        final long[] kWindowHitsRot = new long[K_VALUES.length]; // + 90-degree rotation multiples

        // Structural
        long ringsInHoledPolygon; // ring belongs to a column with any numParts>1
        long ccwRings; // signed area > 0
        long firstVertexIsMinCorner; // stored start == lexicographic min (x, then y)
        // How far the ring's dominant edge direction sits from a tile-grid axis. If buildings were
        // grid-aligned this would be ~0 and every rectilinear lever would pay; the measured spread is
        // why none of them do.
        long ringsNearAxisAligned; // dominant edge within 5 deg of an axis
        long ringsMidRotated; // dominant edge 20-70 deg off axis (worst case for both components)
    }

    /**
     * A shape's first-seen tile and how many times it has recurred; keyed by canonical signature.
     */
    private static final class Signature {
        final long tile;
        long count;

        Signature(long tile) {
            this.tile = tile;
        }
    }

    private static void measure(
            BufferContext ctx,
            boolean hasHoles,
            int z,
            int x,
            int y,
            TreeMap<Integer, Accumulator> buckets,
            Map<String, Signature> shapeIndex,
            Deque<String> recentSignatures) {
        final var vertices = ctx.vertices();
        final var starts = PartBoundaries.of(ctx, vertices.size());
        final long tileId = ((long) z << 42) ^ ((long) x << 21) ^ y;
        final var maxK = K_VALUES[K_VALUES.length - 1];

        for (var s = 0; s < starts.length; s++) {
            final var start = starts[s];
            final var end = s + 1 < starts.length ? starts[s + 1] : vertices.size();
            final var n = end - start;
            // Four-vertex rings are included as the reference bucket: they are the population
            // SEGMENT_QUAD_CORNER's parallelogram predictor already wins, so every other bucket is
            // read against them.
            if (n < 4) {
                continue;
            }
            // Shape recurrence is resolved once per ring, before either accumulator sees it: the ring
            // is folded into two accumulators (its bucket and the pooled ALL), and a shared index would
            // otherwise report the second call as a recurrence of the first.
            final var recurrence = classifyShape(vertices, start, end, tileId, shapeIndex);

            // WP5-E5.1: does this ring's shape match one of the k most recently seen rings, in buffer
            // order? Checked before the ring's own signature is pushed onto the window, so a ring never
            // matches itself.
            final var sig = canonicalSignature(vertices, start, end);
            final var rotSigs = rotationSignatures(vertices, start, end);
            final var kHits = new long[K_VALUES.length];
            final var kHitsRot = new long[K_VALUES.length];
            var position = 0;
            for (final var recent : recentSignatures) {
                for (var ki = 0; ki < K_VALUES.length; ki++) {
                    if (position < K_VALUES[ki]) {
                        if (recent.equals(sig)) {
                            kHits[ki] = 1;
                        }
                        for (final var rs : rotSigs) {
                            if (recent.equals(rs)) {
                                kHitsRot[ki] = 1;
                                break;
                            }
                        }
                    }
                }
                position++;
                if (position >= maxK) {
                    break;
                }
            }
            recentSignatures.addFirst(sig);
            while (recentSignatures.size() > maxK) {
                recentSignatures.removeLast();
            }

            final var bucket = Math.min(n, MANY);
            accumulate(vertices, start, end, hasHoles, recurrence, kHits, kHitsRot,
                    buckets.computeIfAbsent(bucket, k -> new Accumulator()));
            accumulate(vertices, start, end, hasHoles, recurrence, kHits, kHitsRot,
                    buckets.computeIfAbsent(ALL, k -> new Accumulator()));
        }
    }

    /**
     * Whether a ring's shape had been seen before, and if so whether on another tile.
     */
    private enum Recurrence {
        FIRST_SIGHTING,
        REPEAT_SAME_TILE,
        REPEAT_CROSS_TILE
    }

    /**
     * Records one ring's shape in the corpus-wide index and reports whether it had been seen.
     */
    private static Recurrence classifyShape(
            List<Vertex> v, int start, int end, long tileId, Map<String, Signature> shapeIndex) {
        final var sig = canonicalSignature(v, start, end);
        final var prev = shapeIndex.get(sig);
        if (prev == null) {
            shapeIndex.put(sig, new Signature(tileId));
            return Recurrence.FIRST_SIGHTING;
        }
        prev.count++;
        return prev.tile == tileId ? Recurrence.REPEAT_SAME_TILE : Recurrence.REPEAT_CROSS_TILE;
    }

    /**
     * Folds one ring into one accumulator. Called once per bucket and once for the pooled ALL.
     */
    private static void accumulate(
            List<Vertex> vertices,
            int start,
            int end,
            boolean hasHoles,
            Recurrence recurrence,
            long[] kHits,
            long[] kHitsRot,
            Accumulator a) {
        a.rings++;
        bump(a.vertexCountHist, Math.min(end - start, MANY));
        measureShape(vertices, start, end, a);
        measureExtent(vertices, start, end, a);
        measureCompression(vertices, start, end, a);
        measureStructural(vertices, start, end, hasHoles, a);
        if (recurrence != Recurrence.FIRST_SIGHTING) {
            a.shapeRecurs++;
            if (recurrence == Recurrence.REPEAT_CROSS_TILE) {
                a.shapeRecursCrossTile++;
            }
        }
        for (var ki = 0; ki < K_VALUES.length; ki++) {
            a.kWindowHits[ki] += kHits[ki];
            a.kWindowHitsRot[ki] += kHitsRot[ki];
        }
    }

    /**
     * Turns, collinear runs, convexity, and the edge-direction alphabet of one ring.
     */
    private static void measureShape(List<Vertex> v, int start, int end, Accumulator a) {
        final var n = end - start;
        var allRight = true;
        var run = 0; // current collinear run length
        var firstSign = 0;
        var convex = true;
        final var dirs = new HashSet<Integer>();

        // A closed ring's edges wrap: edge i goes v[i] -> v[i+1 mod n]. Turns are between consecutive
        // edges, so there are n turns (one at each vertex).
        for (var k = 0; k < n; k++) {
            final var p0 = v.get(start + k);
            final var p1 = v.get(start + (k + 1) % n);
            final var p2 = v.get(start + (k + 2) % n);
            final var e1x = p1.x() - p0.x();
            final var e1y = p1.y() - p0.y();
            final var e2x = p2.x() - p1.x();
            final var e2y = p2.y() - p1.y();

            dirs.add(quantizeDir(e1x, e1y));

            final double cross = (double) e1x * e2y - (double) e1y * e2x;
            final double dot = (double) e1x * e2x + (double) e1y * e2y;
            final double angle = Math.abs(Math.atan2(cross, dot)); // turn magnitude, 0..pi
            final var deg = Math.toDegrees(angle);

            a.interiorTurns++;
            if (deg >= 80 && deg <= 100) {
                a.rightAngleTurns++;
            } else {
                allRight = false;
            }
            if (deg < 5) {
                a.straightTurns++;
                run++;
            } else {
                if (run > 0) {
                    bump(a.collinearRunHist, run);
                }
                run = 0;
            }

            final var sign = (int) Math.signum(cross);
            if (sign != 0) {
                if (firstSign == 0) {
                    firstSign = sign;
                } else if (sign != firstSign) {
                    convex = false;
                }
            }
        }
        if (run > 0) {
            bump(a.collinearRunHist, run);
        }
        if (allRight) {
            a.allRightAngleRings++;
        }
        if (convex) {
            a.convexRings++;
        }
        bump(a.dirAlphabetHist, dirs.size());
    }

    /**
     * Bounding box aspect, axis-parallel edges, and edge lengths of one ring.
     */
    private static void measureExtent(List<Vertex> v, int start, int end, Accumulator a) {
        final var n = end - start;
        var minX = Integer.MAX_VALUE;
        var minY = Integer.MAX_VALUE;
        var maxX = Integer.MIN_VALUE;
        var maxY = Integer.MIN_VALUE;
        for (var k = 0; k < n; k++) {
            final var p = v.get(start + k);
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
            minY = Math.min(minY, p.y());
            maxY = Math.max(maxY, p.y());
            // Edge k (wrapping) for axis-parallel + length.
            final var q = v.get(start + (k + 1) % n);
            final var dx = q.x() - p.x();
            final var dy = q.y() - p.y();
            a.edges++;
            if (dx == 0 || dy == 0) {
                a.axisParallelEdges++;
            }
            final var len = Math.round(Math.hypot(dx, dy));
            a.edgeLenSum += len;
            a.edgeLenMax = Math.max(a.edgeLenMax, len);
        }
        final var w = Math.max(1, maxX - minX);
        final var h = Math.max(1, maxY - minY);
        final var lo = Math.min(w, h);
        final var hi = Math.max(w, h);
        bump(a.aspectHist, hi / lo); // integer floor of aspect ratio
    }

    /**
     * Repeated edge vectors and near-zero interior residuals of one ring.
     */
    private static void measureCompression(List<Vertex> v, int start, int end, Accumulator a) {
        final var n = end - start;

        // Repeated edge vectors within the ring.
        final var seenEdges = new HashSet<Long>();
        var repeated = false;
        for (var k = 0; k < n; k++) {
            final var p = v.get(start + k);
            final var q = v.get(start + (k + 1) % n);
            final var key = ((long) (q.x() - p.x()) << 32) ^ (q.y() - p.y() & 0xFFFFFFFFL);
            if (!seenEdges.add(key)) {
                repeated = true;
            }
        }
        if (repeated) {
            a.ringsWithRepeatedEdge++;
        }

        // Per-edge back-reference accounting: for each edge, is it an exact repeat of an earlier edge
        // in this ring, and would naming that earlier edge cost less than coding the delta?
        final var earlier = new java.util.HashMap<Long, Integer>();
        for (var k = 0; k < n; k++) {
            final var p = v.get(start + k);
            final var q = v.get(start + (k + 1) % n);
            final var dx = q.x() - p.x();
            final var dy = q.y() - p.y();
            final var key = ((long) dx << 32) ^ (dy & 0xFFFFFFFFL);
            a.ringEdgesTotal++;
            final var prev = earlier.get(key);
            if (prev != null && k > 0) {
                a.edgesRepeatingEarlier++;
                a.edgesRepeatBitsSaved += width(zigzag(dx)) + width(zigzag(dy));
                // Naming one of k candidates costs ceil(log2(k)) bits, minimum 1.
                a.edgesRepeatRefBits += Math.max(1, 32 - Integer.numberOfLeadingZeros(Math.max(1, k - 1)));
            }
            earlier.putIfAbsent(key, k);
        }

        // Near-zero first-order residuals on interior vertices (i-1 exists): cross-check RingPriorCli.
        for (var i = start + 1; i < end; i++) {
            final var p1 = v.get(i - 1);
            final var p = v.get(i);
            final var zzx = zigzag(p.x() - p1.x());
            final var zzy = zigzag(p.y() - p1.y());
            a.interiorResiduals++;
            if (width(zzx) <= 1 && width(zzy) <= 1) {
                a.nearZeroResiduals++;
            }
        }
    }

    /**
     * Holes, winding order, and derivable-start-vertex checks for one ring.
     */
    private static void measureStructural(
            List<Vertex> v, int start, int end, boolean hasHoles, Accumulator a) {
        final var n = end - start;
        if (hasHoles) {
            a.ringsInHoledPolygon++;
        }

        // Shoelace signed area over the closed ring.
        long area2 = 0;
        for (var k = 0; k < n; k++) {
            final var p = v.get(start + k);
            final var q = v.get(start + (k + 1) % n);
            area2 += (long) p.x() * q.y() - (long) q.x() * p.y();
        }
        if (area2 > 0) {
            a.ccwRings++;
        }

        // Is the stored first vertex the ring's lexicographic (min x, then min y) corner?
        var minIdx = 0;
        for (var k = 1; k < n; k++) {
            final var cur = v.get(start + k);
            final var best = v.get(start + minIdx);
            if (cur.x() < best.x() || (cur.x() == best.x() && cur.y() < best.y())) {
                minIdx = k;
            }
        }
        if (minIdx == 0) {
            a.firstVertexIsMinCorner++;
        }

        // Longest edge stands in for the ring's own frame; its angle modulo 90 deg says how far that
        // frame is rotated off the tile grid. A rectilinear prior only becomes narrow residuals when
        // this is near zero, because only then does a 90 deg turn leave one component unchanged.
        var bestLen = -1.0;
        var bestAngle = 0.0;
        for (var k = 0; k < n; k++) {
            final var p = v.get(start + k);
            final var q = v.get(start + (k + 1) % n);
            final var dx = q.x() - p.x();
            final var dy = q.y() - p.y();
            final var len = Math.hypot(dx, dy);
            if (len > bestLen) {
                bestLen = len;
                bestAngle = Math.toDegrees(Math.atan2(dy, dx));
            }
        }
        var off = Math.abs(bestAngle) % 90.0;
        if (off > 45.0) {
            off = 90.0 - off;
        }
        if (off <= 5.0) {
            a.ringsNearAxisAligned++;
        } else if (off >= 20.0) {
            a.ringsMidRotated++;
        }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /**
     * A rotation-canonical, translation-invariant signature: the lexicographically-min rotation of the edge-vector sequence.
     */
    private static String canonicalSignature(List<Vertex> v, int start, int end) {
        final var n = end - start;
        final var edges = new long[n];
        for (var k = 0; k < n; k++) {
            final var p = v.get(start + k);
            final var q = v.get(start + (k + 1) % n);
            edges[k] = ((long) (q.x() - p.x()) << 32) ^ ((q.y() - p.y()) & 0xFFFFFFFFL);
        }
        return canonicalize(edges);
    }

    /**
     * The four starting-vertex-canonical signatures of a ring's edge sequence rotated by 0/90/180/270
     * degrees (geometric rotation, not starting-vertex rotation — {@code canonicalSignature} already
     * handles the latter). Used only by the WP5-E5.1 window scan to test whether two shapes match up
     * to a quarter-turn, not just an exact translation.
     */
    private static String[] rotationSignatures(List<Vertex> v, int start, int end) {
        final var n = end - start;
        final var edges = new long[n][4]; // [edge][rotation 0..3]
        for (var k = 0; k < n; k++) {
            final var p = v.get(start + k);
            final var q = v.get(start + (k + 1) % n);
            var dx = q.x() - p.x();
            var dy = q.y() - p.y();
            for (var r = 0; r < 4; r++) {
                edges[k][r] = ((long) dx << 32) ^ (dy & 0xFFFFFFFFL);
                // Rotate (dx,dy) by 90 degrees ccw for the next iteration: (dx,dy) -> (-dy,dx).
                final var ndx = -dy;
                final var ndy = dx;
                dx = ndx;
                dy = ndy;
            }
        }
        final var sigs = new String[4];
        for (var r = 0; r < 4; r++) {
            final var rotated = new long[n];
            for (var k = 0; k < n; k++) {
                rotated[k] = edges[k][r];
            }
            sigs[r] = canonicalize(rotated);
        }
        return sigs;
    }

    /**
     * Starting-vertex-canonical string form of an edge-vector sequence: lexicographically-min rotation.
     */
    private static String canonicalize(long[] edges) {
        final var n = edges.length;
        var best = 0;
        for (var r = 1; r < n; r++) {
            for (var k = 0; k < n; k++) {
                final var a = edges[(best + k) % n];
                final var b = edges[(r + k) % n];
                if (a != b) {
                    if (Long.compareUnsigned(b, a) < 0) {
                        best = r;
                    }
                    break;
                }
            }
        }
        final var sb = new StringBuilder(n * 8);
        for (var k = 0; k < n; k++) {
            sb.append(edges[(best + k) % n]).append(',');
        }
        return sb.toString();
    }

    /**
     * 16-way direction bucket of an edge, orientation-signed (a reversed edge is a different bucket).
     */
    private static int quantizeDir(int dx, int dy) {
        if (dx == 0 && dy == 0) {
            return -1;
        }
        var angle = Math.atan2(dy, dx); // -pi..pi
        if (angle < 0) {
            angle += 2 * Math.PI;
        }
        return (int) (angle / (2 * Math.PI) * DIR_BUCKETS) % DIR_BUCKETS;
    }

    private static void bump(Map<Integer, Long> hist, int key) {
        hist.merge(key, 1L, Long::sum);
    }

    private static int zigzag(int v) {
        return (v << 1) ^ (v >> 31);
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

    // ---------------------------------------------------------------------------
    // Reporting
    // ---------------------------------------------------------------------------

    private static void report(
            String layer, TreeMap<Integer, Accumulator> buckets, Map<String, Signature> shapeIndex) {
        final var all = buckets.get(ALL);
        if (all == null || all.rings == 0) {
            System.out.printf("No >=4-vertex pure-polygon rings found in layer '%s'.%n", layer);
            return;
        }
        System.out.printf(
                "Geometric properties of polygon rings in layer '%s' (pure-polygon columns),%n"
                        + "resolved per ring vertex count. Bucket 4 is the population%n"
                        + "SEGMENT_QUAD_CORNER's parallelogram predictor already wins; %d == '>8'.%n",
                layer, MANY);
        System.out.printf("Population: %,d rings, %,d distinct shapes.%n%n", all.rings, shapeIndex.size());

        final var keys = new ArrayList<>(buckets.keySet());

        System.out.println("--- Shape / regularity ---");
        header(keys);
        row(buckets, keys, "rings", a -> (double) a.rings, "%,.0f");
        row(buckets, keys, "share of population", a -> 100.0 * a.rings / all.rings, "%.1f%%");
        row(buckets, keys, "turns ~90deg", a -> share(a.rightAngleTurns, a.interiorTurns), "%.1f%%");
        row(buckets, keys, "fully right-angled", a -> share(a.allRightAngleRings, a.rings), "%.1f%%");
        row(buckets, keys, "turns ~straight", a -> share(a.straightTurns, a.interiorTurns), "%.1f%%");
        row(buckets, keys, "convex", a -> share(a.convexRings, a.rings), "%.1f%%");
        row(buckets, keys, "CCW-wound", a -> share(a.ccwRings, a.rings), "%.1f%%");
        row(buckets, keys, "mean dir alphabet", BuildingShapeCli::meanDirAlphabet, "%.2f");
        row(buckets, keys, "  ...exactly 4 dirs", a -> share(count(a.dirAlphabetHist, 4), a.rings), "%.1f%%");
        System.out.println();

        System.out.println("--- Size / extent ---");
        row(buckets, keys, "axis-parallel edges", a -> share(a.axisParallelEdges, a.edges), "%.1f%%");
        row(buckets, keys, "mean edge length", a -> a.edges == 0 ? 0 : (double) a.edgeLenSum / a.edges, "%.1f");
        row(buckets, keys, "bbox aspect ~1", a -> share(count(a.aspectHist, 1), a.rings), "%.1f%%");
        System.out.println();

        System.out.println("--- Compression-relevant ---");
        row(buckets, keys, "repeated edge vector", a -> share(a.ringsWithRepeatedEdge, a.rings), "%.1f%%");
        row(buckets, keys, "edges repeating", a -> share(a.edgesRepeatingEarlier, a.ringEdgesTotal), "%.1f%%");
        row(buckets, keys, "  mean bits if coded", a -> a.edgesRepeatingEarlier == 0 ? 0 : (double) a.edgesRepeatBitsSaved / a.edgesRepeatingEarlier, "%.2f");
        row(buckets, keys, "  mean bits as ref", a -> a.edgesRepeatingEarlier == 0 ? 0 : (double) a.edgesRepeatRefBits / a.edgesRepeatingEarlier, "%.2f");
        row(buckets, keys, "  net bits/ring", a -> a.rings == 0 ? 0 : (double) (a.edgesRepeatBitsSaved - a.edgesRepeatRefBits) / a.rings, "%.1f");
        row(buckets, keys, "residuals near-zero", a -> share(a.nearZeroResiduals, a.interiorResiduals), "%.2f%%");
        row(buckets, keys, "shape recurs", a -> share(a.shapeRecurs, a.rings), "%.1f%%");
        row(buckets, keys, "  ...cross-tile", a -> share(a.shapeRecursCrossTile, a.shapeRecurs), "%.1f%%");
        for (var ki = 0; ki < K_VALUES.length; ki++) {
            final var idx = ki;
            row(buckets, keys, "k=" + K_VALUES[ki] + " window hit",
                    a -> share(a.kWindowHits[idx], a.rings), "%.2f%%");
            row(buckets, keys, "  ...+rot90",
                    a -> share(a.kWindowHitsRot[idx], a.rings), "%.2f%%");
        }
        System.out.println();

        System.out.println("--- Structural ---");
        row(buckets, keys, "in holed column", a -> share(a.ringsInHoledPolygon, a.rings), "%.1f%%");
        row(buckets, keys, "start is min corner", a -> share(a.firstVertexIsMinCorner, a.rings), "%.1f%%");
        row(buckets, keys, "grid-aligned (<5deg)", a -> share(a.ringsNearAxisAligned, a.rings), "%.1f%%");
        row(buckets, keys, "rotated (>=20deg)", a -> share(a.ringsMidRotated, a.rings), "%.1f%%");
        System.out.println();

        System.out.println("--- Collinear-run lengths, pooled over all rings ---");
        printHist(all.collinearRunHist, all.straightTurns == 0 ? 1 : all.straightTurns);
        System.out.println();
    }

    /**
     * The bucket column headers: the vertex counts, then {@code >8}, then the pooled {@code all}.
     */
    private static void header(List<Integer> keys) {
        final var line = new StringBuilder(String.format("%-22s", ""));
        for (final var k : keys) {
            line.append(String.format("%10s", k == ALL ? "all" : k == MANY ? ">8" : k.toString()));
        }
        System.out.println(line);
    }

    /**
     * One metric across every bucket column.
     */
    private static void row(
            TreeMap<Integer, Accumulator> buckets,
            List<Integer> keys,
            String label,
            ToDoubleFunction<Accumulator> metric,
            String format) {
        final var line = new StringBuilder(String.format("%-22s", label));
        for (final var k : keys) {
            line.append(String.format("%10s", String.format(format, metric.applyAsDouble(buckets.get(k)))));
        }
        System.out.println(line);
    }

    private static double share(long num, long den) {
        return den == 0 ? 0 : 100.0 * num / den;
    }

    private static long count(Map<Integer, Long> hist, int key) {
        return hist.getOrDefault(key, 0L);
    }

    /**
     * Mean number of distinct 16-way edge directions per ring.
     */
    private static double meanDirAlphabet(Accumulator a) {
        long weighted = 0;
        long rings = 0;
        for (final var e : a.dirAlphabetHist.entrySet()) {
            weighted += (long) e.getKey() * e.getValue();
            rings += e.getValue();
        }
        return rings == 0 ? 0 : (double) weighted / rings;
    }

    private static void printHist(Map<Integer, Long> hist, long total) {
        hist.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(
                        e ->
                                System.out.printf(
                                        "  %4d : %,10d  (%5.1f%%)%n",
                                        e.getKey(), e.getValue(), 100.0 * e.getValue() / total));
    }

    private static String pct(long num, long den) {
        if (den == 0) {
            return "n/a";
        }
        return String.format("%,d / %,d  (%.1f%%)", num, den, 100.0 * num / den);
    }

    // ---------------------------------------------------------------------------
    // Loading
    // ---------------------------------------------------------------------------

    /**
     * Whether the column contains any multi-ring (holed) polygon.
     */
    private static boolean hasHoles(BufferContext ctx) {
        return ctx.numParts().stream().anyMatch(p -> p > 1);
    }
}
