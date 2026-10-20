package org.maplibre.mlt.research.encoding.research.failed;

import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.decoder.DecodingUtils;
import org.maplibre.mlt.research.encoding.Encoding;
import org.maplibre.mlt.research.encoding.IntCodec;
import org.maplibre.mlt.research.encoding.StreamMetadata;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.EncodedBuffer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link StandardDelta} with the vocabulary learned per tile instead of fixed: the {@code k} most
 * frequent deltas of all the tile's layers, at least {@link #MIN_LENGTH} long, are stored once in the
 * tile, and every buffer of the tile writes its deltas as a sum of those entries plus a remainder.
 *
 * <p>Not an {@link Encoding}, because the vocabulary needs the whole tile: {@link #learn} and {@link
 * #header} produce the bytes the tile carries once, and {@link #forTile} is the per-buffer encoding
 * bound to them.
 *
 * <p>Per delta, the entry that shortens the rest most is subtracted while one does, each as a step
 * token {@code index + 1}, then a terminator 0. A buffer is every step token, then every zigzagged
 * remainder, behind a varint word count.
 *
 * <p>{@code single} caps a delta at one entry: exactly one step token per delta, 0 for none, and no
 * terminator — a delta much longer than its nearest entry is then not a run of that entry's token.
 */
public final class TileStandardDelta {

    /**
     * What one vocabulary entry is: a single dx or dy value, or a whole (dx, dy) pair.
     */
    public enum Unit {
        SCALAR(1), VECTOR(2);

        private final int width;

        Unit(int width) {
            this.width = width;
        }
    }

    /**
     * A delta is a vocabulary candidate only if one of its components is at least this long.
     */
    public static final int MIN_LENGTH = 16;

    private final Unit unit;
    private final int k;
    private final boolean single;
    private final IntCodec codec;

    public TileStandardDelta(Unit unit, int k, boolean single, IntCodec codec) {
        this.unit = unit;
        this.k = k;
        this.single = single;
        this.codec = codec;
    }

    public String name() {
        return "TILE_STD_DELTA_" + unit + "_K" + k + (single ? "_SINGLE_" : "_") + codec.name();
    }

    @Override
    public String toString() {
        return name();
    }

    /**
     * The tile's vocabulary: at most {@code k} entries, most frequent first, ties by value. A {@link
     * Unit#VECTOR} entry is two consecutive values, x then y.
     */
    public int[] learn(List<BufferContext> tile) {
        final var width = unit.width;
        final var counts = new HashMap<Long, Integer>();
        for (final var ctx : tile) {
            final var deltas = StandardDelta.deltas(ctx.vertices());
            for (var i = 0; i < deltas.length; i += width) {
                final var x = deltas[i];
                final var y = width == 2 ? deltas[i + 1] : 0;
                if (Math.max(Math.abs((long) x), Math.abs((long) y)) >= MIN_LENGTH) {
                    counts.merge((long) x << 32 | y & 0xFFFFFFFFL, 1, Integer::sum);
                }
            }
        }
        final var entries = counts.entrySet().stream()
                .sorted(Map.Entry.<Long, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(k)
                .toList();
        final var vocabulary = new int[entries.size() * width];
        for (var e = 0; e < entries.size(); e++) {
            final long key = entries.get(e).getKey();
            vocabulary[e * width] = (int) (key >> 32);
            if (width == 2) {
                vocabulary[e * width + 1] = (int) key;
            }
        }
        return vocabulary;
    }

    /**
     * The vocabulary as the tile stores it: varint entry count, then every value zigzag-varint.
     */
    public byte[] header(int[] vocabulary) {
        final var values = new int[1 + vocabulary.length];
        values[0] = vocabulary.length / unit.width;
        for (var i = 0; i < vocabulary.length; i++) {
            values[1 + i] = EncodingUtils.encodeZigZag(vocabulary[i]);
        }
        return IntCodec.VARINT.encode(values);
    }

    private int[] vocabulary(byte[] header) {
        final var count = IntCodec.VARINT.decode(header, 1)[0];
        final var values = IntCodec.VARINT.decode(header, 1 + count * unit.width);
        final var vocabulary = new int[values.length - 1];
        for (var i = 0; i < vocabulary.length; i++) {
            vocabulary[i] = DecodingUtils.decodeZigZag(values[1 + i]);
        }
        return vocabulary;
    }

    /**
     * The encoding of one buffer of the tile whose {@link #header} this is.
     */
    public Encoding forTile(byte[] header) {
        final var decomposition = new Learned(unit.width, vocabulary(header), single);
        final var sixteenBit = codec instanceof IntCodec.Uint16 || codec instanceof IntCodec.Uint16Planes;
        return new Encoding() {

            @Override
            public String name() {
                return TileStandardDelta.this.name();
            }

            @Override
            public EncodedBuffer encode(BufferContext ctx) {
                final var tokens = decomposition.encode(StandardDelta.deltas(ctx.vertices()));
                final var words = sixteenBit ? StandardDelta.escape(tokens) : tokens;
                final var count = IntCodec.VARINT.encode(new int[]{words.length});
                final var body = codec.encode(words);
                final var payload = Arrays.copyOf(count, count.length + body.length);
                System.arraycopy(body, 0, payload, count.length, body.length);
                return new EncodedBuffer(payload, StreamMetadata.bytes(words.length, payload.length));
            }

            @Override
            public List<Vertex> decode(EncodedBuffer encoded, BufferContext ctx, int vertexCount) {
                final var payload = encoded.payload();
                final var count = IntCodec.VARINT.decode(payload, 1)[0];
                final var body = Arrays.copyOfRange(payload, EncodingUtils.getVarIntSize(count), payload.length);
                final var words = codec.decode(body, count);
                final var deltas = decomposition.decode(
                        sixteenBit ? StandardDelta.unescape(words) : words, vertexCount * 2);
                final var vertices = new ArrayList<Vertex>(vertexCount);
                int x = 0, y = 0;
                for (var i = 0; i < vertexCount; i++) {
                    x += deltas[2 * i];
                    y += deltas[2 * i + 1];
                    vertices.add(new Vertex(x, y));
                }
                return vertices;
            }
        };
    }

    /**
     * Deltas as sums of vocabulary entries: every step token of the buffer ({@code index + 1}, 0 ends
     * a delta), then every zigzagged remainder. With {@code single}, one step token per delta
     * ({@code index + 1}, or 0 for no entry) and no terminator.
     */
    static final class Learned implements StandardDelta.Decomposition {

        private final int width;
        private final int[] vocabulary;
        private final boolean single;

        Learned(int width, int[] vocabulary, boolean single) {
            this.width = width;
            this.vocabulary = vocabulary;
            this.single = single;
        }

        @Override
        public String name() {
            return "LEARNED";
        }

        @Override
        public int[] encode(int[] deltas) {
            var steps = new int[deltas.length / width + 16];
            var s = 0;
            final var remainders = new int[deltas.length];
            final var rest = new long[width];
            for (var i = 0; i < deltas.length; i += width) {
                for (var c = 0; c < width; c++) {
                    rest[c] = deltas[i + c];
                }
                while (true) {
                    var best = -1;
                    var bestLength = length(rest, -1);
                    for (var e = 0; e < vocabulary.length / width; e++) {
                        final var length = length(rest, e);
                        if (length < bestLength) {
                            best = e;
                            bestLength = length;
                        }
                    }
                    if (best < 0) {
                        if (single) {
                            steps[s++] = 0;
                        }
                        break;
                    }
                    for (var c = 0; c < width; c++) {
                        rest[c] -= vocabulary[best * width + c];
                    }
                    if (s == steps.length) {
                        steps = Arrays.copyOf(steps, steps.length * 2);
                    }
                    steps[s++] = best + 1;
                    if (single) {
                        break;
                    }
                }
                if (!single) {
                    if (s == steps.length) {
                        steps = Arrays.copyOf(steps, steps.length * 2);
                    }
                    steps[s++] = 0;
                }
                for (var c = 0; c < width; c++) {
                    remainders[i + c] = EncodingUtils.encodeZigZag((int) rest[c]);
                }
            }
            final var tokens = Arrays.copyOf(steps, s + remainders.length);
            System.arraycopy(remainders, 0, tokens, s, remainders.length);
            return tokens;
        }

        /**
         * L1 length of {@code rest} minus vocabulary entry {@code entry}, or of {@code rest} itself
         * for -1.
         */
        private long length(long[] rest, int entry) {
            long length = 0;
            for (var c = 0; c < width; c++) {
                length += Math.abs(rest[c] - (entry < 0 ? 0 : vocabulary[entry * width + c]));
            }
            return length;
        }

        @Override
        public int[] decode(int[] tokens, int count) {
            final var stepCount = tokens.length - count;
            final var deltas = new int[count];
            var t = 0;
            for (var i = 0; i < count; i += width) {
                for (var c = 0; c < width; c++) {
                    deltas[i + c] = DecodingUtils.decodeZigZag(tokens[stepCount + i + c]);
                }
                for (int step; (step = tokens[t++]) != 0; ) {
                    for (var c = 0; c < width; c++) {
                        deltas[i + c] += vocabulary[(step - 1) * width + c];
                    }
                    if (single) {
                        break;
                    }
                }
            }
            if (t != stepCount) {
                throw new IllegalStateException(name() + " decoded " + t + " of " + stepCount + " step tokens");
            }
            return deltas;
        }
    }
}
