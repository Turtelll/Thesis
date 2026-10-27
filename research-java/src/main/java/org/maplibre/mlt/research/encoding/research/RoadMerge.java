package org.maplibre.mlt.research.encoding.research;

import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.research.SharedVertexRef.Runs;

import java.util.ArrayList;
import java.util.List;

/**
 * Roads out of line parts that meet end to start: which parts join, the vertex stream with the
 * duplicated junction vertices removed, and the topology of the result.
 *
 * <p>A part <em>joins</em> when the previous run is a line run too and the part starts at that
 * run's last vertex. A <em>road</em> is a maximal sequence of joined parts. Both the lossless
 * {@link RoadChainEncoding} (joins signalled by a bitmap, feature boundaries kept in the topology
 * streams) and the lossy {@link RoadMergeEncoding} (features fused, nothing to signal) classify
 * and predict over the reduced stream, so a continuation predictor carries the incoming edge
 * across what used to be a feature boundary.
 */
final class RoadMerge {

    private static final int LINESTRING = 1;
    private static final int POLYGON = 2;
    private static final int MULTILINESTRING = 4;
    private static final int MULTIPOLYGON = 5;

    private RoadMerge() {
    }

    /**
     * The reduced stream and its topology. {@code ctx} has the merged features (one per road chain
     * of features, one part per road) and the reduced vertices, which are empty when built from the
     * topology alone.
     */
    record Merged(BufferContext ctx, int vertexCount) {
    }

    /**
     * Whether a join bit exists for run {@code r}: a line run directly after a line run.
     */
    static boolean hasBit(Runs runs, int r) {
        return r > 0 && runs.isLine()[r] && runs.isLine()[r - 1];
    }

    /**
     * Per run, whether it starts at the previous line run's last vertex.
     */
    static boolean[] detectJoins(List<Vertex> vertices, Runs runs) {
        final var joined = new boolean[runs.count()];
        for (var r = 1; r < runs.count(); r++) {
            if (hasBit(runs, r)) {
                joined[r] = vertices.get(runs.starts()[r]).equals(vertices.get(runs.end(r - 1) - 1));
            }
        }
        return joined;
    }

    static int joinCount(boolean[] joined) {
        var count = 0;
        for (final var j : joined) {
            if (j) {
                count++;
            }
        }
        return count;
    }

    /**
     * The vertices without the first vertex of every joined run.
     */
    static List<Vertex> reduce(List<Vertex> vertices, Runs runs, boolean[] joined) {
        final var reduced = new ArrayList<Vertex>(vertices.size());
        for (var r = 0; r < runs.count(); r++) {
            reduced.addAll(vertices.subList(runs.starts()[r] + (joined[r] ? 1 : 0), runs.end(r)));
        }
        return reduced;
    }

    /**
     * The inverse of {@link #reduce}: a joined run's first vertex repeats the previous vertex.
     */
    static List<Vertex> expand(List<Vertex> reduced, Runs runs, boolean[] joined) {
        final var vertices = new ArrayList<Vertex>(runs.vertexCount());
        var cursor = 0;
        for (var r = 0; r < runs.count(); r++) {
            var length = runs.end(r) - runs.starts()[r];
            if (joined[r]) {
                vertices.add(vertices.get(vertices.size() - 1));
                length--;
            }
            vertices.addAll(reduced.subList(cursor, cursor + length));
            cursor += length;
        }
        return vertices;
    }

    /**
     * The buffer with every road one part and every chain of joined features one feature, around
     * {@code vertices} (pass {@code List.of()} for the topology alone), or {@code null} when the
     * features cannot be located from the streams.
     *
     * <p>A feature whose first part is joined continues the previous feature; a merged feature with
     * one road is a LineString, with more a MultiLineString. Polygon features are copied.
     */
    static Merged merge(BufferContext ctx, Runs runs, boolean[] joined, List<Vertex> vertices) {
        final var groups = GeometryReorder.featureGroups(ctx, runs.count());
        if (groups == null) {
            return null;
        }
        final var polygonColumn = ctx.geometryTypes().stream().anyMatch(t -> t == POLYGON || t == MULTIPOLYGON);

        final var types = new ArrayList<Integer>();
        final var numGeometries = new ArrayList<Integer>();
        final var numParts = new ArrayList<Integer>();
        final var runLengths = new ArrayList<Integer>();
        var geometryCursor = 0;
        var partCursor = 0;
        var openRoads = 0; // roads in the open merged line feature, 0 when none is open
        var vertexCount = 0;

        for (var f = 0; f < groups.size(); f++) {
            final var group = groups.get(f);
            final var type = ctx.geometryTypes().get(f);
            final var multi = type == MULTILINESTRING || type == MULTIPOLYGON;
            final var geometries = multi ? ctx.numGeometries().get(geometryCursor++) : -1;

            if (type == POLYGON || type == MULTIPOLYGON) {
                openRoads = close(types, numGeometries, openRoads);
                types.add(type);
                if (type == MULTIPOLYGON) {
                    numGeometries.add(geometries);
                }
                if (polygonColumn) {
                    final var polygons = type == MULTIPOLYGON ? geometries : 1;
                    for (var p = 0; p < polygons; p++) {
                        numParts.add(ctx.numParts().get(partCursor++));
                    }
                }
                for (final var run : group) {
                    runLengths.add(runs.end(run) - runs.starts()[run]);
                    vertexCount += runs.end(run) - runs.starts()[run];
                }
                continue;
            }

            if (!joined[group[0]]) {
                openRoads = close(types, numGeometries, openRoads);
            }
            for (final var run : group) {
                final var length = runs.end(run) - runs.starts()[run];
                if (joined[run]) {
                    runLengths.set(runLengths.size() - 1, runLengths.get(runLengths.size() - 1) + length - 1);
                    vertexCount += length - 1;
                } else {
                    runLengths.add(length);
                    vertexCount += length;
                    openRoads++;
                }
            }
        }
        close(types, numGeometries, openRoads);

        // A line column keeps its run lengths in numParts; a polygon column in numRings, with
        // numParts counting rings per polygon.
        final var merged = new BufferContext(
                vertices,
                types,
                numGeometries,
                polygonColumn ? numParts : runLengths,
                polygonColumn ? runLengths : List.of());
        return new Merged(merged, vertexCount);
    }

    /**
     * Writes the open merged line feature, if any, and returns 0 (no feature open). The type tag
     * goes in last because only now is the road count known.
     */
    private static int close(List<Integer> types, List<Integer> numGeometries, int roads) {
        if (roads > 0) {
            types.add(roads == 1 ? LINESTRING : MULTILINESTRING);
            if (roads > 1) {
                numGeometries.add(roads);
            }
        }
        return 0;
    }
}
