package org.maplibre.mlt.research.encoder.prediction.mechanism;

import org.maplibre.mlt.converter.geometry.Vertex;

import java.util.Arrays;
import java.util.List;

/**
 * Predicts the frame of a polygon ring — the rotation that makes its walls axis-parallel — from
 * rings the decoder has already decoded, and re-expresses ring edges in that frame.
 *
 * <p>Buildings are right-angled in their own frame but rarely in the tile's, so a plain delta pays
 * two full components per wall. The neighbours of a building are mostly built along the same street,
 * so the frame of the decoded ring nearest to the new ring's start vertex predicts its own to about
 * a degree (median 1,1 deg on {@code building}, {@code docs/results/orientation-prior.md}). That
 * prior is refined by the ring's own decoded edges: the frame is the length-weighted mean wall
 * direction modulo 90 deg, with the neighbour's frame counting as one wall of {@link #PRIOR_WEIGHT}
 * units.
 *
 * <p>An edge {@code (dx, dy)} is rotated by -frame with three lifting shears
 * ({@code x -= round(tan(a/2) y); y += round(sin(a) x); x -= round(tan(a/2) y)}). Each shear is a
 * bijection on the integers, so the edge stays two integers (no side stream, unlike {@code
 * TURN_SCALAR}) and {@link #inverse} undoes it exactly. A wall along the frame has one component near
 * zero.
 *
 * <p>Encoder and decoder run the identical sequence of calls — {@link #beginRing} after a ring's start
 * vertex, {@link #forward}/{@link #inverse} for a coded edge, {@link #edge} after every edge, {@link
 * #endRing} — and every value is derived from integer vertices by {@link StrictMath} and IEEE
 * arithmetic, which are specified bit for bit. Nothing is transmitted.
 */
public final class FramePrior {

    /**
     * The neighbour's frame against the ring's own decoded edges, as an edge length: the neighbour is
     * off by about 1 deg, the direction of an edge of length L by about 57/L deg.
     */
    private static final double PRIOR_WEIGHT = 50;

    /** Neighbour-search grid cell, in tile units. */
    private static final int CELL = 128;

    /**
     * Cells per axis, and the cell holding coordinate 0: a 4096 extent plus buffer is 32 cells, so
     * this covers -2048 to 10240. Centroids outside clamp to the border cells; both sides clamp
     * alike, the prior is only a little worse there.
     */
    private static final int GRID = 96;
    private static final int ORIGIN = 16;

    /** Rings under this many vertices have no frame worth remembering. */
    private static final int MIN_RING = 3;

    /** A line part needs one edge to have a direction. */
    private static final int MIN_LINE = 2;

    // Decoded rings: frame and centroid, and a grid over the centroids (per-cell lists linked
    // through `next`, newest first).
    private int ringCount;
    private double[] theta = new double[64];
    // integer mode: the unit vector of the ring's own votes (e^(4i theta)) instead of theta
    private double[] unitCos = new double[64];
    private double[] unitSin = new double[64];
    private double[] centroidX = new double[64];
    private double[] centroidY = new double[64];
    private int[] next = new int[64];
    private final int[] head = new int[GRID * GRID];

    /** Integer mode: no trigonometry anywhere, see {@link #FramePrior(boolean)}. */
    private final boolean integer;

    private final int zeroFrame;

    /** The float frame: 16-bit frame index, shears from {@link StrictMath} trigonometry. */
    public FramePrior() {
        this(false);
    }

    /**
     * {@code integer} selects the libm-free frame (the {@code *_IFRAME*} predictors): the frame index
     * has {@link #INT_FRAME_BITS} bits and is read off a table of arctangents from a division of the
     * summed votes, the shears are integer multiplies by Q16 tables with a rounding shift, a ring
     * remembers the unit vector of its own votes (so no arctangent and no sine or cosine per ring), and
     * an edge's heading is read off the same table. Only the table values come from trigonometry; a decoder
     * needs {@code + - * /} and {@code sqrt} (IEEE, exactly specified) and integer arithmetic.
     */
    public FramePrior(boolean integer) {
        this(integer, false, false, false, false, false);
    }

