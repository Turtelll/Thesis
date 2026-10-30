package org.maplibre.mlt.research.probe;

import org.locationtech.jts.geom.MultiPoint;
import org.locationtech.jts.geom.Point;
import org.maplibre.mlt.converter.ConversionConfig;
import org.maplibre.mlt.converter.MltConverter;
import org.maplibre.mlt.converter.mvt.MvtUtils;
import org.maplibre.mlt.data.Feature;
import org.maplibre.mlt.data.Layer;
import org.maplibre.mlt.data.MapboxVectorTile;
import org.maplibre.mlt.research.extraction.MbtilesSource;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Total MLT tile bytes of an .mbtiles tileset with vs without buffer points: every Point (and
 * MultiPoint member) outside the tile extent {@code [0, extent)} is dropped, a MultiPoint left
 * empty drops its feature, an emptied layer is dropped. MapLibre GL JS never renders these
 * (symbol_layout.ts / circle_bucket.ts skip anchors outside the extent); the neighbour tile draws
 * them. Encoding as in {@link TileSizeCli}. Prints per-zoom totals and per-layer bytes (each layer
 * encoded as a single-layer tile). Measurement only, no bytes written.
 */
public final class BufferPointProbe {

    public static void main(String[] args) throws Exception {
        var config = ConversionConfig.builder().useFastPFOR(true).build();
        // per zoom: tiles, bytes before, bytes after, features dropped, points dropped
        var perZoom = new TreeMap<Integer, long[]>();
        // per layer: bytes before, bytes after
        var perLayer = new TreeMap<String, long[]>();
        MbtilesSource.forEachTile(
                new File(args[0]),
                Integer.MIN_VALUE,
                Integer.MAX_VALUE,
                tileRef -> {
                    try {
                        var mvt = MvtUtils.decodeMvt(tileRef.mvtBytes());
                        var z = perZoom.computeIfAbsent(tileRef.z(), k -> new long[5]);
                        var layers = new ArrayList<Layer>();
                        for (var layer : mvt.getLayerStream(false).toList()) {
                            var filtered = filter(layer, z);
                            var l = perLayer.computeIfAbsent(layer.name(), k -> new long[2]);
                            l[0] += encode(new MapboxVectorTile(List.of(layer)), config);
                            if (filtered != null) {
                                l[1] += encode(new MapboxVectorTile(List.of(filtered)), config);
                                layers.add(filtered);
                            }
                        }
                        z[0]++;
                        z[1] += encode(mvt, config);
                        z[2] += encode(new MapboxVectorTile(layers), config);
                    } catch (Exception e) {
                        throw new RuntimeException(
                                "tile " + tileRef.z() + "/" + tileRef.x() + "/" + tileRef.y(), e);
                    }
                });

        var total = new long[5];
        System.out.printf("%-20s %6s %12s %12s %8s %9s %9s%n",
                "zoom", "tiles", "MLT bytes", "filtered", "saved", "featDrop", "ptsDrop");
        perZoom.forEach((zoom, s) -> {
            for (var i = 0; i < 5; i++) total[i] += s[i];
            printZoom(String.valueOf(zoom), s);
        });
        printZoom("all", total);
        System.out.printf("%n%-20s %12s %12s %8s%n", "layer", "MLT bytes", "filtered", "saved");
        perLayer.forEach((name, s) -> System.out.printf("%-20s %,12d %,12d %7.2f%%%n",
                name, s[0], s[1], 100.0 * (s[0] - s[1]) / s[0]));
    }

    /** The layer without its buffer points, or null if nothing is left. Counts drops into z. */
    private static Layer filter(Layer layer, long[] z) {
        var extent = layer.tileExtent();
        var features = new ArrayList<Feature>();
        for (var f : layer.features()) {
            switch (f.getGeometry()) {
                case Point p -> {
                    if (inside(p, extent)) {
                        features.add(f);
                    } else {
                        z[3]++;
                        z[4]++;
                    }
                }
                case MultiPoint mp -> {
                    var keep = new ArrayList<Point>();
                    for (var i = 0; i < mp.getNumGeometries(); i++) {
                        var p = (Point) mp.getGeometryN(i);
                        if (inside(p, extent)) keep.add(p);
                        else z[4]++;
                    }
                    if (keep.isEmpty()) {
                        z[3]++;
                    } else if (keep.size() == mp.getNumGeometries()) {
                        features.add(f);
                    } else {
                        features.add(f.toBuilder()
                                .geometry(mp.getFactory().createMultiPoint(keep.toArray(Point[]::new)))
                                .build());
                    }
                }
                case null, default -> features.add(f);
            }
        }
        return features.isEmpty() ? null : new Layer(layer.name(), features, extent);
    }

    private static boolean inside(Point p, int extent) {
        return p.getX() >= 0 && p.getX() < extent && p.getY() >= 0 && p.getY() < extent;
    }

    private static long encode(MapboxVectorTile tile, ConversionConfig config) throws Exception {
        var metadata = MltConverter.createTilesetMetadata(tile, config, null, true);
        return MltConverter.encode(tile, metadata, config, null).length;
    }

    private static void printZoom(String zoom, long[] s) {
        System.out.printf("%-20s %6d %,12d %,12d %7.2f%% %,9d %,9d%n",
                zoom, s[0], s[1], s[2], 100.0 * (s[1] - s[2]) / s[1], s[3], s[4]);
    }
}
