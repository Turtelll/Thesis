package org.maplibre.mlt.research.measurement.compression;

import com.aayushatharva.brotli4j.Brotli4jLoader;
import com.aayushatharva.brotli4j.encoder.Encoder;
import com.github.luben.zstd.Zstd;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.ToLongFunction;
import java.util.zip.GZIPOutputStream;

/**
 * A general-purpose compressor a tile is delivered under. {@code size} is the compressed size of
 * the given bytes, one-shot, so zstd and Brotli size their window and tables to the input the way a
 * server compressing one response would.
 */
public record Compressor(String label, ToLongFunction<byte[]> size) {

    /** Default level 6. */
    public static final Compressor GZIP = new Compressor("gzip", Compressor::gzip);

    /** Level 19, zstd's highest outside the memory-hungry {@code --ultra} range. */
    private static final int ZSTD_LEVEL = 19;

    /**
     * {@link #GZIP} first, then Brotli at {@code brotliQuality} (11 is what static assets are
     * precompressed at, but runs roughly ten times slower than 9), then zstd.
     */
    public static List<Compressor> all(int brotliQuality) {
        return List.of(
                GZIP,
                new Compressor("brotli" + brotliQuality, data -> brotli(data, brotliQuality)),
                new Compressor(
                        "zstd" + ZSTD_LEVEL,
                        data -> data.length == 0 ? 0 : Zstd.compress(data, ZSTD_LEVEL).length));
    }

    public long size(byte[] data) {
        return size.applyAsLong(data);
    }

    private static long gzip(byte[] data) {
        if (data.length == 0) {
            return 0;
        }
        var compressed = new ByteArrayOutputStream();
        try (var out = new GZIPOutputStream(compressed)) {
            out.write(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return compressed.size();
    }

    private static long brotli(byte[] data, int quality) {
        if (data.length == 0) {
            return 0;
        }
        Brotli4jLoader.ensureAvailability();
        try {
            return Encoder.compress(data, new Encoder.Parameters().setQuality(quality)).length;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
