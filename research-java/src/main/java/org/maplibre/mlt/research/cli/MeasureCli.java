package org.maplibre.mlt.research.cli;

import org.maplibre.mlt.research.measurement.harness.MeasureHarness;

/**
 * The {@code measure} command: parses its arguments and runs {@link MeasureHarness}.
 */
public final class MeasureCli {
    private MeasureCli() {
    }

    public static void main(String[] args) throws Exception {
        MeasureHarness.run(parse(args));
    }

    private static final String DEFAULT_IN_DIR = "../data/extractedBaseline";

    private static final String USAGE = "Usage: [extractionDir] [--step layer|s0|s1|...|selector|tile|best|ladder|oracle|all]"
            + " [--rows a,b] [--layer <name>] [--by-zoom] [--by-geometry] [--stats] [--csv <file>]"
            + " [--brotli-quality <0-11>] [--limit-tiles <n>] [--isolated] [--no-verify] [--oracle]"
            + " [--trials gzip,brotli,zstd,order0,order1] [--features <file>]"
            + " [--search gzip,brotli,zstd] [--size-all]";

    static MeasureHarness.Options parse(String[] args) {
        String in = null;
        var step = "layer";
        String rows = null;
        String layer = null;
        var byZoom = false;
        var byGeometry = false;
        var stats = false;
        String csv = null;
        var brotliQuality = 11;
        var limitTiles = 0;
        var isolated = false;
        var skipVerification = false;
        var oracle = false;
        String trials = null;
        String features = null;
        var sizeAll = false;
        String search = null;
        for (var i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--size-all" -> sizeAll = true;
                case "--search" -> search = args[++i];
                case "--oracle" -> oracle = true;
                case "--trials" -> trials = args[++i];
                case "--features" -> features = args[++i];
                case "--no-verify" -> skipVerification = true;
                case "--step" -> step = args[++i];
                case "--rows" -> rows = args[++i];
                case "--layer" -> layer = args[++i];
                case "--by-zoom" -> byZoom = true;
                case "--by-geometry" -> byGeometry = true;
                case "--stats" -> stats = true;
                case "--csv" -> csv = args[++i];
                case "--brotli-quality" -> brotliQuality = Integer.parseInt(args[++i]);
                case "--limit-tiles" -> limitTiles = Integer.parseInt(args[++i]);
                case "--isolated" -> isolated = true;
                case "--help", "-h" -> throw new IllegalArgumentException(USAGE);
                default -> {
                    if (args[i].startsWith("-") || in != null) {
                        throw new IllegalArgumentException("Unexpected argument: " + args[i] + "\n" + USAGE);
                    }
                    in = args[i];
                }
            }
        }
        return new MeasureHarness.Options(in == null ? DEFAULT_IN_DIR : in, step, rows, layer, byZoom, byGeometry, stats, csv,
                brotliQuality, limitTiles, isolated, skipVerification, oracle, trials, features, sizeAll, search);
    }
}
