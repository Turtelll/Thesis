package org.maplibre.mlt.research.cli;

import org.maplibre.mlt.research.encoding.Baselines;
import org.maplibre.mlt.research.encoding.Encoding;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.EncodedBuffer;
import org.maplibre.mlt.research.extraction.ExtractionReader;
import org.maplibre.mlt.research.workshop.Workshop;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.IntStream;

/**
 * The prediction workshop ({@link Workshop}): every workshop predictor interleaved over plain
 * Varint, no segmentation, against both {@code DELTA_ZIGZAG} baselines. Per row it reports the mean
 * residual size of the x and y components (zigzag bit width and absolute value), the share of
 * {@code (0, 0)} pairs and of side values outside the pairs, Varint bytes, and gzip bytes with every
 * tile compressed on its own, as {@code BenchmarkCli} does. Every row is round-tripped on every buffer.
 *
 * <p>Usage: {@code [extractionDir] [--layer <name>] [--zoom <z>] [--only A,B] [--by-layer]
 * [--by-zoom] [--max-tiles N]}. {@code extractionDir} defaults to {@code ../data/extractedBaseline}.
 */
public final class WorkshopCli {
    private WorkshopCli() {
    }

    /** Layers shown under {@code --by-layer}, the ones with the most vertices. */
    private static final int TOP_LAYERS = 6;

