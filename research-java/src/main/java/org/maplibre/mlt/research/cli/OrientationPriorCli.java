package org.maplibre.mlt.research.cli;

import org.maplibre.mlt.research.probe.OrientationPriorProbe;

import java.io.IOException;

/** {@code research orientation-prior}: parses {@code <extractionDir> [--layer <name>]} and runs {@link OrientationPriorProbe}. */
public final class OrientationPriorCli {
    private OrientationPriorCli() {
    }

    public static void main(String[] args) throws IOException {
        final var parsed = ExtractionArgs.parse(args, "building");
        OrientationPriorProbe.run(parsed.inDir(), parsed.layer());
    }
}