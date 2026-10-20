package org.maplibre.mlt.research.encoding.research;

import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.decoder.DecodingUtils;
import org.maplibre.mlt.research.encoding.ClassifiedPredictor;
import org.maplibre.mlt.research.encoding.Encoding;
import org.maplibre.mlt.research.encoding.IntCodec;
import org.maplibre.mlt.research.encoding.StreamMetadata;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.EncodedBuffer;
import org.maplibre.mlt.research.encoding.research.SharedVertexRef.BitReader;
import org.maplibre.mlt.research.encoding.research.SharedVertexRef.BitWriter;
import org.maplibre.mlt.research.encoding.research.SharedVertexRef.PositionTable;
import org.maplibre.mlt.research.encoding.research.SharedVertexRef.Runs;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@link LineContinuation} plus references into a dictionary of the positions earlier line parts
 * hold — the line counterpart of {@link SharedVertexRef}. Roads meet at junctions, so a part's
 * endpoints often repeat a position an earlier part already carries
 * ({@code docs/results/line-shared-vertices.md}).
 *
 * <p>What the dictionary holds is the {@link Table}: every distinct position of every earlier line
 * part ({@code _DICT}), or only the part-end positions the encoder marked ({@code _MARKED}), in
 * first-seen order. Per line part, in buffer order:
 *
 * <ul>
 *   <li><b>Entry flag</b> at the part start, once the dictionary is non-empty: 1 bit, then on a hit
 *       a {@code ⌈log2 size⌉}-bit index into the dictionary replaces the residual.
 *   <li><b>Exit flag</b> at the last vertex: flag plus index, as at entry. The decoder knows the
 *       last vertex from the part lengths.
 *   <li><b>Remember bit</b> ({@code _MARKED} only) after an entry or exit that was not referenced:
 *       1 when the position recurs at a later part end, appending it to the dictionary at once.
 * </ul>
 *
 * <p>Parts are reversed (never transmitted) when only the end is in the dictionary, so the entry
 * flag catches it; {@code GeometryEquivalence} accepts part reversal. This changes line direction,
 * which one-way rendering and direction-dependent labels rely on.
 *
 * <p>Polygon rings and points in the column keep plain residuals and never enter the dictionary.
 * Per buffer the encoder keeps whichever is smaller — this or plain {@link LineContinuation} —
 * behind a one-byte mode.
 */
public final class LineSharedVertexRef implements Encoding {

    /**
     * Which earlier positions a part end can reference.
     */
    public enum Table {
        /**
         * Every distinct position of every earlier line part. Needs no side bits to build, but the
         * decoder deduplicates every line vertex through a position table.
         */
        ALL_PARTS("_DICT"),
        /**
         * Only part-end positions marked by a remember bit, so every entry is used again and the
         * index is narrower; the decoder only appends. A position first seen inside a part (a
         * T-junction onto an earlier road's interior) can only be marked at its first part end.
         */
        MARKED("_MARKED");

        private final String suffix;

        Table(String suffix) {
            this.suffix = suffix;
        }
    }

    private static final int PLAIN = 0;
    private static final int SHARED = 1;

    private final ClassifiedPredictor predictor = new LineContinuation();
    private final Table table;

    public LineSharedVertexRef() {
        this(Table.ALL_PARTS);
    }

    public LineSharedVertexRef(Table table) {
        this.table = table;
    }

    @Override
    public String name() {
        return predictor.name() + "_SHARED" + table.suffix;
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
        final var choice = choose(ctx);
        return choice.vertices() == ctx.vertices()
                ? ctx
                : new BufferContext(
                        choice.vertices(), ctx.geometryTypes(), ctx.numGeometries(), ctx.numParts(), ctx.numRings());
    }

    @Override
    public EncodedBuffer encode(BufferContext ctx) {
        final var payload = choose(ctx).payload();
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
        final var streams = streams(ctx, runs);
        final var sharedPayload = encodeShared(streams);
        return sharedPayload.length < plainPayload.length
                ? new Choice(sharedPayload, streams.vertices())
                : new Choice(plainPayload, vertices);
    }

