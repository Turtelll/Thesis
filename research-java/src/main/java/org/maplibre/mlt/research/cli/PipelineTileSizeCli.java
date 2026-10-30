package org.maplibre.mlt.research.cli;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPoint;
import org.locationtech.jts.geom.Point;
import org.maplibre.mlt.converter.ConversionConfig;
import org.maplibre.mlt.converter.MltConverter;
import org.maplibre.mlt.converter.encodings.GeometryEncoder;
import org.maplibre.mlt.converter.mvt.MvtUtils;
import org.maplibre.mlt.data.Feature;
import org.maplibre.mlt.data.Layer;
import org.maplibre.mlt.data.MapboxVectorTile;
import org.maplibre.mlt.research.buffer.Encoding.BufferContext;
import org.maplibre.mlt.research.encoder.reorder.GeometryReorder;
import org.maplibre.mlt.research.extraction.MbtilesSource;
import org.maplibre.mlt.research.extraction.VertexBufferExtractor;
import org.maplibre.mlt.research.encoder.format.PipelineConfig;
import org.maplibre.mlt.research.encoder.format.PipelineEncoding;
import org.maplibre.mlt.research.registry.PipelineRegistry;
import org.maplibre.mlt.research.encoder.prediction.Prediction;
import org.maplibre.mlt.research.encoder.reorder.Reorder;
import org.maplibre.mlt.research.encoder.segmentation.Segmentation;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Whole-tile check of the feature reorders of the pipeline's S1: the geometry-buffer experiments
 * only see VertexBuffer bytes, but reordering the features of a layer reorders its property
 * columns and ids as well. This encodes every tile of an {@code .mbtiles} tileset with mlt-core's
 * {@code MltConverter} (FastPFOR on, as in {@link TileSizeCli}) once per reorder mode, in two
 * variants:
 *
 * <ul>
 *   <li><b>full</b>: the layers with their features reordered, geometry untouched (the production
 *       geometry encoder, so the geometry gain of the pipeline is <i>not</i> in this number);
 *   <li><b>props</b>: the same with every geometry replaced by a constant point, which isolates
 *       the effect on property and id columns.
 * </ul>
 *
 * A Point/MultiPoint layer is ordered by the curve key of its first point (or bounding-box
 * centre), any other layer by its first coordinate (or centre); layers mixing both are left
 * alone, as the pipeline does. Prints raw/gzip/brotli/zstd per mode with the change against the
 * unreordered tile. Measurement only, no bytes written.
 *
 * <p>Usage: {@code PipelineTileSizeCli <tileset.mbtiles> [--modes A,B,..] [--brotli-quality N]
 * [--limit-tiles N] [--by-layer] [--runs] [--ids keep|sequential|drop] [--joint <layer>]}.
 * {@code --joint} compresses a layer's pipeline geometry payload and its property tile together,
 * once, per tile.
 */
public final class PipelineTileSizeCli {

    private static final List<Reorder> DEFAULT_MODES = List.of(
            Reorder.HILBERT_START, Reorder.HILBERT_CENTROID, Reorder.MORTON_START, Reorder.MORTON_CENTROID);

    /**
     * What happens to the feature ids of every layer after the reorder (applied to every mode,
     * {@code NONE} included, so a run's own {@code NONE} row is the matching baseline). MLT lets a
     * layer opt into {@code allowIdRegeneration} when its ids carry no meaning; {@code SEQUENTIAL}
     * is what mlt-core then writes (0, 1, 2, .. in feature order), {@code DROP} omits the id column.
     * Both change the data, so they only apply to tilesets whose ids are not needed.
     */
    private enum IdPolicy { KEEP, SEQUENTIAL, DROP }

    private static IdPolicy ids = IdPolicy.KEEP;

    private static Layer applyIds(Layer layer) {
        if (ids == IdPolicy.KEEP) {
            return layer;
        }
        final var features = new ArrayList<Feature>();
        var next = 0L;
        for (var f : layer.features()) {
            final var b = f.toBuilder();
            if (ids == IdPolicy.SEQUENTIAL) {
                b.id(next++);
            } else {
                b.id((Long) null);
            }
            features.add(b.build());
        }
        return new Layer(layer.name(), features, layer.tileExtent());
    }

