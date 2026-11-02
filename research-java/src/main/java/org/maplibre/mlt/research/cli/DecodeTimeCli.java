package org.maplibre.mlt.research.cli;

import org.maplibre.mlt.research.probe.DecodeTimeProbe;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;

/**
 * {@code research decode-time}: parses {@code [extractionDir] [--encoding <NAME>,..] [--layer <name>]
 * [--reps <n>] [--warmup <n>] [--max-ratio <x>] [--csv <file>]} and runs {@link DecodeTimeProbe}.
 */
public final class DecodeTimeCli {
    private DecodeTimeCli() {
    }

    public static void main(String[] args) throws IOException {
        String inArg = null;
        String layer = null;
        var reps = 5;
        var warmup = 2;
        var maxRatio = 1.5;
        String csvFile = null;
        final var requested = new ArrayList<String>();
        for (var i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--encoding" -> {
                    for (final var name : args[++i].split(",")) {
                        if (!name.isBlank()) {
                            requested.add(name.trim());
                        }
                    }
                }
                case "--layer" -> layer = args[++i];
                case "--reps" -> reps = Integer.parseInt(args[++i]);
                case "--warmup" -> warmup = Integer.parseInt(args[++i]);
                case "--max-ratio" -> maxRatio = Double.parseDouble(args[++i]);
                case "--csv" -> csvFile = args[++i];
                case "--help", "-h" -> throw new IllegalArgumentException(DecodeTimeProbe.USAGE);
                default -> {
                    if (args[i].startsWith("--")) {
                        final var bare = args[i].substring(2);
                        final var hint = DecodeTimeProbe.candidates().containsKey(bare)
                                ? " Did you mean --encoding " + bare + "?"
                                : "";
                        throw new IllegalArgumentException("Unknown option " + args[i] + "." + hint + " " + DecodeTimeProbe.USAGE);
                    }
                    inArg = args[i];
                }
            }
        }
        DecodeTimeProbe.run(new DecodeTimeProbe.Options(
                Path.of(inArg != null ? inArg : "../data/extractedBaseline"),
                layer, reps, warmup, maxRatio, csvFile, requested));
    }
}