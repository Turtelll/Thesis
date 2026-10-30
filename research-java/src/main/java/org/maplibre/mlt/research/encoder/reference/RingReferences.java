package org.maplibre.mlt.research.encoder.reference;

import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.buffer.VertexRuns;
import org.maplibre.mlt.research.buffer.Encoding.BufferContext;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * References to vertices shared with earlier polygon rings — adjacent buildings repeat each other's
 * wall vertices exactly, adjacent landcover/landuse polygons their borders — as the mechanism behind
 * the pipeline's {@code QUAD_CORNER_SHARED} predictors.
 *
 * <p>Every polygon ring is rotated (winding kept) to start where a shared stretch begins, so one
 * <b>entry flag</b> per ring says "the start vertex is one of the last {@link #WINDOW} rings'
 * positions", followed by a bit-packed index into those positions. After a vertex whose position an
 * earlier ring holds, a <b>follow flag</b> says "the next vertex is that ring's previous vertex" —
 * a shared wall is walked in the opposite direction by the two rings on either side of it. A
 * referenced vertex has no residual. Rotation is never transmitted: the decoder emits the rotated
 * rings, which {@code Verification} accepts.
 *
 * <p>Simplification against the probe this came from: the window and the "earlier" set are counted
 * in polygon rings, not polygons, so a hole sharing a vertex with its own outer ring also counts.
 */
public final class RingReferences {

    private RingReferences() {
    }

    static final int WINDOW = 4;
    // --- rotation (encoder only) --------------------------------------------

    /**
     * Rotates each polygon ring to start at the window position with the longest following run of
     * positions an earlier ring holds. Membership is rotation-invariant, so the unrotated rings
     * decide.
     */
    private static List<Vertex> rotate(List<Vertex> vertices, Runs runs) {
        final var out = new ArrayList<>(vertices);
        final var earlier = new HashSet<Vertex>();
        final var window = new ArrayDeque<List<Vertex>>();
        for (var r = 0; r < runs.count(); r++) {
            if (runs.isLine[r]) {
                continue;
            }
            final var start = runs.starts[r];
            final var n = runs.end(r) - start;
            final var windowSet = new HashSet<Vertex>();
            window.forEach(windowSet::addAll);

            var best = 0;
            var bestRun = 0;
            for (var i = 0; i < n; i++) {
                if (!windowSet.contains(vertices.get(start + i))
                        || earlier.contains(vertices.get(start + (i + n - 1) % n))) {
                    continue;
                }
                var run = 0;
                while (run < n && earlier.contains(vertices.get(start + (i + run) % n))) {
                    run++;
                }
                if (run > bestRun) {
                    bestRun = run;
                    best = i;
                }
            }
            for (var i = 0; i < n; i++) {
                out.set(start + i, vertices.get(start + (best + i) % n));
            }

            final var ring = vertices.subList(start, start + n);
            earlier.addAll(ring);
            window.addLast(ring);
            if (window.size() > WINDOW) {
                window.removeFirst();
            }
        }
        return out;
    }

    // --- reference walk (for the pipeline) -------------------------------------

    /**
     * What the encoder knows about the references of one buffer: its rings rotated to start where a
     * shared stretch begins, which vertices are references (they carry no residual), and the entry
     * and follow flags and the entry indices as the bit streams a decoder reads back with {@link
     * Walk#forDecoding}.
     */
    public record Scan(List<Vertex> vertices, boolean[] referenced, byte[] flags, byte[] indices) {
    }

    /**
     * The references of {@code ctx}, or {@code null} when its runs are not derivable from the
     * topology streams (then no vertex can be a reference). {@code ctx} must hold the vertices.
     */
    public static Scan scan(BufferContext ctx) {
        final var vertices = ctx.vertices();
        final var runs = Runs.of(ctx, vertices.size());
        if (runs == null) {
            return null;
        }
        final var rotated = rotate(vertices, runs);
        final var walk = new Walk(runs, null, null);
        final var referenced = new boolean[rotated.size()];
        for (var i = 0; i < rotated.size(); i++) {
            referenced[i] = walk.encode(i, rotated);
            walk.accept(i, rotated);
        }
        return new Scan(rotated, referenced, walk.flagsOut.toBytes(), walk.indicesOut.toBytes());
    }

    /**
     * What a decoder needs from a reference scheme, ring ({@link Walk}) or line ({@link
     * LineReferences#walkForDecoding}): per vertex in order, first {@link #decode} (the vertex
     * it references, or {@code null} when it carries a residual), then {@link #accept} once the
     * vertex is known.
     */
    public interface ReferenceWalk {

        /** The vertex {@code i} references, or {@code null}; {@code vertices} holds {@code 0..i-1}. */
        Vertex decode(int i, List<Vertex> vertices);

        /** Vertex {@code i} is known ({@code vertices} holds {@code 0..i}). */
        void accept(int i, List<Vertex> vertices);
    }

