package org.maplibre.mlt.research.cli;

import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.PartBoundaries;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.research.GeometryReorder;
import org.maplibre.mlt.research.extraction.ExtractionReader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;

/**
 * Stage 0 probe for road chaining: do line features meet end to start often enough that chaining
 * them into roads can pay? Measurement only, no encoder, no bytes written.
 *
 * <p>Per zoom, line features only (polygon features are left out of every count):
 *
 * <ol>
 *   <li><b>Joins in stored order.</b> A junction position is where a feature ends and another
 *       starts; it is <em>clean</em> if exactly one feature ends and one starts there, otherwise
 *       <em>branching</em>. {@code joinable} is the most features that can follow another there
 *       (the sum of {@code min(ends, starts)}). {@code inner} counts feature ends that land on the
 *       start of a later part of a MultiLineString (or starts on an inner part's end) and nowhere
 *       else: joins a feature-level chain cannot use.
 *   <li><b>Roads after {@link GeometryReorder#ROAD_CHAIN}.</b> A part joins when it starts at the
 *       previous line part's last vertex; a road is a maximal run of joined parts. Vertices are
 *       counted without the duplicated junction vertices.
 *   <li><b>Continuation-eligible vertices</b> (3rd and later vertex of a line run of at least the
 *       gate length) per part today against per road, at gates 6 (the encoding's) and 4.
 *   <li><b>The vertex after a join</b>: zigzag bits of the continuation residual against the plain
 *       delta, for vertices that road-level continuation would predict at gate 6.
 *   <li><b>Topology entries</b> a merge removes: line features, {@code numGeometries} entries and
 *       run-length entries before and after fusing joined features and parts.
 * </ol>
 *
 * <p>Gate: if fewer than {@value #GATE_PERCENT}% of the line vertices end up in roads of two or
 * more features, the probe says so and the idea is not worth building.
 *
 * <p>Usage: {@code ./gradlew roadChain --args="../data/extractedBaseline [--layer <name>]"}
 */
public final class RoadChainCli {

    static final int GATE_PERCENT = 5;
    private static final int[] GATES = {6, 4};

    private RoadChainCli() {
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
        final var byZoom = new TreeMap<Integer, Stats>();
        final var total = new Stats();
        for (final var entry : ExtractionReader.readManifest(inDir)) {
            if (!entry.layerName().equals(layer)) {
                continue;
            }
            final var ctx = ExtractionReader.readContext(inDir, entry);
            if (ctx.vertices().isEmpty()) {
                continue;
            }
            final var local = new Stats();
            if (!measure(ctx, local)) {
                continue;
            }
            byZoom.computeIfAbsent(entry.z(), k -> new Stats()).add(local);
            total.add(local);
        }
        report(layer, byZoom, total);
    }

    // --- measurement ---------------------------------------------------------

    /**
     * Whether the buffer's features could be located (no points, streams line up).
     */
    private static boolean measure(BufferContext ctx, Stats s) {
        final var stored = Layout.of(ctx);
        if (stored == null) {
            return false;
        }
        s.buffers = 1;
        storedOrder(stored, s);
        final var chained = Layout.of(GeometryReorder.ROAD_CHAIN.apply(ctx));
        if (chained == null) {
            return false;
        }
        chainedOrder(chained, s);
        return true;
    }

    /**
     * A buffer's runs, which are line runs, and which runs make up each feature.
     */
    private record Layout(List<Vertex> vertices, int[] starts, boolean[] isLine, List<int[]> groups) {

        static Layout of(BufferContext ctx) {
            final var starts = PartBoundaries.of(ctx, ctx.vertices().size());
            final var isLine = PartBoundaries.lineRuns(ctx, starts.length);
            if (isLine == null) {
                return null;
            }
            final var groups = GeometryReorder.featureGroups(ctx, starts.length);
            return groups == null ? null : new Layout(ctx.vertices(), starts, isLine, groups);
        }

        int end(int run) {
            return run + 1 < starts.length ? starts[run + 1] : vertices.size();
        }

        int length(int run) {
            return end(run) - starts[run];
        }

        Vertex first(int run) {
            return vertices.get(starts[run]);
        }