    /**
     * Plain {@link LineContinuation}: its regions in its own {@link
     * ClassifiedPredictor#regionOrder()}; their lengths follow from the topology streams.
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

    /**
     * {@code {bitSum, valueCount}} for the residual-bits table, all-in: residual bit widths plus
     * every flag plus the index bits, over two values per vertex. Always the shared stream, as in
     * {@link SharedVertexRef#allInBits}.
     */
    public long[] allInBits(BufferContext ctx) {
        final var vertices = ctx.vertices();
        final var runs = Runs.of(ctx, vertices.size());
        if (runs == null) {
            var sum = 0L;
            for (final var region : ClassRegions.split(predictor, ctx)) {
                for (final var value : region) {
                    sum += 32 - Integer.numberOfLeadingZeros(value);
                }
            }
            return new long[]{sum, vertices.size() * 2L};
        }
        final var streams = streams(ctx, runs);
        var sum = (long) streams.flags().length() + streams.indices().length();
        for (final var region : streams.regions()) {
            for (final int value : region) {
                sum += 32 - Integer.numberOfLeadingZeros(value);
            }
        }
        return new long[]{sum, vertices.size() * 2L};
    }

    // --- encoder ---------------------------------------------------------------

    private record Streams(
            List<Vertex> vertices, BitWriter flags, BitWriter indices, List<List<Integer>> regions) {
    }

    private Streams streams(BufferContext ctx, Runs runs) {
        final var original = ctx.vertices();
        final var n = original.size();
        final var classes = predictor.classify(ctx, n);
        final var out = new ArrayList<Vertex>(n);
        final var dictionary = new Dictionary(out, new PositionTable(n));
        final var remaining = table == Table.MARKED ? endOccurrences(original, runs) : null;
        final var flags = new BitWriter();
        final var indices = new BitWriter();
        final var regions = new ArrayList<List<Integer>>();
        for (var c = 0; c < predictor.classCount(); c++) {
            regions.add(new ArrayList<>());
        }

        var prevX = 0;
        var prevY = 0;
        for (var r = 0; r < runs.count(); r++) {
            final var start = runs.starts()[r];
            final var end = runs.end(r);
            final var line = runs.isLine()[r];
            final var part = original.subList(start, end);
            if (line && shouldReverse(dictionary, part)) {
                final var reversed = new ArrayList<>(part);
                Collections.reverse(reversed);
                out.addAll(reversed);
            } else {
                out.addAll(part);
            }

            for (var i = start; i < end; i++) {
                final var vertex = out.get(i);
                final var partEnd = line && (i == start || i == end - 1);
                final var referenced = partEnd && reference(dictionary, vertex, flags, indices);
                if (partEnd && remaining != null) {
                    final var later = remaining.get(vertex) - 1;
                    remaining.put(vertex, later);
                    if (!referenced) {
                        flags.write(later > 0 ? 1 : 0, 1);
                        if (later > 0) {
                            dictionary.add(i);
                        }
                    }
                }
                if (!referenced) {
                    final var predX = predictX(classes[i], i, out, prevX);
                    final var predY = predictY(classes[i], i, out, prevY);
                    final var region = regions.get(classes[i]);
                    region.add(EncodingUtils.encodeZigZag(vertex.x() - predX));
                    region.add(EncodingUtils.encodeZigZag(vertex.y() - predY));
                }
                prevX = vertex.x();
                prevY = vertex.y();
            }
            if (line && table == Table.ALL_PARTS) {
                dictionary.addPart(start, end);
            }
        }
        return new Streams(out, flags, indices, regions);
    }

    /**
     * How often each position is a line part's first or last vertex. Reversal only swaps a part's
     * two ends, so the count holds for the emitted order too.
     */
    private static PositionTable endOccurrences(List<Vertex> vertices, Runs runs) {
        final var counts = new PositionTable(vertices.size());
        for (var r = 0; r < runs.count(); r++) {
            if (!runs.isLine()[r]) {
                continue;
            }
            final var start = runs.starts()[r];
            final var end = runs.end(r);
            count(counts, vertices.get(start));
            if (end - start >= 2) {
                count(counts, vertices.get(end - 1));
            }
        }
        return counts;
    }

