package org.maplibre.mlt.research.encoding;

import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;

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
public final class PartBoundaries {

    private PartBoundaries() {
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

    private static final class GeometryTypeOrdinal {
        private static final int POLYGON = 2;
        private static final int MULTIPOLYGON = 5;

        private GeometryTypeOrdinal() {
        }
    }
}
