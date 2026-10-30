package org.maplibre.mlt.research.extraction;

import org.locationtech.jts.geom.Geometry;
import org.maplibre.mlt.converter.encodings.GeometryEncoder;
import org.maplibre.mlt.converter.mvt.MvtUtils;
import org.maplibre.mlt.data.Feature;
import org.maplibre.mlt.data.Layer;

import java.io.File;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Extracts VertexBuffers (matching MLT's plain feature-table geometry column, see {@link
 * VertexBufferExtractor}) from every MVT tile in an .mbtiles file, one output per (tile, layer).
 *
 * <p>Usage: {@code --mbtiles <path> --out <dir> [--zoom minZ-maxZ] [--layer name[,name...]]
 * [--limit N]}
 */
public final class MbtilesExtraction {
    private MbtilesExtraction() {
    }

    public static void main(String[] args) throws Exception {
        var opts = Options.parse(args);

        var writer = new ExtractionWriter(Path.of(opts.outDir));
        var written = new AtomicInteger(0);
        var limitReached = new boolean[]{false};

        MbtilesSource.forEachTile(
                new File(opts.mbtilesPath),
                opts.minZoom,
                opts.maxZoom,
                tileRef -> {
                    if (limitReached[0]) {
                        return;
                    }
                    try {
                        var mvt = MvtUtils.decodeMvt(tileRef.mvtBytes());
                        for (Layer layer : mvt.getLayers()) {
                            if (limitReached[0]) {
                                return;
                            }
                            if (opts.layers != null && !opts.layers.contains(layer.name())) {
                                continue;
                            }
                            List<Geometry> geometries =
                                    layer.features().stream()
                                            .filter(GeometryEncoder::hasGeometry)
                                            .map(Feature::getGeometry)
                                            .toList();
                            if (geometries.isEmpty()) {
                                continue;
                            }
                            var column = VertexBufferExtractor.extract(geometries);
                            if (column.vertexBuffer().isEmpty()) {
                                continue;
                            }
                            writer.write(
                                    tileRef.z(),
                                    tileRef.x(),
                                    tileRef.y(),
                                    layer.name(),
                                    layer.tileExtent(),
                                    geometries.size(),
                                    column);
                            int count = written.incrementAndGet();
                            if (opts.limit > 0 && count >= opts.limit) {
                                limitReached[0] = true;
                            }
                        }
                    } catch (Exception e) {
                        System.err.printf(
                                "Failed to process tile z=%d x=%d y=%d: %s%n",
                                tileRef.z(), tileRef.x(), tileRef.y(), e);
                    }
                });

        writer.writeManifest();
        System.out.printf("Wrote %d (tile, layer) extractions to %s%n", written.get(), opts.outDir);
    }

    private record Options(
            String mbtilesPath, String outDir, int minZoom, int maxZoom, Set<String> layers, int limit) {

        static Options parse(String[] args) {
            String mbtiles = null;
            String out = null;
            int minZoom = Integer.MIN_VALUE;
            int maxZoom = Integer.MAX_VALUE;
            Set<String> layers = null;
            int limit = -1;

            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--mbtiles" -> mbtiles = args[++i];
                    case "--out" -> out = args[++i];
                    case "--zoom" -> {
                        String[] parts = args[++i].split("-", 2);
                        minZoom = Integer.parseInt(parts[0]);
                        maxZoom = parts.length > 1 ? Integer.parseInt(parts[1]) : minZoom;
                    }
                    case "--layer" -> {
                        layers = new HashSet<>();
                        for (String name : args[++i].split(",")) {
                            layers.add(name.trim());
                        }
                    }
                    case "--limit" -> limit = Integer.parseInt(args[++i]);
                    default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
                }
            }

            if (mbtiles == null || out == null) {
                throw new IllegalArgumentException(
                        "Usage: --mbtiles <path> --out <dir> [--zoom minZ-maxZ] [--layer name[,name...]] [--limit N]");
            }

            return new Options(mbtiles, out, minZoom, maxZoom, layers, limit);
        }
    }
}