        Vertex last(int run) {
            return vertices.get(end(run) - 1);
        }

        boolean isLineFeature(int[] group) {
            return isLine[group[0]];
        }
    }

    private static void storedOrder(Layout l, Stats s) {
        final var entries = new HashMap<Long, Integer>();
        final var exits = new HashMap<Long, Integer>();
        final var innerStarts = new HashSet<Long>();
        final var innerEnds = new HashSet<Long>();
        for (final var group : l.groups()) {
            if (!l.isLineFeature(group)) {
                continue;
            }
            s.lineFeatures++;
            s.lineParts += group.length;
            entries.merge(key(l.first(group[0])), 1, Integer::sum);
            exits.merge(key(l.last(group[group.length - 1])), 1, Integer::sum);
            for (var p = 0; p < group.length; p++) {
                if (p > 0) {
                    innerStarts.add(key(l.first(group[p])));
                }
                if (p < group.length - 1) {
                    innerEnds.add(key(l.last(group[p])));
                }
            }
        }
        for (var run = 0; run < l.starts().length; run++) {
            if (!l.isLine()[run]) {
                continue;
            }
            s.lineVertices += l.length(run);
            for (var g = 0; g < GATES.length; g++) {
                if (l.length(run) >= GATES[g]) {
                    s.eligibleToday[g] += l.length(run) - 2;
                }
            }
        }
        for (final var e : exits.entrySet()) {
            final var starts = entries.getOrDefault(e.getKey(), 0);
            if (starts > 0) {
                s.joinable += Math.min(starts, e.getValue());
                if (starts == 1 && e.getValue() == 1) {
                    s.cleanJunctions++;
                } else {
                    s.branchingJunctions++;
                }
            } else if (innerStarts.contains(e.getKey())) {
                s.inner += e.getValue();
            }
        }
        for (final var e : entries.entrySet()) {
            if (!exits.containsKey(e.getKey()) && innerEnds.contains(e.getKey())) {
                s.inner += e.getValue();
            }
        }
    }

    private static void chainedOrder(Layout l, Stats s) {
        final var runs = l.starts().length;
        final var featureOf = new int[runs];
        for (var f = 0; f < l.groups().size(); f++) {
            for (final var run : l.groups().get(f)) {
                featureOf[run] = f;
            }
        }
        final var joined = new boolean[runs];
        for (var r = 1; r < runs; r++) {
            joined[r] = l.isLine()[r] && l.isLine()[r - 1] && l.first(r).equals(l.last(r - 1));
        }

        // Roads: reduced length, features spanned, residual samples waiting for the road's length.
        var run = 0;
        while (run < runs) {
            if (!l.isLine()[run]) {
                run++;
                continue;
            }
            var reduced = l.length(run);
            var features = 1;
            final var samples = new ArrayList<int[]>();
            var end = run + 1;
            while (end < runs && joined[end]) {
                if (featureOf[end] != featureOf[end - 1]) {
                    features++;
                }
                if (l.length(end - 1) >= 2 && l.length(end) >= 2 && reduced >= 2) {
                    samples.add(residual(l, end, reduced));
                }
                reduced += l.length(end) - 1;
                end++;
            }
            s.roads++;
            s.roadVertices += reduced;
            s.roadFeatures += features;
            if (features >= 2) {
                s.multiFeatureRoads++;
                s.multiFeatureVertices += reduced;
            }
            for (var g = 0; g < GATES.length; g++) {
                if (reduced >= GATES[g]) {
                    s.eligibleRoad[g] += reduced - 2;
                }
            }
            if (reduced >= GATES[0]) {
                for (final var sample : samples) {
                    s.joinSamples++;
                    s.continuationBits += sample[0];
                    s.deltaBits += sample[1];
                    if (sample[0] < sample[1]) {
                        s.continuationSmaller++;
                    }
                }
            }
            run = end;
        }

        // Topology entries of line features before and after fusing joined features and parts.
        var openRoads = 0;
        var open = false;
        for (final var group : l.groups()) {
            if (!l.isLineFeature(group)) {
                continue;
            }
            if (group.length > 1) {
                s.multiBefore++;
            }
            if (joined[group[0]]) {
                s.featureJoins++;
            } else {
                if (open && openRoads > 1) {
                    s.multiAfter++;
                }
                open = true;
                openRoads = 0;
                s.featuresAfter++;
            }
            for (final var part : group) {
                if (!joined[part]) {
                    openRoads++;
                }
            }
        }
        if (open && openRoads > 1) {
            s.multiAfter++;
        }
    }

