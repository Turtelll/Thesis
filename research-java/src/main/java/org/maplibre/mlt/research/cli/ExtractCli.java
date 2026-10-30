package org.maplibre.mlt.research.cli;

import java.util.HashSet;
import java.util.Set;
import org.maplibre.mlt.research.extraction.MbtilesExtraction;

/**
 * The {@code extract} command: parses its arguments and runs {@link MbtilesExtraction}.
 */
public final class ExtractCli {
    private ExtractCli() {
    }

    public static void main(String[] args) throws Exception {
        MbtilesExtraction.run(parse(args));
    }

    static MbtilesExtraction.Options parse(String[] args) {
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

        return new MbtilesExtraction.Options(mbtiles, out, minZoom, maxZoom, layers, limit);
    }
}
