package org.maplibre.mlt.research.encoding.research.failed;

import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.decoder.DecodingUtils;
import org.maplibre.mlt.research.encoding.IntCodec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;

/**
 * {@link IntCodec#VARINT_SPLIT} with a Burrows–Wheeler and/or move-to-front stage in front of the
 * general-purpose compressor, as in bzip2. Neither stage shrinks the stream: BWT sorts symbols by
 * the context that follows them, MTF turns the resulting locality into small ranks and zero runs.
 *
 * <p>The {@link Domain} decides what the stage sees — the zigzag residuals before the varint
 * ({@code INT}), or the serialized varint bytes ({@code BYTE}). With BWT, the primary index leads
 * the payload as a varint.
 */
public final class BwtMtfCodec implements IntCodec {

    public enum Domain {
        INT,
        BYTE
    }

    public enum Stage {
        MTF(false, true),
        BWT(true, false),
        BWT_MTF(true, true);

        private final boolean bwt;
        private final boolean mtf;

        Stage(boolean bwt, boolean mtf) {
            this.bwt = bwt;
            this.mtf = mtf;
        }
    }

    /**
     * Moved symbols the MTF list holds ahead of the implicit natural order; a symbol falling off
     * returns to its natural place. Covers the whole byte alphabet, so over bytes this is plain MTF.
     */
    static final int MTF_FRONT = 256;

    private static final IntCodec INNER = IntCodec.VARINT_SPLIT;

    private final Domain domain;
    private final Stage stage;

    public BwtMtfCodec(Domain domain, Stage stage) {
        this.domain = domain;
        this.stage = stage;
    }

    @Override
    public String name() {
        return domain + "_" + stage;
    }

    @Override
    public String toString() {
        return name();
    }

