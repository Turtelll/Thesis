package org.maplibre.mlt.research.cli;

import org.maplibre.mlt.research.encoding.Baselines;
import org.maplibre.mlt.research.buffer.Encoding;
import org.maplibre.mlt.research.buffer.Encoding.EncodedBuffer;
import org.maplibre.mlt.research.extraction.ExtractionReader;
import org.maplibre.mlt.research.pipeline.Reorder;
import org.maplibre.mlt.research.pipeline.Verification;
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
 * and median absolute residual of the x and y components, the share of
 * {@code (0, 0)} pairs and of side values outside the pairs, Varint bytes, and gzip bytes with every
 * tile compressed on its own, as {@code BenchmarkCli} does. Every row is round-tripped on every buffer.
 *
 * <p>The pipeline's feature reorderings run alongside as {@code REORDER_<variant>} rows, each the plain
 * {@code DELTA} predictor over the reordered buffer (never combined with another predictor), to set
 * against the prediction rows. {@code --reorder} instead reorders every buffer before the prediction
 * rows' predictors run; the baselines stay unordered as the reference. The {@code _PART_CHAIN} rows
 * ({@link Workshop#partChainRows()}) chain the parts inside every MultiLineString and always run.
 *
 * <p>Usage: {@code [extractionDir] [--layer <name>] [--zoom <z>] [--only A,B] [--by-layer]
 * [--by-zoom] [--max-tiles N] [--reorder [VARIANT]]}. {@code VARIANT} is a pipeline {@link Reorder}
 * name (default {@code HILBERT_CENTROID_ROT_MP}). {@code extractionDir} defaults to
 * {@code ../data/extractedBaseline}.
 */
public final class WorkshopCli {
    private WorkshopCli() {
    }

    /** Layers shown under {@code --by-layer}, the ones with the most vertices. */
    private static final int TOP_LAYERS = 6;

    /** The reordering {@code --reorder} applies when no variant is named: the pipeline's own choice. */
    private static final Reorder DEFAULT_REORDER = Reorder.HILBERT_CENTROID_ROT_MP;

    private record Options(
            String inDir, String layer, Integer zoom, Set<String> only, boolean byLayer, boolean byZoom,
            int maxTiles, Reorder reorder) {

        static Options parse(String[] args) {
            var inDir = "../data/extractedBaseline";
            String layer = null;
            Integer zoom = null;
            Set<String> only = null;
            var byLayer = false;
            var byZoom = false;
            var maxTiles = Integer.MAX_VALUE;
            Reorder reorder = null;
            var positional = 0;
            for (var i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--layer" -> layer = args[++i];
                    case "--zoom" -> zoom = Integer.parseInt(args[++i]);
                    case "--only" -> only = Set.of(args[++i].split(","));
                    case "--by-layer" -> byLayer = true;
                    case "--by-zoom" -> byZoom = true;
                    case "--max-tiles" -> maxTiles = Integer.parseInt(args[++i]);
                    case "--reorder" -> {
                        // The variant is optional: take the next argument only if it names one.
                        var named = i + 1 < args.length ? reorderNamed(args[i + 1]) : null;
                        reorder = named != null ? named : DEFAULT_REORDER;
                        if (named != null) {
                            i++;
                        }
                    }
                    default -> {
                        if (args[i].startsWith("-") || positional++ > 0) {
                            throw new IllegalArgumentException(
                                    "Usage: [extractionDir] [--layer <name>] [--zoom <z>] [--only A,B] [--by-layer]"
                                            + " [--by-zoom] [--max-tiles N] [--reorder [VARIANT]]");
                        }
                        inDir = args[i];
                    }
                }
            }
            return new Options(inDir, layer, zoom, only, byLayer, byZoom, maxTiles, reorder);
        }

        /** The variant called {@code name} (case-insensitive), or {@code null} for any other argument. */
        private static Reorder reorderNamed(String name) {
            for (var reorder : Reorder.values()) {
                if (reorder.reorders() && reorder.name().equalsIgnoreCase(name)) {
                    return reorder;
                }
            }
            return null;
        }
    }

    /**
     * Running sums over one row's residual pairs and side values: per component the summed absolute
     * residual and a histogram of it for the median, exact up to {@link #HISTOGRAM_CAP}.
     */
    private static final class ResidualSums {
        /** Absolute residuals above this share the last histogram bucket; the tile extent is 4096. */
        private static final int HISTOGRAM_CAP = 4096;

        private final long[] histX = new long[HISTOGRAM_CAP + 2];
        private final long[] histY = new long[HISTOGRAM_CAP + 2];
        private long absX;
        private long absY;
        private long zeroPairs;
        private long sideValues;
        private long vertices;

        void add(Workshop.Residuals residuals) {
            final var zigzag = residuals.pairs();
            sideValues += residuals.sideValues();
            for (var i = 0; i + 1 < zigzag.length; i += 2) {
                final var x = abs(zigzag[i]);
                final var y = abs(zigzag[i + 1]);
                absX += x;
                absY += y;
                histX[(int) Math.min(x, HISTOGRAM_CAP + 1)]++;
                histY[(int) Math.min(y, HISTOGRAM_CAP + 1)]++;
                if (x == 0 && y == 0) {
                    zeroPairs++;
                }
            }
            vertices += zigzag.length / 2;
        }

        private static long abs(int zigzag) {
            return ((zigzag & 0xFFFFFFFFL) + 1) >> 1;
        }

        private double mean(long sum) {
            return vertices == 0 ? 0.0 : (double) sum / vertices;
        }

        /** The lower median of the absolute residual. */
        private String median(long[] histogram) {
            if (vertices == 0) {
                return "-";
            }
            final var half = (vertices + 1) / 2;
            var seen = 0L;
            for (var value = 0; value <= HISTOGRAM_CAP; value++) {
                seen += histogram[value];
                if (seen >= half) {
                    return String.valueOf(value);
                }
            }
            return ">" + HISTOGRAM_CAP;
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
    private record Measured(String name, byte[] payload, Workshop.Residuals residuals) {
    }

    public static void main(String[] args) throws IOException {
        var opts = Options.parse(args);
        var inDir = Path.of(opts.inDir);

        var baselines = Baselines.baseline();
        // --only names the predictors as they are listed (before --reorder renames them) and the
        // reorder-only rows by their own names.
        var predictors = Workshop.rows().stream()
                .filter(row -> opts.only == null || opts.only.contains(row.name()))
                .toList();
        if (opts.reorder != null) {
            predictors = Workshop.withReorder(opts.reorder, predictors);
        }
        var rows = new ArrayList<>(predictors);
        Workshop.reorderRows().stream()
                .filter(row -> opts.only == null || opts.only.contains(row.name()))
                .forEach(rows::add);
        Workshop.partChainRows().stream()
                .filter(row -> opts.only == null || opts.only.contains(row.name()))
                .forEach(rows::add);
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
        var baselineResiduals = Workshop.baselineResiduals();

        var corpus = new Group();
        var byLayer = new TreeMap<String, Group>();
        var byZoom = new TreeMap<Integer, Group>();
        var perTile = new TileCompression(List.of(Compressor.GZIP));
        var perLayerTile = new TreeMap<String, TileCompression>();
        String currentTile = null;
        var currentZoom = 0;
        var tiles = 0;

        for (var entry : Corpus.groupedByTile(ExtractionReader.readManifest(inDir))) {
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
            var tileKey = Corpus.tileKey(entry);
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
                // The pipeline's gate, not BenchmarkCli's: it also compares reordered Point/MultiPoint
                // columns, which GeometryEquivalence (parts only) would reject.
                Verification.roundTrip(
                        String.format("%d/%d/%d %s", entry.z(), entry.x(), entry.y(), entry.layerName()),
                        ctx, encoding, encoded);
                var residuals = r < baselines.size()
                        ? baselineResiduals.apply(ctx)
                        : rows.get(r - baselines.size()).residuals().apply(ctx);
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
                "%nmean/med |x|, |y| = mean and median absolute residual per component, in tile units,%n"
                        + "zero = share of (0, 0) pairs, side = side values per vertex outside the (x, y) pair%n"
                        + "(TURN_SCALAR's along scalar, RIGHT_ANGLE_TURN's direction bit) - not in |x|/|y|, but%n"
                        + "in the bytes. varint = raw payload bytes (FastPFOR's own for its baseline),%n"
                        + "gzip = every tile's buffers concatenated and compressed on their own. ref = %s for%n"
                        + "varint, the smaller baseline for gzip. Interleaved, no segmentation: compare a predictor%n"
                        + "with research measure before calling it a result.%n"
                        + "REORDER_<variant> = plain DELTA over the buffer that pipeline reordering emits; with%n"
                        + "--reorder every predictor row is <name>_<variant>, reordered the same way before it%n"
                        + "predicts. Baselines are never reordered.%n"
                        + "<row>_PART_CHAIN(_REV) = the row over the buffer with every MultiLineString's parts%n"
                        + "chained end to start (_REV: parts may be reversed), feature order kept.%n",
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
        // Baselines first (smaller gzip first among them), then the rest by gzip bytes.
        names.sort(Comparator
                .comparing((String name) -> !baselineNames.contains(name))
                .thenComparingLong(name -> gzipTotals.get(name)[TileCompression.GZIP]));
        var labels = new LinkedHashMap<String, String>();
        for (var name : names) {
            labels.put(name, baselineNames.contains(name) ? name + " (baseline)" : name);
        }
        var width = Math.max("predictor".length(), labels.values().stream().mapToInt(String::length).max().orElse(0));

        System.out.printf("%n--- %s: %d buffers, %,d vertices ---%n", title, group.buffers, group.vertices);
        var format = "%-" + width + "s %8s %8s %8s %8s %6s %6s %13s %9s %12s %9s%n";
        System.out.printf(format, "predictor", "mean |x|", "mean |y|", "med |x|", "med |y|", "zero", "side",
                "varint", "change", "gzip", "change");
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
                    r == null ? "-" : String.format("%.1f", r.mean(r.absX)),
                    r == null ? "-" : String.format("%.1f", r.mean(r.absY)),
                    r == null ? "-" : r.median(r.histX),
                    r == null ? "-" : r.median(r.histY),
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