    private static void count(PositionTable counts, Vertex position) {
        final var count = counts.get(position);
        counts.put(position, count < 0 ? 1 : count + 1);
    }

    /**
     * Reverse when the end is in the dictionary and the start is not, so the entry flag catches it.
     */
    private static boolean shouldReverse(Dictionary dictionary, List<Vertex> part) {
        return part.size() >= 2
                && dictionary.indexOf(part.get(0)) < 0
                && dictionary.indexOf(part.get(part.size() - 1)) >= 0;
    }

    private static boolean reference(Dictionary dictionary, Vertex vertex, BitWriter flags, BitWriter indices) {
        if (dictionary.size() == 0) {
            return false;
        }
        final var index = dictionary.indexOf(vertex);
        flags.write(index >= 0 ? 1 : 0, 1);
        if (index >= 0) {
            indices.write(index, SharedVertexRef.width(dictionary.size()));
        }
        return index >= 0;
    }

    private byte[] encodeShared(Streams streams) {
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

    // --- decoder ---------------------------------------------------------------

    @Override
    public List<Vertex> decode(EncodedBuffer encoded, BufferContext ctx, int vertexCount) {
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
            final var dictionary = new Dictionary(
                    vertices, table == Table.ALL_PARTS ? new PositionTable(vertexCount) : null);
            var prevX = 0;
            var prevY = 0;
            for (var r = 0; r < runs.count(); r++) {
                final var start = runs.starts()[r];
                final var end = runs.end(r);
                final var line = runs.isLine()[r];
                for (var i = start; i < end; i++) {
                    final var partEnd = line && (i == start || i == end - 1);
                    Vertex vertex = null;
                    if (partEnd && dictionary.size() > 0 && flags.read(1) == 1) {
                        vertex = dictionary.get(indices.read(SharedVertexRef.width(dictionary.size())));
                    }
                    final var remember = vertex == null && partEnd && table == Table.MARKED && flags.read(1) == 1;
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
                    if (remember) {
                        dictionary.add(i);
                    }
                    prevX = vertex.x();
                    prevY = vertex.y();
                }
                if (line && table == Table.ALL_PARTS) {
                    dictionary.addPart(start, end);
                }
            }
            return vertices;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --- shared helpers --------------------------------------------------------

    /**
     * The predictor's region order reversed, so starts go last, as in {@link SharedVertexRef}.
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
     * What encoder and decoder both know so far: the {@link Table}'s positions in first-seen order.
     * Append-only. {@link #addPart} costs one position lookup per line vertex; a decoder that only
     * calls {@link #add} passes no position table and does no lookups.
     */
    private static final class Dictionary {
        private final List<Vertex> vertices;
        private final PositionTable index; // position -> dictionary index, or null
        private int[] entries = new int[64]; // vertex index per dictionary index
        private int size;

        Dictionary(List<Vertex> vertices, PositionTable index) {
            this.vertices = vertices;
            this.index = index;
        }

        int size() {
            return size;
        }

        Vertex get(int dictionaryIndex) {
            return vertices.get(entries[dictionaryIndex]);
        }

        /**
         * Dictionary index of {@code position}, or {@code -1}.
         */
        int indexOf(Vertex position) {
            return index.get(position);
        }

        /**
         * Appends vertex {@code i}, whose position the caller knows is not in the dictionary yet.
         */
        void add(int i) {
            if (size == entries.length) {
                entries = Arrays.copyOf(entries, size * 2);
            }
            if (index != null) {
                index.put(vertices.get(i), size);
            }
            entries[size++] = i;
        }

        void addPart(int start, int end) {
            for (var i = start; i < end; i++) {
                if (index.get(vertices.get(i)) < 0) {
                    add(i);
                }
            }
        }
    }
}
