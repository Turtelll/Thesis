package org.maplibre.mlt.research.pipeline.mechanism;

import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.buffer.Encoding.BufferContext;
import org.maplibre.mlt.research.pipeline.mechanism.SharedVertexRef.BitReader;
import org.maplibre.mlt.research.pipeline.mechanism.SharedVertexRef.BitWriter;
import org.maplibre.mlt.research.pipeline.mechanism.SharedVertexRef.PositionTable;
import org.maplibre.mlt.research.pipeline.mechanism.SharedVertexRef.Runs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * References, for {@link LineContinuation}, into a dictionary of the positions earlier line parts
 * hold — the line counterpart of {@link SharedVertexRef}. Roads meet at junctions, so a part's
 * endpoints often repeat a position an earlier part already carries
 * ({@code docs/results/line-shared-vertices.md}).
 *
 * <p>The dictionary holds every distinct position of every earlier line part, in first-seen order.
 * (The variant that marked only the part-end positions with a remember bit was a measured
 * alternative, not carried over; see the {@code pre-consolidation} tag.) Per line part, in buffer
 * order:
 *
 * <ul>
 *   <li><b>Entry flag</b> at the part start, once the dictionary is non-empty: 1 bit, then on a hit
 *       a {@code ⌈log2 size⌉}-bit index into the dictionary replaces the residual.
 *   <li><b>Exit flag</b> at the last vertex: flag plus index, as at entry. The decoder knows the
 *       last vertex from the part lengths.
 * </ul>
 *
 * <p>Parts are reversed (never transmitted) when only the end is in the dictionary, so the entry
 * flag catches it; {@code Verification} accepts part reversal. This changes line direction, which
 * one-way rendering and direction-dependent labels rely on.
 *
 * <p>Polygon rings and points in the column keep plain residuals and never enter the dictionary.
 */
public final class LineSharedVertexRef {

    private LineSharedVertexRef() {
    }

    /**
     * The references of one buffer: its line parts, reversed where that lets the entry flag catch
     * the part, which vertices are references, and the flag and index bits.
     */
    private record References(List<Vertex> vertices, boolean[] referenced, BitWriter flags, BitWriter indices) {
    }

    private static References references(List<Vertex> original, Runs runs) {
        final var n = original.size();
        final var out = new ArrayList<Vertex>(n);
        final var referenced = new boolean[n];
        final var dictionary = new Dictionary(out, new PositionTable(n));
        final var flags = new BitWriter();
        final var indices = new BitWriter();

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
                referenced[i] = partEnd && reference(dictionary, vertex, flags, indices);
            }
            if (line) {
                dictionary.addPart(start, end);
            }
        }
        return new References(out, referenced, flags, indices);
    }

    /**
     * The references of {@code ctx} for the pipeline ({@code PipelineEncoding}): its line parts
     * reversed where that pays, which vertices are references (they carry no residual) and the flag
     * and index bits {@link #walkForDecoding} reads back. {@code null} when the runs are not
     * derivable from the topology streams (then no vertex can be a reference).
     */
    public static SharedVertexRef.Scan scan(BufferContext ctx) {
        final var runs = Runs.of(ctx, ctx.vertices().size());
        if (runs == null) {
            return null;
        }
        final var scan = references(ctx.vertices(), runs);
        return new SharedVertexRef.Scan(
                scan.vertices(), scan.referenced(), scan.flags().toBytes(), scan.indices().toBytes());
    }

    /**
     * A decoding walk over {@code vertexCount} vertices.
     *
     * @param topo topology streams of the emitted buffer; no vertices needed
     */
    public static SharedVertexRef.ReferenceWalk walkForDecoding(
            BufferContext topo, int vertexCount, byte[] flags, byte[] indices) {
        final var runs = Runs.of(topo, vertexCount);
        if (runs == null) {
            throw new IllegalStateException("the runs of a shared-reference buffer must follow from the topology");
        }
        return new Walk(runs, vertexCount, new BitReader(flags), new BitReader(indices));
    }

    /**
     * The decoder of {@link #references} as a pass over the buffer: reads an entry or exit flag at
     * every line part end, and keeps the dictionary the encoder keeps.
     */
    private static final class Walk implements SharedVertexRef.ReferenceWalk {
        private final Runs runs;
        private final int vertexCount;
        private final BitReader flags;
        private final BitReader indices;
        private Dictionary dictionary;
        private int run;

        Walk(Runs runs, int vertexCount, BitReader flags, BitReader indices) {
            this.runs = runs;
            this.vertexCount = vertexCount;
            this.flags = flags;
            this.indices = indices;
        }

        private void advance(int i) {
            while (run + 1 < runs.count() && runs.starts()[run + 1] <= i) {
                run++;
            }
        }

        private Dictionary dictionary(List<Vertex> vertices) {
            if (dictionary == null) {
                dictionary = new Dictionary(vertices, new PositionTable(vertexCount));
            }
            return dictionary;
        }

        @Override
        public Vertex decode(int i, List<Vertex> vertices) {
            advance(i);
            final var dictionary = dictionary(vertices);
            final var partEnd = runs.isLine()[run] && (i == runs.starts()[run] || i == runs.end(run) - 1);
            if (partEnd && dictionary.size() > 0 && flags.read(1) == 1) {
                return dictionary.get(indices.read(SharedVertexRef.width(dictionary.size())));
            }
            return null;
        }

        @Override
        public void accept(int i, List<Vertex> vertices) {
            advance(i);
            if (runs.isLine()[run] && i == runs.end(run) - 1) {
                dictionary(vertices).addPart(runs.starts()[run], runs.end(run));
            }
        }
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

    /**
     * What encoder and decoder both know so far: the positions of every finished line part in
     * first-seen order. Append-only; {@link #addPart} costs one position lookup per line vertex.
     */
    private static final class Dictionary {
        private final List<Vertex> vertices;
        private final PositionTable index; // position -> dictionary index
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
            index.put(vertices.get(i), size);
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
