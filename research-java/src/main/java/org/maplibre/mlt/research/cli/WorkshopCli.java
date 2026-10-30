package org.maplibre.mlt.research.cli;

import java.util.Set;
import org.maplibre.mlt.research.encoder.reorder.Reorder;
import org.maplibre.mlt.research.workshop.WorkshopReport;

/**
 * The {@code workshop} command: parses its arguments and runs {@link WorkshopReport}.
 */
public final class WorkshopCli {
    private WorkshopCli() {
    }

    public static void main(String[] args) throws Exception {
        WorkshopReport.run(parse(args));
    }

    static WorkshopReport.Options parse(String[] args) {
        var inDir = "../data/extractedBaseline";
        String layer = null;
        Integer zoom = null;
        Set<String> only = null;
        var byLayer = false;
        var byZoom = false;
        var maxTiles = Integer.MAX_VALUE;
        Reorder reorder = null;
        var positional = 0;
        for (var i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--layer" -> layer = args[++i];
                case "--zoom" -> zoom = Integer.parseInt(args[++i]);
                case "--only" -> only = Set.of(args[++i].split(","));
                case "--by-layer" -> byLayer = true;
                case "--by-zoom" -> byZoom = true;
                case "--max-tiles" -> maxTiles = Integer.parseInt(args[++i]);
                case "--reorder" -> {
                    // The variant is optional: take the next argument only if it names one.
                    var named = i + 1 < args.length ? reorderNamed(args[i + 1]) : null;
                    reorder = named != null ? named : WorkshopReport.DEFAULT_REORDER;
                    if (named != null) {
                        i++;
                    }
                }
                default -> {
                    if (args[i].startsWith("-") || positional++ > 0) {
                        throw new IllegalArgumentException(
                                "Usage: [extractionDir] [--layer <name>] [--zoom <z>] [--only A,B] [--by-layer]"
                                        + " [--by-zoom] [--max-tiles N] [--reorder [VARIANT]]");
                    }
                    inDir = args[i];
                }
            }
        }
        return new WorkshopReport.Options(inDir, layer, zoom, only, byLayer, byZoom, maxTiles, reorder);
    }

    /** The variant called {@code name} (case-insensitive), or {@code null} for any other argument. */
    private static Reorder reorderNamed(String name) {
        for (var reorder : Reorder.values()) {
            if (reorder.reorders() && reorder.name().equalsIgnoreCase(name)) {
                return reorder;
            }
        }
        return null;
    }
}
