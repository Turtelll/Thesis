package org.maplibre.mlt.research.encoding.research;

import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.decoder.DecodingUtils;
import org.maplibre.mlt.research.encoding.ClassifiedPredictor;
import org.maplibre.mlt.research.encoding.Encoding;
import org.maplibre.mlt.research.encoding.IntCodec;
import org.maplibre.mlt.research.encoding.PartBoundaries;
import org.maplibre.mlt.research.encoding.StreamMetadata;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.EncodedBuffer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * A {@link ClassifiedPredictor} ({@link SegmentQuadCorner} by default) plus references to vertices
 * shared with earlier polygon rings — adjacent buildings repeat each other's wall vertices exactly,
 * adjacent landcover/landuse polygons their borders.
 *
 * <p>Every polygon ring is rotated (winding kept) to start where a shared stretch begins, so one
 * <b>entry flag</b> per ring says "the start vertex is one of the last {@link #WINDOW} rings'
 * positions", followed by a bit-packed index into those positions. After a vertex whose position an
 * earlier ring holds, a <b>follow flag</b> says "the next vertex is that ring's previous vertex" —
 * a shared wall is walked in the opposite direction by the two rings on either side of it. A
 * referenced vertex has no residual. Rotation is never transmitted: the decoder emits the rotated
 * rings, which {@code GeometryEquivalence} accepts.
 *
 * <p>Simplification against the probe this came from: the window and the "earlier" set are counted
 * in polygon rings, not polygons, so a hole sharing a vertex with its own outer ring also counts.
 *
 * <p>Per buffer the encoder keeps whichever is smaller — this or the plain predictor — behind a
 * one-byte mode, so a layer without shared vertices pays at most that byte.
 */
public final class SharedVertexRef implements Encoding {

    static final int WINDOW = 4;

    private static final int PLAIN = 0;
    private static final int SHARED = 1;

    private final String prefix;
    private final ClassifiedPredictor predictor;
    private final GeometryReorder reorder;

    public SharedVertexRef() {
        this("QUAD_CORNER", new SegmentQuadCorner());
    }

    public SharedVertexRef(String prefix, ClassifiedPredictor predictor) {
        this(prefix, predictor, GeometryReorder.NONE);
    }

    /**
     * The residuals left after referencing go to their predictor class region, and all but the last
     * region length are written to the header: which vertices are referenced is only known while
     * decoding, so the decoder cannot count them.
     *
     * @param prefix  name prefix, e.g. {@code QUAD_CORNER_LINE} for {@link QuadCornerLine}
     * @param reorder applied to the buffer before ring rotation; never transmitted either
     */
    public SharedVertexRef(String prefix, ClassifiedPredictor predictor, GeometryReorder reorder) {
        this.prefix = prefix;
        this.predictor = predictor;
        this.reorder = reorder;
    }

    @Override
    public String name() {
        return prefix + "_SHARED" + reorder.nameSuffix();
    }

    @Override
    public String toString() {
        return name();
    }

    @Override
    public boolean reordersGeometries() {
        return true;
    }

    @Override
    public BufferContext emitted(BufferContext ctx) {
        final var reordered = reorder.apply(ctx);
        final var choice = choose(reordered);
        return choice.vertices() == reordered.vertices()
                ? reordered
                : new BufferContext(
                        choice.vertices(),
                        reordered.geometryTypes(),
                        reordered.numGeometries(),
                        reordered.numParts(),
                        reordered.numRings());
    }

    @Override
    public EncodedBuffer encode(BufferContext ctx) {
        final var payload = choose(reorder.apply(ctx)).payload();
        return new EncodedBuffer(payload, StreamMetadata.bytes(ctx.vertices().size() * 2, payload.length));
    }

    private record Choice(byte[] payload, List<Vertex> vertices) {
    }

    private Choice choose(BufferContext ctx) {
        final var vertices = ctx.vertices();
        final var plainPayload = withMode(PLAIN, encodePlain(ctx));
        final var runs = Runs.of(ctx, vertices.size());
        if (runs == null) {
            return new Choice(plainPayload, vertices);
        }
        final var rotated = rotate(vertices, runs);
        final var sharedPayload = encodeShared(ctx, rotated, runs);
        return sharedPayload.length < plainPayload.length
                ? new Choice(sharedPayload, rotated)
                : new Choice(plainPayload, vertices);
    }

    /**
     * The plain predictor's regions in its own {@link ClassifiedPredictor#regionOrder()}; their
     * lengths follow from the topology streams.
     */
    private byte[] encodePlain(BufferContext ctx) {
        return IntCodec.VARINT_SPLIT.encode(
                ClassRegions.concat(ClassRegions.split(predictor, ctx), predictor.regionOrder()));
    }

