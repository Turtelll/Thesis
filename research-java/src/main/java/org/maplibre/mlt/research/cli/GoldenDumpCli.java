package org.maplibre.mlt.research.cli;

import com.aayushatharva.brotli4j.encoder.Encoder;
import org.maplibre.mlt.research.baseline.Baselines;
import org.maplibre.mlt.research.buffer.Encoding;
import org.maplibre.mlt.research.buffer.Encoding.BufferContext;
import org.maplibre.mlt.research.extraction.ExtractionReader;
import org.maplibre.mlt.research.extraction.ExtractionWriter.ManifestEntry;
import org.maplibre.mlt.research.encoder.format.PipelineEncoding;
import org.maplibre.mlt.research.registry.PipelineRegistry;
import org.maplibre.mlt.research.measurement.verification.Verification;
import org.maplibre.mlt.research.extraction.Corpus;
import org.maplibre.mlt.research.measurement.compression.Compressor;
import org.maplibre.mlt.research.measurement.compression.TileCompression;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The golden master of the consolidation: for every row of {@link Baselines#baseline()}, and {@link
 * PipelineRegistry#all()}, plus the
 * per-layer default ({@value #PER_LAYER}), and every (tile, layer) buffer of an extraction, the
 * SHA-256 of the encoded bytes, their length and the sizes under gzip, Brotli 11 and zstd 19, per
 * buffer (compressed alone) and per tile (the row's buffers concatenated, as the benchmark sizes
 * them). Every buffer is round-tripped with the check its harness applies, so a dump is also a
 * lossless-ness proof of the rows it holds.
 *
 * <p>Output directory: {@value #BUFFERS}, {@value #TILES} and {@value #MANIFEST}. Both tables start
 * with {@code #} header lines (commits, JDK, compressor library versions and levels, corpus
 * manifest hash); a dump contains no timestamps, so dumping twice gives identical files.
 *
 * <p>Usage: {@code [--in <extractionDir>] [--out <dir>] [--limit-tiles <n>] [--layer <name>] [--serial]}
 * dumps; {@code --diff <oldDir> <newDir> [--rows a,b] [--allow-removed a,b] [--max-buffers <n>]}
 * compares two dumps (exit code 1 when a compared row differs).
 */
public final class GoldenDumpCli {

    static final String BUFFERS = "buffers.tsv.gz";
    static final String TILES = "tiles.tsv.gz";
    static final String MANIFEST = "manifest.tsv";
    static final String PER_LAYER = "PIPE_PER_LAYER";
    static final int FORMAT = 1;
    static final int BROTLI_QUALITY = 11;
    static final int ZSTD_LEVEL = 19;

    private static final String BUFFER_COLUMNS =
            "row\tz\tx\ty\tlayer\tsha256\tpayload_bytes\temitted_topology_sha256\tgzip\tbrotli11\tzstd19";
    private static final String TILE_COLUMNS = "row\tz\tx\ty\tpayload_bytes\torder0\tgzip\tbrotli11\tzstd19";

    private GoldenDumpCli() {
    }

    /** One row of the dump; {@code encoding} is {@code null} for the per-layer default. */
    private record Row(String name, Encoding encoding) {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--diff")) {
            System.exit(Diff.run(args));
        }
        dump(Options.parse(args));
    }

    // --- dump ---------------------------------------------------------------------------------

    static List<Row> rows() {
        final var rows = new LinkedHashMap<String, Row>();
        final var add = (java.util.function.Consumer<Encoding>) encoding -> {
            if (rows.putIfAbsent(encoding.name(), new Row(encoding.name(), encoding)) != null) {
                throw new IllegalStateException("two rows are named " + encoding.name());
            }
        };
        Baselines.baseline().forEach(add);
        PipelineRegistry.all().forEach(add);
        if (rows.putIfAbsent(PER_LAYER, new Row(PER_LAYER, null)) != null) {
            throw new IllegalStateException("two rows are named " + PER_LAYER);
        }
        return List.copyOf(rows.values());
    }

    /** The sizes of one row on one buffer. */
    private record Result(byte[] payload, String sha, String emitted, long[] isolated) {
    }

    /** What a file's writer needs to be closed and hashed. */
    private static final class Sink implements AutoCloseable {
        final MessageDigest compressed = digest();
        final MessageDigest plain = digest();
        final BufferedWriter out;

        Sink(Path file) throws IOException {
            final var raw = new DigestOutputStream(Files.newOutputStream(file), compressed);
            final var zipped = new GZIPOutputStream(raw, 1 << 16);
            out = new BufferedWriter(
                    new OutputStreamWriter(new DigestOutputStream(zipped, plain), StandardCharsets.UTF_8), 1 << 16);
        }

        @Override
        public void close() throws IOException {
            out.close();
        }

        String compressedSha() {
            return HexFormat.of().formatHex(compressed.digest());
        }

        String plainSha() {
            return HexFormat.of().formatHex(plain.digest());
        }
    }

    /** Per-row accumulators for {@value #MANIFEST}. */
    private static final class Totals {
        long buffers;
        long payload;
        final long[] isolated = new long[3];
        final long[] tile = new long[5];
    }

    static void dump(Options opts) throws Exception {
        final var inDir = Path.of(opts.in);
        final var rows = rows();
        final var compressors = Compressor.all(BROTLI_QUALITY);
        final var manifest = Corpus.groupedByTile(ExtractionReader.readManifest(inDir));
        final var tiles = new LinkedHashMap<String, List<ManifestEntry>>();
        for (final var entry : manifest) {
            if (opts.layer != null && !opts.layer.equals(entry.layerName())) {
                continue;
            }
            tiles.computeIfAbsent(Corpus.tileKey(entry), k -> new ArrayList<>()).add(entry);
        }
        Files.createDirectories(Path.of(opts.out));

        final var totals = new LinkedHashMap<String, Totals>();
        final var byLayer = new TreeMap<String, Map<String, Totals>>();
        for (final var row : rows) {
            totals.put(row.name(), new Totals());
        }
        final var perLayerEncodings = new HashMap<String, PipelineEncoding>();
        var bufferCount = 0;
        var tileCount = 0;
        final String header = header(opts, inDir, rows, compressors);
        final String bufferSha;
        final String tileSha;
        final String bufferPlainSha;
        final String tilePlainSha;
        try (var buffersOut = new Sink(Path.of(opts.out, BUFFERS)); var tilesOut = new Sink(Path.of(opts.out, TILES))) {
            buffersOut.out.write(header + "# columns: " + BUFFER_COLUMNS + "\n");
            tilesOut.out.write(header + "# columns: " + TILE_COLUMNS + "\n");
            for (final var tile : tiles.values()) {
                if (opts.limitTiles > 0 && tileCount >= opts.limitTiles) {
                    break;
                }
                final var streams = new ArrayList<ByteArrayOutputStream>();
                rows.forEach(row -> streams.add(new ByteArrayOutputStream()));
                var any = false;
                for (final var entry : tile) {
                    final var ctx = ExtractionReader.readContext(inDir, entry);
                    if (ctx.vertices().isEmpty()) {
                        continue;
                    }
                    any = true;
                    bufferCount++;
                    final var where = Corpus.tileKey(entry) + " " + entry.layerName();
                    final var layerEncoding = perLayerEncodings.computeIfAbsent(
                            entry.layerName(), l -> new PipelineEncoding(PipelineRegistry.forLayer(l)));
                    final var range = IntStream.range(0, rows.size());
                    final var results = (opts.serial ? range : range.parallel())
                            .mapToObj(r -> encode(rows.get(r), layerEncoding, entry, ctx, where, compressors))
                            .toList();
                    final var layerTotals = byLayer.computeIfAbsent(entry.layerName(), l -> new LinkedHashMap<>());
                    for (var r = 0; r < rows.size(); r++) {
                        final var result = results.get(r);
                        final var name = rows.get(r).name();
                        streams.get(r).writeBytes(result.payload());
                        buffersOut.out.write(name + "\t" + entry.z() + "\t" + entry.x() + "\t" + entry.y() + "\t"
                                + entry.layerName() + "\t" + result.sha() + "\t" + result.payload().length + "\t"
                                + result.emitted() + "\t" + result.isolated()[0] + "\t" + result.isolated()[1] + "\t"
                                + result.isolated()[2] + "\n");
                        for (final var sum : List.of(
                                totals.get(name), layerTotals.computeIfAbsent(name, k -> new Totals()))) {
                            sum.buffers++;
                            sum.payload += result.payload().length;
                            for (var c = 0; c < 3; c++) {
                                sum.isolated[c] += result.isolated()[c];
                            }
                        }
                    }
                }
                if (!any) {
                    continue;
                }
                tileCount++;
                final var first = tile.get(0);
                final var data = streams.stream().map(ByteArrayOutputStream::toByteArray).toList();
                final var sizes = new long[rows.size()][5];
                IntStream.range(0, rows.size() * 4).parallel().forEach(task -> {
                    final var r = task / 4;
                    final var column = task % 4;
                    switch (column) {
                        case 0 -> {
                            sizes[r][0] = data.get(r).length;
                            sizes[r][1] = TileCompression.order0Bytes(data.get(r));
                        }
                        default -> sizes[r][column + 1] = compressors.get(column - 1).size(data.get(r));
                    }
                });
                for (var r = 0; r < rows.size(); r++) {
                    tilesOut.out.write(rows.get(r).name() + "\t" + first.z() + "\t" + first.x() + "\t" + first.y()
                            + "\t" + sizes[r][0] + "\t" + sizes[r][1] + "\t" + sizes[r][2] + "\t" + sizes[r][3]
                            + "\t" + sizes[r][4] + "\n");
                    final var sum = totals.get(rows.get(r).name()).tile;
                    for (var c = 0; c < 5; c++) {
                        sum[c] += sizes[r][c];
                    }
                }
                if (tileCount % 25 == 0) {
                    System.out.printf("%d tiles, %d buffers%n", tileCount, bufferCount);
                }
            }
            buffersOut.close();
            tilesOut.close();
            bufferSha = buffersOut.compressedSha();
            bufferPlainSha = buffersOut.plainSha();
            tileSha = tilesOut.compressedSha();
            tilePlainSha = tilesOut.plainSha();
        }

        try (var out = Files.newBufferedWriter(Path.of(opts.out, MANIFEST))) {
            out.write(header);
            out.write("# buffers_dumped: " + bufferCount + "\n");
            out.write("# tiles_dumped: " + tileCount + "\n");
            out.write("# file: " + BUFFERS + " sha256(gzip)=" + bufferSha + " sha256(content)=" + bufferPlainSha + "\n");
            out.write("# file: " + TILES + " sha256(gzip)=" + tileSha + " sha256(content)=" + tilePlainSha + "\n");
            out.write("# tile sizes are per-tile sums over the row's concatenated buffers; iso_* are buffers"
                    + " compressed alone\n");
            out.write("scope\trow\tlayer\tbuffers\tpayload_bytes\ttile_order0\ttile_gzip\ttile_brotli11\ttile_zstd19"
                    + "\tiso_gzip\tiso_brotli11\tiso_zstd19\n");
            for (final var row : rows) {
                final var t = totals.get(row.name());
                out.write(manifestLine("row", row.name(), "*", t));
            }
            for (final var layer : byLayer.entrySet()) {
                for (final var row : rows) {
                    final var t = layer.getValue().get(row.name());
                    if (t != null) {
                        out.write(manifestLine("layer", row.name(), layer.getKey(), t));
                    }
                }
            }
        }
        System.out.printf("Dumped %d rows x %d buffers in %d tiles to %s%n", rows.size(), bufferCount, tileCount, opts.out);
    }

    private static String manifestLine(String scope, String row, String layer, Totals t) {
        // per-layer lines carry no tile sizes: a tile is not a per-layer unit
        final var tile = scope.equals("row") ? t.tile : new long[]{0, 0, 0, 0, 0};
        return scope + "\t" + row + "\t" + layer + "\t" + t.buffers + "\t" + t.payload + "\t" + tile[1] + "\t"
                + tile[2] + "\t" + tile[3] + "\t" + tile[4] + "\t" + t.isolated[0] + "\t" + t.isolated[1] + "\t"
                + t.isolated[2] + "\n";
    }

    private static Result encode(
            Row row, PipelineEncoding layerEncoding, ManifestEntry entry, BufferContext ctx, String where,
            List<Compressor> compressors) {
        final var encoding = row.encoding() == null ? layerEncoding : row.encoding();
        final var encoded = encoding.encode(ctx);
        Verification.roundTrip(where, ctx, encoding, encoded);
        var emitted = "-";
        if (encoding.reordersGeometries()) {
            emitted = topologySha(encoding.emitted(ctx));
        }
        final var isolated = new long[3];
        for (var c = 0; c < 3; c++) {
            isolated[c] = compressors.get(c).size(encoded.payload());
        }
        return new Result(encoded.payload(), sha(encoded.payload()), emitted, isolated);
    }

    /** The streams a tile ships beside the vertex buffer: what a reorder or a merge changes. */
    private static String topologySha(BufferContext ctx) {
        final var md = digest();
        for (final var stream : List.of(ctx.geometryTypes(), ctx.numGeometries(), ctx.numParts(), ctx.numRings())) {
            md.update((stream.size() + ":" + stream + ";").getBytes(StandardCharsets.UTF_8));
        }
        md.update(("v" + ctx.vertices().size()).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(md.digest());
    }

    private static String header(Options opts, Path inDir, List<Row> rows, List<Compressor> compressors)
            throws IOException {
        final var h = new StringBuilder();
        h.append("# golden-master dump, format ").append(FORMAT).append('\n');
        h.append("# repo_commit: ").append(git(".", "rev-parse", "HEAD")).append('\n');
        h.append("# repo_dirty_research_java: ").append(!git(".", "status", "--porcelain", "--", ".").isEmpty()).append('\n');
        h.append("# submodule_commit: ").append(git("../maplibre-tile-spec", "rev-parse", "HEAD")).append('\n');
        h.append("# java: ").append(System.getProperty("java.vendor")).append(' ')
                .append(System.getProperty("java.runtime.version")).append(" on ")
                .append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.arch")).append('\n');
        h.append("# gzip: java.util.zip.GZIPOutputStream, default level (6), one-shot per buffer/tile (")
                .append(compressors.get(0).label()).append(")\n");
        h.append("# brotli: ").append(compressors.get(1).label()).append(" quality ").append(BROTLI_QUALITY)
                .append(", Encoder.Parameters default window/mode, one-shot; libraries: ")
                .append(libraries("brotli4j")).append('\n');
        h.append("# zstd: ").append(compressors.get(2).label()).append(" level ").append(ZSTD_LEVEL)
                .append(", Zstd.compress one-shot (zstd-jni bundles the native zstd; its version is the jar's); libraries: ").append(libraries("zstd-jni")).append('\n');
        h.append("# order0: ceil(n*H0/8) of each tile's concatenated bytes (TileCompression.order0Bytes)\n");
        h.append("# corpus: ").append(inDir.toString().replace('\\', '/')).append(" manifest.json sha256=")
                .append(sha(Files.readAllBytes(inDir.resolve("manifest.json")))).append('\n');
        h.append("# filter: layer=").append(opts.layer == null ? "*" : opts.layer).append(" limit_tiles=")
                .append(opts.limitTiles == 0 ? "*" : String.valueOf(opts.limitTiles)).append('\n');
        h.append("# rows: ").append(rows.size()).append('\n');
        h.append("# row_names: ").append(rows.stream().map(Row::name).reduce((a, b) -> a + "," + b).orElse(""))
                .append('\n');
        return h.toString();
    }

    private static String libraries(String needle) {
        final var found = new ArrayList<String>();
        for (final var entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            final var name = Path.of(entry).getFileName();
            if (name != null && name.toString().contains(needle)) {
                found.add(name.toString());
            }
        }
        if (found.isEmpty()) {
            found.add(Encoder.class.getProtectionDomain().getCodeSource() == null
                    ? "unknown"
                    : String.valueOf(Encoder.class.getProtectionDomain().getCodeSource().getLocation()));
        }
        return String.join(" + ", found.stream().sorted().toList());
    }

    private static String git(String dir, String... args) {
        try {
            final var command = new ArrayList<String>(List.of("git", "-C", dir));
            command.addAll(List.of(args));
            final var process = new ProcessBuilder(command).redirectErrorStream(true).start();
            final var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            process.waitFor();
            return out;
        } catch (IOException | InterruptedException e) {
            return "unavailable";
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    record Options(String in, String out, int limitTiles, String layer, boolean serial) {
        static Options parse(String[] args) {
            var in = "../data/extractedBaseline";
            var out = "../data/golden/pre-consolidation";
            var limit = 0;
            String layer = null;
            var serial = false;
            for (var i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--in" -> in = args[++i];
                    case "--out" -> out = args[++i];
                    case "--limit-tiles" -> limit = Integer.parseInt(args[++i]);
                    case "--layer" -> layer = args[++i];
                    case "--serial" -> serial = true;
                    default -> throw new IllegalArgumentException("Unknown argument: " + args[i]
                            + "\nUsage: [--in <dir>] [--out <dir>] [--limit-tiles <n>] [--layer <name>] [--serial]"
                            + " | --diff <oldDir> <newDir> [--rows a,b] [--allow-removed a,b] [--max-buffers <n>]");
                }
            }
            return new Options(in, out, limit, layer, serial);
        }
    }

    // --- diff ---------------------------------------------------------------------------------

    /** Compares two dumps: which rows, layers and buffers changed, by how many bytes. */
    static final class Diff {

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

        static int run(String[] args) throws IOException {
            if (args.length < 3) {
                throw new IllegalArgumentException("--diff <oldDir> <newDir> [--rows a,b] [--allow-removed a,b] [--max-buffers <n>]");
            }
            final var oldDir = Path.of(args[1]);
            final var newDir = Path.of(args[2]);
            Set<String> only = null;
            Set<String> allowRemoved = Set.of();
            var maxBuffers = 20;
            for (var i = 3; i < args.length; i++) {
                switch (args[i]) {
                    case "--rows" -> only = Set.of(args[++i].split(","));
                    case "--allow-removed" -> allowRemoved = Set.of(args[++i].split(","));
                    case "--max-buffers" -> maxBuffers = Integer.parseInt(args[++i]);
                    default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
                }
            }

            final var before = new HashMap<String, Buffer>();
            final var oldRows = new TreeSet<String>();
            read(oldDir.resolve(BUFFERS), fields -> {
                oldRows.add(fields[0]);
                before.put(bufferKey(fields), buffer(fields));
            });
            final var newRows = new TreeSet<String>();
            final var diffs = new TreeMap<String, RowDiff>();
            final var limit = maxBuffers;
            final var compare = only;
            read(newDir.resolve(BUFFERS), fields -> {
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
            read(oldDir.resolve(TILES), f -> before.put(f[0] + "\t" + f[1] + "/" + f[2] + "/" + f[3], tile(f)));
            final var result = new TreeMap<String, long[]>();
            read(newDir.resolve(TILES), f -> {
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
}
