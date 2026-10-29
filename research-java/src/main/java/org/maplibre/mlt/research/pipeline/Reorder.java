package org.maplibre.mlt.research.pipeline;

import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.PartBoundaries;
import org.maplibre.mlt.research.encoding.Encoding.BufferContext;
import org.maplibre.mlt.research.pipeline.mechanism.GeometryReorder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * S1: feature reordering. Rewrites the {@link BufferContext} before any prediction runs, to shrink
 * the jump from one feature to the next in the delta chain and to bring similar geometries close.
 * Nothing is transmitted: MLT does not preserve feature order, so the decoder emits the order it
 * reads, and the reordered topology streams are what the tile carries.
 *
 * <p>Features are sorted by a space-filling-curve key of an anchor point (the start vertex or the
 * centre of the bounding box). Optionally every polygon ring is rotated, winding kept, to start at
 * the vertex nearest the previous emitted vertex. Lines are never reversed (one-way semantics),
 * and the parts of a multi-part feature stay contiguous and in order. A column of Points and
 * MultiPoints is reordered too, per feature; a buffer whose features cannot be located (points
 * mixed with lines or polygons, topology that does not line up) is returned unchanged.
 *
 * <p>{@link #CHAIN} and {@link #CANON_CHAIN} are the greedy nearest-endpoint chain rows
 * ({@link GeometryReorder}), kept as the references the Hilbert and Morton rows are measured against.
 */
public enum Reorder {

    /** The identity. */
    NONE("", null, null, false, 0, false, false),

    HILBERT_START("_HILBERT_START", Curve.HILBERT, Anchor.START, false, 0, false, false),
    HILBERT_CENTROID("_HILBERT_CENTROID", Curve.HILBERT, Anchor.CENTROID, false, 0, false, false),
    MORTON_START("_MORTON_START", Curve.MORTON, Anchor.START, false, 0, false, false),
    MORTON_CENTROID("_MORTON_CENTROID", Curve.MORTON, Anchor.CENTROID, false, 0, false, false),

    /** {@link #HILBERT_START} plus every polygon ring rotated to start nearest the previous vertex. */
    HILBERT_START_ROT("_HILBERT_START_ROT", Curve.HILBERT, Anchor.START, true, 0, false, false),
    HILBERT_CENTROID_ROT("_HILBERT_CENTROID_ROT", Curve.HILBERT, Anchor.CENTROID, true, 0, false, false),

    /**
     * {@link #HILBERT_START} with a secondary key: inside a cell of 2^{@value #COARSE_LEVELS} grid
     * units per side, features are ordered by vertex count first, so equal shapes sit next to each
     * other and their residual strings can repeat.
     */
    HILBERT_START_VC("_HILBERT_START_VC", Curve.HILBERT, Anchor.START, false, Reorder.COARSE_LEVELS, false, false),

    /** Reference: the old pipeline's greedy nearest-endpoint chaining. */
    CHAIN("_CHAIN", null, null, false, 0, false, false),

    /** Reference: the old pipeline's best row, canonical ring start then chaining. */
    CANON_CHAIN("_CANON_CHAIN", null, null, false, 0, false, false),

    /**
     * {@link #HILBERT_CENTROID_ROT} plus, in a Point/MultiPoint column, the points of every
     * MultiPoint sorted along the curve. The order of a MultiPoint's points carries no meaning, so
     * this is free for rendering; a MultiPoint of scattered points (the {@code housenumber} layer)
     * otherwise pays a full-tile jump between its own points.
     */
    HILBERT_CENTROID_ROT_MP("_HILBERT_CENTROID_ROT_MP", Curve.HILBERT, Anchor.CENTROID, true, 0, true, false),

    /**
     * {@link #HILBERT_CENTROID_ROT} with the vertex-count class as the <i>primary</i> key
     * ({@code min(vertexCount, 8)}), the Hilbert key inside a class: every class is contiguous in
     * feature order, so the class segments of S3 come for free (the "(class, Hilbert) sort").
     */
    CLASS_HILBERT_CENTROID_ROT("_CLASS_HILBERT_CENTROID_ROT", Curve.HILBERT, Anchor.CENTROID, true, 0, false, true);

    /** Grid levels dropped from a Hilbert key to form the cell the secondary key sorts inside. */
    static final int COARSE_LEVELS = 5;

    /** Vertex counts from here on share one class in the class-first sort. */
    static final int CLASS_CAP = 8;

    /** Resolution of the curve grid, per axis. */
    private static final int MAX_ORDER = 16;

    private enum Curve {HILBERT, MORTON}

    private enum Anchor {START, CENTROID}

    private final String suffix;
    private final Curve curve;
    private final Anchor anchor;
    private final boolean rotate;
    private final int coarseLevels;
    private final boolean sortPoints;
    private final boolean classFirst;

    Reorder(String suffix, Curve curve, Anchor anchor, boolean rotate, int coarseLevels, boolean sortPoints, boolean classFirst) {
        this.suffix = suffix;
        this.curve = curve;
        this.anchor = anchor;
        this.rotate = rotate;
        this.coarseLevels = coarseLevels;
        this.sortPoints = sortPoints;
        this.classFirst = classFirst;
    }

    /** Contribution to an encoding's name; empty for {@link #NONE}. */
    public String suffix() {
        return suffix;
    }

    public boolean centroidAnchor() {
        return anchor == Anchor.CENTROID;
    }

    public boolean reorders() {
        return this != NONE;
    }

    /** Whether this variant sorts by a curve key, as opposed to the identity and the old chains. */
    public boolean keyed() {
        return curve != null;
    }

    /** The reordered buffer, or {@code ctx} itself when it cannot be reordered safely. */
    public BufferContext apply(BufferContext ctx) {
        return switch (this) {
            case NONE -> ctx;
            case CHAIN -> GeometryReorder.FEATURE.apply(ctx);
            case CANON_CHAIN -> GeometryReorder.RING_CANONICAL_THEN_FEATURE.apply(ctx);
            default -> applyKeyed(ctx);
        };
    }

    private BufferContext applyKeyed(BufferContext ctx) {
        final var features = Features.of(ctx);
        if (features == null || (features.count() < 2 && !sortPoints)) {
            return ctx;
        }
        // With sorted MultiPoints one grid, spanning every vertex, orders points and features alike.
        final var sortMembers = sortPoints && features.pointColumn();
        final var allX = new int[ctx.vertices().size()];
        final var allY = new int[allX.length];
        if (sortMembers) {
            for (var i = 0; i < allX.length; i++) {
                final var vertex = ctx.vertices().get(i);
                allX[i] = vertex.x();
                allY[i] = vertex.y();
            }
            features.sortMultiPoints(this, allX, allY);
        }
        final var anchorX = new int[features.count()];
        final var anchorY = new int[features.count()];
        final var counts = new int[features.count()];
        for (var f = 0; f < features.count(); f++) {
            features.anchor(f, anchor == Anchor.CENTROID, anchorX, anchorY);
            counts[f] = features.vertexCount[f];
        }
        final var gridX = sortMembers ? allX : anchorX;
        final var gridY = sortMembers ? allY : anchorY;
        return features.rebuild(ctx, orderByKey(keys(anchorX, anchorY, gridX, gridY), counts), rotate);
    }

    /**
     * The topology streams of the reordered buffer with <b>no vertices</b>: what a decoder reads
     * from the tile. The vertices are stripped so that a decoder given this cannot "decode" by
     * copying the buffer it is supposed to reconstruct.
     */
    public BufferContext topology(BufferContext ctx) {
        final var reordered = apply(ctx);
        return new BufferContext(
                List.of(),
                reordered.geometryTypes(),
                reordered.numGeometries(),
                reordered.numParts(),
                reordered.numRings());
    }

    /**
     * The order a keyed variant emits features in, given each feature's anchor point and vertex
     * count: indices into the original feature list. Ties keep the original order. The grid spans
     * the anchors themselves.
     */
    public int[] order(int[] anchorX, int[] anchorY, int[] vertexCounts) {
        return orderByKey(keys(anchorX, anchorY, anchorX, anchorY), vertexCounts);
    }

    /** Whether a Point/MultiPoint column is ordered on one grid spanning every vertex ({@link #apply}). */
    public boolean sortsPoints() {
        return sortPoints;
    }

    /**
     * Like {@link #order(int[], int[], int[])} for a Point/MultiPoint column of a variant that
     * {@link #sortsPoints()}: the grid spans {@code (refX, refY)}, every vertex of the column, not just
     * the anchors, which is what {@link #apply} orders such a column by.
     */
    public int[] order(int[] anchorX, int[] anchorY, int[] refX, int[] refY, int[] vertexCounts) {
        return orderByKey(keys(anchorX, anchorY, refX, refY), vertexCounts);
    }

    /**
     * Curve keys of the points {@code (xs, ys)} on a grid spanning {@code (refX, refY)}, at most
     * {@value #MAX_ORDER} bits per axis (coarser if the span is larger).
     */
    long[] keys(int[] xs, int[] ys, int[] refX, int[] refY) {
        var minX = Integer.MAX_VALUE;
        var minY = Integer.MAX_VALUE;
        var maxX = Integer.MIN_VALUE;
        var maxY = Integer.MIN_VALUE;
        for (var i = 0; i < refX.length; i++) {
            minX = Math.min(minX, refX[i]);
            minY = Math.min(minY, refY[i]);
            maxX = Math.max(maxX, refX[i]);
            maxY = Math.max(maxY, refY[i]);
        }
        final var span = Math.max((long) maxX - minX, (long) maxY - minY);
        final var bits = Math.max(1, 64 - Long.numberOfLeadingZeros(span));
        final var shift = Math.max(0, bits - MAX_ORDER);
        final var gridOrder = Math.min(bits, MAX_ORDER);
        final var keys = new long[xs.length];
        for (var i = 0; i < xs.length; i++) {
            final var x = ((long) xs[i] - minX) >> shift;
            final var y = ((long) ys[i] - minY) >> shift;
            keys[i] = curve == Curve.HILBERT ? hilbertKey(gridOrder, x, y) : mortonKey(x, y);
        }
        return keys;
    }

    private int[] orderByKey(long[] keys, int[] vertexCounts) {
        final var n = keys.length;
        final var indices = new Integer[n];
        for (var i = 0; i < n; i++) {
            indices[i] = i;
        }
        Comparator<Integer> comparator = Comparator.comparingLong(i -> keys[i]);
        if (coarseLevels > 0) {
            final var shift = coarseLevels * 2;
            comparator = Comparator.<Integer>comparingLong(i -> keys[i] >> shift)
                    .thenComparingInt(i -> vertexCounts[i])
                    .thenComparing(comparator);
        }
        if (classFirst) {
            comparator = Comparator.<Integer>comparingInt(i -> Math.min(vertexCounts[i], CLASS_CAP))
                    .thenComparing(comparator);
        }
        Arrays.sort(indices, comparator.thenComparingInt(i -> i));
        final var order = new int[n];
        for (var i = 0; i < n; i++) {
            order[i] = indices[i];
        }
        return order;
    }
    // --- space-filling curves -----------------------------------------------

    /** Distance along the Hilbert curve of the cell {@code (x, y)} on a {@code 2^order} grid. */
    static long hilbertKey(int order, long x, long y) {
        final var n = 1L << order;
        var d = 0L;
        var px = x;
        var py = y;
        for (var s = n >> 1; s > 0; s >>= 1) {
            final var rx = (px & s) != 0 ? 1L : 0L;
            final var ry = (py & s) != 0 ? 1L : 0L;
            d += s * s * ((3 * rx) ^ ry);
            if (ry == 0) {
                if (rx == 1) {
                    px = n - 1 - px;
                    py = n - 1 - py;
                }
                final var t = px;
                px = py;
                py = t;
            }
        }
        return d;
    }

    /** The bits of {@code x} and {@code y} interleaved, {@code x} in the even positions. */
    static long mortonKey(long x, long y) {
        return spread(x) | spread(y) << 1;
    }

    private static long spread(long v) {
        var r = v & 0xFFFFFFFFL;
        r = (r | r << 16) & 0x0000FFFF0000FFFFL;
        r = (r | r << 8) & 0x00FF00FF00FF00FFL;
        r = (r | r << 4) & 0x0F0F0F0F0F0F0F0FL;
        r = (r | r << 2) & 0x3333333333333333L;
        r = (r | r << 1) & 0x5555555555555555L;
        return r;
    }

    // --- features -------------------------------------------------------------

    private static final int POINT = 0;
    private static final int POLYGON = 2;
    private static final int MULTIPOINT = 3;
    private static final int MULTILINESTRING = 4;
    private static final int MULTIPOLYGON = 5;

    /**
     * A buffer cut into features: each owns a contiguous vertex range, its runs (rings or line
     * parts; none for points), and the entries of {@code numGeometries} / {@code numParts} that
     * travel with it. The grouping rules are those of the old {@code GeometryReorder}, copied rather
     * than called because they are private there.
     */
    private static final class Features {
        List<Vertex> vertices;
        final int[] vertexStart;
        final int[] vertexCount;
        final int[] firstRun;
        final int[] runCount;
        final int[] geometryAt; // index into numGeometries, -1 for none
        final int[] partAt; // first numParts entry owned (polygon columns)
        final int[] partRun; // numParts entries owned
        final int[] runStarts;
        final int[] runLengths;
        final boolean[] isLine;
        final boolean polygonColumn;

        private Features(int count, List<Vertex> vertices, int[] runStarts, boolean[] isLine, boolean polygonColumn) {
            this.vertices = vertices;
            this.vertexStart = new int[count];
            this.vertexCount = new int[count];
            this.firstRun = new int[count];
            this.runCount = new int[count];
            this.geometryAt = new int[count];
            this.partAt = new int[count];
            this.partRun = new int[count];
            this.runStarts = runStarts;
            this.isLine = isLine;
            this.polygonColumn = polygonColumn;
            this.runLengths = new int[runStarts.length];
            for (var r = 0; r < runStarts.length; r++) {
                runLengths[r] = (r + 1 < runStarts.length ? runStarts[r + 1] : vertices.size()) - runStarts[r];
            }
        }

        int count() {
            return vertexStart.length;
        }

        /** Whether this is a Point/MultiPoint column (no runs). */
        boolean pointColumn() {
            return runStarts.length == 0;
        }

        /**
         * Sorts the points of every MultiPoint along {@code order}'s curve, on a copy of the
         * vertex list (the context's own list is never touched).
         */
        void sortMultiPoints(Reorder order, int[] refX, int[] refY) {
            vertices = new ArrayList<>(vertices);
            for (var f = 0; f < count(); f++) {
                if (geometryAt[f] < 0 || vertexCount[f] < 2) {
                    continue;
                }
                final var from = vertexStart[f];
                final var xs = new int[vertexCount[f]];
                final var ys = new int[xs.length];
                for (var i = 0; i < xs.length; i++) {
                    xs[i] = vertices.get(from + i).x();
                    ys[i] = vertices.get(from + i).y();
                }
                final var sorted = order.orderByKey(order.keys(xs, ys, refX, refY), new int[xs.length]);
                final var original = new ArrayList<>(vertices.subList(from, from + xs.length));
                for (var i = 0; i < sorted.length; i++) {
                    vertices.set(from + i, original.get(sorted[i]));
                }
            }
        }

        /** {@code null} when the features cannot be located from the topology streams. */
        static Features of(BufferContext ctx) {
            final var types = ctx.geometryTypes();
            if (types.isEmpty() || ctx.vertices().isEmpty()) {
                return null;
            }
            return allPoints(types) ? ofPoints(ctx) : ofRuns(ctx);
        }

        private static boolean allPoints(List<Integer> types) {
            for (final var type : types) {
                if (type != POINT && type != MULTIPOINT) {
                    return false;
                }
            }
            return true;
        }

        private static Features ofPoints(BufferContext ctx) {
            final var types = ctx.geometryTypes();
            final var features = new Features(types.size(), ctx.vertices(), new int[0], new boolean[0], false);
            var vertex = 0;
            var geometry = 0;
            for (var f = 0; f < types.size(); f++) {
                features.vertexStart[f] = vertex;
                features.geometryAt[f] = -1;
                var count = 1;
                if (types.get(f) == MULTIPOINT) {
                    if (geometry >= ctx.numGeometries().size()) {
                        return null;
                    }
                    features.geometryAt[f] = geometry;
                    count = ctx.numGeometries().get(geometry++);
                }
                if (count <= 0) {
                    return null;
                }
                features.vertexCount[f] = count;
                vertex += count;
            }
            return vertex == ctx.vertices().size() ? features : null;
        }

        private static Features ofRuns(BufferContext ctx) {
            final var vertices = ctx.vertices();
            final var starts = PartBoundaries.of(ctx, vertices.size());
            if (starts.length < 2) {
                return null;
            }
            final var isLine = PartBoundaries.lineRuns(ctx, starts.length);
            if (isLine == null) {
                return null;
            }
            final var polygonColumn = containsPolygon(ctx.geometryTypes());
            final var features = new Features(ctx.geometryTypes().size(), vertices, starts, isLine, polygonColumn);

            var run = 0;
            var geometryCursor = 0;
            var partCursor = 0;
            for (var f = 0; f < ctx.geometryTypes().size(); f++) {
                final var type = ctx.geometryTypes().get(f);
                var runs = 0;
                features.geometryAt[f] = -1;
                features.partAt[f] = partCursor;
                if (polygonColumn && (type == POLYGON || type == MULTIPOLYGON)) {
                    final int polygons;
                    if (type == MULTIPOLYGON) {
                        if (geometryCursor >= ctx.numGeometries().size()) {
                            return null;
                        }
                        features.geometryAt[f] = geometryCursor;
                        polygons = ctx.numGeometries().get(geometryCursor++);
                    } else {
                        polygons = 1;
                    }
                    for (var p = 0; p < polygons; p++) {
                        if (partCursor >= ctx.numParts().size()) {
                            return null;
                        }
                        runs += ctx.numParts().get(partCursor++);
                    }
                    features.partRun[f] = polygons;
                } else if (type == MULTILINESTRING) {
                    if (geometryCursor >= ctx.numGeometries().size()) {
                        return null;
                    }
                    features.geometryAt[f] = geometryCursor;
                    runs = ctx.numGeometries().get(geometryCursor++);
                } else if (type == POINT || type == MULTIPOINT) {
                    return null;
                } else {
                    runs = 1;
                }
                if (runs <= 0 || run + runs > starts.length) {
                    return null;
                }
                features.firstRun[f] = run;
                features.runCount[f] = runs;
                features.vertexStart[f] = starts[run];
                final var last = run + runs - 1;
                features.vertexCount[f] = starts[last] + features.runLengths[last] - starts[run];
                run += runs;
            }
            return run == starts.length ? features : null;
        }

        private static boolean containsPolygon(List<Integer> types) {
            for (final var type : types) {
                if (type == POLYGON || type == MULTIPOLYGON) {
                    return true;
                }
            }
            return false;
        }

        /** The feature's start vertex, or the centre of its bounding box. */
        void anchor(int f, boolean centroid, int[] xs, int[] ys) {
            final var first = vertices.get(vertexStart[f]);
            if (!centroid) {
                xs[f] = first.x();
                ys[f] = first.y();
                return;
            }
            var minX = first.x();
            var maxX = first.x();
            var minY = first.y();
            var maxY = first.y();
            for (var i = vertexStart[f]; i < vertexStart[f] + vertexCount[f]; i++) {
                final var v = vertices.get(i);
                minX = Math.min(minX, v.x());
                maxX = Math.max(maxX, v.x());
                minY = Math.min(minY, v.y());
                maxY = Math.max(maxY, v.y());
            }
            xs[f] = (int) (((long) minX + maxX) / 2);
            ys[f] = (int) (((long) minY + maxY) / 2);
        }

        /**
         * Emits vertices and topology streams in the new order, the streams permuted with their
         * features so {@link PartBoundaries} derives the same run lengths from the result.
         */
        BufferContext rebuild(BufferContext ctx, int[] order, boolean rotate) {
            final var out = new ArrayList<Vertex>(vertices.size());
            final var types = new ArrayList<Integer>(ctx.geometryTypes().size());
            final var numGeometries = new ArrayList<Integer>(ctx.numGeometries().size());
            final var numParts = new ArrayList<Integer>(ctx.numParts().size());
            final var numRings = new ArrayList<Integer>(ctx.numRings().size());
            Vertex previous = new Vertex(0, 0);

            for (final var f : order) {
                types.add(ctx.geometryTypes().get(f));
                if (geometryAt[f] >= 0) {
                    numGeometries.add(ctx.numGeometries().get(geometryAt[f]));
                }
                for (var i = 0; i < partRun[f]; i++) {
                    numParts.add(ctx.numParts().get(partAt[f] + i));
                }
                if (runCount[f] == 0) {
                    // Points: no runs, only the vertex range.
                    for (var i = 0; i < vertexCount[f]; i++) {
                        out.add(vertices.get(vertexStart[f] + i));
                    }
                    previous = out.get(out.size() - 1);
                    continue;
                }
                for (var r = firstRun[f]; r < firstRun[f] + runCount[f]; r++) {
                    final var start = runStarts[r];
                    final var length = runLengths[r];
                    final var shift = rotate && !isLine[r] && length >= 3 ? nearest(start, length, previous) : 0;
                    for (var v = 0; v < length; v++) {
                        out.add(vertices.get(start + (shift + v) % length));
                    }
                    previous = out.get(out.size() - 1);
                    if (polygonColumn) {
                        numRings.add(length);
                    } else {
                        numParts.add(length);
                    }
                }
            }
            return new BufferContext(out, types, numGeometries, numParts, numRings);
        }

        /** The offset in the ring whose vertex is nearest {@code previous} (Manhattan, first on a tie). */
        private int nearest(int start, int length, Vertex previous) {
            var best = 0;
            var bestDistance = Long.MAX_VALUE;
            for (var k = 0; k < length; k++) {
                final var v = vertices.get(start + k);
                final var d = Math.abs((long) v.x() - previous.x()) + Math.abs((long) v.y() - previous.y());
                if (d < bestDistance) {
                    bestDistance = d;
                    best = k;
                }
            }
            return best;
        }
    }
}