    private List<Vertex> decodePlain(byte[] body, BufferContext ctx, int vertexCount) {
        final var regions = ClassRegions.slice(
                IntCodec.VARINT_SPLIT.decode(body, vertexCount * 2),
                ClassRegions.counts(predictor, ctx, vertexCount),
                predictor.regionOrder());
        return ClassRegions.join(predictor, regions, ctx, vertexCount);
    }

    private static byte[] withMode(int mode, byte[] body) {
        final var out = new byte[body.length + 1];
        out[0] = (byte) mode;
        System.arraycopy(body, 0, out, 1, body.length);
        return out;
    }

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

    // --- shared stream --------------------------------------------------------

    /**
     * {@code {bitSum, valueCount}} for the residual-bits table, all-in: residual bit widths plus one
     * bit per flag plus the index bits, over two values per vertex — a referenced vertex costs only
     * its flag and index. Always the shared stream: the plain fallback is chosen by payload size,
     * and the table measures before serialization. Headers are not counted, as for every other row.
     */
    public long[] allInBits(BufferContext original) {
        final var ctx = reorder.apply(original);
        final var vertices = ctx.vertices();
        final var runs = Runs.of(ctx, vertices.size());
        if (runs == null) {
            var sum = 0L;
            for (final var region : ClassRegions.split(predictor, ctx)) {
                sum += bitSum(region);
            }
            return new long[]{sum, vertices.size() * 2L};
        }
        final var streams = streams(ctx, rotate(vertices, runs), runs);
        var sum = (long) streams.flags().length() + streams.indices().length();
        for (final var region : streams.regions()) {
            for (final int value : region) {
                sum += 32 - Integer.numberOfLeadingZeros(value);
            }
        }
        return new long[]{sum, vertices.size() * 2L};
    }

    private static long bitSum(int[] values) {
        var sum = 0L;
        for (final var value : values) {
            sum += 32 - Integer.numberOfLeadingZeros(value);
        }
        return sum;
    }

    private record Streams(BitWriter flags, BitWriter indices, List<List<Integer>> regions) {
    }

