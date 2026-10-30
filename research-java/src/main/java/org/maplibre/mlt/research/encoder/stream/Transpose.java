package org.maplibre.mlt.research.encoder.stream;

/**
 * S7: byte layout of a fixed-width stream of {@code n} values ({@code x, y} interleaved, so value
 * {@code i} belongs to component {@code i & 1} of vertex {@code i >> 1}), each {@code width} bytes
 * little-endian. {@link #index} is where byte {@code b} of value {@code i} goes; one function drives
 * encoder and decoder, so they cannot disagree.
 */
public enum Transpose {

    /** Values stay interleaved: {@code x0 y0 x1 y1 ...}, each value's bytes together. */
    NONE(""),

    /** Byte planes of the interleaved values: every low byte (x, y alternating), then every second byte, ... */
    INTERLEAVED_PLANES("_PLANES"),

    /** Per plane, all x bytes then all y bytes: {@code x_lo, y_lo, x_hi, y_hi}. */
    PLANE_MAJOR("_PLANE_MAJOR"),

    /** Per component, all its planes: {@code x_lo, x_hi, y_lo, y_hi}. */
    COMPONENT_MAJOR("_COMPONENT_MAJOR");

    private final String suffix;

    Transpose(String suffix) {
        this.suffix = suffix;
    }

    /** Contribution to an encoding's name; empty for {@link #NONE}. */
    public String suffix() {
        return suffix;
    }

    /** Position of byte {@code b} of value {@code i} in a stream of {@code n} values of {@code width} bytes. */
    int index(int i, int b, int n, int width) {
        return switch (this) {
            case NONE -> i * width + b;
            case INTERLEAVED_PLANES -> b * n + i;
            case PLANE_MAJOR -> b * n + (i & 1) * (n >> 1) + (i >> 1);
            case COMPONENT_MAJOR -> (i & 1) * (n >> 1) * width + b * (n >> 1) + (i >> 1);
        };
    }

    /** {@code values}, each as {@code width} little-endian bytes, laid out by this transpose. */
    byte[] write(int[] values, int width) {
        final var n = values.length;
        final var out = new byte[n * width];
        for (var i = 0; i < n; i++) {
            for (var b = 0; b < width; b++) {
                out[index(i, b, n, width)] = (byte) (values[i] >>> (8 * b));
            }
        }
        return out;
    }

    /** The inverse of {@link #write}. */
    int[] read(byte[] data, int n, int width) {
        final var values = new int[n];
        for (var i = 0; i < n; i++) {
            var value = 0;
            for (var b = 0; b < width; b++) {
                value |= (data[index(i, b, n, width)] & 0xFF) << (8 * b);
            }
            values[i] = value;
        }
        return values;
    }
}