    /**
     * The previous-run frame ({@code QUAD_CORNER_PREVFRAME}): the integer frame with no neighbour search
     * and no voting. A ring is coded in the frame of the ring decoded just before it, fixed at the ring's
     * start; the ring's own edges do not refine it. After a Hilbert reorder the previous ring is a
     * spatial neighbour, which is what the nearest-centroid search of {@link #FramePrior(boolean)} looks
     * for. A run is voted for once, when it is complete ({@link #endRing}); there is no grid, no
     * per-edge vote and no per-edge frame index.
     */
    public static FramePrior previousRun() {
        return new FramePrior(true, true, false, false, false, false);
    }

    /**
     * One vote per ring while it is coded: the first edge of a ring is coded in the previous ring's frame,
     * every edge after it in the frame of the ring's own first edge (the direction of that one edge,
     * modulo 90 deg), fixed from then on. A ring is remembered by the votes of all its edges, as in
     * {@link #previousRun()}.
     */
    public static FramePrior previousRunFirstEdge() {
        return new FramePrior(true, true, false, true, false, false);
    }

    /**
     * {@link #previousRunFirstEdge()} with the ring remembered by its first edge too, so a ring is voted for
     * exactly once, by that edge: no batch pass over the ring when it is complete.
     */
    public static FramePrior previousRunFirstEdgeOnly() {
        return new FramePrior(true, true, false, true, true, false);
    }

    /**
     * {@link #previousRun()} refined like the nearest-run frame: the previous run's frame is the prior of
     * weight {@link #PRIOR_WEIGHT} and the ring's own decoded edges vote on top of it. Only the neighbour
     * search and the grid are gone, the per-edge votes and frame indices stay.
     */
    public static FramePrior previousRunVoting() {
        return new FramePrior(true, true, true, false, false, false);
    }

    /**
     * {@link #previousRunFirstEdgeOnly()} where the first edge does not replace the previous ring's frame but
     * votes on top of it, as the own edges do in {@link #previousRunVoting()}: the previous frame is the prior
     * of weight {@link #PRIOR_WEIGHT} and the first edge adds its length, so a short first edge (a chamfer, a
     * ring start the rotation put on a stub) cannot override the neighbour's frame, a long one does. The ring
     * is remembered by that blended frame.
     */
    public static FramePrior previousRunFirstEdgeBlended() {
        return new FramePrior(true, true, false, true, true, true);
    }

    private FramePrior(
            boolean integer, boolean previous, boolean refine, boolean firstEdge, boolean rememberFirst,
            boolean blend) {
        Arrays.fill(head, -1);
        this.integer = integer;
        this.previous = previous;
        this.refine = refine;
        this.firstEdge = firstEdge;
        this.rememberFirst = rememberFirst;
        this.blend = blend;
        this.zeroFrame = integer ? INT_ZERO_FRAME : ZERO_FRAME;
    }

    /** First-edge mode where the edge votes on top of the previous ring's frame, see {@link #previousRunFirstEdgeBlended()}. */
    private final boolean blend;

    /** Previous-run mode (always with the integer frame), see {@link #previousRun()}. */
    private final boolean previous;

    /** Previous-run mode with the ring's own edges voting, see {@link #previousRunVoting()}. */
    private final boolean refine;

    /** Previous-run mode where the ring's first edge sets the frame of the edges after it. */
    private final boolean firstEdge;

    /** With {@link #firstEdge}: a ring is remembered by its first edge, not by all its edges. */
    private final boolean rememberFirst;

    // first-edge mode: edges of the ring seen so far, and the unit vector of its first edge's vote
    private int edgesSeen;
    private boolean firstValid;
    private double firstCos;
    private double firstSin;

    // previous-run mode: the unit vector of the last complete run's votes, and the frame index
    // derived from it for the run being coded
    private double lastCos = 1;
    private double lastSin = 0;
    private int fixedFrame = INT_ZERO_FRAME;

    /** Index of the frame 0 deg (the tile's axes): (0 + 45) / 90 * 65536. */
    private static final int ZERO_FRAME = 32768;

    /** Bits of the integer frame index (4096 frames over 90 deg, 0,022 deg apart). */
    static final int INT_FRAME_BITS = 12;

    private static final int INT_FRAMES = 1 << INT_FRAME_BITS;
    private static final int INT_ZERO_FRAME = INT_FRAMES / 2;

    // The ring being coded.
    private double sumCos;
    private double sumSin;
    private double voteCos;
    private double voteSin;

    // Result of the last forward/inverse.
    private int x;
    private int y;