    /**
     * The shared-vertex state of one pass over a buffer's vertices in order: encoding writes the
     * flags, decoding reads them. {@link #accept} follows every vertex, referenced or not, so a
     * finished ring becomes a candidate for the rings after it.
     */
    public static final class Walk implements ReferenceWalk {
        private final Runs runs;
        private final BitWriter flagsOut = new BitWriter();
        private final BitWriter indicesOut = new BitWriter();
        private final BitReader flagsIn;
        private final BitReader indicesIn;
        private State state;
        private int run;

        private Walk(Runs runs, BitReader flagsIn, BitReader indicesIn) {
            this.runs = runs;
            this.flagsIn = flagsIn;
            this.indicesIn = indicesIn;
        }

        /**
         * A decoding walk over {@code vertexCount} vertices.
         *
         * @param topo topology streams of the emitted buffer; no vertices needed
         */
        public static Walk forDecoding(BufferContext topo, int vertexCount, byte[] flags, byte[] indices) {
            final var runs = Runs.of(topo, vertexCount);
            if (runs == null) {
                throw new IllegalStateException("the runs of a shared-reference buffer must follow from the topology");
            }
            return new Walk(runs, new BitReader(flags), new BitReader(indices));
        }

        private State state(List<Vertex> vertices) {
            if (state == null) {
                state = new State(vertices, runs.vertexCount());
            }
            return state;
        }

        private void advance(int i) {
            while (run + 1 < runs.count() && runs.starts[run + 1] <= i) {
                run++;
            }
        }

        /**
         * Encoder: whether vertex {@code i} of {@code vertices} is a reference; writes its flag. The
         * list holds at least {@code 0..i}.
         */
        boolean encode(int i, List<Vertex> vertices) {
            advance(i);
            final var state = state(vertices);
            final var ring = !runs.isLine[run];
            final var start = runs.starts[run];
            final var vertex = vertices.get(i);
            final var source = ring && i > start ? state.sourceOf(vertices.get(i - 1)) : -1;
            if (source >= 0) {
                final var referenced = vertex.equals(state.previousOnSource(source));
                flagsOut.write(referenced ? 1 : 0, 1);
                return referenced;
            }
            if (ring && i == start && state.hasCandidates()) {
                state.collectCandidates();
                final var index = state.candidateIndex(vertex);
                flagsOut.write(index >= 0 ? 1 : 0, 1);
                if (index >= 0) {
                    indicesOut.write(index, width(state.candidateCount()));
                }
                return index >= 0;
            }
            return false;
        }

        /**
         * Decoder: the vertex {@code i} references, or {@code null} when it carries a residual.
         * {@code vertices} holds {@code 0..i-1}.
         */
        @Override
        public Vertex decode(int i, List<Vertex> vertices) {
            advance(i);
            final var state = state(vertices);
            final var ring = !runs.isLine[run];
            final var start = runs.starts[run];
            final var source = ring && i > start ? state.sourceOf(vertices.get(i - 1)) : -1;
            if (source >= 0) {
                return flagsIn.read(1) == 1 ? state.previousOnSource(source) : null;
            }
            if (ring && i == start && state.hasCandidates() && flagsIn.read(1) == 1) {
                state.collectCandidates();
                return state.candidate(indicesIn.read(width(state.candidateCount())));
            }
            return null;
        }

        /** Vertex {@code i} is known ({@code vertices} holds {@code 0..i}); a ring's last closes it. */
        @Override
        public void accept(int i, List<Vertex> vertices) {
            advance(i);
            if (!runs.isLine[run] && i == runs.end(run) - 1) {
                state(vertices).addRing(runs.starts[run], runs.end(run));
            }
        }
    }

    /**
     * Bits to index {@code size} candidates.
     */
    static int width(int size) {
        return size <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(size - 1);
    }

    /**
     * What encoder and decoder both know after each completed polygon ring: where every earlier
     * position sits (most recent ring wins), and the last {@link #WINDOW} rings.
     *
     * <p>Every ring vertex passes through here on the decode path, so it is kept allocation-free:
     * primitive position tables instead of {@code HashMap<Vertex, ...>}, and the window's candidate
     * list is only built when an entry flag actually references it.
     */
    private static final class State {
        private final List<Vertex> vertices;
        private final int[] ringStart; // per vertex index
        private final int[] ringEnd;
        private final PositionTable seen; // position -> latest vertex index holding it
        private final ArrayDeque<int[]> window = new ArrayDeque<>(); // {ringStart, ringEnd}
        private int windowVertices;
        private final PositionTable distinct; // position -> candidate index, rebuilt per ring
        private int[] candidates = new int[64]; // vertex index per candidate
        private int candidateCount;

        State(List<Vertex> vertices, int vertexCount) {
            this.vertices = vertices;
            this.ringStart = new int[vertexCount];
            this.ringEnd = new int[vertexCount];
            this.seen = new PositionTable(vertexCount);
            this.distinct = new PositionTable(vertexCount);
        }

