package org.maplibre.mlt.research.workshop;

import org.maplibre.mlt.research.encoding.PartBoundaries;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;

/**
 * Per-vertex view of a buffer's vertex runs (rings or line parts), derived from the topology
 * streams alone through {@link PartBoundaries}, so encoder and decoder build the same one.
 *
 * <p>When the topology does not tile the buffer, the whole buffer is one run, and when it cannot
 * say which runs are lines ({@link PartBoundaries#lineRuns} returns {@code null}, e.g. points in the
 * column) every run is neither line nor ring.
 */
public final class Topology {

    private final int[] starts;
    private final int[] runStart;
    private final int[] runLength;
    private final boolean[] line;
    private final boolean[] ring;

    private Topology(int[] starts, int vertexCount) {
        this.starts = starts;
        runStart = new int[vertexCount];
        runLength = new int[vertexCount];
        line = new boolean[vertexCount];
        ring = new boolean[vertexCount];
    }

    public static Topology of(BufferContext ctx, int vertexCount) {
        final var starts = PartBoundaries.of(ctx, vertexCount);
        final var topology = new Topology(starts, vertexCount);
        final var isLine = PartBoundaries.lineRuns(ctx, starts.length);
        for (var r = 0; r < starts.length; r++) {
            final var start = starts[r];
            final var end = r + 1 < starts.length ? starts[r + 1] : vertexCount;
            for (var i = start; i < end; i++) {
                topology.runStart[i] = start;
                topology.runLength[i] = end - start;
                topology.line[i] = isLine != null && isLine[r];
                topology.ring[i] = isLine != null && !isLine[r];
            }
        }
        return topology;
    }

    public int vertexCount() {
        return runStart.length;
    }

    /** Start index of every run, ascending, beginning with {@code 0}: {@link PartBoundaries#of}. */
    public int[] runStarts() {
        return starts;
    }

    /** Index of the first vertex of the run containing {@code i}. */
    public int runStart(int i) {
        return runStart[i];
    }

    /** Number of vertices in the run containing {@code i}. */
    public int runLength(int i) {
        return runLength[i];
    }

    /** Position of {@code i} within its run, {@code 0} for the run start. */
    public int indexInRun(int i) {
        return i - runStart[i];
    }

    public boolean isRunStart(int i) {
        return runStart[i] == i;
    }

    /** Whether {@code i} belongs to a LineString/MultiLineString part. */
    public boolean isLine(int i) {
        return line[i];
    }

    /** Whether {@code i} belongs to a polygon ring. */
    public boolean isRing(int i) {
        return ring[i];
    }
}
