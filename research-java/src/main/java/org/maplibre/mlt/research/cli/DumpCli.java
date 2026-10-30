package org.maplibre.mlt.research.cli;

import org.maplibre.mlt.research.measurement.golden.GoldenDiff;
import org.maplibre.mlt.research.measurement.golden.GoldenDump;

import java.nio.file.Path;
import java.util.Set;

/**
 * The {@code dump} command: parses its arguments and runs {@link GoldenDump}, or with {@code --diff}
 * {@link GoldenDiff}.
 */
public final class DumpCli {
    private DumpCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--diff")) {
            System.exit(GoldenDiff.run(parseDiff(args)));
        }
        GoldenDump.run(parseDump(args));
    }

    static GoldenDump.Options parseDump(String[] args) {
        var in = "../data/extractedBaseline";
        var out = "../data/golden/pre-consolidation";
        var limit = 0;
        String layer = null;
        var serial = false;
        for (var i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--in" -> in = args[++i];
                case "--out" -> out = args[++i];
                case "--limit-tiles" -> limit = Integer.parseInt(args[++i]);
                case "--layer" -> layer = args[++i];
                case "--serial" -> serial = true;
                default -> throw new IllegalArgumentException("Unknown argument: " + args[i]
                        + "\nUsage: [--in <dir>] [--out <dir>] [--limit-tiles <n>] [--layer <name>] [--serial]"
                        + " | --diff <oldDir> <newDir> [--rows a,b] [--allow-removed a,b] [--max-buffers <n>]");
            }
        }
        return new GoldenDump.Options(in, out, limit, layer, serial);
    }

    static GoldenDiff.Options parseDiff(String[] args) {
        if (args.length < 3) {
            throw new IllegalArgumentException("--diff <oldDir> <newDir> [--rows a,b] [--allow-removed a,b] [--max-buffers <n>]");
        }
        final var oldDir = Path.of(args[1]);
        final var newDir = Path.of(args[2]);
        Set<String> only = null;
        Set<String> allowRemoved = Set.of();
        var maxBuffers = 20;
        for (var i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "--rows" -> only = Set.of(args[++i].split(","));
                case "--allow-removed" -> allowRemoved = Set.of(args[++i].split(","));
                case "--max-buffers" -> maxBuffers = Integer.parseInt(args[++i]);
                default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }
        return new GoldenDiff.Options(oldDir, newDir, only, allowRemoved, maxBuffers);
    }
}
