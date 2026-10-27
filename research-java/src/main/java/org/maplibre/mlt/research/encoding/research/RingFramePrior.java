package org.maplibre.mlt.research.encoding.research;

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
public final class RingFramePrior {

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
    private double[] centroidX = new double[64];
    private double[] centroidY = new double[64];
    private int[] next = new int[64];
    private final int[] head = new int[GRID * GRID];

    public RingFramePrior() {
        Arrays.fill(head, -1);
    }

    /** Index of the frame 0 deg (the tile's axes): (0 + 45) / 90 * 65536. */
    private static final int ZERO_FRAME = 32768;

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
        final var nearest = nearest(start.x(), start.y());
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
        if (q == ZERO_FRAME) {
            x = dx;
            y = dy;
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
        if (q == ZERO_FRAME) {
            x = rx;
            y = ry;
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
    private static int[] heading(int ex, int ey) {
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
            centroidX = Arrays.copyOf(centroidX, size);
            centroidY = Arrays.copyOf(centroidY, size);
            next = Arrays.copyOf(next, size);
        }
        final var cell = cell(cy) * GRID + cell(cx);
        theta[ringCount] = ownFrame(vertices, start, end, closed);
        centroidX[ringCount] = cx;
        centroidY[ringCount] = cy;
        next[ringCount] = head[cell];
        head[cell] = ringCount++;
    }

    // --- frame arithmetic ------------------------------------------------------

    /** The frame as a 16-bit index: the current frame folded into [-45, 45) and quantized. */
    private int frameIndex() {
        return index(fold(StrictMath.toDegrees(StrictMath.atan2(sumSin, sumCos)) / 4));
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
        return c == 0 && s == 0 ? 0 : fold(StrictMath.toDegrees(StrictMath.atan2(s, c)) / 4);
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
}
