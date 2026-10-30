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

    public static void run(Options opts) throws Exception {

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

    public record Options(
            String mbtilesPath, String outDir, int minZoom, int maxZoom, Set<String> layers, int limit) {
    }
}
