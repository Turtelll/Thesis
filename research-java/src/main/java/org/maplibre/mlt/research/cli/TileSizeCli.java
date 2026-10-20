package org.maplibre.mlt.research.cli;

import org.maplibre.mlt.converter.ConversionConfig;
import org.maplibre.mlt.converter.MltConverter;
import org.maplibre.mlt.converter.mvt.MvtUtils;
import org.maplibre.mlt.research.extraction.MbtilesSource;

import java.io.File;

/**
 * Total MLT tile bytes of an .mbtiles tileset: converts every tile with mlt-core's
 * {@code MltConverter} (same per-tile path as mlt-cli's {@code Conversion.kt}, FastPFOR on,
 * otherwise {@code ConversionConfig} defaults) and sums the uncompressed tile sizes.
 * Measurement only, no bytes written.
 */
public final class TileSizeCli {

    public static void main(String[] args) throws Exception {
        var config = ConversionConfig.builder().useFastPFOR(true).build();
        long[] totals = new long[2]; // tiles, bytes
        MbtilesSource.forEachTile(
                new File(args[0]),
                Integer.MIN_VALUE,
                Integer.MAX_VALUE,
                tileRef -> {
                    try {
                        var mvt = MvtUtils.decodeMvt(tileRef.mvtBytes());
                        var metadata = MltConverter.createTilesetMetadata(mvt, config, null, true);
                        totals[0]++;
                        totals[1] += MltConverter.encode(mvt, metadata, config, null).length;
                    } catch (Exception e) {
                        throw new RuntimeException(
                                "tile " + tileRef.z() + "/" + tileRef.x() + "/" + tileRef.y(), e);
                    }
                });
        System.out.printf("tiles: %d%nMLT bytes: %d%n", totals[0], totals[1]);
    }
}