    /** Result of the last {@link #forward} or {@link #inverse}. */
    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    /**
     * A ring's start vertex is decoded: seed the frame with the nearest decoded ring's, or the tile's
     * axes if there is none.
     */
    public void beginRing(Vertex start) {
        if (previous) {
            sumCos = PRIOR_WEIGHT * lastCos;
            sumSin = PRIOR_WEIGHT * lastSin;
            fixedFrame = integerFrameIndex();
            edgesSeen = 0;
            firstValid = false;
            return;
        }
        final var nearest = nearest(start.x(), start.y());
        if (integer) {
            sumCos = PRIOR_WEIGHT * (nearest < 0 ? 1 : unitCos[nearest]);
            sumSin = PRIOR_WEIGHT * (nearest < 0 ? 0 : unitSin[nearest]);
            return;
        }
        final var prior = nearest < 0 ? 0 : theta[nearest];
        sumCos = PRIOR_WEIGHT * StrictMath.cos(StrictMath.toRadians(4 * prior));
        sumSin = PRIOR_WEIGHT * StrictMath.sin(StrictMath.toRadians(4 * prior));
    }

    /** Rotates an edge into the current frame; the result is in {@link #x()}, {@link #y()}. */
    public void forward(int dx, int dy) {
        rotate(frameIndex(), dx, dy);
    }

    /** The exact inverse of {@link #forward} in the same frame. */
    public void inverse(int rx, int ry) {
        unrotate(frameIndex(), rx, ry);
    }

    /** Rotation by -frame (three shears) of frame index {@code q}; the result is in {@link #x}, {@link #y}. */
    private void rotate(int q, int dx, int dy) {
        if (q == zeroFrame) {
            x = dx;
            y = dy;
            return;
        }
        if (integer) {
            final long t = IntTables.TAN_HALF[q];
            final long s = IntTables.SIN[q];
            final var x1 = dx - ((t * dy + Q16_HALF) >> Q16);
            final var y1 = dy + ((s * x1 + Q16_HALF) >> Q16);
            x = (int) (x1 - ((t * y1 + Q16_HALF) >> Q16));
            y = (int) y1;
            return;
        }
        final var t = shearT(q);
        final var s = shearS(q);
        final var x1 = dx - StrictMath.round(t * dy);
        final var y1 = dy + StrictMath.round(s * x1);
        x = (int) (x1 - StrictMath.round(t * y1));
        y = (int) y1;
    }

    private void unrotate(int q, int rx, int ry) {
        if (q == zeroFrame) {
            x = rx;
            y = ry;
            return;
        }
        if (integer) {
            final long t = IntTables.TAN_HALF[q];
            final long s = IntTables.SIN[q];
            final var x1 = rx + ((t * ry + Q16_HALF) >> Q16);
            final var dy = ry - ((s * x1 + Q16_HALF) >> Q16);
            x = (int) (x1 + ((t * dy + Q16_HALF) >> Q16));
            y = (int) dy;
            return;
        }
        final var t = shearT(q);
        final var s = shearS(q);
        final var x1 = rx + StrictMath.round(t * ry);
        final var dy = ry - StrictMath.round(s * x1);
        x = (int) (x1 + StrictMath.round(t * dy));
        y = (int) dy;
    }

    /**
     * Rotates {@code (dx, dy)} into the direction of the edge {@code (ex, ey)} (the full turn, not
     * the frame modulo 90 deg), so a vector along the edge lands on the positive x axis and one across
     * it on the y axis: whole quarter turns (exact swaps and sign flips) followed by the shear
     * rotation of the remainder, each a bijection on the integers. A zero edge has no direction and
     * leaves the vector as it is. State-free: the result is in {@link #x()}, {@link #y()}.
     */
    public void forwardHeading(int ex, int ey, int dx, int dy) {
        final var heading = heading(ex, ey);
        var qx = dx;
        var qy = dy;
        for (var k = 0; k < heading[0]; k++) {
            final var turned = qy;
            qy = -qx;
            qx = turned;
        }
        rotate(heading[1], qx, qy);
    }

    /** The exact inverse of {@link #forwardHeading} for the same edge. */
    public void inverseHeading(int ex, int ey, int rx, int ry) {
        final var heading = heading(ex, ey);
        unrotate(heading[1], rx, ry);
        var qx = x;
        var qy = y;
        for (var k = 0; k < heading[0]; k++) {
            final var turned = -qy;
            qy = qx;
            qx = turned;
        }
        x = qx;
        y = qy;
    }

