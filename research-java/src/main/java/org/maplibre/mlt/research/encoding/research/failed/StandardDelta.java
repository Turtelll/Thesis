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
import java.util.List;

/**
 * Componentwise delta, continuous across the buffer like {@code DELTA_ZIGZAG}, with every delta
 * written as a short sequence of tokens from one fixed vocabulary of standardized lengths (a
 * {@link Decomposition}), serialized as fixed 16-bit words.
 *
 * <p>The vocabulary is a global constant, identical for every layer, so the buildings, roads and
 * POIs of one tile emit the same 2-byte symbols — redundancy a per-tile gzip, Brotli or zstd pass can
 * match across layers, where {@code DELTA_ZIGZAG_VARINT}'s byte strings depend on each layer's
 * own delta distribution.
 *
 * <p>A token that does not fit a 16-bit word, and the word {@link #ESCAPE} itself, is written as
 * {@link #ESCAPE} followed by its high and low half. The word count is never stored: under a fixed
 * 16-bit layout it is the payload length over two.
 *
 * <p>No decomposition beats {@code DELTA_ZIGZAG_VARINT_SPLIT} under any compressor, see {@code
 * docs/results/failed/standard-delta-uint16.md}. Kept for its mechanism test and {@code --std-delta}.
 */
public final class StandardDelta implements Encoding {

    static final int ESCAPE = 0xFFFF;

    private final Decomposition decomposition;
    private final IntCodec codec;

    /**
     * @param codec a fixed 16-bit serializer, {@link IntCodec#UINT16} or {@link IntCodec#UINT16_PLANES}
     */
    public StandardDelta(Decomposition decomposition, IntCodec codec) {
        if (!(codec instanceof IntCodec.Uint16 || codec instanceof IntCodec.Uint16Planes)) {
            throw new IllegalArgumentException("StandardDelta needs a fixed 16-bit codec, got " + codec.name());
        }
        this.decomposition = decomposition;
        this.codec = codec;
    }

    @Override
    public String name() {
        return "STD_DELTA_" + decomposition.name() + "_" + codec.name();
    }

    @Override
    public String toString() {
        return name();
    }

    public IntCodec codec() {
        return codec;
    }

    @Override
    public EncodedBuffer encode(BufferContext ctx) {
        final var words = escape(decomposition.encode(deltas(ctx.vertices())));
        final var payload = codec.encode(words);
        return new EncodedBuffer(payload, StreamMetadata.bytes(words.length, payload.length));
    }

    @Override
    public List<Vertex> decode(EncodedBuffer encoded, BufferContext ctx, int vertexCount) {
        final var payload = encoded.payload();
        final var tokens = unescape(codec.decode(payload, payload.length / 2));
        final var deltas = decomposition.decode(tokens, vertexCount * 2);
        final var vertices = new ArrayList<Vertex>(vertexCount);
        int x = 0, y = 0;
        for (var i = 0; i < vertexCount; i++) {
            x += deltas[2 * i];
            y += deltas[2 * i + 1];
            vertices.add(new Vertex(x, y));
        }
        return vertices;
    }

    /**
     * Signed componentwise deltas, interleaved x, y, both predecessors starting at zero.
     */
    static int[] deltas(List<Vertex> vertices) {
        final var deltas = new int[vertices.size() * 2];
        int prevX = 0, prevY = 0;
        for (var i = 0; i < vertices.size(); i++) {
            final var v = vertices.get(i);
            deltas[2 * i] = v.x() - prevX;
            deltas[2 * i + 1] = v.y() - prevY;
            prevX = v.x();
            prevY = v.y();
        }
        return deltas;
    }

    static int[] escape(int[] tokens) {
        var escapes = 0;
        for (final var token : tokens) {
            if (needsEscape(token)) {
                escapes++;
            }
        }
        if (escapes == 0) {
            return tokens;
        }
        final var words = new int[tokens.length + 2 * escapes];
        var w = 0;
        for (final var token : tokens) {
            if (needsEscape(token)) {
                words[w++] = ESCAPE;
                words[w++] = token >>> 16;
                words[w++] = token & 0xFFFF;
            } else {
                words[w++] = token;
            }
        }
        return words;
    }

