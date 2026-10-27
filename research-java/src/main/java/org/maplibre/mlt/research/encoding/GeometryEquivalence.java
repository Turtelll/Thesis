package org.maplibre.mlt.research.encoding;

import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * The one place the strict round-trip contract is weakened, and deliberately the only one — see
 * {@link Encoding#reordersGeometries()} for what the MLT spec permits. The property that must
 * still hold: the buffer describes <em>the same set of geometries</em> — same coordinates, same
 * part structure, nothing dropped, nothing duplicated, nothing silently deformed. Kept separate
 * from {@link Encoding} so the weaker contract cannot be applied to an encoding by accident — one
 * that merely permuted its output would pass this and must not.
 *
 * <p>Two failure modes it exists to catch, both silent otherwise:
 *
 * <ul>
 *   <li>a geometry dropped or emitted twice — the multiset comparison catches it, a count
 *       comparison would not;
 *   <li>a part whose vertices were permuted internally. Only whole-part <em>reversal</em> is a
 *       legitimate rewrite (a LineString reversed is the same LineString for rendering), so parts
 *       are compared up to reversal and any other permutation fails.
 * </ul>
 */
public final class GeometryEquivalence {

    private GeometryEquivalence() {
    }

    /**
     * Whether both contexts describe the same multiset of parts, each part compared up to reversal.
     *
     * <p>Compares parts rather than whole features because a part is the unit the topology streams
     * actually delimit; a feature's parts stay contiguous under {@code GeometryReorder}, and that
     * grouping is pinned by its own test rather than re-derived here.
     */
    public static boolean holds(BufferContext original, BufferContext reordered) {
        if (original.vertices().size() != reordered.vertices().size()) {
            return false;
        }
        final var before = parts(original);
        final var after = parts(reordered);
        if (before == null || after == null || before.size() != after.size()) {
            return false;
        }

        return sameParts(original, before, reordered, after);
    }

    private static boolean sameParts(
            BufferContext original,
            List<List<Vertex>> before,
            BufferContext reordered,
            List<List<Vertex>> after) {
        final var ringsBefore = ringRuns(original, before.size());
        final var ringsAfter = ringRuns(reordered, after.size());
        final var counts = new HashMap<List<Vertex>, Integer>();
        for (var i = 0; i < before.size(); i++) {
            counts.merge(canonical(before.get(i), ringsBefore[i]), 1, Integer::sum);
        }
        for (var i = 0; i < after.size(); i++) {
            final var key = canonical(after.get(i), ringsAfter[i]);
            final var remaining = counts.get(key);
            if (remaining == null) {
                return false;
            }
            if (remaining == 1) {
                counts.remove(key);
            } else {
                counts.put(key, remaining - 1);
            }
        }
        return counts.isEmpty();
    }

    /**
     * A human-readable reason for a failure, for the message {@code BenchmarkCli} throws.
     */
    public static String describe(BufferContext original, BufferContext reordered) {
        if (original.vertices().size() != reordered.vertices().size()) {
            return String.format(
                    "vertex count changed: %d -> %d",
                    original.vertices().size(), reordered.vertices().size());
        }
        final var before = parts(original);
        final var after = parts(reordered);
        if (before == null || after == null) {
            return "part runs are not derivable from the topology streams";
        }
        if (before.size() != after.size()) {
            return String.format("part count changed: %d -> %d", before.size(), after.size());
        }
        return "the multiset of parts differs: a geometry was dropped, duplicated or deformed";
    }

    /**
     * The parts of a buffer, as vertex lists, or {@code null} if the runs are not derivable.
     */
    private static List<List<Vertex>> parts(BufferContext ctx) {
        final var vertices = ctx.vertices();
        final var starts = PartBoundaries.of(ctx, vertices.size());
        final var parts = new ArrayList<List<Vertex>>(starts.length);
        for (var i = 0; i < starts.length; i++) {
            final var end = i + 1 < starts.length ? starts[i + 1] : vertices.size();
            if (starts[i] > end || end > vertices.size()) {
                return null;
            }
            parts.add(List.copyOf(vertices.subList(starts[i], end)));
        }
        return parts;
    }

    /**
     * Per part, whether it is a polygon ring; all {@code false} (strict) if not derivable.
     */
    private static boolean[] ringRuns(BufferContext ctx, int runCount) {
        final var isLine = PartBoundaries.lineRuns(ctx, runCount);
        final var isRing = new boolean[runCount];
        if (isLine != null) {
            for (var i = 0; i < runCount; i++) {
                isRing[i] = !isLine[i];
            }
        }
        return isRing;
    }

    /**
     * A part and its reversal share one key, so reversal compares equal while any other permutation
     * of the same vertices does not. A polygon ring additionally shares its key with every cyclic
     * rotation — a ring has no distinguished start vertex, so rotating it is not a deformation.
     */
    private static List<Vertex> canonical(List<Vertex> part, boolean ring) {
        final var reversed = new ArrayList<>(part);
        java.util.Collections.reverse(reversed);
        if (!ring) {
            return compare(part, reversed) <= 0 ? part : List.copyOf(reversed);
        }
        final var best = minRotation(part);
        final var bestReversed = minRotation(reversed);
        return compare(best, bestReversed) <= 0 ? best : bestReversed;
    }

    /**
     * The lexicographically smallest cyclic rotation; only rotations starting at the minimal vertex
     * are candidates, so this stays near-linear.
     */
    private static List<Vertex> minRotation(List<Vertex> part) {
        final var min = part.get(indexOfMin(part));
        List<Vertex> best = null;
        for (var s = 0; s < part.size(); s++) {
            if (compareVertex(part.get(s), min) != 0) {
                continue;
            }
            final var candidate = new ArrayList<Vertex>(part.size());
            candidate.addAll(part.subList(s, part.size()));
            candidate.addAll(part.subList(0, s));
            if (best == null || compare(candidate, best) < 0) {
                best = candidate;
            }
        }
        return best == null ? part : List.copyOf(best);
    }

    private static int indexOfMin(List<Vertex> part) {
        var min = 0;
        for (var i = 1; i < part.size(); i++) {
            if (compareVertex(part.get(i), part.get(min)) < 0) {
                min = i;
            }
        }
        return min;
    }

    private static int compareVertex(Vertex left, Vertex right) {
        return left.x() != right.x() ? Integer.compare(left.x(), right.x()) : Integer.compare(left.y(), right.y());
    }

    private static int compare(List<Vertex> a, List<Vertex> b) {
        for (var i = 0; i < a.size(); i++) {
            final var left = a.get(i);
            final var right = b.get(i);
            if (left.x() != right.x()) {
                return Integer.compare(left.x(), right.x());
            }
            if (left.y() != right.y()) {
                return Integer.compare(left.y(), right.y());
            }
        }
        return 0;
    }
}
