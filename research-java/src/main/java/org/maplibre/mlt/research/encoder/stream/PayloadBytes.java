package org.maplibre.mlt.research.encoder.stream;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Byte-level helpers shared by the stream codec: unsigned LEB128 varints over the full 32 bits and a
 * cursor over a byte array. Kept here rather than borrowed from mlt-core so a value above
 * {@code Integer.MAX_VALUE} (a zigzag code stored as a signed int) is never reinterpreted.
 */
public final class PayloadBytes {

    private PayloadBytes() {
    }

    /** Writes {@code value} as an unsigned LEB128 varint (negative ints are the large unsigned values). */
    public static void putVarint(ByteArrayOutputStream out, int value) {
        var rest = value;
        while ((rest & ~0x7F) != 0) {
            out.write((rest & 0x7F) | 0x80);
            rest >>>= 7;
        }
        out.write(rest);
    }

    /** A cursor over a byte array. */
    public static final class Reader {
        private final byte[] data;
        private int position;

        public Reader(byte[] data, int position) {
            this.data = data;
            this.position = position;
        }

        public int readByte() {
            return data[position++] & 0xFF;
        }

        public int readVarint() {
            var value = 0;
            for (var shift = 0; ; shift += 7) {
                final var b = data[position++];
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
            }
        }

        /** The next {@code length} bytes. */
        public byte[] readBytes(int length) {
            final var bytes = Arrays.copyOfRange(data, position, position + length);
            position += length;
            return bytes;
        }

        public int remaining() {
            return data.length - position;
        }

        /** Everything not read yet. */
        byte[] readRest() {
            return readBytes(remaining());
        }
    }
}
