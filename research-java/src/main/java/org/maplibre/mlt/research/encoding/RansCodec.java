package org.maplibre.mlt.research.encoding;

import java.util.ArrayList;
import java.util.List;

/**
 * Static rANS over an exponential bucket alphabet, with the sub-bucket bits passed through raw.
 *
 * <p>This is the coder WP7 costed but did not build. It exists to replace that cost-model estimate
 * with a measured number, because the estimate is a ceiling in a direction that cannot be signed:
 * order-0 entropy assumes an ideal coder with no per-symbol overhead and no table cost, so it
 * overstates what is achievable, while the bucket decomposition this coder actually uses is itself
 * lossy relative to order-0 (the raw bits below a bucket are not uniformly distributed). Only a real
 * encode/decode round trip settles where the two land relative to FastPFOR.
 *
 * <p><b>Alphabet.</b> A residual is split into its bit-length {@code bucket} (the entropy-coded
 * symbol, at most 33 values) and the {@code bucket-1} low bits below the leading one (written raw).
 * This keeps the alphabet small enough that a per-buffer frequency table is affordable, which is
 * what makes the coder self-contained: no corpus-trained table has to be shipped alongside the
 * tile, and no context has to be plumbed through {@link IntCodec}, which is context-free by design.
 *
 * <p><b>Why the table is per-buffer.</b> The alternative — one table trained over the corpus — is
 * cheaper per tile but only valid for tiles resembling the training set. A sparse 33-symbol table
 * costs at most ~100 bytes and typically far less (see below), small enough that per-buffer
 * adaptivity is worth more than the bytes it costs, and it keeps the measurement honest: every byte
 * the decoder needs is inside the payload being counted.
 *
 * <p><b>Stream layout.</b> rANS decodes in the reverse order of encoding, so the encoder walks
 * values back-to-front and the decoder front-to-back. The raw bits are <em>not</em> interleaved into
 * the rANS state; they go in their own forward-written bit region. Interleaving them would force the
 * raw reader to run backwards too, which buys nothing — raw bits are incompressible by definition,
 * so their position carries no modelling benefit and costs a reversed reader.
 */
public final class RansCodec implements IntCodec {

    /**
     * Frequencies are normalised to sum to exactly {@code 1 << PRECISION}.
     */
    private static final int PRECISION = 12;

    private static final int TOTAL = 1 << PRECISION;

    /**
     * 32-bit state with 16-bit renormalisation: the standard rANS working range.
     */
    private static final long STATE_LOWER = 1L << 16;

    private static final int MAX_SYMBOL = 33;

    @Override
    public String name() {
        return "RANS";
    }

    // ---------------------------------------------------------------------------
    // Bucket split
    // ---------------------------------------------------------------------------

    /**
     * Bit-length of a (zigzag, hence non-negative) residual: the entropy-coded symbol.
     */
    private static int bucket(int value) {
        return 32 - Integer.numberOfLeadingZeros(value);
    }

    /**
     * Number of raw bits carried below the leading one.
     */
    private static int rawBitCount(int bucket) {
        return bucket <= 1 ? 0 : bucket - 1;
    }

    // ---------------------------------------------------------------------------
    // Encode
    // ---------------------------------------------------------------------------

    @Override
    public byte[] encode(int[] values) {
        final var freq = normalisedFrequencies(values);
        final var cumulative = cumulative(freq);

        // Raw bits are written forward, in value order, so the decoder can read them the same way.
        final var raw = new BitWriter();
        for (final var value : values) {
            final var b = bucket(value);
            final var bits = rawBitCount(b);
            if (bits > 0) {
                // The leading one is implied by the bucket, so only the bits below it are stored.
                raw.write(value & ((1 << bits) - 1), bits);
            }
        }

        // rANS proper: encode backwards so the decoder recovers values front-to-front.
        var state = STATE_LOWER;
        final var ransBytes = new ArrayList<Byte>();
        for (var i = values.length - 1; i >= 0; i--) {
            final var symbol = bucket(values[i]);
            final int f = freq[symbol];
            // A symbol that never occurs has no code; normalisation guarantees f>0 for present symbols.
            final var max = ((STATE_LOWER >>> PRECISION) << 8) * f;
            while (state >= max) {
                ransBytes.add((byte) (state & 0xFF));
                state >>>= 8;
            }
            state = ((state / f) << PRECISION) + (state % f) + cumulative[symbol];
        }

        final var out = new ByteList();
        writeTable(out, freq);
        out.addInt(values.length);
        out.addInt((int) state);
        // The rANS bytes were produced back-to-front; reverse them so the decoder reads forward.
        out.addInt(ransBytes.size());
        for (var i = ransBytes.size() - 1; i >= 0; i--) {
            out.add(ransBytes.get(i));
        }
        final var rawBytes = raw.toBytes();
        out.addInt(rawBytes.length);
        out.addAll(rawBytes);
        return out.toArray();
    }

