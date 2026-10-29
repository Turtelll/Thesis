package org.maplibre.mlt.research.pipeline;

import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.Encoding;
import org.maplibre.mlt.research.encoding.GeometryEquivalence;
import org.maplibre.mlt.research.encoding.Encoding.BufferContext;
import org.maplibre.mlt.research.encoding.Encoding.EncodedBuffer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/**
 * The lossless-round-trip gate every pipeline row passes on every buffer. The old {@link
 * GeometryEquivalence} compares parts, and a Point column has no parts (every vertex is one run of
 * the whole buffer), so it cannot tell a reordered point column from a corrupted one. Here a
 * Point/MultiPoint column is compared as the multiset of its features instead (the points of a\n * MultiPoint as a set, their order has no meaning); every other column
 * is delegated to {@link GeometryEquivalence} unchanged.
 */
public final class Verification {

    private Verification() {
    }

    private static final int POINT = 0;
    private static final int MULTIPOINT = 3;

    /** Whether {@code emitted} describes exactly the geometries of {@code original}, in any order. */
    public static boolean holds(BufferContext original, BufferContext emitted) {
        if (isPointColumn(original) && isPointColumn(emitted)) {
            final var counts = new HashMap<List<Object>, Integer>();
            final var before = pointFeatures(original);
            final var after = pointFeatures(emitted);
            if (before == null || after == null || before.size() != after.size()) {
                return false;
            }
            for (final var feature : before) {
                counts.merge(feature, 1, Integer::sum);
            }
            for (final var feature : after) {
                final var remaining = counts.get(feature);
                if (remaining == null) {
                    return false;
                }
                if (remaining == 1) {
                    counts.remove(feature);
                } else {
                    counts.put(feature, remaining - 1);
                }
            }
            return counts.isEmpty();
        }
        return GeometryEquivalence.holds(original, emitted);
    }

    /** The reason {@link #holds} failed, for an error message. */
    public static String describe(BufferContext original, BufferContext emitted) {
        return isPointColumn(original)
                ? "the multiset of point features differs: a point was dropped, duplicated or changed"
                : GeometryEquivalence.describe(original, emitted);
    }

    /**
     * Decodes what was just encoded and fails if it does not reconstruct the buffer: exactly for a
     * row that keeps feature order, and for a reordering row as "decodes to the buffer it emitted"
     * plus {@link #holds} against the extraction.
     *
     * @param where the buffer's identity, for the message
     */
    public static void roundTrip(String where, BufferContext ctx, Encoding encoding, EncodedBuffer encoded) {
        final var decoded = encoding.decode(encoded, ctx, ctx.vertices().size());
        if (encoding.reordersGeometries()) {
            final var emitted = encoding.emitted(ctx);
            if (!emitted.vertices().equals(decoded)) {
                throw new IllegalStateException(encoding.name() + " does not decode to the buffer it emitted on " + where);
            }
            if (!holds(ctx, emitted)) {
                throw new IllegalStateException(
                        encoding.name() + " did not preserve the geometry of " + where + ": " + describe(ctx, emitted));
            }
            return;
        }
        if (!ctx.vertices().equals(decoded)) {
            throw new IllegalStateException(encoding.name() + " is not lossless on " + where);
        }
    }

    private static boolean isPointColumn(BufferContext ctx) {
        if (ctx.geometryTypes().isEmpty()) {
            return false;
        }
        for (final var type : ctx.geometryTypes()) {
            if (type != POINT && type != MULTIPOINT) {
                return false;
            }
        }
        return true;
    }

    /** Each feature as {@code (type, vertices)}, or {@code null} if the streams do not add up. */
    private static List<List<Object>> pointFeatures(BufferContext ctx) {
        final var features = new ArrayList<List<Object>>();
        var vertex = 0;
        var geometry = 0;
        for (final var type : ctx.geometryTypes()) {
            var count = 1;
            if (type == MULTIPOINT) {
                if (geometry >= ctx.numGeometries().size()) {
                    return null;
                }
                count = ctx.numGeometries().get(geometry++);
            }
            if (count < 1 || vertex + count > ctx.vertices().size()) {
                return null;
            }
            final var members = new ArrayList<>(ctx.vertices().subList(vertex, vertex + count));
            if (type == MULTIPOINT) {
                // The order of a MultiPoint's points carries no meaning: compare them as a set.
                members.sort(Comparator.comparingInt(Vertex::x).thenComparingInt(Vertex::y));
            }
            final List<Vertex> points = List.copyOf(members);
            features.add(List.of(type, points));
            vertex += count;
        }
        return vertex == ctx.vertices().size() ? features : null;
    }
}
