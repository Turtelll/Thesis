package org.maplibre.mlt.research.probe;

import org.maplibre.mlt.research.buffer.VertexRuns;
import org.maplibre.mlt.research.extraction.ExtractionReader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.TreeMap;

/**
 * Probe: how many vertices of a layer repeat an exact position elsewhere in the same buffer, per
 * zoom level? Measurement only, no encoder, no bytes written.
 *
 * <p>{@code docs/results/shared-vertices.md} measured this for {@code building} (polygon rings;
 * z13 0,03 % vs z14 23,55 %) and {@code docs/results/failed/junction-backref.md} quotes one pooled
 * figure for {@code transportation} (37,3 % of vertices) from the old {@code munich.mbtiles}
 * corpus. Neither splits {@code transportation} by zoom, and the zoom split is what decides
 * whether a reference scheme can pay — {@code building} shows sharing can vanish entirely at one
 * zoom (Planetiler merges/simplifies differently per zoom), so a pooled number hides it.
 *
 * <p>A <b>run</b> is one entry of {@link VertexRuns#of} (a line part, or a polygon ring when
 * the buffer holds polygons). Per buffer, every vertex is classified by its exact {@code (x, y)}:
 *
 * <ul>
 *   <li><b>shared</b> — the position occurs at two or more indices in the buffer (every
 *       occurrence counts, first included: this is the "how many vertices take part" figure).
 *   <li><b>cross-run</b> — shared, and at least two of the occurrences lie in different runs
 *       (junctions: the part a back-reference could address).
 *   <li><b>within-run</b> — shared, but all occurrences lie in the same run (closed loops,
 *       self-touching lines; a closed ring's repeated last vertex lands here).
 *   <li><b>redundant</b> — occurrences after the first. This is the addressable ceiling: what a
 *       decoder that has already seen the first occurrence could replace by a reference. {@code
 *       shared - redundant} is the number of distinct positions that repeat.
 * </ul>
 *
 * <p>Also reported: the share of shared vertices that are run endpoints (first/last vertex of a
 * run) rather than interior, and the share of runs with at least one endpoint position occurring
 * in a different run ({@code junction-backref.md}'s "45 % of parts share an endpoint").
 *
 * <p>All percentages are vertex-weighted over the pooled buffers of a zoom, not a mean of
 * per-buffer percentages — small low-zoom buffers must not outvote the z14 mass.
 *
 * <p>Usage: {@code ./gradlew research --args="shared-vertex ../data/extractedBaseline [--layer <name>]"}
 */
public final class SharedVertexProbe {
    private SharedVertexProbe() {
    }

    public static void main(String[] args) throws IOException {
        String inArg = null;
        var layer = "transportation";
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
        final var inDir = Path.of(inArg);
        final var manifest = ExtractionReader.readManifest(inDir);

        final var byZoom = new TreeMap<Integer, Stats>();
        final var total = new Stats();

        for (final var entry : manifest) {
            if (!entry.layerName().equals(layer)) {
                continue;
            }
            final var ctx = ExtractionReader.readContext(inDir, entry);
            final var vertices = ctx.vertices();
            if (vertices.isEmpty()) {
                continue;
            }
            final var starts = VertexRuns.of(ctx, vertices.size());

            final var zoomStats = byZoom.computeIfAbsent(entry.z(), k -> new Stats());
            zoomStats.buffers++;
            total.buffers++;
            measure(vertices, starts, zoomStats, total);
        }

        report(layer, byZoom, total);
    }

