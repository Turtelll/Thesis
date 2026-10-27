package org.maplibre.mlt.research.encoding;

import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The contract of an {@link Encoding#mergesFeatures() feature-merging} encoding, the one place the
 * line feature boundaries may be lost. Weaker than {@link GeometryEquivalence} (the original parts
 * need not exist as parts any more) but stronger than "same edges": the original line parts must
 * be recoverable from the merged buffer by cutting it at vertex indices, because that is what
 * linear referencing will do later.
 *
 * <p>Holds when
 *
 * <ul>
 *   <li>every merged line part can be tiled, left to right, by original line parts, each piece
 *       starting at the vertex the previous one ended on (no reversal), and every original line
 *       part is used exactly once. This implies the multiset of directed edges is unchanged and
 *       that the vertices removed equal the parts removed;
 *   <li>the polygon rings are the same multiset ({@link GeometryEquivalence#holdsRings}).
 * </ul>
 *
 * <p>Tiling is a per-part backtracking search over parts sharing a start vertex; the original
 * parts are consumed in merged-part order, so an adversarial buffer of duplicated parts could be
 * rejected although another assignment exists. Merging by chaining never produces one.
 */
public final class MergeEquivalence {

    private MergeEquivalence() {
    }

    public static boolean holds(BufferContext original, BufferContext merged) {
        return describe(original, merged) == null;
    }

    /**
     * {@code null} when {@link #holds}, otherwise the reason.
     */
    public static String describe(BufferContext original, BufferContext merged) {
        final var before = lineParts(original);
        final var after = lineParts(merged);
        if (before == null && after == null) {
            // A column with points has no derivable runs: nothing can have been merged.
            return GeometryEquivalence.holds(original, merged)
                    ? null
                    : "the buffer changed although its runs are not derivable";
        }
        if (before == null || after == null) {
            return "part runs are not derivable from the topology streams";
        }
        if (!GeometryEquivalence.holdsRings(original, merged)) {
            return "the polygon rings differ";
        }
        var originalVertices = 0;
        for (final var part : before) {
            originalVertices += part.size();
        }
        var mergedVertices = 0;
        for (final var part : after) {
            mergedVertices += part.size();
        }
        if (originalVertices - mergedVertices != before.size() - after.size()) {
            return String.format(
                    "vertices removed (%d) differ from parts removed (%d)",
                    originalVertices - mergedVertices, before.size() - after.size());
        }

        // Original parts by multiset, indexed by their first vertex for the tiling search.
        final var remaining = new HashMap<List<Vertex>, Integer>();
        final var byStart = new HashMap<Long, List<List<Vertex>>>();
        for (final var part : before) {
            if (remaining.merge(part, 1, Integer::sum) == 1) {
                byStart.computeIfAbsent(key(part.get(0)), k -> new ArrayList<>()).add(part);
            }
        }
        for (var m = 0; m < after.size(); m++) {
            if (!tile(after.get(m), 0, remaining, byStart)) {
                return String.format(
                        "merged line part %d (%d vertices) is not a vertex-aligned sequence of original parts",
                        m, after.get(m).size());
            }
        }
        for (final var left : remaining.values()) {
            if (left != 0) {
                return "an original line part is missing from the merged buffer";
            }
        }
        return null;
    }

    /**
     * Whether {@code part[at..]} can be tiled by unused original parts, consuming them.
     */
    private static boolean tile(
            List<Vertex> part, int at, Map<List<Vertex>, Integer> remaining, Map<Long, List<List<Vertex>>> byStart) {
        for (final var piece : byStart.getOrDefault(key(part.get(at)), List.of())) {
            final var last = at + piece.size() - 1;
            if (remaining.get(piece) == 0 || last >= part.size() || !part.subList(at, last + 1).equals(piece)) {
                continue;
            }
            // A one-vertex original part advances nothing; it can only be a merged part by itself.
            if (piece.size() == 1 && part.size() != 1) {
                continue;
            }
            remaining.merge(piece, -1, Integer::sum);
            if (last == part.size() - 1 || tile(part, last, remaining, byStart)) {
                return true;
            }
            remaining.merge(piece, 1, Integer::sum);
        }
        return false;
    }

    /**
     * The line parts of a buffer in order, or {@code null} when the runs are not derivable.
     */
    private static List<List<Vertex>> lineParts(BufferContext ctx) {
        final var vertices = ctx.vertices();
        final var starts = PartBoundaries.of(ctx, vertices.size());
        final var isLine = PartBoundaries.lineRuns(ctx, starts.length);
        if (isLine == null) {
            return null;
        }
        final var parts = new ArrayList<List<Vertex>>();
        for (var r = 0; r < starts.length; r++) {
            if (!isLine[r]) {
                continue;
            }
            final var end = r + 1 < starts.length ? starts[r + 1] : vertices.size();
            parts.add(List.copyOf(vertices.subList(starts[r], end)));
        }
        return parts;
    }

    private static long key(Vertex vertex) {
        return ((long) vertex.x() << 32) | (vertex.y() & 0xFFFFFFFFL);
    }
}