    public static void main(String[] args) throws Exception {
        File file = null;
        var modes = new ArrayList<Reorder>(DEFAULT_MODES);
        var brotli = 9;
        var limit = Integer.MAX_VALUE;
        var byLayer = false;
        var runs = false;
        var joint = false;
        String jointLayer = null;
        for (var i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--ids" -> ids = IdPolicy.valueOf(args[++i].trim().toUpperCase());
                case "--modes" -> {
                    modes.clear();
                    for (var name : args[++i].split(",")) {
                        modes.add(Reorder.valueOf(name.trim()));
                    }
                }
                case "--brotli-quality" -> brotli = Integer.parseInt(args[++i]);
                case "--limit-tiles" -> limit = Integer.parseInt(args[++i]);
                case "--by-layer" -> byLayer = true;
                case "--runs" -> runs = true;
                case "--joint" -> {
                    joint = true;
                    jointLayer = args[++i];
                }
                default -> file = new File(args[i]);
            }
        }
        if (file == null) {
            file = new File("../data/MVT/munich-plantiler.mbtiles");
        }
        if (runs) {
            runs(file, modes.get(0), limit);
            return;
        }
        if (joint) {
            joint(file, jointLayer, limit, brotli);
            return;
        }
        if (byLayer) {
            byLayer(file, modes, limit);
            return;
        }
        final var compressors = Compressor.all(brotli);
        final var config = ConversionConfig.builder().useFastPFOR(true).includeIds(ids != IdPolicy.DROP).build();
        final var all = new ArrayList<Reorder>();
        all.add(Reorder.NONE);
        all.addAll(modes);
        // per mode, per kind (full, props): raw then one slot per compressor
        final var totals = new long[all.size()][2][1 + compressors.size()];
        final var tiles = new long[1];
        final var max = limit;
        MbtilesSource.forEachTile(file, Integer.MIN_VALUE, Integer.MAX_VALUE, tileRef -> {
            if (tiles[0] >= max) {
                return;
            }
            try {
                final var mvt = MvtUtils.decodeMvt(tileRef.mvtBytes());
                final var layers = mvt.getLayerStream(false).toList();
                tiles[0]++;
                for (var m = 0; m < all.size(); m++) {
                    final var reordered = new ArrayList<Layer>();
                    final var stripped = new ArrayList<Layer>();
                    for (var layer : layers) {
                        final var r = applyIds(reorder(layer, all.get(m)));
                        reordered.add(r);
                        stripped.add(stripGeometry(r));
                    }
                    record(totals[m][0], encode(reordered, config), compressors);
                    record(totals[m][1], encode(stripped, config), compressors);
                }
            } catch (Exception e) {
                throw new RuntimeException("tile " + tileRef.z() + "/" + tileRef.x() + "/" + tileRef.y(), e);
            }
        });

