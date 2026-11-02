package org.maplibre.mlt.research.cli;

import java.nio.file.Path;

/**
 * The arguments the per-layer probes over an extraction share: one {@code <extractionDir>} and an
 * optional {@code --layer <name>}.
 */
record ExtractionArgs(Path inDir, String layer) {

    static ExtractionArgs parse(String[] args, String defaultLayer) {
        String inArg = null;
        var layer = defaultLayer;
        for (var i = 0; i < args.length; i++) {
            if (args[i].equals("--layer") && i + 1 < args.length) {
                layer = args[++i];
            } else if (!args[i].startsWith("--")) {
                inArg = args[i];
            }
        }
        if (inArg == null) {
            throw new IllegalArgumentException("Usage: <extractionDir> [--layer <name>]");
        }
        return new ExtractionArgs(Path.of(inArg), layer);
    }
}