package org.maplibre.mlt.research.pipeline.mechanism;

import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.ClassifiedPredictor;
import org.maplibre.mlt.research.encoding.PartBoundaries;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;

import java.util.List;

/**
 * Edge-continuation prediction ({@code p[i-1] + (p[i-1] - p[i-2])}) gated by geometry type and run
 * length: applied only to the 3rd+ vertex of a LineString/MultiLineString run that has at least
 * {@value #MIN_LINE_RUN_LENGTH} vertices, never to polygon rings, points, or short line runs.
 *
 * <p>Continuation is a linear-geometry effect, not specific to any one layer or feature class: it
 * costs +11.09% mean residual bits on POLYGON but -3.58% on LINESTRING (measured corpus-wide, all
 * layers). Within LINESTRING it is also length-dependent — 2- and 3-vertex runs (most of
 * {@code transportation}) gain nothing or regress, while runs of 11-50 vertices gain 7-8%; see
 * {@code docs/results/line-continuation.md}. Both gates need nothing signalled — {@code
 * geometryTypes} and run length (via {@link PartBoundaries}) are topology streams MLT ships in the
 * tile, so they are derivable identically on encode and decode, the same rule {@link
 * PartBoundaries} exists to enforce for part boundaries.
 *
 * <p>Three classes, packed like {@link SegmentQuadCorner}:
 *
 * <ol>
 *   <li><b>part starts</b> — the first vertex of each run, plain delta.
 *   <li><b>line interior</b> — the 3rd+ vertex of a LineString/MultiLineString run of at least
 *       {@value #MIN_LINE_RUN_LENGTH} vertices, predicted by continuation.
 *   <li><b>interior</b> — every other vertex (2nd vertex of any run, every vertex of a line run
 *       shorter than {@value #MIN_LINE_RUN_LENGTH}, and all polygon/point interior vertices), plain
 *       delta.
 * </ol>
 *
 * <p>Measured: {@code docs/results/line-continuation.md}.
 */
public final class LineContinuation implements ClassifiedPredictor {

    // INTERIOR is 0 so the default-zeroed class array already means "interior".
    static final int INTERIOR = 0;
    static final int START = 1;
    static final int LINE_INTERIOR = 2;

    // Below this run length, continuation measured a regression (2 verts: no interior vertex
    // exists; 3 verts: +1,13% mean bits; 4-5 verts: -0,2% Munich but +9,7% Chicago) — see
    // docs/results/line-continuation.md. Runs shorter than this fall back to plain delta on every
    // vertex, exactly like a non-line run.
    static final int MIN_LINE_RUN_LENGTH = 6;

    private final int minLineRunLength;

    public LineContinuation() {
        this(MIN_LINE_RUN_LENGTH);
    }

    /**
     * A different length gate: road chaining measures it over roads, whose length is not a feature's
     * ({@code docs/results/road-chain.md}). The default gate is the one every other row uses.
     */
    public LineContinuation(int minLineRunLength) {
        this.minLineRunLength = minLineRunLength;
    }

    @Override
    public String name() {
        return "LINE_CONTINUATION";
    }

    @Override
    public int classCount() {
        return 3;
    }

    @Override
    public int[] regionOrder() {
        return new int[]{START, INTERIOR, LINE_INTERIOR};
    }

    @Override
    public int[] classify(BufferContext ctx, int vertexCount) {
        final var starts = PartBoundaries.of(ctx, vertexCount);
        final var classes = new int[vertexCount];
        final var isLineRun = PartBoundaries.lineRuns(ctx, starts.length);
        if (isLineRun == null) {
            // Topology does not line up with the derived runs (e.g. points in the column) — degrade
            // to marking only part starts, exactly as PartBoundaries' own honest-fallback rule.
            for (final var start : starts) {
                if (start < vertexCount) {
                    classes[start] = START;
                }
            }
            return classes;
        }
        for (var r = 0; r < starts.length; r++) {
            final var start = starts[r];
            final var end = r + 1 < starts.length ? starts[r + 1] : vertexCount;
            if (start < vertexCount) {
                classes[start] = START;
            }
            if (isLineRun[r] && end - start >= minLineRunLength) {
                for (var i = start + 2; i < end; i++) {
                    classes[i] = LINE_INTERIOR;
                }
            }
        }
        return classes;
    }

    @Override
    public boolean hasPrediction(int vertexClass) {
        return vertexClass == LINE_INTERIOR;
    }

    @Override
    public int predictX(int vertexClass, int i, List<Vertex> vertices) {
        return 2 * vertices.get(i - 1).x() - vertices.get(i - 2).x();
    }

    @Override
    public int predictY(int vertexClass, int i, List<Vertex> vertices) {
        return 2 * vertices.get(i - 1).y() - vertices.get(i - 2).y();
    }
}