    static int[] unescape(int[] words) {
        final var tokens = new int[words.length];
        var t = 0;
        for (var i = 0; i < words.length; i++) {
            tokens[t++] = words[i] == ESCAPE ? words[++i] << 16 | words[++i] : words[i];
        }
        return Arrays.copyOf(tokens, t);
    }

    private static boolean needsEscape(int token) {
        return (token & ~0xFFFF) != 0 || token == ESCAPE;
    }

    private static int zigZag(long value) {
        return EncodingUtils.encodeZigZag((int) value);
    }

    private static int unZigZag(int token) {
        return DecodingUtils.decodeZigZag(token);
    }

    private static void requireConsumed(int consumed, int[] tokens, Decomposition decomposition) {
        if (consumed != tokens.length) {
            throw new IllegalStateException(decomposition.name() + " decoded " + consumed + " of "
                    + tokens.length + " tokens");
        }
    }

    /**
     * How a signed delta becomes tokens from the fixed vocabulary. Tokens are unsigned 32-bit values;
     * {@link StandardDelta} escapes the ones a 16-bit word cannot hold.
     */
    public interface Decomposition {

        String name();

        int[] encode(int[] deltas);

        /**
         * @param count the number of deltas to reconstruct, known from the vertex count
         */
        int[] decode(int[] tokens, int count);

        /**
         * Every decomposition the benchmark sweeps, {@link Plain} first as the 16-bit reference.
         */
        static List<Decomposition> all() {
            return List.of(
                    new Plain(),
                    new Chunk(16), new Chunk(64), new Chunk(256),
                    new QuotRem(16), new QuotRem(64), new QuotRem(256),
                    new Pow2(),
                    new Snap());
        }
    }

    /**
     * One token per delta, {@code zigzag(d)} — {@code DELTA_ZIGZAG} on a fixed 16-bit word.
     */
    public static final class Plain implements Decomposition {

        @Override
        public String name() {
            return "PLAIN";
        }

        @Override
        public int[] encode(int[] deltas) {
            final var tokens = new int[deltas.length];
            for (var i = 0; i < deltas.length; i++) {
                tokens[i] = zigZag(deltas[i]);
            }
            return tokens;
        }

        @Override
        public int[] decode(int[] tokens, int count) {
            requireConsumed(count, tokens, this);
            final var deltas = new int[count];
            for (var i = 0; i < count; i++) {
                deltas[i] = unZigZag(tokens[i]);
            }
            return deltas;
        }
    }

    /**
     * A run of {@code +-S} tokens while the rest is at least {@code S}, then the remainder
     * ({@code |r| < S}, possibly 0). A token of magnitude {@code S} means "continue", so the stream
     * is self-delimiting; a long part-start jump becomes a run of one repeated word.
     */
    public static final class Chunk implements Decomposition {

        private final int step;

        public Chunk(int step) {
            this.step = step;
        }

        @Override
        public String name() {
            return "CHUNK" + step;
        }

        @Override
        public int[] encode(int[] deltas) {
            var count = 0;
            for (final var d : deltas) {
                count += (int) (Math.abs((long) d) / step) + 1;
            }
            final var tokens = new int[count];
            var t = 0;
            for (final var d : deltas) {
                final long sign = d < 0 ? -1 : 1;
                var rest = Math.abs((long) d);
                final var full = zigZag(sign * step);
                for (; rest >= step; rest -= step) {
                    tokens[t++] = full;
                }
                tokens[t++] = zigZag(sign * rest);
            }
            return tokens;
        }

        @Override
        public int[] decode(int[] tokens, int count) {
            final var deltas = new int[count];
            var t = 0;
            for (var i = 0; i < count; i++) {
                long d = 0;
                int part;
                do {
                    part = unZigZag(tokens[t++]);
                    d += part;
                } while (Math.abs(part) == step);
                deltas[i] = (int) d;
            }
            requireConsumed(t, tokens, this);
            return deltas;
        }
    }

    /**
     * {@code d = q * S + r} with {@code 0 <= r < S}: every {@code zigzag(q)} of the buffer, then
     * every {@code r}. Two tokens per delta.
     */
    public static final class QuotRem implements Decomposition {

