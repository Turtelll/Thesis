package org.maplibre.mlt.research.probe;

import java.io.ByteArrayOutputStream;

/** The few numeric helpers the frame-prior probes share: angle folding and zigzag varint bytes. */
final class ProbeMath {
    private ProbeMath() {
    }

    /** Folds degrees into [-45, 45). */
    static double fold(double deg) {
        return ((deg + 45) % 90 + 90) % 90 - 45;
    }

    static double angleDiff(double a, double b) {
        return Math.abs(fold(a - b));
    }

    static long zigzag(long value) {
        return (value << 1) ^ (value >> 63);
    }

    static int bits(long value) {
        return 64 - Long.numberOfLeadingZeros(zigzag(value));
    }

    static void writeVarint(ByteArrayOutputStream out, long value) {
        var zz = zigzag(value);
        while ((zz & ~0x7FL) != 0) {
            out.write((int) (zz & 0x7F) | 0x80);
            zz >>>= 7;
        }
        out.write((int) zz);
    }
}