    /**
     * The direction of an edge as {@code {quarter turns (0 to 3), frame index}}: the direction is
     * {@code 90 * quarters + degrees(index)} deg up to the 16-bit quantization, with the remainder in
     * [-45, 45). A zero edge is {@code {0, ZERO_FRAME}}.
     */
    private int[] heading(int ex, int ey) {
        if (integer) {
            // direction in units of 1/16384 turn (4096 per quarter turn), shifted by 45 deg so the
            // remainder of the quarter turns is the index
            final var shifted = IntTables.direction(ey, ex) + INT_FRAMES / 2;
            final var quarters = Math.floorDiv(shifted, INT_FRAMES);
            return new int[] {Math.floorMod(quarters, 4), shifted - quarters * INT_FRAMES};
        }
        if (ex == 0 && ey == 0) {
            return new int[] {0, ZERO_FRAME};
        }
        final var t = (StrictMath.toDegrees(StrictMath.atan2(ey, ex)) + 45) / 90;
        final var whole = StrictMath.floor(t);
        var quarters = (int) whole;
        var index = StrictMath.round((t - whole) * 65536);
        if (index == 65536) {
            index = 0;
            quarters++;
        }
        return new int[] {Math.floorMod(quarters, 4), (int) index};
    }

    /** An edge of the current ring is decoded: it votes for the ring's frame. */
    public void edge(int dx, int dy) {
        if (firstEdge) {
            // only the first edge votes; a zero edge has no direction and leaves the previous ring's frame
            if (edgesSeen++ == 0) {
                vote(dx, dy);
                if (voteCos != 0 || voteSin != 0) {
                    if (blend) {
                        // sumCos, sumSin hold the previous ring's frame at weight PRIOR_WEIGHT since beginRing
                        sumCos += voteCos;
                        sumSin += voteSin;
                    } else {
                        sumCos = voteCos;
                        sumSin = voteSin;
                    }
                    fixedFrame = integerFrameIndex();
                    final var length = StrictMath.sqrt(sumCos * sumCos + sumSin * sumSin);
                    firstCos = sumCos / length;
                    firstSin = sumSin / length;
                    firstValid = true;
                }
            }
            return;
        }
        if (previous && !refine) {
            return;
        }
        vote(dx, dy);
        sumCos += voteCos;
        sumSin += voteSin;
    }

    /** The ring {@code vertices[start, end)} is complete: remember its frame for the rings after it. */
    public void endRing(List<Vertex> vertices, int start, int end) {
        if (end - start < MIN_RING) {
            return;
        }
        remember(vertices, start, end, true);
    }

    /**
     * The open run {@code vertices[start, end)} (a line part) is complete: remember its frame for the
     * runs after it. Its frame is the length-weighted mean direction of its edges modulo 90 deg, with
     * no closing edge; a run of two vertices (one edge) is enough. Rings and lines share one
     * neighbour search, so a line is predicted from rings too.
     */
    public void endLine(List<Vertex> vertices, int start, int end) {
        if (end - start < MIN_LINE) {
            return;
        }
        remember(vertices, start, end, false);
    }

    private void remember(List<Vertex> vertices, int start, int end, boolean closed) {
        if (previous && rememberFirst) {
            // a ring whose first edge had no direction leaves the remembered frame as it was
            if (firstValid) {
                lastCos = firstCos;
                lastSin = firstSin;
            }
            return;
        }
        if (previous) {
            ownVotes(vertices, start, end, closed);
            if (ownCos == 0 && ownSin == 0) {
                lastCos = 1;
                lastSin = 0;
            } else {
                final var length = StrictMath.sqrt(ownCos * ownCos + ownSin * ownSin);
                lastCos = ownCos / length;
                lastSin = ownSin / length;
            }
            return;
        }
        var cx = 0.0;
        var cy = 0.0;
        for (var i = start; i < end; i++) {
            cx += vertices.get(i).x();
            cy += vertices.get(i).y();
        }
        cx /= end - start;
        cy /= end - start;
        if (ringCount == theta.length) {
            final var size = ringCount * 2;
            theta = Arrays.copyOf(theta, size);
            unitCos = Arrays.copyOf(unitCos, size);
            unitSin = Arrays.copyOf(unitSin, size);
            centroidX = Arrays.copyOf(centroidX, size);
            centroidY = Arrays.copyOf(centroidY, size);
            next = Arrays.copyOf(next, size);
        }
        final var cell = cell(cy) * GRID + cell(cx);
        if (integer) {
            ownVotes(vertices, start, end, closed);
            // the unit vector of the ring's own votes is e^(4i theta): no arctangent to take now and no
            // sine or cosine to take when a later ring starts from it
            if (ownCos == 0 && ownSin == 0) {
                unitCos[ringCount] = 1;
                unitSin[ringCount] = 0;
            } else {
                final var length = StrictMath.sqrt(ownCos * ownCos + ownSin * ownSin);
                unitCos[ringCount] = ownCos / length;
                unitSin[ringCount] = ownSin / length;
            }
        } else {
            theta[ringCount] = ownFrame(vertices, start, end, closed);
        }
        centroidX[ringCount] = cx;
        centroidY[ringCount] = cy;
        next[ringCount] = head[cell];
        head[cell] = ringCount++;
    }

