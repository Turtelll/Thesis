package org.maplibre.mlt.research.encoding.research;

import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.PartBoundaries;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reorders whole features within a buffer so each one starts near where the previous one ended,
 * shrinking the part-start residuals that a continuous delta cursor pays at every boundary.
 *
 * <p>This is <b>not</b> an {@link org.maplibre.mlt.research.encoding.Encoding}. It rewrites the
 * {@link BufferContext} before any encoding runs, so every existing encoding stays byte-for-byte
 * unchanged and strictly lossless <em>against the reordered context</em>:
 *
 * <pre>ctx --[GeometryReorder]--&gt; ctx' --[any Encoding]--&gt; bytes</pre>
 *
 * <p>The reason this can pay at all is that <b>no permutation is transmitted</b> — the order need
 * not be recovered: no coordinates change, only a geometry's position <em>within</em> the buffer,
 * which the MLT spec allows (see {@link org.maplibre.mlt.research.encoding.Encoding#reordersGeometries()}
 * and {@code docs/results/reordering.md} for the earlier experiments that assumed otherwise). The
 * decoder does not reconstruct the original layout and is not asked to — it emits the order it
 * reads. What must be preserved is the <em>set</em> of geometries, which {@link
 * org.maplibre.mlt.research.encoding.GeometryEquivalence} checks.
 *
 * <p>Two constraints the greedy chain respects:
 *
 * <ul>
 *   <li>a multi-part feature's parts stay contiguous and in order, or the feature's geometry
 *       changes; {@link #PART_CHAIN} permutes a MultiLineString's parts only among themselves,
 *       which leaves the set of lines it describes unchanged;
 *   <li>a buffer whose topology does not yield usable part runs is returned unchanged, matching how
 *       {@link PartBoundaries} degrades rather than guessing at boundaries.
 * </ul>
 */
public final class GeometryReorder {

    /**
     * The identity: the buffer is passed through untouched.
     *
     * <p>This is what makes reordering an <em>optional</em> component of {@link
     * org.maplibre.mlt.research.encoding.VertexBufferCodec} rather than a separate wrapper class —
     * a codec always has a reorder, and one that does not reorder has this one. It also contributes
     * no name suffix, so an unreordered encoding's name is unchanged.
     */
    public static final GeometryReorder NONE = new GeometryReorder(Mode.NONE);

    /**
     * Greedy nearest-endpoint feature chaining, emitting every feature in its original direction.
     */
    public static final GeometryReorder FEATURE = new GeometryReorder(Mode.FEATURE);

    /**
     * Feature chaining that may also emit a feature backwards. Free for rendering, but it destroys
     * direction semantics (one-way streets, direction-dependent labelling), so it is a separate
     * variant rather than always on.
     */
    public static final GeometryReorder FEATURE_REV = new GeometryReorder(Mode.FEATURE_REV);

    /**
     * Keeps feature order, but starts every four-vertex polygon ring at the vertex that minimises
     * the zigzag bits of its start delta, two interior deltas and parallelogram-corner residual.
     * Rotation keeps winding, so ring orientation is unchanged; chosen greedily in buffer order.
     * Probe: {@code QuadRotationCli}.
     */
    public static final GeometryReorder RING_ROTATE = new GeometryReorder(Mode.RING_ROTATE);

    /**
     * Keeps feature order, but starts every polygon ring at its smallest vertex (lowest x, then
     * lowest y), so two translated copies of one shape start at the same corner and emit
     * byte-identical residual strings — repeats a general-purpose compressor's LZ77 stage can match,
     * where two different start corners give rotations of one string. Winding is kept, and needs no
     * canonicalizing: the MVT spec already fixes it per ring role.
     */
    public static final GeometryReorder RING_CANONICAL = new GeometryReorder(Mode.RING_CANONICAL);

    /**
     * {@link #RING_CANONICAL} first, then feature chaining over the rotated rings, so the chain
     * follows the endpoints that are actually emitted. Chaining first and rotating afterwards was
     * measured too and is slightly larger on the whole tileset (see {@code
     * docs/results/revisit-varint-split.md}).
     */
    public static final GeometryReorder RING_CANONICAL_THEN_FEATURE =
            new GeometryReorder(Mode.RING_CANONICAL_THEN_FEATURE);

    /**
     * Keeps feature order, but reorders the parts inside every MultiLineString so that each part
     * starts, where possible, at the vertex the previous part ended on: its start delta is then
     * {@code (0, 0)} and a continuation predictor can carry the previous part's last edge across the
     * join. Parts keep their direction. A MultiLineString's part order carries no rendering meaning,
     * so nothing is transmitted beyond the permuted part lengths.
     */
    public static final GeometryReorder PART_CHAIN = new GeometryReorder(Mode.PART_CHAIN);

    /**
     * {@link #PART_CHAIN} that may also reverse a part to join it, with {@link #FEATURE_REV}'s
     * caveat: a reversed part loses its direction semantics (one-way streets).
     */
    public static final GeometryReorder PART_CHAIN_REV = new GeometryReorder(Mode.PART_CHAIN_REV);

    /**
     * Reconstructs roads: line features are chained end to start, forward only, so a feature that
     * begins exactly where the previous one ended follows it directly. From the current exit the
     * next feature is an unused one starting at that exact position (the straightest if several),
     * otherwise the unused feature nearest the exit that no other remaining feature arrives at, so a
     * road is entered at its beginning. A MultiLineString keeps its part order; its entry is its
     * first part's first vertex, its exit its last part's last vertex. Polygon features follow the
     * lines in their original order. No feature is reversed, so direction semantics survive.
     * {@code docs/results/road-chain.md}.
     */
    public static final GeometryReorder ROAD_CHAIN = new GeometryReorder(Mode.ROAD_CHAIN);

    private enum Mode {
        NONE("", false),
        ROAD_CHAIN("_ROAD_CHAIN", false),
        FEATURE("_REORDER", false),
        FEATURE_REV("_REORDER_REV", true),
        RING_ROTATE("_RING_ROTATE", false),
        RING_CANONICAL("_RING_CANONICAL", false),
        RING_CANONICAL_THEN_FEATURE("_RING_CANONICAL_REORDER", false),
        PART_CHAIN("_PART_CHAIN", false),
        PART_CHAIN_REV("_PART_CHAIN_REV", true);

        private final String suffix;
        private final boolean allowReversal;

        Mode(String suffix, boolean allowReversal) {
            this.suffix = suffix;
            this.allowReversal = allowReversal;
        }
    }

    /**
     * {@code GeometryType} ordinals as persisted in the sidecar.
     */
    private static final int POINT = 0;

    private static final int POLYGON = 2;
    private static final int MULTIPOINT = 3;
    private static final int MULTILINESTRING = 4;
    private static final int MULTIPOLYGON = 5;

    private final Mode mode;

    private GeometryReorder(Mode mode) {
        this.mode = mode;
    }

    public String nameSuffix() {
        return mode.suffix;
    }

    public boolean reorders() {
        return mode != Mode.NONE;
    }

    /**
     * The topology streams of the reordered buffer, for a decoder that has not seen the vertices.
     *
     * <p>In a tile these streams are simply <em>read</em>: the encoder writes the reordered
     * {@code numParts}/{@code numRings}/{@code geometryTypes} into the tile and the decoder consumes
     * them, exactly as it consumes the unreordered ones today. Nothing about the ordering is derived
     * or signalled beyond the streams MLT already ships.
     *
     * <p>The benchmark, however, hands {@code decode} the <em>original</em> context, so this
     * reproduces what the decoder would have read. It is the one place the reordered streams are
     * recomputed rather than read, and it deliberately returns a context with <b>no vertices</b>: the
     * inner encoding derives its part boundaries from the streams alone, and passing the reordered
     * vertices here would hand a decoder the buffer it is supposed to be reconstructing.
     */
    public BufferContext topology(BufferContext ctx, int vertexCount) {
        final var reordered = apply(ctx);
        // Under NONE that is ctx itself, so the vertices are stripped here rather than passed on.
        return new BufferContext(
                List.of(),
                reordered.geometryTypes(),
                reordered.numGeometries(),
                reordered.numParts(),
                reordered.numRings());
    }

    /**
     * The reordered context, or {@code ctx} itself when the buffer cannot be reordered safely (no
     * derivable part runs, or topology streams that do not describe this buffer).
     */
    public BufferContext apply(BufferContext ctx) {
        if (mode == Mode.NONE) {
            return ctx;
        }
        if (mode == Mode.RING_CANONICAL_THEN_FEATURE) {
            return FEATURE.apply(RING_CANONICAL.apply(ctx));
        }
        final var vertices = ctx.vertices();
        final var starts = PartBoundaries.of(ctx, vertices.size());
        // A single whole-buffer run is PartBoundaries' documented "I cannot locate the boundaries"
        // answer, not a one-part buffer worth reordering.
        if (starts.length < 2) {
            return ctx;
        }
        if (mode == Mode.RING_ROTATE) {
            return rotateQuads(ctx, vertices, starts);
        }
        if (mode == Mode.RING_CANONICAL) {
            return canonicalRings(ctx, vertices, starts);
        }

        final var groups = featureGroups(ctx, starts.length);
        if (groups == null) {
            return ctx;
        }

        final var lengths = runLengths(starts, vertices.size());
        if (mode == Mode.PART_CHAIN || mode == Mode.PART_CHAIN_REV) {
            return chainParts(ctx, vertices, starts, lengths, groups);
        }
        if (mode == Mode.ROAD_CHAIN) {
            final var roads = roadChain(ctx, vertices, starts, lengths, groups);
            return roads == null ? ctx : rebuild(ctx, vertices, starts, lengths, groups, roads);
        }
        final var order = greedyChain(vertices, starts, lengths, groups);
        return rebuild(ctx, vertices, starts, lengths, groups, order);
    }

    /**
     * The emission order of the greedy feature chain ({@link #FEATURE}, {@link
     * #RING_CANONICAL_THEN_FEATURE}) as original feature indices, for a caller that has to permute
     * something besides the vertices (the property columns of a tile); {@code null} when {@link
     * #apply} would leave the buffer alone. Features are the entries of the geometry-type stream, in
     * the order the buffer holds them.
     */
    public int[] featureOrder(BufferContext ctx) {
        if (mode != Mode.FEATURE && mode != Mode.RING_CANONICAL_THEN_FEATURE) {
            throw new UnsupportedOperationException("no feature permutation for " + mode);
        }
        final var source = mode == Mode.RING_CANONICAL_THEN_FEATURE ? RING_CANONICAL.apply(ctx) : ctx;
        final var vertices = source.vertices();
        final var starts = PartBoundaries.of(source, vertices.size());
        if (starts.length < 2) {
            return null;
        }
        final var groups = featureGroups(source, starts.length);
        if (groups == null) {
            return null;
        }
        return greedyChain(vertices, starts, runLengths(starts, vertices.size()), groups).stream()
                .mapToInt(Placed::group)
                .toArray();
    }

    // --- road chaining ------------------------------------------------------

    /**
     * The emission order of {@link #ROAD_CHAIN}, or {@code null} when the buffer has no line
     * feature. Features are the unit; see {@link #ROAD_CHAIN} for the rules.
     */
    private List<Placed> roadChain(
            BufferContext ctx, List<Vertex> vertices, int[] starts, int[] lengths, List<int[]> groups) {
        final var n = groups.size();
        final var isLine = new boolean[n];
        var lineCount = 0;
        for (var f = 0; f < n; f++) {
            final var type = ctx.geometryTypes().get(f);
            isLine[f] = type != POLYGON && type != MULTIPOLYGON;
            if (isLine[f]) {
                lineCount++;
            }
        }
        if (lineCount == 0) {
            return null;
        }

        final var entry = new Vertex[n];
        final var exit = new Vertex[n];
        final var startingAt = new HashMap<Long, List<Integer>>();
        final var arriving = new HashMap<Long, Integer>();
        final var grid = new EndpointGrid(lineCount);
        for (var f = 0; f < n; f++) {
            if (!isLine[f]) {
                continue;
            }
            final var group = groups.get(f);
            entry[f] = first(vertices, starts, group[0]);
            exit[f] = last(vertices, starts, lengths, group[group.length - 1]);
            startingAt.computeIfAbsent(key(entry[f]), k -> new ArrayList<>()).add(f);
            arriving.merge(key(exit[f]), 1, Integer::sum);
            grid.add(f, false, entry[f]);
        }
        grid.build();

        // used: already emitted. blocked: used, or another remaining feature still arrives at its
        // entry, so it is not the beginning of a road. Restarts prefer unblocked features.
        final var used = new boolean[n];
        final var blocked = new boolean[n];
        for (var f = 0; f < n; f++) {
            blocked[f] = !isLine[f] || arriving.getOrDefault(key(entry[f]), 0) > 0;
            used[f] = !isLine[f];
        }

        final var order = new ArrayList<Placed>(n);
        Vertex at = null;
        Vertex before = null;
        for (var placed = 0; placed < lineCount; placed++) {
            var pick = -1;
            if (at != null) {
                final var predictedX = 2L * at.x() - before.x();
                final var predictedY = 2L * at.y() - before.y();
                var bestCost = Long.MAX_VALUE;
                for (final var candidate : startingAt.getOrDefault(key(at), List.of())) {
                    if (used[candidate]) {
                        continue;
                    }
                    final var second = second(vertices, starts, lengths, groups.get(candidate)[0], false);
                    final var cost = Math.abs(second.x() - predictedX) + Math.abs(second.y() - predictedY);
                    if (cost < bestCost) {
                        bestCost = cost;
                        pick = candidate;
                    }
                }
            }
            if (pick < 0) {
                final var cursorX = at == null ? 0 : at.x();
                final var cursorY = at == null ? 0 : at.y();
                var hit = grid.nearest(cursorX, cursorY, blocked);
                if (hit.feature() < 0) {
                    // Only closed loops and cycles remain: every entry still has an arrival.
                    hit = grid.nearest(cursorX, cursorY, used);
                }
                pick = hit.feature();
            }

            used[pick] = true;
            blocked[pick] = true;
            order.add(new Placed(pick, false));
            at = exit[pick];
            final var group = groups.get(pick);
            final var lastPart = group[group.length - 1];
            before = lengths[lastPart] < 2 ? at : vertices.get(starts[lastPart] + lengths[lastPart] - 2);

            final var endKey = key(exit[pick]);
            if (arriving.merge(endKey, -1, Integer::sum) == 0) {
                for (final var released : startingAt.getOrDefault(endKey, List.of())) {
                    if (!used[released]) {
                        blocked[released] = false;
                    }
                }
            }
        }
        for (var f = 0; f < n; f++) {
            if (!isLine[f]) {
                order.add(new Placed(f, false));
            }
        }
        return order;
    }

    // --- part chaining inside a MultiLineString -----------------------------

    /**
     * Emits every feature in place, the parts of each MultiLineString in {@link #partChain} order;
     * only the run-length stream ({@code numParts}, or {@code numRings} in a polygon column) changes,
     * permuted within each feature.
     */
    private BufferContext chainParts(
            BufferContext ctx, List<Vertex> vertices, int[] starts, int[] lengths, List<int[]> groups) {
        final var polygonColumn = containsPolygon(ctx.geometryTypes());
        final var out = new ArrayList<Vertex>(vertices.size());
        final var runs = new ArrayList<Integer>(starts.length);
        var cursor = new Vertex(0, 0);
        for (var f = 0; f < groups.size(); f++) {
            final var group = groups.get(f);
            final List<Placed> order;
            if (ctx.geometryTypes().get(f) == MULTILINESTRING && group.length >= 2) {
                order = partChain(vertices, starts, lengths, group, cursor);
            } else {
                order = new ArrayList<>(group.length);
                for (final var part : group) {
                    order.add(new Placed(part, false));
                }
            }
            for (final var placed : order) {
                final var from = starts[placed.group()];
                final var length = lengths[placed.group()];
                for (var v = 0; v < length; v++) {
                    out.add(vertices.get(from + (placed.reversed() ? length - 1 - v : v)));
                }
                runs.add(length);
            }
            cursor = out.get(out.size() - 1);
        }
        return new BufferContext(
                out,
                ctx.geometryTypes(),
                ctx.numGeometries(),
                polygonColumn ? ctx.numParts() : runs,
                polygonColumn ? runs : ctx.numRings());
    }

    /**
     * The emission order of one MultiLineString's parts ({@link Placed#group()} holds the part
     * index). From the current exit, the next part is an unused one entering at that exact position,
     * the one whose second vertex is nearest the previous edge continued (the residual a continuation
     * predictor pays across the join). With none, the chain restarts at the unused entry nearest the
     * exit, preferring entries a chain should start from: an odd remaining degree with reversal, more
     * remaining parts leaving than arriving without it. Ties keep the lower part index, forward
     * first.
     */
    private List<Placed> partChain(
            List<Vertex> vertices, int[] starts, int[] lengths, int[] group, Vertex cursor) {
        final var allowReversal = mode.allowReversal;
        final var m = group.length;
        final var leaving = new HashMap<Long, Integer>();
        final var arriving = new HashMap<Long, Integer>();
        // Every (part, orientation) entering at a position, encoded local * 2 + reversed.
        final var entering = new HashMap<Long, List<Integer>>();
        for (var local = 0; local < m; local++) {
            final var first = key(first(vertices, starts, group[local]));
            final var last = key(last(vertices, starts, lengths, group[local]));
            leaving.merge(first, 1, Integer::sum);
            arriving.merge(last, 1, Integer::sum);
            entering.computeIfAbsent(first, k -> new ArrayList<>()).add(local * 2);
            if (allowReversal) {
                entering.computeIfAbsent(last, k -> new ArrayList<>()).add(local * 2 + 1);
            }
        }

        final var used = new boolean[m];
        final var order = new ArrayList<Placed>(m);
        Vertex exit = cursor;
        Vertex beforeExit = null;
        for (var placed = 0; placed < m; placed++) {
            var pick = -1;
            if (beforeExit != null) {
                final var predictedX = 2L * exit.x() - beforeExit.x();
                final var predictedY = 2L * exit.y() - beforeExit.y();
                var bestCost = Long.MAX_VALUE;
                for (final var code : entering.getOrDefault(key(exit), List.of())) {
                    if (used[code >> 1]) {
                        continue;
                    }
                    final var second = second(vertices, starts, lengths, group[code >> 1], (code & 1) == 1);
                    final var cost = Math.abs(second.x() - predictedX) + Math.abs(second.y() - predictedY);
                    if (cost < bestCost) {
                        bestCost = cost;
                        pick = code;
                    }
                }
            }
            if (pick < 0) {
                pick = restart(vertices, starts, lengths, group, used, leaving, arriving, exit);
            }

            final var local = pick >> 1;
            final var reversed = (pick & 1) == 1;
            final var part = group[local];
            used[local] = true;
            leaving.merge(key(first(vertices, starts, part)), -1, Integer::sum);
            arriving.merge(key(last(vertices, starts, lengths, part)), -1, Integer::sum);
            order.add(new Placed(part, reversed));
            exit = reversed ? first(vertices, starts, part) : last(vertices, starts, lengths, part);
            beforeExit = lengths[part] < 2
                    ? exit
                    : vertices.get(reversed ? starts[part] + 1 : starts[part] + lengths[part] - 2);
        }
        return order;
    }

    /** The unused (part, orientation) a new chain starts from, encoded as in {@link #partChain}. */
    private int restart(
            List<Vertex> vertices,
            int[] starts,
            int[] lengths,
            int[] group,
            boolean[] used,
            Map<Long, Integer> leaving,
            Map<Long, Integer> arriving,
            Vertex exit) {
        var pick = -1;
        var pickPreferred = false;
        var pickDistance = Long.MAX_VALUE;
        for (var local = 0; local < group.length; local++) {
            if (used[local]) {
                continue;
            }
            for (var r = 0; r < (mode.allowReversal ? 2 : 1); r++) {
                final var entry = r == 1
                        ? last(vertices, starts, lengths, group[local])
                        : first(vertices, starts, group[local]);
                final var position = key(entry);
                final var out = leaving.getOrDefault(position, 0);
                final var in = arriving.getOrDefault(position, 0);
                final var preferred = mode.allowReversal ? (out + in) % 2 == 1 : out > in;
                final var distance =
                        Math.abs((long) entry.x() - exit.x()) + Math.abs((long) entry.y() - exit.y());
                if (pick < 0
                        || (preferred && !pickPreferred)
                        || (preferred == pickPreferred && distance < pickDistance)) {
                    pick = local * 2 + r;
                    pickPreferred = preferred;
                    pickDistance = distance;
                }
            }
        }
        return pick;
    }

    private static Vertex first(List<Vertex> vertices, int[] starts, int part) {
        return vertices.get(starts[part]);
    }

    private static Vertex last(List<Vertex> vertices, int[] starts, int[] lengths, int part) {
        return vertices.get(starts[part] + lengths[part] - 1);
    }

    /** The vertex after the entry, in the given orientation; the entry itself for a 1-vertex part. */
    private static Vertex second(List<Vertex> vertices, int[] starts, int[] lengths, int part, boolean reversed) {
        if (lengths[part] < 2) {
            return vertices.get(starts[part]);
        }
        return vertices.get(reversed ? starts[part] + lengths[part] - 2 : starts[part] + 1);
    }

    private static long key(Vertex vertex) {
        return ((long) vertex.x() << 32) | (vertex.y() & 0xFFFFFFFFL);
    }

    // --- ring rotation ------------------------------------------------------

    /**
     * Rotates each four-vertex polygon ring in place; line runs and topology streams are untouched.
     */
    private static BufferContext rotateQuads(BufferContext ctx, List<Vertex> vertices, int[] starts) {
        final var isLine = PartBoundaries.lineRuns(ctx, starts.length);
        if (isLine == null) {
            return ctx;
        }
        final var rotated = new ArrayList<>(vertices);
        var prev = new Vertex(0, 0);
        for (var i = 0; i < starts.length; i++) {
            final var start = starts[i];
            final var end = i + 1 < starts.length ? starts[i + 1] : vertices.size();
            if (!isLine[i] && end - start == 4) {
                var best = 0;
                var bestCost = Integer.MAX_VALUE;
                for (var k = 0; k < 4; k++) {
                    final var cost = quadCost(vertices, start, k, prev);
                    if (cost < bestCost) {
                        bestCost = cost;
                        best = k;
                    }
                }
                for (var v = 0; v < 4; v++) {
                    rotated.set(start + v, vertices.get(start + (best + v) % 4));
                }
            }
            prev = rotated.get(end - 1);
        }
        return new BufferContext(
                rotated, ctx.geometryTypes(), ctx.numGeometries(), ctx.numParts(), ctx.numRings());
    }

    /**
     * Rotates every polygon ring in place to start at its smallest vertex; line runs and topology
     * streams are untouched. Ties (a repeated smallest position) keep the first occurrence.
     */
    private static BufferContext canonicalRings(BufferContext ctx, List<Vertex> vertices, int[] starts) {
        final var isLine = PartBoundaries.lineRuns(ctx, starts.length);
        if (isLine == null) {
            return ctx;
        }
        final var rotated = new ArrayList<>(vertices);
        for (var i = 0; i < starts.length; i++) {
            final var start = starts[i];
            final var end = i + 1 < starts.length ? starts[i + 1] : vertices.size();
            final var n = end - start;
            if (isLine[i] || n < 3) {
                continue;
            }
            var min = 0;
            for (var k = 1; k < n; k++) {
                final var candidate = vertices.get(start + k);
                final var best = vertices.get(start + min);
                if (candidate.x() < best.x() || (candidate.x() == best.x() && candidate.y() < best.y())) {
                    min = k;
                }
            }
            for (var v = 0; v < n; v++) {
                rotated.set(start + v, vertices.get(start + (min + v) % n));
            }
        }
        return new BufferContext(
                rotated, ctx.geometryTypes(), ctx.numGeometries(), ctx.numParts(), ctx.numRings());
    }

    /**
     * Zigzag bits of the quad decoded from rotation {@code k}: start delta, two interior deltas,
     * parallelogram-corner residual.
     */
    private static int quadCost(List<Vertex> vertices, int start, int k, Vertex prev) {
        final var a = vertices.get(start + k);
        final var b = vertices.get(start + (k + 1) % 4);
        final var c = vertices.get(start + (k + 2) % 4);
        final var d = vertices.get(start + (k + 3) % 4);
        return bits(a.x() - prev.x()) + bits(a.y() - prev.y())
                + bits(b.x() - a.x()) + bits(b.y() - a.y())
                + bits(c.x() - b.x()) + bits(c.y() - b.y())
                + bits(d.x() - (a.x() + c.x() - b.x())) + bits(d.y() - (a.y() + c.y() - b.y()));
    }

    private static int bits(int value) {
        return 32 - Integer.numberOfLeadingZeros(EncodingUtils.encodeZigZag(value));
    }

    // --- feature grouping ---------------------------------------------------

    /**
     * Part indices grouped by owning feature, or {@code null} if the topology streams do not line up
     * with the derived runs — in which case the grouping would be a guess and nothing is reordered.
     *
     * <p>Only the Multi* types consume a {@code numGeometries} entry; a single-part type owns exactly
     * one run. In a polygon column the runs are rings, and {@code numParts} counts the rings of each
     * polygon, so a feature there owns as many runs as its polygons have rings.
     */
    public static List<int[]> featureGroups(BufferContext ctx, int partCount) {
        final var polygonColumn = containsPolygon(ctx.geometryTypes());
        final var groups = new ArrayList<int[]>();
        var part = 0;
        var geometryCursor = 0;
        var partCursor = 0;
        for (final var type : ctx.geometryTypes()) {
            // Points occupy a vertex but contribute no run, so their extent cannot be located and the
            // whole column is left alone.
            if (type == POINT || type == MULTIPOINT) {
                return null;
            }

            var runs = 0;
            if (polygonColumn && (type == POLYGON || type == MULTIPOLYGON)) {
                final int polygons;
                if (type == MULTIPOLYGON) {
                    polygons = multiCount(ctx, geometryCursor++);
                    if (polygons < 0) {
                        return null;
                    }
                } else {
                    polygons = 1;
                }
                for (var p = 0; p < polygons; p++) {
                    if (partCursor >= ctx.numParts().size()) {
                        return null;
                    }
                    runs += ctx.numParts().get(partCursor++);
                }
            } else {
                // A LineString contributes one run; a MultiLineString one per child. In a polygon column
                // those runs live in numRings, but the count is the same either way.
                runs = type == MULTILINESTRING ? multiCount(ctx, geometryCursor++) : 1;
                if (runs < 0) {
                    return null;
                }
            }

            if (runs <= 0 || part + runs > partCount) {
                return null;
            }
            final var group = new int[runs];
            for (var i = 0; i < runs; i++) {
                group[i] = part + i;
            }
            groups.add(group);
            part += runs;
        }
        return part == partCount ? groups : null;
    }

    private static int multiCount(BufferContext ctx, int cursor) {
        return cursor < ctx.numGeometries().size() ? ctx.numGeometries().get(cursor) : -1;
    }

    private static boolean containsPolygon(List<Integer> geometryTypes) {
        for (final var type : geometryTypes) {
            if (type == POLYGON || type == MULTIPOLYGON) {
                return true;
            }
        }
        return false;
    }

    // --- the chain ----------------------------------------------------------

    /**
     * One feature's placement in the emitted order.
     */
    private record Placed(int group, boolean reversed) {
    }

    /**
     * Greedily takes the unused feature whose entry endpoint is nearest the cursor, then advances the
     * cursor to that feature's exit endpoint.
     *
     * <p>Manhattan distance rather than Euclidean because the cost being minimised is a componentwise
     * zigzag residual: {@code |dx| + |dy|} is what the two delta cursors actually pay.
     *
     * <p>The nearest unused endpoint is found through a {@link EndpointGrid uniform grid} rather than
     * by scanning every candidate. A linear scan makes the chain quadratic in the feature count,
     * which is not merely slow on this corpus but unusable: buffers here reach ~65.000 vertices, and
     * the largest are exactly the ones a reordering experiment most wants to measure. The grid
     * returns the same answer — ties included — because it widens its search ring until no unscanned
     * cell could hold anything closer.
     */
    private List<Placed> greedyChain(
            List<Vertex> vertices, int[] starts, int[] lengths, List<int[]> groups) {
        // Precomputed once: looking a part's endpoints up inside the selection loop would make this
        // O(features * parts) instead of O(features).
        final var firstVertex = new Vertex[starts.length];
        final var lastVertex = new Vertex[starts.length];
        for (var p = 0; p < starts.length; p++) {
            firstVertex[p] = vertices.get(starts[p]);
            lastVertex[p] = vertices.get(starts[p] + lengths[p] - 1);
        }

        // Each feature contributes its entry endpoint, and its exit endpoint too when reversal may
        // pick it up; both map back to the same feature index.
        final var allowReversal = mode.allowReversal;
        final var grid = new EndpointGrid(groups.size() * (allowReversal ? 2 : 1));
        for (var g = 0; g < groups.size(); g++) {
            final var group = groups.get(g);
            grid.add(g, false, firstVertex[group[0]]);
            if (allowReversal) {
                grid.add(g, true, lastVertex[group[group.length - 1]]);
            }
        }
        grid.build();

        final var used = new boolean[groups.size()];
        final var order = new ArrayList<Placed>(groups.size());
        var cursorX = 0;
        var cursorY = 0;
        for (var placed = 0; placed < groups.size(); placed++) {
            final var hit = grid.nearest(cursorX, cursorY, used);
            final var best = hit.feature();
            used[best] = true;
            order.add(new Placed(best, hit.reversed()));
            final var group = groups.get(best);
            final var exit = hit.reversed() ? firstVertex[group[0]] : lastVertex[group[group.length - 1]];
            cursorX = exit.x();
            cursorY = exit.y();
        }
        return order;
    }

    // --- rebuilding the context ---------------------------------------------

    /**
     * Emits vertices and topology streams in the new order. The streams are permuted with the
     * features, so {@link PartBoundaries} derives the same run lengths from the result — deriving
     * them wrongly is silent, which is why they are rebuilt here rather than carried over.
     */
    private BufferContext rebuild(
            BufferContext ctx,
            List<Vertex> vertices,
            int[] starts,
            int[] lengths,
            List<int[]> groups,
            List<Placed> order) {

        final var polygonColumn = containsPolygon(ctx.geometryTypes());
        final var reordered = new ArrayList<Vertex>(vertices.size());
        final var geometryTypes = new ArrayList<Integer>(ctx.geometryTypes().size());
        final var numGeometries = new ArrayList<Integer>(ctx.numGeometries().size());
        final var numParts = new ArrayList<Integer>(ctx.numParts().size());
        final var numRings = new ArrayList<Integer>(ctx.numRings().size());

        // Per-feature cursors into the original numGeometries/numParts streams, so each feature's
        // entries can be copied in the new order.
        final var geometryAt = new int[groups.size()];
        final var partAt = new int[groups.size()];
        final var partRun = new int[groups.size()];
        indexStreams(ctx, groups, polygonColumn, geometryAt, partAt, partRun);

        for (final var placed : order) {
            final var feature = placed.group();
            final var group = groups.get(feature);
            geometryTypes.add(ctx.geometryTypes().get(feature));

            if (geometryAt[feature] >= 0) {
                numGeometries.add(ctx.numGeometries().get(geometryAt[feature]));
            }
            for (var i = 0; i < partRun[feature]; i++) {
                numParts.add(ctx.numParts().get(partAt[feature] + i));
            }

            // Parts of a multi-part feature stay contiguous and in order; reversing the feature walks
            // them backwards so the geometry it describes is unchanged.
            for (var i = 0; i < group.length; i++) {
                final var part = placed.reversed() ? group[group.length - 1 - i] : group[i];
                final var from = starts[part];
                final var length = lengths[part];
                for (var v = 0; v < length; v++) {
                    final var offset = placed.reversed() ? length - 1 - v : v;
                    reordered.add(vertices.get(from + offset));
                }
                if (polygonColumn) {
                    numRings.add(length);
                } else {
                    numParts.add(length);
                }
            }
        }

        return new BufferContext(reordered, geometryTypes, numGeometries, numParts, numRings);
    }

    /**
     * Records, per feature, which {@code numGeometries} entry it owns ({@code -1} for none) and which
     * span of {@code numParts} entries — in a polygon column that stream holds ring counts, which are
     * distinct from the run lengths and must travel with their feature.
     */
    private static void indexStreams(
            BufferContext ctx,
            List<int[]> groups,
            boolean polygonColumn,
            int[] geometryAt,
            int[] partAt,
            int[] partRun) {
        var geometryCursor = 0;
        var partCursor = 0;
        for (var f = 0; f < groups.size(); f++) {
            final var type = ctx.geometryTypes().get(f);
            final var multi = type == MULTILINESTRING || type == MULTIPOLYGON;
            geometryAt[f] = multi ? geometryCursor++ : -1;

            partAt[f] = partCursor;
            if (polygonColumn && (type == POLYGON || type == MULTIPOLYGON)) {
                final var polygons =
                        type == MULTIPOLYGON ? ctx.numGeometries().get(geometryAt[f]) : 1;
                partRun[f] = polygons;
                partCursor += polygons;
            } else {
                partRun[f] = 0;
            }
        }
    }

    /**
     * Vertex count of each run, from its start offsets.
     */
    private static int[] runLengths(int[] starts, int vertexCount) {
        final var lengths = new int[starts.length];
        for (var i = 0; i < starts.length; i++) {
            lengths[i] = (i + 1 < starts.length ? starts[i + 1] : vertexCount) - starts[i];
        }
        return lengths;
    }

    // --- nearest-endpoint index --------------------------------------------

    /**
     * A uniform grid over candidate endpoints, answering "nearest unused endpoint to (x,y)" in
     * Manhattan distance.
     *
     * <p>It exists purely to keep {@link #greedyChain} out of quadratic time; it does not change
     * which feature the chain picks. Cells are sized so the average occupancy is a small constant,
     * and the search expands ring by ring around the cursor cell, stopping only once the best
     * distance found is no greater than the closest possible distance to any unvisited ring — so a
     * nearer endpoint cannot be sitting in a cell that was skipped.
     *
     * <p>Stored as parallel arrays in CSR form rather than per-cell lists: this runs over every
     * buffer in the corpus, and the buffers that matter carry tens of thousands of endpoints.
     */
    private static final class EndpointGrid {

        /**
         * Target average endpoints per cell — small enough that a hit cell is cheap to scan.
         */
        private static final int TARGET_PER_CELL = 2;

        private final int[] feature;
        private final boolean[] reversed;
        private final int[] pointX;
        private final int[] pointY;
        private int size;

        private int minX = Integer.MAX_VALUE;
        private int minY = Integer.MAX_VALUE;
        private int maxX = Integer.MIN_VALUE;
        private int maxY = Integer.MIN_VALUE;

        private int cols;
        private int rows;
        private int cellSize;
        private int[] cellStart;
        private int[] cellItems;

        EndpointGrid(int capacity) {
            feature = new int[capacity];
            reversed = new boolean[capacity];
            pointX = new int[capacity];
            pointY = new int[capacity];
        }

        void add(int featureIndex, boolean isReversed, Vertex vertex) {
            feature[size] = featureIndex;
            reversed[size] = isReversed;
            pointX[size] = vertex.x();
            pointY[size] = vertex.y();
            minX = Math.min(minX, vertex.x());
            minY = Math.min(minY, vertex.y());
            maxX = Math.max(maxX, vertex.x());
            maxY = Math.max(maxY, vertex.y());
            size++;
        }

        void build() {
            final var spanX = (long) maxX - minX + 1;
            final var spanY = (long) maxY - minY + 1;
            // One cell per TARGET_PER_CELL endpoints, as a square over the bounding box.
            final var target = Math.max(1, size / TARGET_PER_CELL);
            final var side = Math.max(1.0, Math.sqrt((double) spanX * spanY / target));
            cellSize = (int) Math.min(Integer.MAX_VALUE, Math.max(1, (long) Math.ceil(side)));
            cols = (int) Math.max(1, (spanX + cellSize - 1) / cellSize);
            rows = (int) Math.max(1, (spanY + cellSize - 1) / cellSize);

            final var counts = new int[cols * rows + 1];
            for (var i = 0; i < size; i++) {
                counts[cellOf(pointX[i], pointY[i]) + 1]++;
            }
            for (var c = 0; c < cols * rows; c++) {
                counts[c + 1] += counts[c];
            }
            cellStart = counts;
            cellItems = new int[size];
            final var cursor = counts.clone();
            for (var i = 0; i < size; i++) {
                cellItems[cursor[cellOf(pointX[i], pointY[i])]++] = i;
            }
        }

        private int cellOf(int x, int y) {
            final var cx = Math.min(cols - 1, (x - minX) / cellSize);
            final var cy = Math.min(rows - 1, (y - minY) / cellSize);
            return cy * cols + cx;
        }

        /**
         * The chosen endpoint: which feature, and whether it was entered from its far end.
         */
        record Hit(int feature, boolean reversed) {
        }

        Hit nearest(int x, int y, boolean[] used) {
            final var originX = Math.min(cols - 1, Math.max(0, (int) (((long) x - minX) / cellSize)));
            final var originY = Math.min(rows - 1, Math.max(0, (int) (((long) y - minY) / cellSize)));

            var bestDistance = Long.MAX_VALUE;
            var bestFeature = -1;
            var bestReversed = false;

            final var maxRing = Math.max(cols, rows);
            for (var ring = 0; ring <= maxRing; ring++) {
                // Everything outside the already-scanned area is at least (ring-1)*cellSize away. The
                // bound is >, not >=: a cell whose closest possible point ties the current best may still
                // hold a tied endpoint with a lower feature index, and the tie-break below must see it.
                if (bestFeature >= 0 && (long) (ring - 1) * cellSize > bestDistance) {
                    break;
                }
                for (var cy = originY - ring; cy <= originY + ring; cy++) {
                    if (cy < 0 || cy >= rows) {
                        continue;
                    }
                    final var edgeRow = cy == originY - ring || cy == originY + ring;
                    for (var cx = originX - ring; cx <= originX + ring; cx++) {
                        if (cx < 0 || cx >= cols) {
                            continue;
                        }
                        // Interior cells were covered by an earlier ring.
                        if (!edgeRow && cx != originX - ring && cx != originX + ring) {
                            continue;
                        }
                        final var cell = cy * cols + cx;
                        for (var s = cellStart[cell]; s < cellStart[cell + 1]; s++) {
                            final var i = cellItems[s];
                            if (used[feature[i]]) {
                                continue;
                            }
                            final var d =
                                    Math.abs((long) pointX[i] - x) + Math.abs((long) pointY[i] - y);
                            // Ties break on the lower feature index, and on the forward endpoint within a
                            // feature — what a first-wins linear scan yields. Without this the grid's cell
                            // order would decide, so the same corpus could chain differently between runs and
                            // the measured byte totals would not be reproducible.
                            final boolean better;
                            if (d != bestDistance) {
                                better = d < bestDistance;
                            } else if (feature[i] != bestFeature) {
                                better = feature[i] < bestFeature;
                            } else {
                                better = bestReversed && !reversed[i];
                            }
                            if (better) {
                                bestDistance = d;
                                bestFeature = feature[i];
                                bestReversed = reversed[i];
                            }
                        }
                    }
                }
            }
            return new Hit(bestFeature, bestReversed);
        }
    }
}
