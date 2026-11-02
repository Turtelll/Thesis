package org.maplibre.mlt.research.cli;

import org.maplibre.mlt.research.probe.SharedVertexProbe;

import java.io.IOException;

/** {@code research shared-vertex}: parses {@code <extractionDir> [--layer <name>]} and runs {@link SharedVertexProbe}. */
public final class SharedVertexCli {
    private SharedVertexCli() {
    }

    public static void main(String[] args) throws IOException {
        final var parsed = ExtractionArgs.parse(args, "transportation");
        SharedVertexProbe.run(parsed.inDir(), parsed.layer());
    }
}