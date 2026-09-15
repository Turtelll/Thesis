package org.maplibre.mlt.research.encoding;

import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * The one place the strict round-trip contract is weakened, and deliberately the only one.
 *
 * <p>{@link Encoding} requires {@code decode} to reproduce the vertex buffer exactly, and {@code
 * BenchmarkCli} enforces it on every buffer. A reordering pre-pass cannot satisfy that: it emits
 * the same geometries in a different order on purpose. The MLT spec permits precisely that and no
 * more ({@code docs/specification.md}):
 *
 * <blockquote>
 * Feature order is not necessarily a stable round-trip property. [...] MLT encoders may preserve
 * order, but they may also sort features by id or spatial locality to improve compression when that
 * optimization is enabled.
 * </blockquote>
 *
 * <p>So the property that must still hold is that the buffer describes <em>the same set of
 * geometries</em>: same coordinates, same part structure, nothing dropped, nothing duplicated,
 * nothing silently deformed. This class is that check, kept separate from {@link Encoding} so the
 * weaker contract cannot be applied to an encoding by accident — an encoding that merely permuted
 * its output would pass this and must not.
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

        final var counts = new HashMap<List<Vertex>, Integer>();
        for (final var part : before) {
            counts.merge(canonical(part), 1, Integer::sum);
        }
        for (final var part : after) {
            final var key = canonical(part);
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
     * A part and its reversal share one key, so reversal compares equal while any other permutation
     * of the same vertices does not.
     */
    private static List<Vertex> canonical(List<Vertex> part) {
        final var reversed = new ArrayList<>(part);
        java.util.Collections.reverse(reversed);
        return compare(part, reversed) <= 0 ? part : List.copyOf(reversed);
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