        /**
         * The vertex index an earlier ring last held {@code position} at, or {@code -1}.
         */
        int sourceOf(Vertex position) {
            return seen.get(position);
        }

        /**
         * The vertex before {@code source} on its ring — the reverse walk.
         */
        Vertex previousOnSource(int source) {
            final var start = ringStart[source];
            final var length = ringEnd[source] - start;
            return vertices.get(start + (source - start + length - 1) % length);
        }

        boolean hasCandidates() {
            return windowVertices > 0;
        }

        /**
         * Collects the distinct positions of the window, newest ring first, first occurrence kept.
         */
        void collectCandidates() {
            distinct.clear();
            candidateCount = 0;
            final var rings = window.descendingIterator();
            while (rings.hasNext()) {
                final var ring = rings.next();
                for (var i = ring[0]; i < ring[1]; i++) {
                    final var position = vertices.get(i);
                    if (distinct.get(position) < 0) {
                        if (candidateCount == candidates.length) {
                            candidates = Arrays.copyOf(candidates, candidateCount * 2);
                        }
                        distinct.put(position, candidateCount);
                        candidates[candidateCount++] = i;
                    }
                }
            }
        }

        int candidateCount() {
            return candidateCount;
        }

        Vertex candidate(int index) {
            return vertices.get(candidates[index]);
        }

        /**
         * Index of {@code position} among the collected candidates, or {@code -1}.
         */
        int candidateIndex(Vertex position) {
            return distinct.get(position);
        }

        void addRing(int start, int end) {
            for (var i = start; i < end; i++) {
                ringStart[i] = start;
                ringEnd[i] = end;
                seen.put(vertices.get(i), i);
            }
            window.addLast(new int[]{start, end});
            windowVertices += end - start;
            if (window.size() > WINDOW) {
                final var dropped = window.removeFirst();
                windowVertices -= dropped[1] - dropped[0];
            }
        }
    }

    /**
     * Open-addressing map from a position to a non-negative int. Sized for at most {@code
     * maxEntries} live entries, so it never resizes; {@link #clear} is O(1) via a generation stamp.
     */
    static final class PositionTable {
        private final long[] keys;
        private final int[] values;
        private final int[] stamps;
        private final int mask;
        private int generation = 1;

        PositionTable(int maxEntries) {
            final var capacity = Integer.highestOneBit(Math.max(2, maxEntries * 2 - 1)) << 1;
            keys = new long[capacity];
            values = new int[capacity];
            stamps = new int[capacity];
            mask = capacity - 1;
        }

        int get(Vertex position) {
            final var slot = find(key(position));
            return stamps[slot] == generation ? values[slot] : -1;
        }

        void put(Vertex position, int value) {
            final var key = key(position);
            final var slot = find(key);
            keys[slot] = key;
            values[slot] = value;
            stamps[slot] = generation;
        }

        void clear() {
            generation++;
        }

        private int find(long key) {
            var slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> 32) & mask;
            while (stamps[slot] == generation && keys[slot] != key) {
                slot = (slot + 1) & mask;
            }
            return slot;
        }

        private static long key(Vertex position) {
            return ((long) position.x() << 32) | (position.y() & 0xFFFFFFFFL);
        }
    }

    /**
     * Vertex runs and which of them are polygon rings, from the topology streams alone; {@code null}
     * when they are not derivable (then only the plain mode is used).
     */
    record Runs(int[] starts, boolean[] isLine, int vertexCount) {
        static Runs of(BufferContext ctx, int vertexCount) {
            final var starts = VertexRuns.of(ctx, vertexCount);
            if (starts.length < 2) {
                return null;
            }
            final var isLine = VertexRuns.lineRuns(ctx, starts.length);
            return isLine == null ? null : new Runs(starts, isLine, vertexCount);
        }

        int count() {
            return starts.length;
        }

        int end(int r) {
            return r + 1 < starts.length ? starts[r + 1] : vertexCount;
        }
    }

    static final class BitWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int current;
        private int filled;
        private int length;

        void write(int value, int width) {
            length += width;
            for (var b = width - 1; b >= 0; b--) {
                current = (current << 1) | ((value >>> b) & 1);
                if (++filled == 8) {
                    out.write(current);
                    current = 0;
                    filled = 0;
                }
            }
        }

        /**
         * Bits written, before padding.
         */
        int length() {
            return length;
        }

        byte[] toBytes() {
            if (filled > 0) {
                out.write(current << (8 - filled));
                current = 0;
                filled = 0;
            }
            return out.toByteArray();
        }
    }

    static final class BitReader {
        private final byte[] bytes;
        private int position;

        BitReader(byte[] bytes) {
            this.bytes = bytes;
        }

        int read(int width) {
            var value = 0;
            for (var b = 0; b < width; b++, position++) {
                value = (value << 1) | ((bytes[position >>> 3] >>> (7 - (position & 7))) & 1);
            }
            return value;
        }
    }
}