        private final int step;

        public QuotRem(int step) {
            this.step = step;
        }

        @Override
        public String name() {
            return "QUOTREM" + step;
        }

        @Override
        public int[] encode(int[] deltas) {
            final var n = deltas.length;
            final var tokens = new int[2 * n];
            for (var i = 0; i < n; i++) {
                tokens[i] = zigZag(Math.floorDiv(deltas[i], step));
                tokens[n + i] = Math.floorMod(deltas[i], step);
            }
            return tokens;
        }

        @Override
        public int[] decode(int[] tokens, int count) {
            requireConsumed(2 * count, tokens, this);
            final var deltas = new int[count];
            for (var i = 0; i < count; i++) {
                deltas[i] = unZigZag(tokens[i]) * step + tokens[count + i];
            }
            return deltas;
        }
    }

    /**
     * The set bits of {@code |d|}, high to low, each as a signed exponent token {@code +-(k + 1)}
     * standing for {@code +-2^k}, then a terminator token 0. A vocabulary of 65 symbols for every
     * layer.
     */
    public static final class Pow2 implements Decomposition {

        @Override
        public String name() {
            return "POW2";
        }

        @Override
        public int[] encode(int[] deltas) {
            var count = 0;
            for (final var d : deltas) {
                count += Long.bitCount(Math.abs((long) d)) + 1;
            }
            final var tokens = new int[count];
            var t = 0;
            for (final var d : deltas) {
                final long sign = d < 0 ? -1 : 1;
                final var magnitude = Math.abs((long) d);
                for (var k = 63 - Long.numberOfLeadingZeros(magnitude); k >= 0; k--) {
                    if ((magnitude >>> k & 1) != 0) {
                        tokens[t++] = zigZag(sign * (k + 1));
                    }
                }
                tokens[t++] = 0;
            }
            return tokens;
        }

        @Override
        public int[] decode(int[] tokens, int count) {
            final var deltas = new int[count];
            var t = 0;
            for (var i = 0; i < count; i++) {
                long d = 0;
                for (int exponent; (exponent = unZigZag(tokens[t++])) != 0; ) {
                    d += Long.signum(exponent) * (1L << (Math.abs(exponent) - 1));
                }
                deltas[i] = (int) d;
            }
            requireConsumed(t, tokens, this);
            return deltas;
        }
    }

    /**
     * {@code |d|} snapped down to the largest entry of a fixed log-spaced table {@code {0, 1, 2, 3, 4,
     * 6, 8, 12, 16, 24, ...}} (powers of two and their 1.5 multiples): every {@code zigzag(+-index)} of
     * the buffer, then every correction {@code |d| - T[index]}. Two tokens per delta.
     */
    public static final class Snap implements Decomposition {

        private static final long[] TABLE = table();

        private static long[] table() {
            final var table = new ArrayList<Long>(List.of(0L, 1L));
            for (var k = 1; k <= 31; k++) {
                table.add(1L << k);
                table.add(3L << (k - 1));
            }
            return table.stream().mapToLong(Long::longValue).toArray();
        }

        @Override
        public String name() {
            return "SNAP";
        }

        @Override
        public int[] encode(int[] deltas) {
            final var n = deltas.length;
            final var tokens = new int[2 * n];
            for (var i = 0; i < n; i++) {
                final var magnitude = Math.abs((long) deltas[i]);
                var index = Arrays.binarySearch(TABLE, magnitude);
                if (index < 0) {
                    index = -index - 2;
                }
                tokens[i] = zigZag(deltas[i] < 0 ? -index : index);
                tokens[n + i] = (int) (magnitude - TABLE[index]);
            }
            return tokens;
        }

        @Override
        public int[] decode(int[] tokens, int count) {
            requireConsumed(2 * count, tokens, this);
            final var deltas = new int[count];
            for (var i = 0; i < count; i++) {
                final var index = unZigZag(tokens[i]);
                final var magnitude = TABLE[Math.abs(index)] + Integer.toUnsignedLong(tokens[count + i]);
                deltas[i] = (int) (index < 0 ? -magnitude : magnitude);
            }
            return deltas;
        }
    }
}