    /**
     * Occurrence record for one position: {@code [count, firstRun, crossRun (0/1)]}.
     */
    private static void measure(
            java.util.List<org.maplibre.mlt.converter.geometry.Vertex> vertices,
            int[] starts,
            Stats... sinks) {
        final var n = vertices.size();
        final var runOf = new int[n];
        for (var r = 0; r < starts.length; r++) {
            final var end = r + 1 < starts.length ? starts[r + 1] : n;
            for (var i = starts[r]; i < end; i++) {
                runOf[i] = r;
            }
        }

        final var seen = new HashMap<Long, int[]>(n * 2);
        for (var i = 0; i < n; i++) {
            final var key = key(vertices.get(i));
            final var rec = seen.get(key);
            if (rec == null) {
                seen.put(key, new int[]{1, runOf[i], 0});
            } else {
                rec[0]++;
                if (rec[1] != runOf[i]) {
                    rec[2] = 1;
                }
            }
        }

        // Runs with at least one endpoint position living in a different run.
        final var runHasJunction = new boolean[starts.length];

        final var firstSeen = new java.util.HashSet<Long>(n * 2);
        long vertexCount = n;
        long shared = 0;
        long cross = 0;
        long within = 0;
        long redundant = 0;
        long sharedEndpoint = 0;
        long sharedInterior = 0;
        for (var i = 0; i < n; i++) {
            final var key = key(vertices.get(i));
            final var rec = seen.get(key);
            final var r = runOf[i];
            final var end = r + 1 < starts.length ? starts[r + 1] : n;
            final var endpoint = i == starts[r] || i == end - 1;

            if (!firstSeen.add(key)) {
                redundant++;
            }
            if (rec[0] < 2) {
                continue;
            }
            shared++;
            if (rec[2] == 1) {
                cross++;
                if (endpoint) {
                    runHasJunction[r] = true;
                }
            } else {
                within++;
            }
            if (endpoint) {
                sharedEndpoint++;
            } else {
                sharedInterior++;
            }
        }

        var junctionRuns = 0;
        for (final var b : runHasJunction) {
            if (b) {
                junctionRuns++;
            }
        }

        for (final var s : sinks) {
            s.vertices += vertexCount;
            s.shared += shared;
            s.cross += cross;
            s.within += within;
            s.redundant += redundant;
            s.sharedEndpoint += sharedEndpoint;
            s.sharedInterior += sharedInterior;
            s.runs += starts.length;
            s.junctionRuns += junctionRuns;
        }
    }

    private static long key(org.maplibre.mlt.converter.geometry.Vertex v) {
        return ((long) v.x() << 32) | (v.y() & 0xffffffffL);
    }

    private static final class Stats {
        long buffers;
        long vertices;
        long shared;
        long cross;
        long within;
        long redundant;
        long sharedEndpoint;
        long sharedInterior;
        long runs;
        long junctionRuns;
    }

    private static String pct(long part, long whole) {
        return whole == 0 ? "-" : String.format("%.2f%%", 100.0 * part / whole);
    }

    private static void report(String layer, TreeMap<Integer, Stats> byZoom, Stats total) {
        if (total.buffers == 0) {
            System.out.println("No buffers matched layer '" + layer + "'.");
            return;
        }
        System.out.printf("Shared-vertex probe, layer '%s' (exact (x,y) repeated within one buffer)%n", layer);
        System.out.printf(
                "shared = every vertex whose position occurs >= 2 times; cross-run = also in another run;%n"
                        + "within-run = all repeats in the same run; redundant = occurrences after the first;%n"
                        + "endpoint%% = shared vertices that are a run's first/last; junction runs = runs with an%n"
                        + "endpoint position found in another run. All vertex-weighted per zoom.%n%n");
        System.out.printf(
                "%-5s %6s %12s %9s %9s %9s %10s %10s %10s %10s%n",
                "zoom", "bufs", "vertices", "shared", "cross", "within", "redundant", "endpoint%", "runs", "junction");
        byZoom.forEach((z, s) -> row("z" + z, s));
        row("all", total);
    }

    private static void row(String label, Stats s) {
        System.out.printf(
                "%-5s %6d %,12d %9s %9s %9s %10s %10s %,10d %10s%n",
                label,
                s.buffers,
                s.vertices,
                pct(s.shared, s.vertices),
                pct(s.cross, s.vertices),
                pct(s.within, s.vertices),
                pct(s.redundant, s.vertices),
                pct(s.sharedEndpoint, s.shared),
                s.runs,
                pct(s.junctionRuns, s.runs));
    }
}
