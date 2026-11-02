package org.maplibre.mlt.research.cli;

import org.maplibre.mlt.research.probe.TurnRadiusProbe;

import java.io.IOException;

/** {@code research turn-radius}: parses {@code <extractionDir> [--layer <name>]} and runs {@link TurnRadiusProbe}. */
public final class TurnRadiusCli {
    private TurnRadiusCli() {
    }

    public static void main(String[] args) throws IOException {
        final var parsed = ExtractionArgs.parse(args, "transportation");
        TurnRadiusProbe.run(parsed.inDir(), parsed.layer());
    }
}