        System.out.printf("tiles: %d%n", tiles[0]);
        for (var kind = 0; kind < 2; kind++) {
            System.out.printf("%n%s tile bytes, change vs. the unreordered tile%n", kind == 0 ? "full" : "props-only");
            final var header = new StringBuilder(String.format("%-20s %12s", "mode", "raw"));
            for (var c : compressors) {
                header.append(String.format(" %12s %8s", c.label(), "change"));
            }
            System.out.println(header);
            for (var m = 0; m < all.size(); m++) {
                final var row = new StringBuilder(String.format("%-20s %,12d", all.get(m).name(), totals[m][kind][0]));
                for (var c = 0; c < compressors.size(); c++) {
                    final var base = totals[0][kind][1 + c];
                    final var now = totals[m][kind][1 + c];
                    row.append(String.format(" %,12d %+7.2f%%", now, 100.0 * (now - base) / base));
                }
                System.out.println(row);
            }
        }
    }

    /**
     * Per layer, each layer encoded as a single-layer tile: gzip of the props-only tile (geometry
     * replaced by a constant point) per mode, and its change against the unreordered layer. Set
     * beside the per-layer geometry change of {@code PipelineCli} this gives the net effect of a
     * reorder per layer.
     */
    private static void byLayer(File file, List<Reorder> modes, int limit) throws Exception {
        final var config = ConversionConfig.builder().useFastPFOR(true).includeIds(ids != IdPolicy.DROP).build();
        final var all = new ArrayList<Reorder>();
        all.add(Reorder.NONE);
        all.addAll(modes);
        final var perLayer = new java.util.TreeMap<String, long[]>();
        final var tiles = new long[1];
        MbtilesSource.forEachTile(file, Integer.MIN_VALUE, Integer.MAX_VALUE, tileRef -> {
            if (tiles[0] >= limit) {
                return;
            }
            try {
                tiles[0]++;
                final var mvt = MvtUtils.decodeMvt(tileRef.mvtBytes());
                for (var layer : mvt.getLayerStream(false).toList()) {
                    final var sums = perLayer.computeIfAbsent(layer.name(), k -> new long[all.size()]);
                    for (var m = 0; m < all.size(); m++) {
                        final var stripped = stripGeometry(applyIds(reorder(layer, all.get(m))));
                        sums[m] += Compressor.GZIP.size(encode(List.of(stripped), config));
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("tile " + tileRef.z() + "/" + tileRef.x() + "/" + tileRef.y(), e);
            }
        });
        System.out.printf("tiles: %d%n%nprops-only layer bytes (gzip of single-layer tiles), change vs. unreordered%n", tiles[0]);
        final var header = new StringBuilder(String.format("%-22s %12s", "layer", "NONE"));
        modes.forEach(m -> header.append(String.format(" %28s", m.name())));
        System.out.println(header);
        perLayer.forEach((name, sums) -> {
            final var row = new StringBuilder(String.format("%-22s %,12d", name, sums[0]));
            for (var m = 1; m < all.size(); m++) {
                row.append(String.format(" %,19d %+7.2f%%", sums[m], 100.0 * (sums[m] - sums[0]) / sums[0]));
            }
            System.out.println(row);
        });
    }

    /**
     * The line compositions {@link #joint} compares, for the layer's terminal: the layer's default
     * (the reference), the same without the reorder, the references with no reorder, with Hilbert and
     * with the two greedy chains, and the chains without the references.
     */
    private static List<PipelineConfig> jointConfigs(String layer) {
        final var base = PipelineRegistry.forLayer(layer)
                .withPredictor(Prediction.LINE_CONTINUATION_FRAME_HEADING)
                .withSegmentation(Segmentation.CLASS);
        final var shared = Prediction.LINE_CONTINUATION_SHARED_FRAME_HEADING;
        return List.of(
                base.withReorder(Reorder.HILBERT_CENTROID_ROT_MP),
                base.withReorder(Reorder.NONE),
                base.withReorder(Reorder.CHAIN),
                base.withReorder(Reorder.NONE).withPredictor(shared),
                base.withReorder(Reorder.HILBERT_CENTROID_ROT_MP).withPredictor(shared),
                base.withReorder(Reorder.CHAIN).withPredictor(shared),
                base.withReorder(Reorder.CANON_CHAIN).withPredictor(shared));
    }

    /**
     * What a tile of one layer costs when its geometry and properties are compressed together, as a
     * browser receives them: per tile, the pipeline's geometry payload of the layer followed by the
     * property-only MLT tile of the layer with the same feature order, compressed once. The sum of the
     * two compressed alone is printed beside it, so the gap of the additive estimate shows. The MLT
     * writer cannot take the pipeline's geometry bytes, so this concatenation stands in for the tile;
     * other layers are not part of it. The first configuration is the reference of the change column.
     */
    private static void joint(File file, String layerName, int limit, int brotli) throws Exception {
        final var compressors = Compressor.all(brotli);
        final var configs = jointConfigs(layerName);
        final var mlt = ConversionConfig.builder().useFastPFOR(true).includeIds(ids != IdPolicy.DROP).build();
        // per configuration, per kind (geometry alone, properties alone, both together), per compressor
        final var sums = new long[configs.size()][3][compressors.size()];
        final var tiles = new long[1];
        MbtilesSource.forEachTile(file, Integer.MIN_VALUE, Integer.MAX_VALUE, tileRef -> {
            if (tiles[0] >= limit) {
                return;
            }
            try {
                final var mvt = MvtUtils.decodeMvt(tileRef.mvtBytes());
                for (var layer : mvt.getLayerStream(false).toList()) {
                    if (!layer.name().equals(layerName)) {
                        continue;
                    }
                    final var geometries = layer.features().stream()
                            .filter(GeometryEncoder::hasGeometry)
                            .map(Feature::getGeometry)
                            .toList();
                    if (geometries.isEmpty()) {
                        continue;
                    }
                    final var column = VertexBufferExtractor.extract(geometries);
                    if (column.vertexBuffer().isEmpty()) {
                        continue;
                    }
                    final var ctx = new BufferContext(
                            column.vertexBuffer(), column.geometryTypes(), column.numGeometries(),
                            column.numParts(), column.numRings());
                    tiles[0]++;
                    for (var c = 0; c < configs.size(); c++) {
                        final var geometry = new PipelineEncoding(configs.get(c)).encode(ctx).payload();
                        final var props = encode(
                                List.of(stripGeometry(applyIds(reorder(layer, configs.get(c).reorder())))), mlt);
                        final var both = new byte[geometry.length + props.length];
                        System.arraycopy(geometry, 0, both, 0, geometry.length);
                        System.arraycopy(props, 0, both, geometry.length, props.length);
                        for (var k = 0; k < compressors.size(); k++) {
                            sums[c][0][k] += compressors.get(k).size(geometry);
                            sums[c][1][k] += compressors.get(k).size(props);
                            sums[c][2][k] += compressors.get(k).size(both);
                        }
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("tile " + tileRef.z() + "/" + tileRef.x() + "/" + tileRef.y(), e);
            }
        });
        System.out.printf("layer %s, %d tiles, ids %s%n", layerName, tiles[0], ids);
        for (var k = 0; k < compressors.size(); k++) {
            System.out.printf("%n--- %s: geometry payload + property-only tile, per tile ---%n", compressors.get(k).label());
            System.out.printf("%-90s %11s %11s %11s %11s %8s%n", "composition", "geometry", "props", "alone+alone",
                    "together", "vs ref");
            final var reference = sums[0][2][k];
            for (var c = 0; c < configs.size(); c++) {
                System.out.printf("%-90s %,11d %,11d %,11d %,11d %+7.2f%%%n", new PipelineEncoding(configs.get(c)).name(),
                        sums[c][0][k], sums[c][1][k], sums[c][0][k] + sums[c][1][k], sums[c][2][k],
                        100.0 * (sums[c][2][k] - reference) / reference);
            }
        }
    }

    private static void record(long[] into, byte[] bytes, List<Compressor> compressors) {
        into[0] += bytes.length;
        for (var c = 0; c < compressors.size(); c++) {
            into[1 + c] += compressors.get(c).size(bytes);
        }
    }

    private static byte[] encode(List<Layer> layers, ConversionConfig config) throws Exception {
        final var tile = new MapboxVectorTile(layers);
        final var metadata = MltConverter.createTilesetMetadata(tile, config, null, true);
        return MltConverter.encode(tile, metadata, config, null);
    }

    /** The layer with every geometry replaced by one constant point. */
    private static Layer stripGeometry(Layer layer) {
        final var features = new ArrayList<Feature>();
        for (var f : layer.features()) {
            final var g = f.getGeometry();
            features.add(f.toBuilder()
                    .geometry(g.getFactory().createPoint(new Coordinate(0, 0)))
                    .build());
        }
        return new Layer(layer.name(), features, layer.tileExtent());
    }

    /**
     * The old pipeline's greedy nearest-endpoint chain ({@link Reorder#CHAIN}, {@link
     * Reorder#CANON_CHAIN}): the feature permutation {@link GeometryReorder#featureOrder} derives from
     * the layer's vertex buffer, applied to the features. Features without a geometry are not in the
     * buffer and keep their place at the end; a layer the chain would leave alone (points, runs not
     * derivable) is returned unchanged.
     */
    private static Layer chain(Layer layer, Reorder mode) {
        final var features = new ArrayList<>(layer.features());
        final var located = new ArrayList<Integer>();
        final var geometries = new ArrayList<Geometry>();
        for (var i = 0; i < features.size(); i++) {
            if (GeometryEncoder.hasGeometry(features.get(i))) {
                located.add(i);
                geometries.add(features.get(i).getGeometry());
            }
        }
        if (geometries.size() < 2) {
            return layer;
        }
        final var column = VertexBufferExtractor.extract(geometries);
        final var ctx = new BufferContext(
                column.vertexBuffer(), column.geometryTypes(), column.numGeometries(), column.numParts(),
                column.numRings());
        final var order = (mode == Reorder.CHAIN ? GeometryReorder.FEATURE : GeometryReorder.RING_CANONICAL_THEN_FEATURE)
                .featureOrder(ctx);
        if (order == null) {
            return layer;
        }
        final var sorted = new ArrayList<Feature>(features.size());
        final var placed = new boolean[features.size()];
        for (final var f : order) {
            sorted.add(features.get(located.get(f)));
            placed[located.get(f)] = true;
        }
        for (var i = 0; i < features.size(); i++) {
            if (!placed[i]) {
                sorted.add(features.get(i));
            }
        }
        return new Layer(layer.name(), sorted, layer.tileExtent());
    }

    private static Layer reorder(Layer layer, Reorder mode) {
        if (mode == Reorder.CHAIN || mode == Reorder.CANON_CHAIN) {
            return chain(layer, mode);
        }
        final var features = new ArrayList<>(layer.features());
        if (!mode.keyed() || features.size() < 2) {
            return layer;
        }
        var points = 0;
        for (var f : features) {
            if (f.getGeometry() instanceof Point || f.getGeometry() instanceof MultiPoint) {
                points++;
            }
        }
        if (points != 0 && points != features.size()) {
            return layer;
        }
        final var n = features.size();
        final var ax = new int[n];
        final var ay = new int[n];
        final var counts = new int[n];
        for (var i = 0; i < n; i++) {
            final Geometry g = features.get(i).getGeometry();
            final Coordinate c;
            if (mode.centroidAnchor()) {
                final var env = g.getEnvelopeInternal();
                c = new Coordinate((env.getMinX() + env.getMaxX()) / 2, (env.getMinY() + env.getMaxY()) / 2);
            } else {
                c = g.getCoordinate();
            }
            ax[i] = (int) Math.round(c.x);
            ay[i] = (int) Math.round(c.y);
            counts[i] = g.getNumPoints();
        }
        final int[] order;
        if (mode.sortsPoints() && points == n) {
            // the variant orders a point column on one grid spanning every vertex, as Reorder.apply does
            final var xs = new ArrayList<Integer>();
            final var ys = new ArrayList<Integer>();
            for (var f : features) {
                for (var c : f.getGeometry().getCoordinates()) {
                    xs.add((int) Math.round(c.x));
                    ys.add((int) Math.round(c.y));
                }
            }
            order = mode.order(ax, ay, xs.stream().mapToInt(Integer::intValue).toArray(),
                    ys.stream().mapToInt(Integer::intValue).toArray(), counts);
        } else {
            order = mode.order(ax, ay, counts);
        }
        final var sorted = new ArrayList<Feature>(n);
        Arrays.stream(order).forEach(i -> sorted.add(features.get(i)));
        return new Layer(layer.name(), sorted, layer.tileExtent());
    }


    /**
     * Why the property columns grow under a reorder: per layer, how many neighbouring features keep
     * the same value of a property (a run the byte-level compressors and the dictionary/RLE column
     * encodings exploit), and how the feature ids (delta coded) step, before and after the reorder.
     * Only the first mode is probed.
     */
    private static void runs(File file, Reorder mode, int limit) throws Exception {
        // layer -> key (or "<id>") -> {pairs, equalBefore, equalAfter, idAscBefore, idAscAfter, log2StepBefore, log2StepAfter}
        final var stats = new java.util.TreeMap<String, java.util.Map<String, double[]>>();
        final var tiles = new long[1];
        MbtilesSource.forEachTile(file, Integer.MIN_VALUE, Integer.MAX_VALUE, tileRef -> {
            if (tiles[0] >= limit) {
                return;
            }
            try {
                tiles[0]++;
                final var mvt = MvtUtils.decodeMvt(tileRef.mvtBytes());
                for (var layer : mvt.getLayerStream(false).toList()) {
                    final var after = reorder(layer, mode).features();
                    final var before = layer.features();
                    if (before.size() < 2) {
                        continue;
                    }
                    final var byKey = stats.computeIfAbsent(layer.name(), k -> new java.util.TreeMap<>());
                    accumulate(byKey, new ArrayList<>(before), 0);
                    accumulate(byKey, new ArrayList<>(after), 1);
                }
            } catch (Exception e) {
                throw new RuntimeException("tile " + tileRef.z() + "/" + tileRef.x() + "/" + tileRef.y(), e);
            }
        });
        System.out.printf("tiles: %d, mode %s%n%n", tiles[0], mode.name());
        System.out.printf("%-22s %-22s %10s %9s %9s%n", "layer", "column", "pairs", "same:orig", "same:new");
        stats.forEach((layer, byKey) -> {
            byKey.entrySet().stream()
                    .filter(e -> e.getValue()[0] > 0)
                    .sorted((a, b) -> Double.compare(
                            b.getValue()[0] * (b.getValue()[1] - b.getValue()[2]) / Math.max(1, b.getValue()[0]),
                            a.getValue()[0] * (a.getValue()[1] - a.getValue()[2]) / Math.max(1, a.getValue()[0])))
                    .limit(4)
                    .forEach(e -> {
                        final var v = e.getValue();
                        System.out.printf("%-22s %-22s %,10.0f %8.1f%% %8.1f%%%n", layer, e.getKey(), v[0],
                                100 * v[1] / v[0], 100 * v[2] / v[0]);
                    });
            final var id = byKey.get("<id step>");
            if (id != null && id[0] > 0) {
                System.out.printf("%-22s %-22s %,10.0f  id ascending %.1f%% -> %.1f%%, mean log2|step| %.1f -> %.1f%n",
                        layer, "<id>", id[0], 100 * id[3] / id[0], 100 * id[4] / id[0], id[5] / id[0], id[6] / id[0]);
            }
        });
    }

    /** One pass over a feature list: slot 0/1 of {@code which} selects the original or the reordered columns. */
    private static void accumulate(java.util.Map<String, double[]> byKey, List<Feature> features, int which) {
        for (var i = 1; i < features.size(); i++) {
            final var previous = features.get(i - 1);
            final var current = features.get(i);
            if (which == 0) {
                // pairs are counted once, on the original list
                final var keys = new java.util.TreeSet<String>();
                previous.getPropertyStream().forEach(p -> keys.add(p.getName()));
                current.getPropertyStream().forEach(p -> keys.add(p.getName()));
                for (var key : keys) {
                    byKey.computeIfAbsent(key, k -> new double[7])[0]++;
                }
                byKey.computeIfAbsent("<id step>", k -> new double[7])[0]++;
            }
            final var keys = new java.util.TreeSet<String>();
            previous.getPropertyStream().forEach(p -> keys.add(p.getName()));
            current.getPropertyStream().forEach(p -> keys.add(p.getName()));
            for (var key : keys) {
                final var a = previous.getPropertyStream().filter(p -> p.getName().equals(key)).findFirst();
                final var b = current.getPropertyStream().filter(p -> p.getName().equals(key)).findFirst();
                final var same = a.isPresent() && b.isPresent()
                        && java.util.Objects.equals(a.get().getValue(0), b.get().getValue(0));
                if (same) {
                    byKey.get(key)[1 + which]++;
                }
            }
            final var id = byKey.get("<id step>");
            final var step = current.getId() - previous.getId();
            if (step > 0) {
                id[3 + which]++;
            }
            id[5 + which] += Math.log(Math.abs((double) step) + 1) / Math.log(2);
        }
    }
    private PipelineTileSizeCli() {
    }
}
