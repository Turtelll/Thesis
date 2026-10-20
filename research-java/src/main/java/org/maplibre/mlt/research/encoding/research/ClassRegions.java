package org.maplibre.mlt.research.encoding.research;

import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.decoder.DecodingUtils;
import org.maplibre.mlt.research.encoding.ClassifiedPredictor;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link ClassifiedPredictor}'s residuals as one stream per vertex class, with no opinion on how
 * the streams become bytes — that is each encoding's own decision. Componentwise zigzag residual
 * against the predictor's (x, y); a class without a prediction is first-order delta against the
 * previous vertex, as in {@link org.maplibre.mlt.research.encoding.PredictedTransform}.
 */
final class ClassRegions {

    private ClassRegions() {
    }

    /**
     * Values (two per vertex) per class, from the topology streams alone — what a decoder knows
     * before it reads a residual.
     */
    static int[] counts(ClassifiedPredictor predictor, BufferContext ctx, int vertexCount) {
        final var counts = new int[predictor.classCount()];
        for (final var c : predictor.classify(ctx, vertexCount)) {
            counts[c] += 2;
        }
        return counts;
    }

    /**
     * The residual stream of each class, {@code x, y} interleaved, in buffer order.
     */
    static int[][] split(ClassifiedPredictor predictor, BufferContext ctx) {
        final var vertices = ctx.vertices();
        final var classes = predictor.classify(ctx, vertices.size());
        final var regions = new int[predictor.classCount()][];
        final var counts = counts(predictor, ctx, vertices.size());
        for (var c = 0; c < regions.length; c++) {
            regions[c] = new int[counts[c]];
        }

        final var cursors = new int[regions.length];
        var prevX = 0;
        var prevY = 0;
        for (var i = 0; i < vertices.size(); i++) {
            final var vertex = vertices.get(i);
            final var vertexClass = classes[i];
            final int predX;
            final int predY;
            if (predictor.hasPrediction(vertexClass)) {
                predX = predictor.predictX(vertexClass, i, vertices);
                predY = predictor.predictY(vertexClass, i, vertices);
            } else {
                predX = prevX;
                predY = prevY;
            }
            final var region = regions[vertexClass];
            region[cursors[vertexClass]++] = EncodingUtils.encodeZigZag(vertex.x() - predX);
            region[cursors[vertexClass]++] = EncodingUtils.encodeZigZag(vertex.y() - predY);
            prevX = vertex.x();
            prevY = vertex.y();
        }
        return regions;
    }

    /**
     * The inverse of {@link #split}. {@link BufferContext#vertices()} is not read.
     */
    static List<Vertex> join(ClassifiedPredictor predictor, int[][] regions, BufferContext ctx, int vertexCount) {
        final var classes = predictor.classify(ctx, vertexCount);
        final var cursors = new int[regions.length];
        final var vertices = new ArrayList<Vertex>(vertexCount);
        var prevX = 0;
        var prevY = 0;
        for (var i = 0; i < vertexCount; i++) {
            final var vertexClass = classes[i];
            final int predX;
            final int predY;
            if (predictor.hasPrediction(vertexClass)) {
                predX = predictor.predictX(vertexClass, i, vertices);
                predY = predictor.predictY(vertexClass, i, vertices);
            } else {
                predX = prevX;
                predY = prevY;
            }
            final var region = regions[vertexClass];
            final var x = predX + DecodingUtils.decodeZigZag(region[cursors[vertexClass]++]);
            final var y = predY + DecodingUtils.decodeZigZag(region[cursors[vertexClass]++]);
            vertices.add(new Vertex(x, y));
            prevX = x;
            prevY = y;
        }
        return vertices;
    }

    /**
     * The regions back to back, in {@code order} (a permutation of the classes).
     */
    static int[] concat(int[][] regions, int[] order) {
        var length = 0;
        for (final var region : regions) {
            length += region.length;
        }
        final var values = new int[length];
        var offset = 0;
        for (final var c : order) {
            System.arraycopy(regions[c], 0, values, offset, regions[c].length);
            offset += regions[c].length;
        }
        return values;
    }

    /**
     * The inverse of {@link #concat}, given each class's value count.
     */
    static int[][] slice(int[] values, int[] counts, int[] order) {
        final var regions = new int[counts.length][];
        var offset = 0;
        for (final var c : order) {
            regions[c] = new int[counts[c]];
            System.arraycopy(values, offset, regions[c], 0, counts[c]);
            offset += counts[c];
        }
        return regions;
    }
}