    // --- frame arithmetic ------------------------------------------------------

    /** The frame as a 16-bit index: the current frame folded into [-45, 45) and quantized. */
    private int frameIndex() {
        if (previous && !refine) {
            return fixedFrame;
        }
        if (integer) {
            return integerFrameIndex();
        }
        return index(fold(StrictMath.toDegrees(StrictMath.atan2(sumSin, sumCos)) / 4));
    }

    /** The integer frame index of the summed votes: 4 theta is their direction in 1/16384 turns, theta a rounded quarter of it. */
    private int integerFrameIndex() {
        return (((IntTables.direction(sumSin, sumCos) + 2) >> 2) + INT_ZERO_FRAME) & (INT_FRAMES - 1);
    }

    /**
     * An edge's vote for its ring's frame, into {@link #voteCos}/{@link #voteSin}: its length times
     * {@code e^(4i phi)}, so walls at right angles vote together. {@code z^4 / |z|^3} for
     * {@code z = dx + i dy}, in IEEE arithmetic only — no trigonometry.
     */
    private void vote(int dx, int dy) {
        if (dx == 0 && dy == 0) {
            voteCos = 0;
            voteSin = 0;
            return;
        }
        final double ex = dx;
        final double ey = dy;
        final var a = ex * ex - ey * ey; // Re z^2
        final var b = 2 * ex * ey; // Im z^2
        final var squared = ex * ex + ey * ey;
        final var cube = squared * StrictMath.sqrt(squared);
        voteCos = (a * a - b * b) / cube;
        voteSin = 2 * a * b / cube;
    }

    /**
     * Length-weighted mean wall direction modulo 90 deg of a complete run: a ring with its closing
     * edge, or an open line without one.
     */
    private double ownFrame(List<Vertex> v, int start, int end, boolean closed) {
        ownVotes(v, start, end, closed);
        return ownCos == 0 && ownSin == 0 ? 0 : fold(StrictMath.toDegrees(StrictMath.atan2(ownSin, ownCos)) / 4);
    }

    // The summed votes of the last complete run, edges in order.
    private double ownCos;
    private double ownSin;

    private void ownVotes(List<Vertex> v, int start, int end, boolean closed) {
        final var n = end - start;
        var c = 0.0;
        var s = 0.0;
        for (var k = 0; k < (closed ? n : n - 1); k++) {
            final var p = v.get(start + k);
            final var q = v.get(start + (k + 1) % n);
            vote(q.x() - p.x(), q.y() - p.y());
            c += voteCos;
            s += voteSin;
        }
        ownCos = c;
        ownSin = s;
    }

    /** Folds degrees into [-45, 45). */
    private static double fold(double degrees) {
        return ((degrees + 45) % 90 + 90) % 90 - 45;
    }

    /** Quantizes a frame in [-45, 45) to a 16-bit index; the frame 0 deg is {@link #ZERO_FRAME}. */
    private static int index(double theta) {
        return (int) (StrictMath.round((theta + 45) / 90 * 65536) & 0xFFFF);
    }

    private static double degrees(int index) {
        return index * 90.0 / 65536 - 45;
    }

    // Shear coefficients per quantized frame, built once at class initialization.
    private static final double[] TAN_HALF = new double[65536];
    private static final double[] SIN = new double[65536];

    static {
        for (var index = 0; index < 65536; index++) {
            final var alpha = StrictMath.toRadians(-degrees(index));
            TAN_HALF[index] = StrictMath.tan(alpha / 2);
            SIN[index] = StrictMath.sin(alpha);
        }
    }

    private static double shearT(int index) {
        return TAN_HALF[index];
    }

