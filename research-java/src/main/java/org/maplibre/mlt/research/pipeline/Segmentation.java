package org.maplibre.mlt.research.pipeline;

import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.ClassifiedPredictor;
import org.maplibre.mlt.research.encoding.PartBoundaries;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * S3: segmentation. Assigns every vertex to one residual stream, as a pure function of the topology
 * streams (decoded before the vertex buffer) and the predictor's classes, so no segment boundary or
 * length is ever signalled. The residuals of one stream are written contiguously.
 *
 * <p>The delta chain itself is unaffected: residuals are always taken against the previous vertex
 * of the buffer (or the class's geometric prediction); segmentation only decides which stream a
 * residual is written to.
 */
public enum Segmentation {

    /** One stream, buffer order. */
    FLAT,

    /**
     * One stream per predictor class, in the predictor's region order: the old pipeline's
     * {@code ClassRegions} layout.
     */
    CLASS,

    /**
     * One stream per vertex role: {@code jump} (first vertex of a run: ring, line part or point),
     * {@code edge} (second vertex of a run), {@code body} (the rest).
     */
    ROLE,

    /** {@link #ROLE}, with the body split by predictor class (corners, line interiors, ...). */
    ROLE_CLASS;

    static final int JUMP = 0;
    static final int EDGE = 1;
    static final int BODY = 2;

    /**
     * Which stream each vertex's residual pair goes to, the predictor class of each vertex, and the
     * number of values (two per vertex) in each stream.
     */
    public record Layout(int[] streamOf, int[] classes, int[] valueCounts) {

        public int streamCount() {
            return valueCounts.length;
        }
    }

    /** The layout of a buffer of {@code vertexCount} vertices; {@code topo} needs no vertices. */
    public Layout layout(BufferContext topo, ClassifiedPredictor predictor, int vertexCount) {
        final var classes = predictor.classify(topo, vertexCount);
        final var streamOf = new int[vertexCount];
        final int streamCount = switch (this) {
            case FLAT -> 1;
            case CLASS -> {
                final var position = new int[predictor.classCount()];
                final var order = predictor.regionOrder();
                for (var i = 0; i < order.length; i++) {
                    position[order[i]] = i;
                }
                for (var i = 0; i < vertexCount; i++) {
                    streamOf[i] = position[classes[i]];
                }
                yield predictor.classCount();
            }
            case ROLE -> {
                System.arraycopy(roles(topo, vertexCount), 0, streamOf, 0, vertexCount);
                yield 3;
            }
            case ROLE_CLASS -> {
                final var roles = roles(topo, vertexCount);
                for (var i = 0; i < vertexCount; i++) {
                    if (roles[i] == BODY) {
                        streamOf[i] = classes[i] < 2 ? BODY : classes[i] + 1;
                    } else {
                        streamOf[i] = roles[i];
                    }
                }
                yield Math.max(3, predictor.classCount() + 1);
            }
        };
        final var counts = new int[streamCount];
        for (final var stream : streamOf) {
            counts[stream] += 2;
        }
        return new Layout(streamOf, classes, counts);
    }

    /**
     * The role of every vertex ({@link #JUMP}, {@link #EDGE}, {@link #BODY}) from the topology
     * streams. A column of Points and MultiPoints has no run structure: every vertex is a jump. A
     * buffer whose runs cannot be derived is one run.
     */
    static int[] roles(BufferContext topo, int vertexCount) {
        final var roles = new int[vertexCount];
        Arrays.fill(roles, BODY);
        if (vertexCount == 0) {
            return roles;
        }
        final var starts = PartBoundaries.of(topo, vertexCount);
        if (starts.length == 1 && !topo.geometryTypes().isEmpty()
                && topo.geometryTypes().stream().allMatch(t -> t == 0 || t == 3)) {
            Arrays.fill(roles, JUMP);
            return roles;
        }
        for (var r = 0; r < starts.length; r++) {
            final var end = r + 1 < starts.length ? starts[r + 1] : vertexCount;
            roles[starts[r]] = JUMP;
            if (end - starts[r] > 1) {
                roles[starts[r] + 1] = EDGE;
            }
        }
        return roles;
    }

    /**
     * The signed residual of every vertex against its prediction, one interleaved {@code x, y} array
     * per stream, in buffer order within a stream.
     */
    public static int[][] split(Layout layout, ClassifiedPredictor predictor, List<Vertex> vertices) {
        final var streams = new int[layout.streamCount()][];
        for (var s = 0; s < streams.length; s++) {
            streams[s] = new int[layout.valueCounts()[s]];
        }
        final var cursors = new int[streams.length];
        var prevX = 0;
        var prevY = 0;
        for (var i = 0; i < vertices.size(); i++) {
            final var vertex = vertices.get(i);
            final var vertexClass = layout.classes()[i];
            final var predicted = predictor.hasPrediction(vertexClass);
            final var predX = predicted ? predictor.predictX(vertexClass, i, vertices) : prevX;
            final var predY = predicted ? predictor.predictY(vertexClass, i, vertices) : prevY;
            final var stream = layout.streamOf()[i];
            streams[stream][cursors[stream]++] = vertex.x() - predX;
            streams[stream][cursors[stream]++] = vertex.y() - predY;
            prevX = vertex.x();
            prevY = vertex.y();
        }
        return streams;
    }

    /** The inverse of {@link #split}; reads only the streams, the layout and what it has rebuilt. */
    public static List<Vertex> join(Layout layout, ClassifiedPredictor predictor, int[][] streams, int vertexCount) {
        final var cursors = new int[streams.length];
        final var vertices = new ArrayList<Vertex>(vertexCount);
        var prevX = 0;
        var prevY = 0;
        for (var i = 0; i < vertexCount; i++) {
            final var vertexClass = layout.classes()[i];
            final var predicted = predictor.hasPrediction(vertexClass);
            final var predX = predicted ? predictor.predictX(vertexClass, i, vertices) : prevX;
            final var predY = predicted ? predictor.predictY(vertexClass, i, vertices) : prevY;
            final var stream = layout.streamOf()[i];
            final var x = predX + streams[stream][cursors[stream]++];
            final var y = predY + streams[stream][cursors[stream]++];
            vertices.add(new Vertex(x, y));
            prevX = x;
            prevY = y;
        }
        return vertices;
    }
}