    // ---------------------------------------------------------------------------
    // Decode
    // ---------------------------------------------------------------------------

    @Override
    public int[] decode(byte[] data, int numValues) {
        final var in = new ByteReader(data);
        final var freq = readTable(in);
        final var cumulative = cumulative(freq);
        final var symbolLookup = symbolLookup(cumulative, freq);

        final var count = in.readInt();
        var state = in.readInt() & 0xFFFFFFFFL;
        final var ransLength = in.readInt();
        final var ransStart = in.position();
        in.skip(ransLength);
        final var rawLength = in.readInt();
        final var raw = new BitReader(data, in.position());
        in.skip(rawLength);

        final var ransReader = new ByteReader(data, ransStart, ransStart + ransLength);
        final var out = new int[count];
        for (var i = 0; i < count; i++) {
            final var slot = (int) (state & (TOTAL - 1));
            final var symbol = symbolLookup[slot];
            state = freq[symbol] * (state >>> PRECISION) + slot - cumulative[symbol];
            while (state < STATE_LOWER && ransReader.hasRemaining()) {
                state = (state << 8) | (ransReader.readByte() & 0xFFL);
            }
            final var bits = rawBitCount(symbol);
            out[i] = symbol == 0 ? 0 : (1 << (symbol - 1)) | (bits > 0 ? raw.read(bits) : 0);
        }
        return out;
    }

    // ---------------------------------------------------------------------------
    // Frequency table
    // ---------------------------------------------------------------------------

    /**
     * Counts bucket occurrences and scales them to sum to exactly {@link #TOTAL}, with every present
     * symbol guaranteed a non-zero frequency — a present symbol with frequency zero would be
     * unencodable, which is a correctness requirement, not a compression tradeoff.
     */
    private static int[] normalisedFrequencies(int[] values) {
        final var counts = new long[MAX_SYMBOL];
        for (final var v : values) {
            counts[bucket(v)]++;
        }
        var total = 0L;
        for (final var c : counts) {
            total += c;
        }
        final var freq = new int[MAX_SYMBOL];
        if (total == 0) {
            freq[0] = TOTAL;
            return freq;
        }

        var assigned = 0;
        for (var s = 0; s < MAX_SYMBOL; s++) {
            if (counts[s] == 0) {
                continue;
            }
            freq[s] = (int) Math.max(1, counts[s] * TOTAL / total);
            assigned += freq[s];
        }
        // Fix the rounding drift on the most frequent symbol, which can absorb it proportionally.
        var largest = 0;
        for (var s = 1; s < MAX_SYMBOL; s++) {
            if (freq[s] > freq[largest]) {
                largest = s;
            }
        }
        freq[largest] += TOTAL - assigned;
        if (freq[largest] <= 0) {
            // Pathological: too many distinct symbols for the precision. Fall back to a flat table over
            // present symbols, which is always encodable if less compact.
            return flatTable(counts);
        }
        return freq;
    }

    private static int[] flatTable(long[] counts) {
        var present = 0;
        for (final var c : counts) {
            if (c > 0) {
                present++;
            }
        }
        final var freq = new int[MAX_SYMBOL];
        final var share = TOTAL / present;
        var assigned = 0;
        var last = -1;
        for (var s = 0; s < MAX_SYMBOL; s++) {
            if (counts[s] > 0) {
                freq[s] = share;
                assigned += share;
                last = s;
            }
        }
        freq[last] += TOTAL - assigned;
        return freq;
    }

    private static int[] cumulative(int[] freq) {
        final var cum = new int[MAX_SYMBOL + 1];
        for (var s = 0; s < MAX_SYMBOL; s++) {
            cum[s + 1] = cum[s] + freq[s];
        }
        return cum;
    }

    /**
     * Slot-to-symbol map, so decode resolves a state slot without scanning the table.
     */
    private static int[] symbolLookup(int[] cumulative, int[] freq) {
        final var lookup = new int[TOTAL];
        for (var s = 0; s < MAX_SYMBOL; s++) {
            for (var i = cumulative[s]; i < cumulative[s] + freq[s]; i++) {
                lookup[i] = s;
            }
        }
        return lookup;
    }