    private static double shearS(int index) {
        return SIN[index];
    }

    // --- nearest decoded ring --------------------------------------------------

    /** Index of the decoded ring whose centroid is nearest {@code (px, py)}, or -1. */
    private int nearest(double px, double py) {
        if (ringCount == 0) {
            return -1;
        }
        final var cx = cell(px);
        final var cy = cell(py);
        var best = -1;
        var bestDistance = Double.MAX_VALUE;
        for (var radius = 0; radius < GRID; radius++) {
            // The square ring of cells at Chebyshev distance `radius`.
            for (var gy = Math.max(0, cy - radius); gy <= Math.min(GRID - 1, cy + radius); gy++) {
                final var fullRow = Math.abs(gy - cy) == radius;
                final var step = fullRow ? 1 : Math.max(1, 2 * radius);
                for (var gx = cx - radius; gx <= cx + radius; gx += step) {
                    if (gx < 0 || gx >= GRID) {
                        continue;
                    }
                    for (var index = head[gy * GRID + gx]; index >= 0; index = next[index]) {
                        final var ddx = centroidX[index] - px;
                        final var ddy = centroidY[index] - py;
                        final var distance = ddx * ddx + ddy * ddy;
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = index;
                        }
                    }
                }
            }
            // Anything not yet visited lies at least `radius` cells away.
            if (best >= 0 && bestDistance <= (double) radius * CELL * radius * CELL) {
                break;
            }
        }
        return best;
    }

    /** The grid cell along one axis, clamped to the grid. */
    private static int cell(double coordinate) {
        return Math.min(GRID - 1, Math.max(0, (int) Math.floor(coordinate / CELL) + ORIGIN));
    }

    // --- the integer frame's tables --------------------------------------------

    private static final int Q16 = 16;
    private static final long Q16_HALF = 1L << (Q16 - 1);

    /**
     * What the integer frame reads instead of calling trigonometry, built once at class initialization from
     * {@link StrictMath} (fdlibm; a port reproduces it by rounding to the integers below, which hides the
     * last bit of any accurate implementation):
     *
     * <ul>
     *   <li>{@code ATAN[k] = round(atan(k / 4096) * 8192 / pi)}, {@code k} in {@code 0..4096}: the angle of
     *       a slope in units of 1/16384 turn, up to 2048 (45 deg);
     *   <li>{@code TAN_HALF[q] = round(tan(a / 2) * 65536)} and {@code SIN[q] = round(sin(a) * 65536)} for
     *       {@code a = -(q * 90 / 4096 - 45)} deg: the two coefficients of the three shears that rotate by
     *       the frame with index {@code q}, in Q16.
     * </ul>
     */
    static final class IntTables {

        static final int[] ATAN = new int[4097];
        static final int[] TAN_HALF = new int[INT_FRAMES];
        static final int[] SIN = new int[INT_FRAMES];

        static {
            for (var k = 0; k <= 4096; k++) {
                ATAN[k] = (int) StrictMath.round(StrictMath.atan(k / 4096.0) * (8192 / Math.PI));
            }
            for (var q = 0; q < INT_FRAMES; q++) {
                final var alpha = StrictMath.toRadians(-(q * 90.0 / INT_FRAMES - 45));
                TAN_HALF[q] = (int) StrictMath.round(StrictMath.tan(alpha / 2) * 65536);
                SIN[q] = (int) StrictMath.round(StrictMath.sin(alpha) * 65536);
            }
        }

        private IntTables() {
        }

        /**
         * The direction of {@code (x, y)} in units of 1/16384 turn, {@code -8192..8192} ({@code atan2} times
         * {@code 8192 / pi}), from one division and one table read: the slope of the shorter component
         * to the longer, quantized to 1/4096, is the table index; the octant and the quadrant are
         * signs and comparisons. {@code (0, 0)} is 0. IEEE arithmetic and integers only.
         */
        static int direction(double y, double x) {
            final var ax = Math.abs(x);
            final var ay = Math.abs(y);
            if (!(ax > 0 || ay > 0)) {
                return 0;
            }
            final var swap = ay > ax;
            final var slope = swap ? ax / ay : ay / ax;
            var t = ATAN[(int) (slope * 4096 + 0.5)];
            if (swap) {
                t = 4096 - t;
            }
            if (x < 0) {
                t = 8192 - t;
            }
            return y < 0 ? -t : t;
        }
    }
}
