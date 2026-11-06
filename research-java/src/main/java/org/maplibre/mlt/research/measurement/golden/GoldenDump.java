package org.maplibre.mlt.research.measurement.golden;

import com.aayushatharva.brotli4j.encoder.Encoder;
import org.maplibre.mlt.research.baseline.Baselines;
import org.maplibre.mlt.research.buffer.Encoding;
import org.maplibre.mlt.research.buffer.Encoding.BufferContext;
import org.maplibre.mlt.research.extraction.ExtractionReader;
import org.maplibre.mlt.research.extraction.ExtractionWriter.ManifestEntry;
import org.maplibre.mlt.research.encoder.format.PipelineEncoding;
import org.maplibre.mlt.research.registry.CompositionGrids;
import org.maplibre.mlt.research.registry.LayerCompositions;
import org.maplibre.mlt.research.registry.PipelineRegistry;
import org.maplibre.mlt.research.measurement.verification.RoundTrip;
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
public final class GoldenDump {

    public static final String BUFFERS = "buffers.tsv.gz";
    public static final String TILES = "tiles.tsv.gz";
    static final String MANIFEST = "manifest.tsv";
    static final String PER_LAYER = "PIPE_PER_LAYER";
    static final int FORMAT = 1;
    static final int BROTLI_QUALITY = 11;
    static final int ZSTD_LEVEL = 19;

    private static final String BUFFER_COLUMNS =
            "row\tz\tx\ty\tlayer\tsha256\tpayload_bytes\temitted_topology_sha256\tgzip\tbrotli11\tzstd19";
    private static final String TILE_COLUMNS = "row\tz\tx\ty\tpayload_bytes\torder0\tgzip\tbrotli11\tzstd19";

    private GoldenDump() {
    }

    /** One row of the dump; {@code encoding} is {@code null} for the per-layer default. */
    private record Row(String name, Encoding encoding) {
    }

    // --- dump ---------------------------------------------------------------------------------

    /** {@code --grid best}: the 96 compositions of the tile oracle's grid, no baselines, no per-layer row. */
    public static final String GRID_BEST = "best";

    static List<Row> rows(String grid) {
        if (grid == null) {
            return rows();
        }
        if (!GRID_BEST.equals(grid)) {
            throw new IllegalArgumentException("unknown grid " + grid + " (known: " + GRID_BEST + ")");
        }
        final var rows = new ArrayList<Row>();
        for (final var composition : CompositionGrids.bestGrid()) {
            final var encoding = new PipelineEncoding(composition);
            rows.add(new Row(encoding.name(), encoding));
        }
        return List.copyOf(rows);
    }

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

    public static void run(Options opts) throws Exception {
        final var inDir = Path.of(opts.in);
        final var rows = rows(opts.grid);
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
                            entry.layerName(), l -> new PipelineEncoding(LayerCompositions.forLayer(l)));
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
        RoundTrip.roundTrip(where, ctx, encoding, encoded);
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

    /**
     * @param grid {@code null} for the registry's rows (the default dump), {@value #GRID_BEST} for the 96
     *             compositions of {@link CompositionGrids#bestGrid()} and nothing else: the rows the Rust port's
     *             {@code golden-check} compares against
     */
    public record Options(String in, String out, int limitTiles, String layer, boolean serial, String grid) {
        public Options(String in, String out, int limitTiles, String layer, boolean serial) {
            this(in, out, limitTiles, layer, serial, null);
        }
    }

}
