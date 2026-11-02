package org.maplibre.mlt.research.cli;

import org.maplibre.mlt.research.probe.BufferPointProbe;

import java.io.File;

/** {@code research buffer-point}: takes {@code <tileset.mbtiles>} and runs {@link BufferPointProbe}. */
public final class BufferPointCli {
    private BufferPointCli() {
    }

    public static void main(String[] args) throws Exception {
        BufferPointProbe.run(new File(args[0]));
    }
}