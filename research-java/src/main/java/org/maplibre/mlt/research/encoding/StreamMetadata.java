package org.maplibre.mlt.research.encoding;

import org.maplibre.mlt.converter.encodings.EncodingUtils;

/**
 * Size of the {@code StreamMetadata} header MLT writes in front of an encoded stream.
 *
 * <p>The benchmark compares payload <em>plus</em> this header, since that is what lands in the tile:
 * an encoding that shrinks the payload but needs a longer header has not necessarily won. Kept here
 * so every {@link Encoding} accounts for its own cost the same way.
 */
public final class StreamMetadata {

    private StreamMetadata() {
    }

    /**
     * Two bit-packed bytes plus varint {@code numValues} and varint {@code byteLength}.
     */
    public static int bytes(int numValues, int byteLength) {
        return 2 + EncodingUtils.getVarIntSize(numValues) + EncodingUtils.getVarIntSize(byteLength);
    }
}
