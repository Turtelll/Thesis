package org.maplibre.mlt.research.cli;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;

/**
 * Sizes every row the way a browser receives it: every buffer of one tile concatenated (one tile is
 * one HTTP response) and compressed on its own, summed over tiles. A saving that only removes
 * redundancy the compressor would have removed anyway disappears here.
 *
 * <p>Columns per row: {@link #RAW} payload bytes, {@link #ORDER0} — the order-0 byte entropy of each
 * tile's bytes (what a single static Huffman table could reach before any LZ77 match; the gap down
 * to a compressed column is what match-finding and context modelling found) — then one column per
 * compressor, gzip at {@link #GZIP}.
 *
 * <p>Tiles are compressed as they complete, so only one tile's payloads are ever held; the caller
 * must visit a tile's buffers contiguously and call {@link #endTile} between tiles.
 */
final class TileCompression {

    static final int RAW = 0;
    static final int ORDER0 = 1;
    /** {@link Compressor#all} lists gzip first. */
    static final int GZIP = 2;

    private final List<Compressor> compressors;
    private final Map<String, ByteArrayOutputStream> tile = new LinkedHashMap<>();
    private final Map<String, long[]> totals = new LinkedHashMap<>();
    private final Map<Integer, Map<String, long[]>> byZoom = new TreeMap<>();
    private int tiles;

    TileCompression(List<Compressor> compressors) {
        this.compressors = compressors;
    }

    void add(String row, byte[] payload) {
        tile.computeIfAbsent(row, k -> new ByteArrayOutputStream()).writeBytes(payload);
    }

    /** Compresses the tile collected since the last call, once per row and compressor. */
    void endTile(int zoom) {
        if (tile.isEmpty()) {
            return;
        }
        var rows = new ArrayList<>(tile.keySet());
        var data = rows.stream().map(row -> tile.get(row).toByteArray()).toList();
        var columns = compressors.size() + 2;
        var tasks = compressors.size() + 1;
        var sizes = new long[rows.size()][columns];
        IntStream.range(0, rows.size() * tasks).parallel().forEach(task -> {
            var row = task / tasks;
            var column = task % tasks;
            var bytes = data.get(row);
            if (column == 0) {
                sizes[row][RAW] = bytes.length;
                sizes[row][ORDER0] = order0Bytes(bytes);
            } else {
                sizes[row][column + 1] = compressors.get(column - 1).size(bytes);
            }
        });
        var zoomTotals = byZoom.computeIfAbsent(zoom, z -> new LinkedHashMap<>());
        for (var i = 0; i < rows.size(); i++) {
            for (var into : List.of(
                    totals.computeIfAbsent(rows.get(i), k -> new long[columns]),
                    zoomTotals.computeIfAbsent(rows.get(i), k -> new long[columns]))) {
                for (var c = 0; c < columns; c++) {
                    into[c] += sizes[i][c];
                }
            }
        }
        tile.clear();
        tiles++;
    }

    /**
     * Bytes a static order-0 code over this tile's byte histogram would need: {@code
     * ceil(n * H / 8)}, the entropy lower bound for any per-byte code with no context and no
     * matches.
     */
    static long order0Bytes(byte[] bytes) {
        if (bytes.length == 0) {
            return 0;
        }
        var counts = new int[256];
        for (var b : bytes) {
            counts[b & 0xFF]++;
        }
        var bits = 0.0;
        for (var count : counts) {
            if (count > 0) {
                bits -= count * (Math.log((double) count / bytes.length) / Math.log(2));
            }
        }
        return (long) Math.ceil(bits / 8);
    }

    /**
     * Bytes an order-1 code would need: each byte coded with the statistics of the bytes that follow
     * the same predecessor, the first byte at 8 bits. Like {@link #order0Bytes} it compresses nothing,
     * but it sees the byte-to-byte structure a layout or terminal choice changes.
     */
    static long order1Bytes(byte[] bytes) {
        if (bytes.length == 0) {
            return 0;
        }
        var counts = new int[256 * 256];
        var contexts = new int[256];
        for (var i = 1; i < bytes.length; i++) {
            var context = bytes[i - 1] & 0xFF;
            counts[context << 8 | (bytes[i] & 0xFF)]++;
            contexts[context]++;
        }
        var bits = 8.0;
        for (var context = 0; context < 256; context++) {
            if (contexts[context] == 0) {
                continue;
            }
            for (var symbol = 0; symbol < 256; symbol++) {
                var count = counts[context << 8 | symbol];
                if (count > 0) {
                    bits -= count * (Math.log((double) count / contexts[context]) / Math.log(2));
                }
            }
        }
        return (long) Math.ceil(bits / 8);
    }

    /** Per row, the column sums over every tile. */
    Map<String, long[]> totals() {
        return totals;
    }

    /** {@link #totals()} split by tile zoom. */
    Map<Integer, Map<String, long[]>> byZoom() {
        return byZoom;
    }

    int tiles() {
        return tiles;
    }

    List<Compressor> compressors() {
        return compressors;
    }
}
