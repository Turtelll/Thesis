package org.maplibre.mlt.research.encoder.stream;

import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.decoder.DecodingUtils;

/**
 * S4: maps signed residuals to unsigned values (as 32-bit patterns; a value above
 * {@code Integer.MAX_VALUE} is a negative {@code int}). The stream is {@code x, y} interleaved.
 * Zigzag is the only map; the bias map was measured and removed.
 */
public enum ResidualMap {

    /** {@code (n << 1) ^ (n >> 31)}: small magnitudes of either sign become small values. */
    ZIGZAG;

    /** How many signed side values {@link #side} returns. Zigzag carries none. */
    public int sideValues() {
        return 0;
    }

    /** The side information the decoder needs. Zigzag needs none. */
    public int[] side(int[] signed) {
        return new int[0];
    }

    /** Signed residual to unsigned value. */
    public int[] map(int[] signed, int[] side) {
        final var mapped = new int[signed.length];
        for (var i = 0; i < signed.length; i++) {
            mapped[i] = EncodingUtils.encodeZigZag(signed[i]);
        }
        return mapped;
    }

    /** The inverse of {@link #map}. */
    public int[] unmap(int[] mapped, int[] side) {
        final var signed = new int[mapped.length];
        for (var i = 0; i < mapped.length; i++) {
            signed[i] = DecodingUtils.decodeZigZag(mapped[i]);
        }
        return signed;
    }
}
