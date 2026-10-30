package org.maplibre.mlt.research.measurement.golden;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;

/**
 * Compares two golden dumps ({@link GoldenDump}): which rows, layers and buffers changed, by how many
 * bytes. Exit status 0 only if every compared row is byte for byte the same.
 */
public final class GoldenDiff {

    private GoldenDiff() {
    }

    /** What {@code --diff} compares and what is allowed to differ. */
    public record Options(Path oldDir, Path newDir, Set<String> only, Set<String> allowRemoved, int maxBuffers) {
    }


    private record Buffer(String sha, long payload, String emitted, long gzip, long brotli, long zstd) {
    }

    private record Tile(long payload, long order0, long gzip, long brotli, long zstd) {
    }

    /** Per-row differences. */
    private static final class RowDiff {
        long changed;
        long added;
        long removed;
        long payload;
        final long[] iso = new long[3];
        final Map<String, long[]> layers = new TreeMap<>(); // changed, payload, gzip, brotli, zstd deltas
        final List<String> samples = new ArrayList<>();
    }

    public static int run(Options opts) throws IOException {
        final var oldDir = opts.oldDir();
        final var newDir = opts.newDir();
        final var only = opts.only();
        final var allowRemoved = opts.allowRemoved();
        final var maxBuffers = opts.maxBuffers();

        final var before = new HashMap<String, Buffer>();
        final var oldRows = new TreeSet<String>();
        read(oldDir.resolve(GoldenDump.BUFFERS), fields -> {
            oldRows.add(fields[0]);
            before.put(bufferKey(fields), buffer(fields));
        });
        final var newRows = new TreeSet<String>();
        final var diffs = new TreeMap<String, RowDiff>();
        final var limit = maxBuffers;
        final var compare = only;
        read(newDir.resolve(GoldenDump.BUFFERS), fields -> {
            newRows.add(fields[0]);
            if (compare != null && !compare.contains(fields[0])) {
                return;
            }
            if (!oldRows.contains(fields[0])) {
                return;
            }
            final var key = bufferKey(fields);
            final var now = buffer(fields);
            final var was = before.remove(key);
            final var diff = diffs.computeIfAbsent(fields[0], k -> new RowDiff());
            if (was == null) {
                diff.added++;
                return;
            }
            if (!was.sha().equals(now.sha()) || !was.emitted().equals(now.emitted())) {
                diff.changed++;
                diff.payload += now.payload() - was.payload();
                diff.iso[0] += now.gzip() - was.gzip();
                diff.iso[1] += now.brotli() - was.brotli();
                diff.iso[2] += now.zstd() - was.zstd();
                final var layer = layer(fields);
                final var l = diff.layers.computeIfAbsent(layer, k -> new long[5]);
                l[0]++;
                l[1] += now.payload() - was.payload();
                l[2] += now.gzip() - was.gzip();
                l[3] += now.brotli() - was.brotli();
                l[4] += now.zstd() - was.zstd();
                if (diff.samples.size() < limit) {
                    diff.samples.add(String.format("%s: %d -> %d payload bytes, sha %s -> %s%s",
                            key.substring(key.indexOf('\t') + 1).replace('\t', ' '), was.payload(),
                            now.payload(), was.sha().substring(0, 12), now.sha().substring(0, 12),
                            was.emitted().equals(now.emitted()) ? "" : ", emitted topology changed"));
                }
            }
        });
        // what the new dump lacks
        for (final var key : before.keySet()) {
            final var row = key.substring(0, key.indexOf('\t'));
            if (compare == null || compare.contains(row)) {
                diffs.computeIfAbsent(row, k -> new RowDiff()).removed++;
            }
        }

        final var tileDiff = tileDiff(oldDir, newDir, compare, oldRows);

        var failed = false;
        System.out.printf("diff %s -> %s%n", oldDir, newDir);
        for (final var row : oldRows) {
            if (compare != null && !compare.contains(row)) {
                continue;
            }
            if (!newRows.contains(row)) {
                final var allowed = allowRemoved.contains(row);
                System.out.printf("REMOVED %s%s%n", row, allowed ? " (allowed)" : "");
                failed |= !allowed;
            }
        }
        for (final var row : newRows) {
            if (!oldRows.contains(row)) {
                System.out.printf("ADDED   %s (not in the old dump)%n", row);
            }
        }
        for (final var entry : diffs.entrySet()) {
            final var d = entry.getValue();
            final var tile = tileDiff.get(entry.getKey());
            if (d.changed == 0 && d.added == 0 && d.removed == 0 && (tile == null || tile[0] == 0)) {
                continue;
            }
            failed = true;
            System.out.printf("CHANGED %s: %d buffers differ, %d added, %d removed; payload %+d bytes, "
                            + "isolated gzip %+d / brotli %+d / zstd %+d%n",
                    entry.getKey(), d.changed, d.added, d.removed, d.payload, d.iso[0], d.iso[1], d.iso[2]);
            if (tile != null) {
                System.out.printf("    per-tile totals: %d tiles differ; gzip %+d / brotli %+d / zstd %+d%n",
                        tile[0], tile[1], tile[2], tile[3]);
            }
            for (final var layer : d.layers.entrySet()) {
                final var l = layer.getValue();
                System.out.printf("    layer %-20s %6d buffers, payload %+d, gzip %+d / brotli %+d / zstd %+d%n",
                        layer.getKey(), l[0], l[1], l[2], l[3], l[4]);
            }
            d.samples.forEach(s -> System.out.println("    " + s));
        }
        System.out.println(failed ? "DIFFERENCES" : "IDENTICAL: every compared row is byte for byte the same");
        return failed ? 1 : 0;
    }