    private byte[] encodeShared(BufferContext ctx, List<Vertex> vertices, Runs runs) {
        final var streams = streams(ctx, vertices, runs);
        final var regions = streams.regions();
        final var order = regionOrder();
        final var values = new ArrayList<Integer>();
        for (final var c : order) {
            values.addAll(regions.get(c));
        }
        try {
            final var bytes = new ByteArrayOutputStream();
            final var out = new DataOutputStream(bytes);
            out.write(SHARED);
            final var flagBytes = streams.flags().toBytes();
            EncodingUtils.putVarInt(out, flagBytes.length);
            out.write(flagBytes);
            final var indexBytes = streams.indices().toBytes();
            EncodingUtils.putVarInt(out, indexBytes.length);
            out.write(indexBytes);
            EncodingUtils.putVarInt(out, values.size());
            // The last region's length follows from the total.
            for (var k = 0; k < order.length - 1; k++) {
                EncodingUtils.putVarInt(out, regions.get(order[k]).size());
            }
            out.write(IntCodec.VARINT_SPLIT.encode(values.stream().mapToInt(Integer::intValue).toArray()));
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Streams streams(BufferContext ctx, List<Vertex> vertices, Runs runs) {
        final var classes = predictor.classify(ctx, vertices.size());
        final var state = new State(vertices, vertices.size());
        final var flags = new BitWriter();
        final var indices = new BitWriter();
        final var regions = new ArrayList<List<Integer>>();
        for (var c = 0; c < predictor.classCount(); c++) {
            regions.add(new ArrayList<>());
        }

        var prevX = 0;
        var prevY = 0;
        for (var r = 0; r < runs.count(); r++) {
            final var start = runs.starts[r];
            final var end = runs.end(r);
            final var ring = !runs.isLine[r];
            for (var i = start; i < end; i++) {
                final var vertex = vertices.get(i);
                final var predX = predictX(classes[i], i, vertices, prevX);
                final var predY = predictY(classes[i], i, vertices, prevY);
                final var zx = EncodingUtils.encodeZigZag(vertex.x() - predX);
                final var zy = EncodingUtils.encodeZigZag(vertex.y() - predY);

                var referenced = false;
                final var source = ring && i > start ? state.sourceOf(vertices.get(i - 1)) : -1;
                if (source >= 0) {
                    referenced = vertex.equals(state.previousOnSource(source));
                    flags.write(referenced ? 1 : 0, 1);
                } else if (ring && i == start && state.hasCandidates()) {
                    state.collectCandidates();
                    final var index = state.candidateIndex(vertex);
                    // Always cheaper than the residual in practice: Varint spends >= 8 bits per value
                    // and FastPFOR prices a block's widest value, against an index of a few bits.
                    referenced = index >= 0;
                    flags.write(referenced ? 1 : 0, 1);
                    if (referenced) {
                        indices.write(index, width(state.candidateCount()));
                    }
                }
                if (!referenced) {
                    final var region = regions.get(classes[i]);
                    region.add(zx);
                    region.add(zy);
                }
                prevX = vertex.x();
                prevY = vertex.y();
            }
            if (ring) {
                state.addRing(start, end);
            }
        }
        return new Streams(flags, indices, regions);
    }

    @Override
    public List<Vertex> decode(EncodedBuffer encoded, BufferContext original, int vertexCount) {
        // The reordered topology streams are what a decoder reads from the tile.
        final var ctx = reorder.reorders() ? reorder.topology(original, vertexCount) : original;
        try {
            final var in = new ByteArrayInputStream(encoded.payload());
            final var mode = in.read();
            if (mode == PLAIN) {
                return decodePlain(in.readAllBytes(), ctx, vertexCount);
            }
            final var runs = Runs.of(ctx, vertexCount);
            final var flags = new BitReader(in.readNBytes(DecodingUtils.decodeVarint(in)));
            final var indices = new BitReader(in.readNBytes(DecodingUtils.decodeVarint(in)));
            final var residualCount = DecodingUtils.decodeVarint(in);
            final var order = regionOrder();
            final var cursors = new int[predictor.classCount()];
            var offset = 0;
            for (var k = 0; k < order.length; k++) {
                cursors[order[k]] = offset;
                offset += k < order.length - 1 ? DecodingUtils.decodeVarint(in) : 0;
            }
            final var residuals = IntCodec.VARINT_SPLIT.decode(in.readAllBytes(), residualCount);
            final var classes = predictor.classify(ctx, vertexCount);

            final var vertices = new ArrayList<Vertex>(vertexCount);
            final var state = new State(vertices, vertexCount);
            var prevX = 0;
            var prevY = 0;
            for (var r = 0; r < runs.count(); r++) {
                final var start = runs.starts[r];
                final var end = runs.end(r);
                final var ring = !runs.isLine[r];
                for (var i = start; i < end; i++) {
                    Vertex vertex = null;
                    final var source = ring && i > start ? state.sourceOf(vertices.get(i - 1)) : -1;
                    if (source >= 0) {
                        if (flags.read(1) == 1) {
                            vertex = state.previousOnSource(source);
                        }
                    } else if (ring && i == start && state.hasCandidates()) {
                        if (flags.read(1) == 1) {
                            state.collectCandidates();
                            vertex = state.candidate(indices.read(width(state.candidateCount())));
                        }
                    }
                    if (vertex == null) {
                        final var predX = predictX(classes[i], i, vertices, prevX);
                        final var predY = predictY(classes[i], i, vertices, prevY);
                        final var region = classes[i];
                        vertex = new Vertex(
                                predX + DecodingUtils.decodeZigZag(residuals[cursors[region]]),
                                predY + DecodingUtils.decodeZigZag(residuals[cursors[region] + 1]));
                        cursors[region] += 2;
                    }
                    vertices.add(vertex);
                    prevX = vertex.x();
                    prevY = vertex.y();
                }
                if (ring) {
                    state.addRing(start, end);
                }
            }
            return vertices;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The predictor's {@link ClassifiedPredictor#regionOrder()} reversed, so starts go last. For
     * {@link SegmentQuadCorner}: CORNER, INTERIOR, START. Measured: {@code
     * docs/results/shared-vertices.md}.
     */
    private int[] regionOrder() {
        final var order = predictor.regionOrder();
        final var reversed = new int[order.length];
        for (var k = 0; k < order.length; k++) {
            reversed[k] = order[order.length - 1 - k];
        }
        return reversed;
    }

    private int predictX(int vertexClass, int i, List<Vertex> vertices, int prevX) {
        return predictor.hasPrediction(vertexClass) ? predictor.predictX(vertexClass, i, vertices) : prevX;
    }

    private int predictY(int vertexClass, int i, List<Vertex> vertices, int prevY) {
        return predictor.hasPrediction(vertexClass) ? predictor.predictY(vertexClass, i, vertices) : prevY;
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
            final var starts = PartBoundaries.of(ctx, vertexCount);
            if (starts.length < 2) {
                return null;
            }
            final var isLine = PartBoundaries.lineRuns(ctx, starts.length);
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
