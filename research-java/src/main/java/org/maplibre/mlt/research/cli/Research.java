package org.maplibre.mlt.research.cli;

import org.maplibre.mlt.research.extraction.ExtractCli;
import org.maplibre.mlt.research.measurement.golden.GoldenDumpCli;
import org.maplibre.mlt.research.measurement.harness.PipelineCli;
import org.maplibre.mlt.research.probe.BufferPointProbe;
import org.maplibre.mlt.research.probe.DecodeTimeProbe;
import org.maplibre.mlt.research.probe.LineFramePriorProbe;
import org.maplibre.mlt.research.probe.OrientationPriorProbe;
import org.maplibre.mlt.research.probe.TileSizeProbe;
import org.maplibre.mlt.research.probe.ResidualBitsProbe;
import org.maplibre.mlt.research.probe.SharedVertexProbe;
import org.maplibre.mlt.research.probe.TurnRadiusProbe;
import org.maplibre.mlt.research.workshop.WorkshopCli;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one entry point of the research code: {@code ./gradlew research --args="<command> ..."}. Each
 * command is one measurement or tool; the options after the command are the command's own.
 *
 * <p>The measurement harness is {@code measure}: baselines against the pipeline's rows, per tile
 * under gzip, Brotli and zstd, round-tripped, with the per-buffer oracle ({@code --step oracle}), the
 * per-layer / per-geometry / per-zoom breakdowns and the selector reports. Everything else is a
 * probe over an extraction (no bytes written) or a tool: {@code extract}, {@code dump} (the golden
 * master, and {@code --diff} between two dumps), {@code tile-size} (whole-tile MLT bytes, properties
 * included).
 */
public final class Research {

    private Research() {
    }

    @FunctionalInterface
    private interface Command {
        void run(String[] args) throws Exception;
    }

    private record Entry(String help, Command command) {
    }

    private static final Map<String, Entry> COMMANDS = new LinkedHashMap<>();

    static {
        COMMANDS.put("extract", new Entry(".mbtiles -> VertexBuffer extraction", ExtractCli::main));
        COMMANDS.put("measure", new Entry(
                "baselines vs the pipeline's rows per tile: gzip/Brotli/zstd, round-tripped, oracle, breakdowns",
                PipelineCli::main));
        COMMANDS.put("tile-size", new Entry(
                "whole-tile MLT bytes (property columns and ids included) of the pipeline reorders",
                TileSizeProbe::main));
        COMMANDS.put("dump", new Entry(
                "golden master of every row (SHA-256, sizes); --diff <old> <new> compares two dumps",
                GoldenDumpCli::main));
        COMMANDS.put("workshop", new Entry("predictor workshop: residual size and bytes of one-line rules", WorkshopCli::main));
        COMMANDS.put("decode-time", new Entry("decode time against the baseline with the same codec", DecodeTimeProbe::main));
        COMMANDS.put("residual-bits", new Entry("probe: residual bits and per-vertex change per transform", ResidualBitsProbe::main));
        COMMANDS.put("shared-vertex", new Entry("probe: vertices repeating a position within a buffer, per zoom", SharedVertexProbe::main));
        COMMANDS.put("turn-radius", new Entry("probe: per-zoom turn radius and curvature", TurnRadiusProbe::main));
        COMMANDS.put("orientation-prior", new Entry("probe: ring frame predicted from decoded neighbours", OrientationPriorProbe::main));
        COMMANDS.put("line-frame-prior", new Entry("probe: line-run edges in a predicted frame", LineFramePriorProbe::main));
        COMMANDS.put("buffer-point", new Entry("MLT bytes with vs without buffer points", BufferPointProbe::main));
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h") || !COMMANDS.containsKey(args[0])) {
            usage(args.length == 0 ? null : args[0]);
            if (args.length != 0 && !args[0].equals("--help") && !args[0].equals("-h")) {
                System.exit(2);
            }
            return;
        }
        COMMANDS.get(args[0]).command().run(Arrays.copyOfRange(args, 1, args.length));
    }

    private static void usage(String unknown) {
        if (unknown != null) {
            System.err.printf("Unknown command: %s%n%n", unknown);
        }
        System.out.printf("Usage: ./gradlew research --args=\"<command> [options]\"%n%nCommands:%n");
        final var width = COMMANDS.keySet().stream().mapToInt(String::length).max().orElse(0);
        COMMANDS.forEach((name, entry) -> System.out.printf("  %-" + width + "s  %s%n", name, entry.help()));
        System.out.printf("%nEach command takes --help or a bad option for its own usage.%n");
    }
}