    /** Per row: tiles whose sizes differ, and the per-tile total deltas for gzip, Brotli, zstd. */
    private static Map<String, long[]> tileDiff(Path oldDir, Path newDir, Set<String> only, Set<String> oldRows)
            throws IOException {
        final var before = new HashMap<String, Tile>();
        read(oldDir.resolve(GoldenDump.TILES), f -> before.put(f[0] + "\t" + f[1] + "/" + f[2] + "/" + f[3], tile(f)));
        final var result = new TreeMap<String, long[]>();
        read(newDir.resolve(GoldenDump.TILES), f -> {
            if ((only != null && !only.contains(f[0])) || !oldRows.contains(f[0])) {
                return;
            }
            final var was = before.remove(f[0] + "\t" + f[1] + "/" + f[2] + "/" + f[3]);
            final var now = tile(f);
            final var d = result.computeIfAbsent(f[0], k -> new long[4]);
            if (was == null) {
                d[0]++;
            } else if (!was.equals(now)) {
                d[0]++;
                d[1] += now.gzip() - was.gzip();
                d[2] += now.brotli() - was.brotli();
                d[3] += now.zstd() - was.zstd();
            }
        });
        for (final var key : before.keySet()) {
            final var row = key.substring(0, key.indexOf('\t'));
            if (only == null || only.contains(row)) {
                result.computeIfAbsent(row, k -> new long[4])[0]++;
            }
        }
        return result;
    }

    private static String bufferKey(String[] f) {
        return f[0] + "\t" + f[1] + "/" + f[2] + "/" + f[3] + "\t" + f[4];
    }

    private static String layer(String[] f) {
        return f[4];
    }

    private static Buffer buffer(String[] f) {
        return new Buffer(f[5], Long.parseLong(f[6]), f[7], Long.parseLong(f[8]), Long.parseLong(f[9]),
                Long.parseLong(f[10]));
    }

    private static Tile tile(String[] f) {
        return new Tile(Long.parseLong(f[4]), Long.parseLong(f[5]), Long.parseLong(f[6]), Long.parseLong(f[7]),
                Long.parseLong(f[8]));
    }

    private static void read(Path file, java.util.function.Consumer<String[]> lines) throws IOException {
        try (var in = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(file), 1 << 16), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (!line.startsWith("#")) {
                    lines.accept(line.split("\t", -1));
                }
            }
        }
    }
}
