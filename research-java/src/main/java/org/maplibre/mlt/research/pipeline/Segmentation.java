package org.maplibre.mlt.research.pipeline;

import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoder.prediction.mechanism.ClassifiedPredictor;
import org.maplibre.mlt.research.buffer.PartBoundaries;
import org.maplibre.mlt.research.buffer.Encoding.BufferContext;
import org.maplibre.mlt.research.encoder.reference.SharedVertexRef;
import org.maplibre.mlt.research.encoder.prediction.Prediction;

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
     * number of values in each stream (two per vertex). A {@link Prediction.SideValues} predictor adds
     * one stream after the residual streams, {@code sideStream}, with one value per vertex that
     * carries a side value; {@code -1} when the predictor has none.
     */
    public record Layout(int[] streamOf, int[] classes, int[] valueCounts, int sideStream) {

        public int streamCount() {
            return valueCounts.length;
        }

        /** This layout without the vertices that are references: no residual pair each. */
        public Layout withoutReferenced(boolean[] referenced) {
            final var counts = valueCounts.clone();
            for (var i = 0; i < referenced.length; i++) {
                if (referenced[i]) {
                    counts[streamOf[i]] -= 2;
                }
            }
            return new Layout(streamOf, classes, counts, sideStream);
        }

        /** This layout with the stream lengths a decoder read from the buffer. */
        public Layout withCounts(int[] counts) {
            return new Layout(streamOf, classes, counts, sideStream);
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
        if (!(predictor instanceof Prediction.SideValues sided)) {
            final var counts = new int[streamCount];
            for (final var stream : streamOf) {
                counts[stream] += 2;
            }
            return new Layout(streamOf, classes, counts, -1);
        }
        final var counts = new int[streamCount + 1];
        for (var i = 0; i < vertexCount; i++) {
            counts[streamOf[i]] += 2;
            if (sided.hasSide(classes[i])) {
                counts[streamCount]++;
            }
        }
        return new Layout(streamOf, classes, counts, streamCount);
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
        return split(layout, predictor, vertices, null);
    }

    /**
     * {@link #split(Layout, ClassifiedPredictor, List)} where the vertices marked in {@code referenced}
     * (shared-vertex references, {@code null} for none) write no residual: {@code layout} must be
     * {@link Layout#withoutReferenced}. They still count as decoded for the prediction and the frame.
     */
    public static int[][] split(
            Layout layout, ClassifiedPredictor predictor, List<Vertex> vertices, boolean[] referenced) {
        final var streams = new int[layout.streamCount()][];
        for (var s = 0; s < streams.length; s++) {
            streams[s] = new int[layout.valueCounts()[s]];
        }
        final var cursors = new int[streams.length];
        final var sided = predictor instanceof Prediction.SideValues s ? s : null;
        final var pass = predictor instanceof Prediction.FramedEdges f ? f.pass() : null;
        var prevX = 0;
        var prevY = 0;
        for (var i = 0; i < vertices.size(); i++) {
            final var vertex = vertices.get(i);
            if (referenced != null && referenced[i]) {
                if (pass != null) {
                    pass.accept(i, vertices);
                }
                prevX = vertex.x();
                prevY = vertex.y();
                continue;
            }
            final var vertexClass = layout.classes()[i];
            final int predX;
            final int predY;
            if (sided != null && sided.hasSide(vertexClass)) {
                final var side = sided.side(vertexClass, i, vertices);
                streams[layout.sideStream()][cursors[layout.sideStream()]++] = side;
                predX = sided.predictX(vertexClass, i, vertices, side);
                predY = sided.predictY(vertexClass, i, vertices, side);
            } else {
                final var predicted = predictor.hasPrediction(vertexClass);
                predX = predicted ? predictor.predictX(vertexClass, i, vertices) : prevX;
                predY = predicted ? predictor.predictY(vertexClass, i, vertices) : prevY;
            }
            final var stream = layout.streamOf()[i];
            var rx = vertex.x() - predX;
            var ry = vertex.y() - predY;
            if (pass != null) {
                if (pass.framed(i)) {
                    pass.forward(rx, ry);
                    rx = pass.x();
                    ry = pass.y();
                }
                pass.accept(i, vertices);
            }
            streams[stream][cursors[stream]++] = rx;
            streams[stream][cursors[stream]++] = ry;
            prevX = vertex.x();
            prevY = vertex.y();
        }
        return streams;
    }

    /** The inverse of {@link #split}; reads only the streams, the layout and what it has rebuilt. */
    public static List<Vertex> join(Layout layout, ClassifiedPredictor predictor, int[][] streams, int vertexCount) {
        return join(layout, predictor, streams, vertexCount, null);
    }

    /**
     * {@link #join(Layout, ClassifiedPredictor, int[][], int)} where {@code walk} (shared-vertex
     * references, {@code null} for none) says which vertices are copied from an earlier ring instead
     * of read from the streams; {@code layout} then carries the stream lengths written.
     */
    public static List<Vertex> join(
            Layout layout, ClassifiedPredictor predictor, int[][] streams, int vertexCount,
            SharedVertexRef.ReferenceWalk walk) {
        final var cursors = new int[streams.length];
        final var vertices = new ArrayList<Vertex>(vertexCount);
        final var sided = predictor instanceof Prediction.SideValues s ? s : null;
        final var pass = predictor instanceof Prediction.FramedEdges f ? f.pass() : null;
        var prevX = 0;
        var prevY = 0;
        for (var i = 0; i < vertexCount; i++) {
            final var shared = walk == null ? null : walk.decode(i, vertices);
            if (shared != null) {
                vertices.add(shared);
                if (pass != null) {
                    pass.accept(i, vertices);
                }
                walk.accept(i, vertices);
                prevX = shared.x();
                prevY = shared.y();
                continue;
            }
            final var vertexClass = layout.classes()[i];
            final int predX;
            final int predY;
            if (sided != null && sided.hasSide(vertexClass)) {
                final var side = streams[layout.sideStream()][cursors[layout.sideStream()]++];
                predX = sided.predictX(vertexClass, i, vertices, side);
                predY = sided.predictY(vertexClass, i, vertices, side);
            } else {
                final var predicted = predictor.hasPrediction(vertexClass);
                predX = predicted ? predictor.predictX(vertexClass, i, vertices) : prevX;
                predY = predicted ? predictor.predictY(vertexClass, i, vertices) : prevY;
            }
            final var stream = layout.streamOf()[i];
            var rx = streams[stream][cursors[stream]++];
            var ry = streams[stream][cursors[stream]++];
            if (pass != null && pass.framed(i)) {
                pass.inverse(rx, ry);
                rx = pass.x();
                ry = pass.y();
            }
            final var x = predX + rx;
            final var y = predY + ry;
            vertices.add(new Vertex(x, y));
            if (pass != null) {
                pass.accept(i, vertices);
            }
            if (walk != null) {
                walk.accept(i, vertices);
            }
            prevX = x;
            prevY = y;
        }
        return vertices;
    }
}
