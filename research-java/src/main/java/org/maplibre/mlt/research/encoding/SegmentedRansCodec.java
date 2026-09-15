package org.maplibre.mlt.research.encoding;

/**
 * {@link RansCodec} with one frequency table per residual region instead of one shared table.
 *
 * <p>A transform like {@code SEGMENT_QUAD_CORNER} already packs its residuals into contiguous
 * regions (starts | interior | corners) whose distributions differ sharply. Coding them with a
 * single table prices every region at the mixture of all three, which is strictly worse than
 * pricing each at its own distribution — measured in {@code docs/results/rans-entropy-coder.md}
 * ("Does the region split help rANS?"), including the buffer-size threshold below which the extra
 * tables cost more than the split saves.
 *
 * <p><b>Why the boundaries are written into the payload.</b> {@link IntCodec} is context-free: it
 * receives a flat {@code int[]} and cannot see the topology streams the transform used to place its
 * regions. Rather than widen that interface for one codec, the encoder writes the region lengths
 * into the payload it produces. This keeps the codec a drop-in {@code IntCodec}, keeps every byte
 * the decoder needs inside the measured payload, and costs 4 bytes per boundary — already included
 * in every number the benchmark reports.
 *
 * <p><b>Fallback.</b> When the supplied splitter yields no usable split — a transform that does not
 * segment, or a buffer whose topology gives no boundaries — this degrades to a single region, which
 * is exactly {@link RansCodec}. That keeps it safe to pair with any transform.
 */
public final class SegmentedRansCodec implements IntCodec {

    private final RansCodec rans = new RansCodec();
    private final String name;

    /**
     * Region lengths for the next {@link #encode} call, in int slots.
     *
     * <p>The regions depend on the buffer's topology, which {@link IntCodec} cannot see — it receives
     * a flat {@code int[]}. Rather than widen that interface for one codec, the owning {@link
     * SegmentedEncoding} sets this immediately before each encode. Decoding needs no such hint: the
     * lengths travel in the payload, so {@link #decode} stays context-free like every other codec.
     *
     * <p>This makes the codec stateful between those two calls and therefore <b>not thread-safe</b>,
     * unlike the stateless codecs it sits beside. {@link SegmentedEncoding} owns one instance per
     * encoding and the benchmark encodes one buffer at a time, so the coupling stays local.
     */
    private int[] pendingRegions;

    public SegmentedRansCodec(String name) {
        this.name = name;
    }

    void setPendingRegions(int[] regions) {
        this.pendingRegions = regions;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public byte[] encode(int[] values) {
        final var lengths = validated(pendingRegions, values.length);
        pendingRegions = null;

        final var parts = new byte[lengths.length][];
        var offset = 0;
        for (var i = 0; i < lengths.length; i++) {
            final var region = new int[lengths[i]];
            System.arraycopy(values, offset, region, 0, lengths[i]);
            parts[i] = rans.encode(region);
            offset += lengths[i];
        }

        var total = 4 + lengths.length * 8;
        for (final var p : parts) {
            total += p.length;
        }
        final var out = new byte[total];
        var pos = writeInt(out, 0, lengths.length);
        for (var i = 0; i < lengths.length; i++) {
            pos = writeInt(out, pos, lengths[i]);
            pos = writeInt(out, pos, parts[i].length);
        }
        for (final var p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    @Override
    public int[] decode(byte[] data, int numValues) {
        final var regions = readInt(data, 0);
        final var lengths = new int[regions];
        final var sizes = new int[regions];
        var pos = 4;
        for (var i = 0; i < regions; i++) {
            lengths[i] = readInt(data, pos);
            sizes[i] = readInt(data, pos + 4);
            pos += 8;
        }

        final var out = new int[numValues];
        var valueOffset = 0;
        for (var i = 0; i < regions; i++) {
            final var region = new byte[sizes[i]];
            System.arraycopy(data, pos, region, 0, sizes[i]);
            final var decoded = rans.decode(region, lengths[i]);
            System.arraycopy(decoded, 0, out, valueOffset, lengths[i]);
            valueOffset += lengths[i];
            pos += sizes[i];
        }
        return out;
    }

    /**
     * Falls back to a single region unless the splitter's lengths tile the stream exactly.
     */
    private static int[] validated(int[] lengths, int total) {
        if (lengths == null || lengths.length == 0) {
            return new int[]{total};
        }
        var sum = 0;
        for (final var l : lengths) {
            if (l < 0) {
                return new int[]{total};
            }
            sum += l;
        }
        return sum == total ? lengths : new int[]{total};
    }

    private static int writeInt(byte[] out, int pos, int v) {
        out[pos] = (byte) (v & 0xFF);
        out[pos + 1] = (byte) ((v >>> 8) & 0xFF);
        out[pos + 2] = (byte) ((v >>> 16) & 0xFF);
        out[pos + 3] = (byte) ((v >>> 24) & 0xFF);
        return pos + 4;
    }

    private static int readInt(byte[] data, int pos) {
        return (data[pos] & 0xFF)
                | ((data[pos + 1] & 0xFF) << 8)
                | ((data[pos + 2] & 0xFF) << 16)
                | ((data[pos + 3] & 0xFF) << 24);
    }
}
