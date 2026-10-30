package org.maplibre.mlt.research.buffer;

import org.maplibre.mlt.research.buffer.Encoding.BufferContext;

import java.util.List;

/**
 * Derives the vertex-run boundaries of a buffer from the topology streams alone.
 *
 * <p>This is the one piece every structural encoding here shares, and it exists as its own class
 * because deriving it wrongly is silent: a transform and its inverse that disagree about where a
 * part begins still round-trip on point-only columns and still produce plausible geometry
 * elsewhere. Both halves call this, so they cannot disagree.
 *
 * <p>The mapping is not uniform across columns — it is the extractor's rule, which the spec text
 * does not state (see {@code VertexBufferExtractor}):
 *
 * <ul>
 *   <li>a column containing any (Multi)Polygon puts vertex-counts-per-ring in {@code numRings},
 *       while {@code numParts} counts rings per polygon;
 *   <li>a column without polygons puts vertex-counts-per-linestring in {@code numParts};
 *   <li>points contribute to neither stream, so a run boundary cannot be derived for them.
 * </ul>
 *
 * <p>When the derived runs do not cover the buffer exactly, {@link #of} returns a single run
 * spanning the whole buffer. That is the honest fallback: a transform that cannot locate the
 * boundaries must degrade to the continuous baseline rather than guess at them.
 */
public final class VertexRuns {

    private VertexRuns() {
    }

    /**
     * Start offsets of each vertex run, ascending, always beginning with 0. A buffer whose topology
     * yields no usable runs gives {@code [0]} — one run covering everything.
     */
    public static int[] of(BufferContext ctx, int vertexCount) {
        if (vertexCount <= 0) {
            return new int[]{0};
        }
        final var lengths = vertexRunLengths(ctx);
        if (lengths.isEmpty()) {
            return new int[]{0};
        }

        final var starts = new int[lengths.size()];
        var offset = 0;
        for (var i = 0; i < lengths.size(); i++) {
            final int length = lengths.get(i);
            if (length <= 0 || offset > vertexCount) {
                return new int[]{0};
            }
            starts[i] = offset;
            offset += length;
        }
        // Runs that do not tile the buffer exactly mean the stream is not what we assumed it is.
        return offset == vertexCount ? starts : new int[]{0};
    }

    private static List<Integer> vertexRunLengths(BufferContext ctx) {
        return containsPolygon(ctx.geometryTypes()) ? ctx.numRings() : ctx.numParts();
    }

    private static boolean containsPolygon(List<Integer> geometryTypes) {
        for (var type : geometryTypes) {
            if (type == GeometryTypeOrdinal.POLYGON || type == GeometryTypeOrdinal.MULTIPOLYGON) {
                return true;
            }
        }
        return false;
    }

    /**
     * Per run (in {@link #of} order), whether it is a LineString/MultiLineString part rather than a
     * polygon ring or point — or {@code null} if the topology streams do not line up with the runs
     * and the mapping would be a guess.
     *
     * <p>Adapted from {@code GeometryReorder}'s {@code featureGroups}: only the Multi* types consume
     * a {@code numGeometries} entry, and in a polygon column a polygon feature owns as many runs as
     * {@code numParts} says it has rings, which is why this cannot be read off {@link
     * BufferContext#geometryTypes()} alone.
     */
    public static boolean[] lineRuns(BufferContext ctx, int runCount) {
        final var polygonColumn = containsPolygon(ctx.geometryTypes());
        final var isLine = new boolean[runCount];
        var run = 0;
        var geometryCursor = 0;
        var partCursor = 0;
        for (final var type : ctx.geometryTypes()) {
            if (type == GeometryTypeOrdinal.POINT || type == GeometryTypeOrdinal.MULTIPOINT) {
                return null;
            }

            var runs = 0;
            var line = false;
            if (polygonColumn
                    && (type == GeometryTypeOrdinal.POLYGON || type == GeometryTypeOrdinal.MULTIPOLYGON)) {
                final int polygons;
                if (type == GeometryTypeOrdinal.MULTIPOLYGON) {
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
                // A LineString contributes one run; a MultiLineString one per child. In a polygon
                // column those runs live in numRings, but the count is the same either way.
                runs = type == GeometryTypeOrdinal.MULTILINESTRING ? multiCount(ctx, geometryCursor++) : 1;
                if (runs < 0) {
                    return null;
                }
                line = true;
            }

            if (runs <= 0 || run + runs > runCount) {
                return null;
            }
            for (var i = 0; i < runs; i++) {
                isLine[run + i] = line;
            }
            run += runs;
        }
        return run == runCount ? isLine : null;
    }

    private static int multiCount(BufferContext ctx, int cursor) {
        return cursor < ctx.numGeometries().size() ? ctx.numGeometries().get(cursor) : -1;
    }

    private static final class GeometryTypeOrdinal {
        private static final int POINT = 0;
        private static final int LINESTRING = 1;
        private static final int POLYGON = 2;
        private static final int MULTIPOINT = 3;
        private static final int MULTILINESTRING = 4;
        private static final int MULTIPOLYGON = 5;

        private GeometryTypeOrdinal() {
        }
    }
}
