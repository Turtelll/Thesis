package org.maplibre.mlt.research.cli;

import org.maplibre.mlt.research.probe.LineFramePriorProbe;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;

/**
 * {@code research line-frame-prior}: parses {@code <extractionDir>... [--layer <name>]} and runs
 * {@link LineFramePriorProbe} on each directory.
 */
public final class LineFramePriorCli {
    private LineFramePriorCli() {
    }

    public static void main(String[] args) throws IOException {
        var layer = "transportation";
        final var dirs = new ArrayList<String>();
        for (var i = 0; i < args.length; i++) {
            if (args[i].equals("--layer") && i + 1 < args.length) {
                layer = args[++i];
            } else if (!args[i].startsWith("--")) {
                dirs.add(args[i]);
            }
        }
        if (dirs.isEmpty()) {
            throw new IllegalArgumentException("Usage: <extractionDir>... [--layer <name>]");
        }
        for (final var dir : dirs) {
            LineFramePriorProbe.run(Path.of(dir), layer);
        }
    }
}