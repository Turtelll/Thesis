package org.maplibre.mlt.research.cli;

import org.maplibre.mlt.research.probe.ResidualBitsProbe;

import java.io.IOException;

/**
 * {@code research residual-bits}: parses {@code [extractionDir] [--layer <name>] [--by-zoom]} and
 * runs {@link ResidualBitsProbe}.
 */
public final class ResidualBitsCli {
    private ResidualBitsCli() {
    }

    public static void main(String[] args) throws IOException {
        var inDir = "../data/extractedBaseline";
        String layer = null;
        var byZoom = false;
        var positional = 0;
        for (var i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--layer" -> layer = args[++i];
                case "--by-zoom" -> byZoom = true;
                default -> {
                    if (args[i].startsWith("-") || positional++ > 0) {
                        throw new IllegalArgumentException(
                                "Usage: [extractionDir] [--layer <name>] [--by-zoom]");
                    }
                    inDir = args[i];
                }
            }
        }
        ResidualBitsProbe.run(inDir, layer, byZoom);
    }
}