    /**
     * Zigzag bits of {@code {continuation residual, plain delta}} for the vertex after the join at
     * run {@code r}, whose junction vertex is the road's vertex {@code position - 1}.
     */
    private static int[] residual(Layout l, int r, int position) {
        final var junction = l.first(r);
        final var before = l.vertices().get(l.starts()[r - 1] + l.length(r - 1) - 2);
        final var next = l.vertices().get(l.starts()[r] + 1);
        final var continuation = bits(next.x() - (2 * junction.x() - before.x()))
                + bits(next.y() - (2 * junction.y() - before.y()));
        final var delta = bits(next.x() - junction.x()) + bits(next.y() - junction.y());
        return new int[]{continuation, delta};
    }

    private static int bits(int value) {
        return 32 - Integer.numberOfLeadingZeros(EncodingUtils.encodeZigZag(value));
    }

    private static long key(Vertex v) {
        return ((long) v.x() << 32) | (v.y() & 0xffffffffL);
    }

    // --- report ----------------------------------------------------------------

    private static final class Stats {
        long buffers;
        long lineFeatures;
        long lineParts;
        long lineVertices;
        long joinable;
        long cleanJunctions;
        long branchingJunctions;
        long inner;
        long featureJoins;
        long roads;
        long roadVertices;
        long roadFeatures;
        long multiFeatureRoads;
        long multiFeatureVertices;
        final long[] eligibleToday = new long[GATES.length];
        final long[] eligibleRoad = new long[GATES.length];
        long joinSamples;
        long continuationBits;
        long deltaBits;
        long continuationSmaller;
        long multiBefore;
        long multiAfter;
        long featuresAfter;

        void add(Stats o) {
            buffers += o.buffers;
            lineFeatures += o.lineFeatures;
            lineParts += o.lineParts;
            lineVertices += o.lineVertices;
            joinable += o.joinable;
            cleanJunctions += o.cleanJunctions;
            branchingJunctions += o.branchingJunctions;
            inner += o.inner;
            featureJoins += o.featureJoins;
            roads += o.roads;
            roadVertices += o.roadVertices;
            roadFeatures += o.roadFeatures;
            multiFeatureRoads += o.multiFeatureRoads;
            multiFeatureVertices += o.multiFeatureVertices;
            for (var g = 0; g < GATES.length; g++) {
                eligibleToday[g] += o.eligibleToday[g];
                eligibleRoad[g] += o.eligibleRoad[g];
            }
            joinSamples += o.joinSamples;
            continuationBits += o.continuationBits;
            deltaBits += o.deltaBits;
            continuationSmaller += o.continuationSmaller;
            multiBefore += o.multiBefore;
            multiAfter += o.multiAfter;
            featuresAfter += o.featuresAfter;
        }

        long entriesBefore() {
            return lineFeatures + multiBefore + lineParts;
        }

        long entriesAfter() {
            return featuresAfter + multiAfter + roads;
        }
    }

    private static String pct(long part, long whole) {
        return whole == 0 ? "-" : String.format("%.2f%%", 100.0 * part / whole);
    }

    private static String ratio(long part, long whole) {
        return whole == 0 ? "-" : String.format("%.2f", (double) part / whole);
    }

