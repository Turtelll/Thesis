package org.maplibre.mlt.research.encoder.stream;

import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.decoder.DecodingUtils;

import java.io.ByteArrayOutputStream;

/**
 * Stages S4, S6 and S7 for one stream: residual map, byte width and transpose, written as the
 * stream's bytes. Streams are written one after another in stream order; empty streams write
 * nothing, since the decoder knows every stream's value count from the topology streams.
 */
public final class StreamCodec {

    private static final int ESCAPE_MARKER = 0xFF;
    private static final int WIDTH_MASK = 0b11;
    private static final int ESCAPE_FLAG = 1 << 2;
    private static final int EXCEPTION_SHIFT = 3;

    private StreamCodec() {
    }

    /** What one stream is written with: the residual map, the byte width and the transpose. */
    public record Format(ResidualMap map, ByteWidth width, Transpose transpose) {

        public Format withTranspose(Transpose value) {
            return new Format(map, width, value);
        }
    }

    /** Unsigned range of a mapped stream, and how many values an escape stream would split out. */
    private record Span(long max, int exceptions, long maxException) {
    }

    /**
     * Appends one non-empty stream of signed residuals ({@code x, y} interleaved).
     *
     * @param last whether no non-empty stream follows; a stream whose byte length depends on its
     *             values signals it, except the last, which takes the rest of the payload
     */
    public static void encode(ByteArrayOutputStream out, int[] signed, Format config, boolean last) {
        final var side = config.map().side(signed);
        for (final var value : side) {
            PayloadBytes.putVarint(out, EncodingUtils.encodeZigZag(value));
        }
        final var mapped = config.map().map(signed, side);
        if (config.width().varint() == null) {
            writeFixed(out, mapped, config);
            return;
        }
        final var body = config.width().varint().encode(mapped);
        if (!last) {
            PayloadBytes.putVarint(out, body.length);
        }
        out.writeBytes(body);
    }

    /** One fixed-width stream: descriptor, main stream, exceptions (see {@link ByteWidth}). */
    private static void writeFixed(ByteArrayOutputStream out, int[] mapped, Format config) {
        final var transpose = config.transpose();
        if (config.width() == ByteWidth.FORCE_4) {
            out.writeBytes(transpose.write(mapped, 4));
            return;
        }
        final var span = span(mapped);
        final var width = ByteWidth.minWidth(span.max);
        final var exceptionWidth = ByteWidth.minWidth(span.maxException);
        if (escapePays(config, mapped.length, width, exceptionWidth, span.exceptions)) {
            writeEscaped(out, mapped, transpose, span.exceptions, exceptionWidth);
            return;
        }
        out.write(ByteWidth.codeOf(width));
        out.writeBytes(transpose.write(mapped, width));
    }

    private static Span span(int[] mapped) {
        var max = 0L;
        var exceptions = 0;
        var maxException = 0L;
        for (final var value : mapped) {
            final var unsigned = value & 0xFFFFFFFFL;
            max = Math.max(max, unsigned);
            if (unsigned >= ESCAPE_MARKER) {
                exceptions++;
                maxException = Math.max(maxException, unsigned - ESCAPE_MARKER);
            }
        }
        return new Span(max, exceptions, maxException);
    }

    /** Escape is smaller than a plain fixed width only when a 1-byte main plus exceptions beats it. */
    private static boolean escapePays(
            Format config, int n, int width, int exceptionWidth, int exceptions) {
        return config.width() == ByteWidth.ESCAPE
                && width > 1
                && (long) n + (long) exceptions * exceptionWidth < (long) n * width;
    }

    private static void writeEscaped(
            ByteArrayOutputStream out, int[] mapped, Transpose transpose, int exceptions, int exceptionWidth) {
        final var main = new int[mapped.length];
        final var tail = new int[exceptions];
        var next = 0;
        for (var i = 0; i < mapped.length; i++) {
            final var unsigned = mapped[i] & 0xFFFFFFFFL;
            if (unsigned >= ESCAPE_MARKER) {
                main[i] = ESCAPE_MARKER;
                tail[next++] = (int) (unsigned - ESCAPE_MARKER);
            } else {
                main[i] = mapped[i];
            }
        }
        out.write(ByteWidth.codeOf(1) | ESCAPE_FLAG | ByteWidth.codeOf(exceptionWidth) << EXCEPTION_SHIFT);
        out.writeBytes(transpose.write(main, 1));
        out.writeBytes(Transpose.INTERLEAVED_PLANES.write(tail, exceptionWidth));
    }

    private static int[] readFixed(PayloadBytes.Reader in, int n, Format config) {
        final var transpose = config.transpose();
        if (config.width() == ByteWidth.FORCE_4) {
            return transpose.read(in.readBytes(n * 4), n, 4);
        }
        final var descriptor = in.readByte();
        final var width = ByteWidth.widthOf(descriptor & WIDTH_MASK);
        final var main = transpose.read(in.readBytes(n * width), n, width);
        if ((descriptor & ESCAPE_FLAG) == 0) {
            return main;
        }
        final var exceptionWidth = ByteWidth.widthOf((descriptor >> EXCEPTION_SHIFT) & WIDTH_MASK);
        var exceptions = 0;
        for (final var value : main) {
            if (value == ESCAPE_MARKER) {
                exceptions++;
            }
        }
        final var tail = Transpose.INTERLEAVED_PLANES.read(
                in.readBytes(exceptions * exceptionWidth), exceptions, exceptionWidth);
        var next = 0;
        for (var i = 0; i < n; i++) {
            if (main[i] == ESCAPE_MARKER) {
                main[i] = ESCAPE_MARKER + tail[next++];
            }
        }
        return main;
    }

    /** The inverse of {@link #encode}: the signed residuals of a stream of {@code valueCount} values. */
    public static int[] decode(PayloadBytes.Reader in, int valueCount, Format config, boolean last) {
        final var side = new int[config.map().sideValues()];
        for (var i = 0; i < side.length; i++) {
            side[i] = DecodingUtils.decodeZigZag(in.readVarint());
        }
        final int[] mapped;
        if (config.width().varint() == null) {
            mapped = readFixed(in, valueCount, config);
        } else {
            final var body = last ? in.readRest() : in.readBytes(in.readVarint());
            mapped = config.width().varint().decode(body, valueCount);
        }
        return config.map().unmap(mapped, side);
    }
}