    private record Options(
            String inDir, String layer, Integer zoom, Set<String> only, boolean byLayer, boolean byZoom,
            int maxTiles) {

        static Options parse(String[] args) {
            var inDir = "../data/extractedBaseline";
            String layer = null;
            Integer zoom = null;
            Set<String> only = null;
            var byLayer = false;
            var byZoom = false;
            var maxTiles = Integer.MAX_VALUE;
            var positional = 0;
            for (var i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--layer" -> layer = args[++i];
                    case "--zoom" -> zoom = Integer.parseInt(args[++i]);
                    case "--only" -> only = Set.of(args[++i].split(","));
                    case "--by-layer" -> byLayer = true;
                    case "--by-zoom" -> byZoom = true;
                    case "--max-tiles" -> maxTiles = Integer.parseInt(args[++i]);
                    default -> {
                        if (args[i].startsWith("-") || positional++ > 0) {
                            throw new IllegalArgumentException(
                                    "Usage: [extractionDir] [--layer <name>] [--zoom <z>] [--only A,B] [--by-layer]"
                                            + " [--by-zoom] [--max-tiles N]");
                        }
                        inDir = args[i];
                    }
                }
            }
            return new Options(inDir, layer, zoom, only, byLayer, byZoom, maxTiles);
        }
    }

    /** Running sums over one row's residual pairs and side values. */
    private static final class ResidualSums {
        private long bitsX;
        private long bitsY;
        private long absX;
        private long absY;
        private long zeroPairs;
        private long sideValues;
        private long vertices;

        void add(Workshop.Residuals residuals) {
            final var zigzag = residuals.pairs();
            sideValues += residuals.sideValues();
            for (var i = 0; i + 1 < zigzag.length; i += 2) {
                bitsX += bits(zigzag[i]);
                bitsY += bits(zigzag[i + 1]);
                absX += abs(zigzag[i]);
                absY += abs(zigzag[i + 1]);
                if (zigzag[i] == 0 && zigzag[i + 1] == 0) {
                    zeroPairs++;
                }
            }
            vertices += zigzag.length / 2;
        }

        void add(ResidualSums other) {
            bitsX += other.bitsX;
            bitsY += other.bitsY;
            absX += other.absX;
            absY += other.absY;
            zeroPairs += other.zeroPairs;
            sideValues += other.sideValues;
            vertices += other.vertices;
        }

        private static int bits(int zigzag) {
            return 32 - Integer.numberOfLeadingZeros(zigzag);
        }

        private static long abs(int zigzag) {
            return ((zigzag & 0xFFFFFFFFL) + 1) >> 1;
        }

        private double mean(long sum) {
            return vertices == 0 ? 0.0 : (double) sum / vertices;
        }
    }

    /** Residual statistics and raw payload bytes per row within one group (corpus, layer or zoom). */
    private static final class Group {
        private final Map<String, ResidualSums> residuals = new LinkedHashMap<>();
        private final Map<String, Long> payloadBytes = new LinkedHashMap<>();
        private long vertices;
        private int buffers;
    }

    /** One row's result on one buffer. */
    private record Measured(String name, byte[] payload, ResidualSums residuals) {
    }

    public static void main(String[] args) throws IOException {
        var opts = Options.parse(args);
        var inDir = Path.of(opts.inDir);

        var baselines = Baselines.baseline();
        var rows = Workshop.rows().stream()
                .filter(row -> opts.only == null || opts.only.contains(row.name()))
                .toList();
        if (rows.isEmpty()) {
            System.out.println("No workshop rows match --only " + opts.only);
            return;
        }
        var encodings = new ArrayList<Encoding>(baselines);
        var names = new ArrayList<String>(baselines.stream().map(Encoding::name).toList());
        for (var row : rows) {
            encodings.add(row.encoding());
            names.add(row.name());
        }
        var baselineNames = baselines.stream().map(Encoding::name).toList();
        var varintBaseline = baselines.get(1).name();

        var corpus = new Group();
        var byLayer = new TreeMap<String, Group>();
        var byZoom = new TreeMap<Integer, Group>();
        var perTile = new TileCompression(List.of(Compressor.GZIP));
        var perLayerTile = new TreeMap<String, TileCompression>();
        String currentTile = null;
        var currentZoom = 0;
        var tiles = 0;

        for (var entry : BenchmarkCli.groupedByTile(ExtractionReader.readManifest(inDir))) {
            if (opts.layer != null && !opts.layer.equals(entry.layerName())) {
                continue;
            }
            if (opts.zoom != null && opts.zoom != entry.z()) {
                continue;
            }
            var ctx = ExtractionReader.readContext(inDir, entry);
            if (ctx.vertices().isEmpty()) {
                continue;
            }
            var tileKey = BenchmarkCli.tileKey(entry);
            if (!tileKey.equals(currentTile)) {
                if (tiles == opts.maxTiles) {
                    break;
                }
                endTile(perTile, perLayerTile, currentZoom);
                currentTile = tileKey;
                currentZoom = entry.z();
                tiles++;
            }

            var measured = IntStream.range(0, encodings.size()).parallel().mapToObj(r -> {
                var encoding = encodings.get(r);
                EncodedBuffer encoded = encoding.encode(ctx);
                BenchmarkCli.verifyRoundTrip(entry, ctx, encoding, encoded);
                ResidualSums residuals = null;
                if (r >= baselines.size()) {
                    residuals = new ResidualSums();
                    residuals.add(rows.get(r - baselines.size()).residuals().apply(ctx));
                }
                return new Measured(names.get(r), encoded.payload(), residuals);
            }).toList();

            var groups = new ArrayList<>(List.of(corpus));
            if (opts.byLayer) {
                groups.add(byLayer.computeIfAbsent(entry.layerName(), k -> new Group()));
            }
            if (opts.byZoom) {
                groups.add(byZoom.computeIfAbsent(entry.z(), k -> new Group()));
            }
            var layerTile = opts.byLayer
                    ? perLayerTile.computeIfAbsent(entry.layerName(), k -> new TileCompression(List.of(Compressor.GZIP)))
                    : null;
            for (var group : groups) {
                group.buffers++;
                group.vertices += ctx.vertices().size();
            }
            for (var m : measured) {
                perTile.add(m.name(), m.payload());
                if (layerTile != null) {
                    layerTile.add(m.name(), m.payload());
                }
                for (var group : groups) {
                    group.payloadBytes.merge(m.name(), (long) m.payload().length, Long::sum);
                    if (m.residuals() != null) {
                        group.residuals.computeIfAbsent(m.name(), k -> new ResidualSums()).add(m.residuals());
                    }
                }
            }
        }
        endTile(perTile, perLayerTile, currentZoom);

        if (corpus.buffers == 0) {
            System.out.println("No buffers matched.");
            return;
        }

        System.out.printf("%d buffers in %d tiles, %,d vertices%n", corpus.buffers, perTile.tiles(), corpus.vertices);
        table("all tiles", corpus, perTile.totals(), baselineNames, varintBaseline);

        if (opts.byLayer) {
            byLayer.entrySet().stream()
                    .sorted(Comparator.comparingLong((Map.Entry<String, Group> e) -> e.getValue().vertices).reversed())
                    .limit(TOP_LAYERS)
                    .forEach(e -> table(
                            "layer " + e.getKey() + " (tiles restricted to this layer)", e.getValue(),
                            perLayerTile.get(e.getKey()).totals(), baselineNames, varintBaseline));
        }
        if (opts.byZoom) {
            for (var e : byZoom.entrySet()) {
                table("z" + e.getKey(), e.getValue(), perTile.byZoom().get(e.getKey()), baselineNames, varintBaseline);
            }
        }
        System.out.printf(
                "%nbits x/y = mean zigzag bit width per component, |x|/|y| = mean absolute residual,%n"
                        + "zero = share of (0, 0) pairs, side = side values per vertex outside the (x, y) pair%n"
                        + "(TURN_SCALAR's along scalar, RIGHT_ANGLE_TURN's direction bit) - not in bits/|x|/|y|, but%n"
                        + "in the bytes. varint = raw payload bytes (FastPFOR's own for its baseline),%n"
                        + "gzip = every tile's buffers concatenated and compressed on their own. ref = %s for%n"
                        + "varint, the smaller baseline for gzip. Interleaved, no segmentation: compare a predictor%n"
                        + "with ./gradlew benchmark before calling it a result.%n",
                varintBaseline);
    }

    private static void endTile(TileCompression perTile, Map<String, TileCompression> perLayerTile, int zoom) {
        perTile.endTile(zoom);
        for (var layerTile : perLayerTile.values()) {
            layerTile.endTile(zoom);
        }
    }

    private static void table(
            String title, Group group, Map<String, long[]> gzipTotals, List<String> baselineNames,
            String varintBaseline) {
        var gzipRef = baselineNames.stream()
                .mapToLong(name -> gzipTotals.get(name)[TileCompression.GZIP])
                .min()
                .orElse(0);
        var varintRef = group.payloadBytes.getOrDefault(varintBaseline, 0L);
        var names = new ArrayList<>(group.payloadBytes.keySet());
        names.sort(Comparator.comparingLong(name -> gzipTotals.get(name)[TileCompression.GZIP]));
        var labels = new LinkedHashMap<String, String>();
        for (var name : names) {
            labels.put(name, baselineNames.contains(name) ? name + " (baseline)" : name);
        }
        var width = Math.max("predictor".length(), labels.values().stream().mapToInt(String::length).max().orElse(0));

        System.out.printf("%n--- %s: %d buffers, %,d vertices ---%n", title, group.buffers, group.vertices);
        var format = "%-" + width + "s %7s %7s %8s %8s %6s %6s %13s %9s %12s %9s%n";
        System.out.printf(format, "predictor", "bits x", "bits y", "|x|", "|y|", "zero", "side", "varint", "change",
                "gzip", "change");
        var gzipRefName = names.stream()
                .filter(baselineNames::contains)
                .findFirst()
                .orElse(null);
        for (var name : names) {
            var r = group.residuals.get(name);
            var payload = group.payloadBytes.get(name);
            var gzip = gzipTotals.get(name)[TileCompression.GZIP];
            var isFastPfor = baselineNames.contains(name) && !name.equals(varintBaseline);
            System.out.printf(
                    format,
                    labels.get(name),
                    r == null ? "-" : String.format("%.2f", r.mean(r.bitsX)),
                    r == null ? "-" : String.format("%.2f", r.mean(r.bitsY)),
                    r == null ? "-" : String.format("%.1f", r.mean(r.absX)),
                    r == null ? "-" : String.format("%.1f", r.mean(r.absY)),
                    r == null ? "-" : share(r.zeroPairs, r.vertices),
                    r == null ? "-" : share(r.sideValues, r.vertices),
                    String.format("%,d", payload),
                    isFastPfor ? "" : change(payload, varintRef, name.equals(varintBaseline)),
                    String.format("%,d", gzip),
                    change(gzip, gzipRef, name.equals(gzipRefName)));
        }
    }

    private static String share(long part, long whole) {
        return String.format("%.1f%%", whole == 0 ? 0.0 : 100.0 * part / whole);
    }

    /** The signed change against {@code reference}; {@code ref} on the reference row itself. */
    private static String change(long bytes, long reference, boolean isReference) {
        if (isReference) {
            return "ref";
        }
        if (reference == 0) {
            return "-";
        }
        return String.format("%+.2f%%", 100.0 * (bytes - reference) / reference);
    }
}