    private static void report(String layer, TreeMap<Integer, Stats> byZoom, Stats total) {
        if (total.buffers == 0) {
            System.out.println("No buffers matched layer '" + layer + "'.");
            return;
        }
        System.out.printf("Road-chain probe, layer '%s' (line features only)%n", layer);

        System.out.printf(
                "%n--- joins in stored order: features whose end equals another feature's start ---%n"
                        + "clean = one end and one start at the position; branching = more; joinable = most features that%n"
                        + "can follow another there; inner = ends landing only on an inner part of a MultiLineString.%n");
        System.out.printf(
                "%-5s %5s %11s %11s %10s %10s %11s %9s %9s%n",
                "zoom", "bufs", "features", "vertices", "clean", "branching", "joinable", "joinable%", "inner");
        byZoom.forEach((z, s) -> storedRow("z" + z, s));
        storedRow("all", total);

        System.out.printf(
                "%n--- roads after ROAD_CHAIN (a road = a run of line parts, each starting where the last ended) ---%n");
        System.out.printf(
                "%-5s %9s %9s %12s %12s %14s %14s%n",
                "zoom", "joins", "roads", "feat/road", "vert/road", "multi-feat rds", "verts in multi");
        byZoom.forEach((z, s) -> roadRow("z" + z, s));
        roadRow("all", total);

        System.out.printf(
                "%n--- continuation-eligible vertices (3rd+ of a run of >= gate), share of line vertices ---%n");
        System.out.printf("%-5s", "zoom");
        for (final var gate : GATES) {
            System.out.printf(" %10s %10s", "part g" + gate, "road g" + gate);
        }
        System.out.println();
        byZoom.forEach((z, s) -> eligibleRow("z" + z, s));
        eligibleRow("all", total);

        System.out.printf(
                "%n--- the vertex after a join (road >= 6): zigzag bits, continuation vs plain delta ---%n");
        System.out.printf("%-5s %10s %10s %10s %12s%n", "zoom", "samples", "cont bits", "delta bits", "cont smaller");
        byZoom.forEach((z, s) -> joinRow("z" + z, s));
        joinRow("all", total);

        System.out.printf(
                "%n--- topology entries of line features before and after fusing (geometryTypes + numGeometries + run lengths) ---%n");
        System.out.printf(
                "%-5s %10s %10s %10s %10s %10s %10s %9s%n",
                "zoom", "features", "-> fused", "multi", "-> fused", "runs", "-> roads", "entries");
        byZoom.forEach((z, s) -> topologyRow("z" + z, s));
        topologyRow("all", total);

        final var inRoads = total.lineVertices == 0 ? 0.0 : 100.0 * total.multiFeatureVertices / total.lineVertices;
        System.out.printf(
                "%nGATE (>= %d%% of line vertices in roads of two or more features): %.2f%% -> %s%n",
                GATE_PERCENT, inRoads, inRoads >= GATE_PERCENT ? "build it" : "STOP, write up the negative result");
    }

    private static void storedRow(String label, Stats s) {
        System.out.printf(
                "%-5s %5d %,11d %,11d %,10d %,10d %,11d %9s %,9d%n",
                label, s.buffers, s.lineFeatures, s.lineVertices, s.cleanJunctions, s.branchingJunctions,
                s.joinable, pct(s.joinable, s.lineFeatures), s.inner);
    }

    private static void roadRow(String label, Stats s) {
        System.out.printf(
                "%-5s %,9d %,9d %12s %12s %,14d %14s%n",
                label, s.featureJoins, s.roads, ratio(s.roadFeatures, s.roads), ratio(s.roadVertices, s.roads),
                s.multiFeatureRoads, pct(s.multiFeatureVertices, s.lineVertices));
    }

    private static void eligibleRow(String label, Stats s) {
        System.out.printf("%-5s", label);
        for (var g = 0; g < GATES.length; g++) {
            System.out.printf(
                    " %10s %10s", pct(s.eligibleToday[g], s.lineVertices), pct(s.eligibleRoad[g], s.lineVertices));
        }
        System.out.println();
    }

    private static void joinRow(String label, Stats s) {
        System.out.printf(
                "%-5s %,10d %10s %10s %12s%n",
                label, s.joinSamples, ratio(s.continuationBits, s.joinSamples), ratio(s.deltaBits, s.joinSamples),
                pct(s.continuationSmaller, s.joinSamples));
    }

    private static void topologyRow(String label, Stats s) {
        System.out.printf(
                "%-5s %,10d %,10d %,10d %,10d %,10d %,10d %9s%n",
                label, s.lineFeatures, s.featuresAfter, s.multiBefore, s.multiAfter, s.lineParts, s.roads,
                pct(s.entriesAfter(), s.entriesBefore()));
    }
}