    @Override
    public byte[] encode(int[] values) {
        final var symbols = domain == Domain.INT ? values : unsigned(INNER.encode(values));
        var primary = 0;
        var transformed = symbols;
        if (stage.bwt) {
            final var bwt = bwt(transformed);
            transformed = bwt.symbols();
            primary = bwt.primary();
        }
        if (stage.mtf) {
            transformed = mtf(transformed);
        }
        final var body = domain == Domain.INT ? INNER.encode(transformed) : bytes(transformed);
        try {
            final var out = new ByteArrayOutputStream();
            if (stage.bwt) {
                EncodingUtils.putVarInt(new DataOutputStream(out), primary);
            }
            out.write(body);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public int[] decode(byte[] data, int numValues) {
        try {
            final var in = new ByteArrayInputStream(data);
            final var primary = stage.bwt ? DecodingUtils.decodeVarint(in) : 0;
            final var body = in.readAllBytes();
            var symbols = domain == Domain.INT ? INNER.decode(body, numValues) : unsigned(body);
            if (stage.mtf) {
                symbols = inverseMtf(symbols);
            }
            if (stage.bwt) {
                symbols = inverseBwt(symbols, primary);
            }
            return domain == Domain.INT ? symbols : INNER.decode(bytes(symbols), numValues);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int[] unsigned(byte[] bytes) {
        final var symbols = new int[bytes.length];
        for (var i = 0; i < bytes.length; i++) {
            symbols[i] = bytes[i] & 0xFF;
        }
        return symbols;
    }

    private static byte[] bytes(int[] symbols) {
        final var out = new byte[symbols.length];
        for (var i = 0; i < symbols.length; i++) {
            out[i] = (byte) symbols[i];
        }
        return out;
    }

    // --- Burrows–Wheeler ----------------------------------------------------

    /**
     * The last column of the sorted suffixes of {@code symbols} plus a unique smallest sentinel,
     * with the sentinel's own entry left out and its row returned as {@code primary}.
     */
    record Bwt(int[] symbols, int primary) {
    }

    static Bwt bwt(int[] symbols) {
        final var n = symbols.length;
        final var text = new int[n + 1];
        final var alphabet = denseRanks(symbols, text) + 1;
        final var sa = suffixArray(text, alphabet);
        final var out = new int[n];
        var primary = 0;
        var k = 0;
        for (var row = 0; row <= n; row++) {
            if (sa[row] == 0) {
                primary = row;
            } else {
                out[k++] = symbols[sa[row] - 1];
            }
        }
        return new Bwt(out, primary);
    }

    static int[] inverseBwt(int[] last, int primary) {
        final var n = last.length;
        final var values = new int[n + 1];
        for (int i = 0, k = 0; i <= n; i++) {
            values[i] = i == primary ? 0 : last[k++];
        }
        // Dense ranks of the symbols with the sentinel row at 0, so it sorts first.
        final var ranks = new int[n + 1];
        final var alphabet = denseRanks(last, ranks) + 1;
        System.arraycopy(ranks, primary, ranks, primary + 1, n - primary);
        ranks[primary] = 0;

        final var start = new int[alphabet + 1];
        for (final var r : ranks) {
            start[r + 1]++;
        }
        for (var c = 0; c < alphabet; c++) {
            start[c + 1] += start[c];
        }
        final var lf = new int[n + 1];
        for (var i = 0; i <= n; i++) {
            lf[i] = start[ranks[i]]++;
        }

        // Row 0 is the sentinel suffix, whose last-column symbol is the final input symbol.
        final var out = new int[n];
        var row = 0;
        for (var j = n - 1; j >= 0; j--) {
            out[j] = values[row];
            row = lf[row];
        }
        return out;
    }

    /**
     * Writes each symbol's rank among the distinct values (unsigned order, starting at 1) into
     * {@code ranks[0..n)} and 0 — the sentinel — at {@code ranks[n]} if there is room. Returns the
     * number of distinct values.
     */
    private static int denseRanks(int[] symbols, int[] ranks) {
        final var sorted = new long[symbols.length];
        for (var i = 0; i < symbols.length; i++) {
            sorted[i] = symbols[i] & 0xFFFFFFFFL;
        }
        Arrays.sort(sorted);
        var distinct = 0;
        for (var i = 0; i < sorted.length; i++) {
            if (i == 0 || sorted[i] != sorted[i - 1]) {
                sorted[distinct++] = sorted[i];
            }
        }
        for (var i = 0; i < symbols.length; i++) {
            ranks[i] = Arrays.binarySearch(sorted, 0, distinct, symbols[i] & 0xFFFFFFFFL) + 1;
        }
        if (ranks.length > symbols.length) {
            ranks[symbols.length] = 0;
        }
        return distinct;
    }

    /**
     * Suffix array by prefix doubling with two counting-sort passes per round, O(n log n); {@code
     * text} ends in its unique smallest symbol 0, and every symbol is below {@code alphabet}.
     */
    private static int[] suffixArray(int[] text, int alphabet) {
        final var n = text.length;
        var rank = text.clone();
        var next = new int[n];
        final var sa = new int[n];
        final var byKey2 = new int[n];
        final var key2 = new int[n];
        var buckets = Math.max(alphabet, n) + 1;
        var count = new int[buckets + 1];
        for (var k = 1; ; k <<= 1) {
            for (var i = 0; i < n; i++) {
                key2[i] = i + k < n ? rank[i + k] + 1 : 0;
            }
            countingSort(null, key2, byKey2, count);
            countingSort(byKey2, rank, sa, count);
            next[sa[0]] = 0;
            for (var i = 1; i < n; i++) {
                final var a = sa[i - 1];
                final var b = sa[i];
                next[b] = next[a] + (rank[a] != rank[b] || key2[a] != key2[b] ? 1 : 0);
            }
            final var swap = rank;
            rank = next;
            next = swap;
            if (rank[sa[n - 1]] == n - 1) {
                return sa;
            }
        }
    }

    /**
     * Stable counting sort of {@code order} (identity when {@code null}) by {@code keys}, into
     * {@code out}.
     */
    private static void countingSort(int[] order, int[] keys, int[] out, int[] count) {
        Arrays.fill(count, 0);
        for (final var key : keys) {
            count[key + 1]++;
        }
        for (var c = 1; c < count.length; c++) {
            count[c] += count[c - 1];
        }
        for (var i = 0; i < keys.length; i++) {
            final var index = order == null ? i : order[i];
            out[count[keys[index]]++] = index;
        }
    }

    // --- move-to-front -------------------------------------------------------

    /**
     * Rank of each symbol in a list of up to {@link #MTF_FRONT} recently used symbols, followed by
     * every other value in natural (unsigned) order. A symbol not in the front list ranks {@code v +
     * |front > v|}, so a value never seen before keeps a small rank when it is small.
     */
    static int[] mtf(int[] symbols) {
        final var front = new int[MTF_FRONT];
        var size = 0;
        final var out = new int[symbols.length];
        for (var i = 0; i < symbols.length; i++) {
            final var v = symbols[i];
            var position = -1;
            for (var p = 0; p < size && position < 0; p++) {
                if (front[p] == v) {
                    position = p;
                }
            }
            if (position >= 0) {
                out[i] = position;
            } else {
                out[i] = v + countGreater(front, size, v);
                position = size < MTF_FRONT ? size++ : MTF_FRONT - 1;
            }
            System.arraycopy(front, 0, front, 1, position);
            front[0] = v;
        }
        return out;
    }

    static int[] inverseMtf(int[] ranks) {
        final var front = new int[MTF_FRONT];
        var size = 0;
        final var out = new int[ranks.length];
        for (var i = 0; i < ranks.length; i++) {
            final var r = ranks[i];
            int v;
            int position;
            if (Integer.compareUnsigned(r, size) < 0) {
                position = r;
                v = front[r];
            } else {
                // Smallest v outside the front list with v - |front < v| == r - size.
                final var tail = r - size;
                v = tail;
                while (true) {
                    final var candidate = tail + countAtMost(front, size, v);
                    if (candidate == v) {
                        break;
                    }
                    v = candidate;
                }
                position = size < MTF_FRONT ? size++ : MTF_FRONT - 1;
            }
            System.arraycopy(front, 0, front, 1, position);
            front[0] = v;
            out[i] = v;
        }
        return out;
    }

    private static int countGreater(int[] front, int size, int v) {
        var count = 0;
        for (var p = 0; p < size; p++) {
            if (Integer.compareUnsigned(front[p], v) > 0) {
                count++;
            }
        }
        return count;
    }

    private static int countAtMost(int[] front, int size, int v) {
        var count = 0;
        for (var p = 0; p < size; p++) {
            if (Integer.compareUnsigned(front[p], v) <= 0) {
                count++;
            }
        }
        return count;
    }
}