    /**
     * Table format: {@code [1 B present count][present x (1 B symbol, 2 B frequency)]}, instead of
     * all 33 symbols unconditionally (a fixed 66 bytes, most of them zero — the alphabet stays sparse
     * even on the largest buffers, mean ~11 of 33 symbols present; see
     * {@code docs/results/shared-tables.md}). Sparse wins whenever a table has ≤21 present symbols,
     * which is effectively always; the worst case (all 33 present) costs 100 bytes instead of 66.
     */
    private static void writeTable(ByteList out, int[] freq) {
        var present = 0;
        for (var s = 0; s < MAX_SYMBOL; s++) {
            if (freq[s] != 0) {
                present++;
            }
        }
        out.add((byte) present);
        for (var s = 0; s < MAX_SYMBOL; s++) {
            if (freq[s] == 0) {
                continue;
            }
            out.add((byte) s);
            out.add((byte) (freq[s] & 0xFF));
            out.add((byte) ((freq[s] >>> 8) & 0xFF));
        }
    }

    private static int[] readTable(ByteReader in) {
        final var freq = new int[MAX_SYMBOL];
        final var present = in.readByte() & 0xFF;
        for (var i = 0; i < present; i++) {
            final var symbol = in.readByte() & 0xFF;
            final var lo = in.readByte() & 0xFF;
            final var hi = in.readByte() & 0xFF;
            freq[symbol] = lo | (hi << 8);
        }
        return freq;
    }

    // ---------------------------------------------------------------------------
    // Small byte/bit helpers
    // ---------------------------------------------------------------------------

    /**
     * Growable byte buffer; avoids a dependency for what is a few dozen lines.
     */
    private static final class ByteList {
        private byte[] data = new byte[256];
        private int size;

        void add(byte b) {
            if (size == data.length) {
                final var grown = new byte[data.length * 2];
                System.arraycopy(data, 0, grown, 0, size);
                data = grown;
            }
            data[size++] = b;
        }

        void addAll(byte[] bytes) {
            for (final var b : bytes) {
                add(b);
            }
        }

        void addInt(int v) {
            add((byte) (v & 0xFF));
            add((byte) ((v >>> 8) & 0xFF));
            add((byte) ((v >>> 16) & 0xFF));
            add((byte) ((v >>> 24) & 0xFF));
        }

        byte[] toArray() {
            final var out = new byte[size];
            System.arraycopy(data, 0, out, 0, size);
            return out;
        }
    }

    private static final class ByteReader {
        private final byte[] data;
        private final int limit;
        private int pos;

        ByteReader(byte[] data) {
            this(data, 0, data.length);
        }

        ByteReader(byte[] data, int pos) {
            this(data, pos, data.length);
        }

        /**
         * {@code limit} bounds the reader to one region of the payload; renormalisation must not
         * read past the end of the rANS bytes into the raw-bit region that follows them.
         */
        ByteReader(byte[] data, int pos, int limit) {
            this.data = data;
            this.pos = pos;
            this.limit = limit;
        }

        byte readByte() {
            return data[pos++];
        }

        int readInt() {
            return (data[pos++] & 0xFF)
                    | ((data[pos++] & 0xFF) << 8)
                    | ((data[pos++] & 0xFF) << 16)
                    | ((data[pos++] & 0xFF) << 24);
        }

        void skip(int n) {
            pos += n;
        }

        int position() {
            return pos;
        }

        boolean hasRemaining() {
            return pos < limit;
        }
    }

    /**
     * MSB-first bit writer for the raw sub-bucket bits.
     */
    private static final class BitWriter {
        private final List<Byte> bytes = new ArrayList<>();
        private int current;
        private int filled;

        void write(int value, int bits) {
            for (var i = bits - 1; i >= 0; i--) {
                current = (current << 1) | ((value >>> i) & 1);
                if (++filled == 8) {
                    bytes.add((byte) current);
                    current = 0;
                    filled = 0;
                }
            }
        }

        byte[] toBytes() {
            if (filled > 0) {
                bytes.add((byte) (current << (8 - filled)));
            }
            final var out = new byte[bytes.size()];
            for (var i = 0; i < out.length; i++) {
                out[i] = bytes.get(i);
            }
            return out;
        }
    }

    /**
     * MSB-first bit reader, mirroring {@link BitWriter}.
     */
    private static final class BitReader {
        private final byte[] data;
        private int bitPos;

        BitReader(byte[] data, int byteOffset) {
            this.data = data;
            this.bitPos = byteOffset * 8;
        }

        int read(int bits) {
            var value = 0;
            for (var i = 0; i < bits; i++) {
                final var byteIndex = bitPos >>> 3;
                final var bit = (data[byteIndex] >>> (7 - (bitPos & 7))) & 1;
                value = (value << 1) | bit;
                bitPos++;
            }
            return value;
        }
    }
}
