package org.maplibre.mlt.research.pipeline;

import org.maplibre.mlt.research.encoding.IntCodec;

/**
 * S6: how a stream's unsigned values become bytes. The fixed-width choices replace the varint
 * terminals of S0 to S5, which stay as comparison rows: a fixed width gives every value the same
 * number of bytes, which is what a byte-plane transpose (S7) needs.
 *
 * <p>Fixed-width streams carry a one-byte descriptor (main width, escape flag, exception width)
 * except {@link #FORCE_4}, and no length: the decoder derives the main stream's size from the
 * topology and counts the escape markers to size the exception stream.
 */
public enum ByteWidth {

    /** Protobuf-style varints, interleaved. The reference for the S0 anchor. */
    VARINT(IntCodec.VARINT),

    /** Varints with the first byte of every value in one run, continuation bytes after. */
    VARINT_SPLIT(IntCodec.VARINT_SPLIT),

    /** Per stream, the narrowest of 1, 2 or 4 bytes that holds its largest value. */
    MIN_FIXED(null),

    /**
     * Per stream, either {@link #MIN_FIXED} or, if smaller, a 1-byte main stream where {@code 0xFF}
     * marks an exception, followed by the exceptions ({@code value - 255}) at their own minimal
     * width, as byte planes. PFOR-like, byte-aligned.
     */
    ESCAPE(null),

    /** Every value 4 bytes. Test-only: the anchor for {@code IntCodec.BYTESPLIT}. */
    FORCE_4(null);

    private final IntCodec varint;

    ByteWidth(IntCodec varint) {
        this.varint = varint;
    }

    /** Whether the body length of a stream depends on its values (so it must be signalled). */
    public boolean variableLength() {
        return varint != null;
    }

    /** The varint codec behind a varint terminal, {@code null} for the fixed-width choices. */
    public IntCodec varint() {
        return varint;
    }

    /** Width in bytes behind a 2-bit code of the descriptor. */
    static int widthOf(int code) {
        return switch (code) {
            case 0 -> 1;
            case 1 -> 2;
            case 2 -> 4;
            default -> throw new IllegalStateException("bad width code " + code);
        };
    }

    /** The 2-bit descriptor code of a width of 1, 2 or 4 bytes. */
    static int codeOf(int width) {
        return switch (width) {
            case 1 -> 0;
            case 2 -> 1;
            default -> 2;
        };
    }

    /** The narrowest of 1, 2, 4 bytes that holds the unsigned {@code max}. */
    static int minWidth(long max) {
        if (max < 1L << 8) {
            return 1;
        }
        if (max < 1L << 16) {
            return 2;
        }
        return 4;
    }
}