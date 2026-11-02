package org.maplibre.mlt.research.cli;

import org.maplibre.mlt.research.encoder.reorder.Reorder;
import org.maplibre.mlt.research.probe.TileSizeProbe;

import java.io.File;
import java.util.ArrayList;

/**
 * {@code research tile-size}: parses {@code <tileset.mbtiles> [--modes A,B,..] [--brotli-quality N]
 * [--limit-tiles N] [--by-layer] [--runs] [--ids keep|sequential|drop] [--joint <layer>]} and runs
 * {@link TileSizeProbe}.
 */
public final class TileSizeCli {
    private TileSizeCli() {
    }

    public static void main(String[] args) throws Exception {
        File file = null;
        var ids = TileSizeProbe.IdPolicy.KEEP;
        var modes = new ArrayList<Reorder>(TileSizeProbe.DEFAULT_MODES);
        var brotli = 9;
        var limit = Integer.MAX_VALUE;
        var byLayer = false;
        var runs = false;
        var joint = false;
        String jointLayer = null;
        for (var i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--ids" -> ids = TileSizeProbe.IdPolicy.valueOf(args[++i].trim().toUpperCase());
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
        TileSizeProbe.run(new TileSizeProbe.Options(
                file, modes, brotli, limit, byLayer, runs, joint, jointLayer, ids));
    }
}