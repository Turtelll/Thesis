package org.maplibre.mlt.research.cli;

import org.maplibre.mlt.research.encoding.PartBoundaries;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.extraction.ExtractionReader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Measures what the continuous delta cursor actually pays at run boundaries, to answer whether
 * delta-ing from one feature into the next is worth keeping at all.
 *
 * <p>The baseline ({@code DELTA_ZIGZAG}) runs one cursor across the whole buffer, so the first
 * vertex of every run is coded as a jump from the last vertex of the previous run. Those jumps are
 * not small local offsets — they are inter-feature distances — yet they share a bit-width budget
 * with the interior residuals that <em>are</em> small. This reports, per layer:
 *
 * <ul>
 *   <li>how many values are boundary jumps vs interior residuals, and the magnitude of each;
 *   <li><b>the bit-width cost</b> — FastPFOR packs in blocks of 128 at a width set by the largest
 *       value in the block, so one boundary jump inflates every value it shares a block with. This
 *       is the real cost of the relation, and it is not the same as the jumps' own size.
 * </ul>
 *
 * <p>Three coding strategies for the boundary are compared on magnitude alone (no codec):
 * {@code continuous} (today's baseline: delta from the previous run's end), {@code absolute} (code
 * the run start against 0, i.e. no cross-feature relation at all), and {@code first-anchor} (delta
 * against the <em>first</em> run's start, a shared origin rather than a chain).
 *
 * <p>Usage: {@code ./gradlew featureBoundary --args="../data/extracted"}
 */
public final class FeatureBoundaryCli {
    private FeatureBoundaryCli() {
    }

    /**
     * FastPFOR's packing block; the width of one block is set by its largest value.
     */
    private static final int BLOCK = 128;

    private static final class Stats {
        int buffers;
        long runs;
        long boundaryValues;
        long interiorValues;

        // Of the boundaries, how many cross a feature vs sit between parts/rings of one feature.
        long featureBoundaryValues;
        long featureBoundaryBitsContinuous;
        long featureBoundaryBitsAbsolute;
        long intraBoundaryValues;
        long intraBoundaryBitsContinuous;

        // Summed zigzag bit widths: what each value would cost if coded at its own width.
        long boundaryBitsContinuous;
        long boundaryBitsAbsolute;
        long boundaryBitsFirstAnchor;
        long interiorBits;

        // Block-width accounting: sum over 128-value blocks of (block width * 128).
        long blockBitsWithBoundaries;
        long blockBitsWithoutBoundaries;
    }

    public static void main(String[] args) throws IOException {
        String inArg = null;
        for (final var arg : args) {
            if (!arg.startsWith("--")) {
                inArg = arg;
            }
        }
        if (inArg == null) {
            throw new IllegalArgumentException("Usage: <extractionDir>");
        }
        final var inDir = Path.of(inArg);
        final var manifest = ExtractionReader.readManifest(inDir);

        final var byLayer = new TreeMap<String, Stats>();
        final var corpus = new Stats();

        for (final var entry : manifest) {
            final var ctx = ExtractionReader.readContext(inDir, entry);
            if (ctx.vertices().isEmpty()) {
                continue;
            }
            final var stats = byLayer.computeIfAbsent(entry.layerName(), k -> new Stats());
            measure(ctx, stats);
            measure(ctx, corpus);
        }

        report(byLayer, corpus);
    }

    /**
     * Accumulates one buffer. Runs come from {@link PartBoundaries}, the same derivation every
     * structural encoding here uses; a buffer whose topology yields no usable runs degrades to a
     * single run and therefore contributes no boundaries, which is the honest outcome — points have
     * no recoverable feature structure.
     */
    private static void measure(BufferContext ctx, Stats stats) {
        final var vertices = ctx.vertices();
        final var n = vertices.size();
        final var starts = PartBoundaries.of(ctx, n);

        stats.buffers++;
        stats.runs += starts.length;

        final var isBoundary = new boolean[n];
        for (final var start : starts) {
            if (start < n) {
                isBoundary[start] = true;
            }
        }
        // The very first vertex is coded against 0 under every strategy, so it is not a
        // cross-feature relation and must not be counted as one.
        isBoundary[0] = false;

        final var featureStarts = featureStarts(ctx, n, starts);

        final var anchor = vertices.get(0);
        var prevX = 0;
        var prevY = 0;

        // Per-value widths in stream order, for the block accounting.
        final var widths = new int[n * 2];

        for (var i = 0; i < n; i++) {
            final var v = vertices.get(i);
            final var dx = v.x() - prevX;
            final var dy = v.y() - prevY;
            final var wx = zigZagBits(dx);
            final var wy = zigZagBits(dy);
            widths[i * 2] = wx;
            widths[i * 2 + 1] = wy;

            if (isBoundary[i]) {
                stats.boundaryValues += 2;
                stats.boundaryBitsContinuous += wx + wy;
                stats.boundaryBitsAbsolute += zigZagBits(v.x()) + zigZagBits(v.y());
                stats.boundaryBitsFirstAnchor += zigZagBits(v.x() - anchor.x()) + zigZagBits(v.y() - anchor.y());
                if (featureStarts != null) {
                    if (featureStarts[i]) {
                        stats.featureBoundaryValues += 2;
                        stats.featureBoundaryBitsContinuous += wx + wy;
                        stats.featureBoundaryBitsAbsolute += zigZagBits(v.x()) + zigZagBits(v.y());
                    } else {
                        stats.intraBoundaryValues += 2;
                        stats.intraBoundaryBitsContinuous += wx + wy;
                    }
                }
            } else {
                stats.interiorValues += 2;
                stats.interiorBits += wx + wy;
            }
            prevX = v.x();
            prevY = v.y();
        }

        stats.blockBitsWithBoundaries += blockBits(widths, null);
        stats.blockBitsWithoutBoundaries += blockBits(widths, isBoundary);
    }

    /**
     * What bit-packing charges: each block of {@link #BLOCK} values costs its widest member times
     * the block size. Passing {@code excluded} drops the boundary values from the width decision
     * (but still charges for them at their own width elsewhere), which isolates how much the
     * boundaries inflate the blocks they land in.
     */
    private static long blockBits(int[] widths, boolean[] excludedVertices) {
        var total = 0L;
        for (var from = 0; from < widths.length; from += BLOCK) {
            final var to = Math.min(from + BLOCK, widths.length);
            var width = 0;
            for (var i = from; i < to; i++) {
                if (excludedVertices != null && excludedVertices[i / 2]) {
                    continue;
                }
                width = Math.max(width, widths[i]);
            }
            total += (long) width * (to - from);
        }
        return total;
    }

    /**
     * Vertex offsets at which a new <em>feature</em> begins, derived the way the extractor built the
     * streams (see {@code VertexBufferExtractor.prepareGeometry}): one entry in {@code geometryTypes}
     * per feature, with the multi-geometry members counted off {@code numGeometries} in order. A
     * shape that cannot be walked exactly returns {@code null}, and the caller then reports no
     * feature split rather than a guessed one.
     */
    private static boolean[] featureStarts(BufferContext ctx, int vertexCount, int[] runStarts) {
        final var types = ctx.geometryTypes();
        if (types.isEmpty()) {
            return null;
        }
        final var polygonal = containsPolygon(types);
        // Vertex counts per run live in numRings for polygonal columns, numParts otherwise; a
        // polygonal column additionally uses numParts as rings-per-polygon.
        final var runLengths = polygonal ? ctx.numRings() : ctx.numParts();
        final var ringsPerPolygon = ctx.numParts();

        final var starts = new boolean[vertexCount];
        var offset = 0;
        var run = 0;
        var ringGroup = 0;
        var multi = 0;

        for (final var type : types) {
            if (offset > vertexCount) {
                return null;
            }
            if (offset < vertexCount) {
                starts[offset] = true;
            }
            try {
                switch (type) {
                    case 0 -> offset += 1;
                    case 3 -> offset += ctx.numGeometries().get(multi++);
                    case 1 -> {
                        offset += runLengths.get(run++);
                    }
                    case 4 -> {
                        final var members = ctx.numGeometries().get(multi++);
                        for (var i = 0; i < members; i++) {
                            offset += runLengths.get(run++);
                        }
                    }
                    case 2 -> {
                        final var rings = polygonal ? ringsPerPolygon.get(ringGroup++) : 1;
                        for (var i = 0; i < rings; i++) {
                            offset += runLengths.get(run++);
                        }
                    }
                    case 5 -> {
                        final var members = ctx.numGeometries().get(multi++);
                        for (var m = 0; m < members; m++) {
                            final var rings = polygonal ? ringsPerPolygon.get(ringGroup++) : 1;
                            for (var i = 0; i < rings; i++) {
                                offset += runLengths.get(run++);
                            }
                        }
                    }
                    default -> {
                        return null;
                    }
                }
            } catch (IndexOutOfBoundsException e) {
                return null;
            }
        }
        return offset == vertexCount ? starts : null;
    }

    private static boolean containsPolygon(List<Integer> geometryTypes) {
        for (final var type : geometryTypes) {
            if (type == 2 || type == 5) {
                return true;
            }
        }
        return false;
    }

    /**
     * Bits needed for the zigzag encoding of a signed delta.
     */
    private static int zigZagBits(int value) {
        final var zigzag = (value << 1) ^ (value >> 31);
        return 32 - Integer.numberOfLeadingZeros(zigzag);
    }

    private static void report(TreeMap<String, Stats> byLayer, Stats corpus) {
        System.out.printf(
                "Cost of the cross-feature delta relation (%,d buffers, %,d runs).%n",
                corpus.buffers, corpus.runs);
        System.out.printf(
                "Boundary values are the %,d run-start components; interior are the other %,d.%n%n",
                corpus.boundaryValues, corpus.interiorValues);

        System.out.printf("--- average magnitude, bits per value ---%n");
        System.out.printf(
                "%-16s %10s %12s %12s %12s %12s%n",
                "layer", "bound.%", "interior", "continuous", "absolute", "first-anchor");

        final var rows = new ArrayList<>(byLayer.entrySet());
        rows.sort((a, b) -> Long.compare(b.getValue().boundaryValues, a.getValue().boundaryValues));
        for (final var row : rows) {
            final var s = row.getValue();
            if (s.boundaryValues == 0) {
                continue;
            }
            final var all = s.boundaryValues + s.interiorValues;
            System.out.printf(
                    "%-16s %9.1f%% %12.2f %12.2f %12.2f %12.2f%n",
                    row.getKey(),
                    100.0 * s.boundaryValues / all,
                    (double) s.interiorBits / s.interiorValues,
                    (double) s.boundaryBitsContinuous / s.boundaryValues,
                    (double) s.boundaryBitsAbsolute / s.boundaryValues,
                    (double) s.boundaryBitsFirstAnchor / s.boundaryValues);
        }
        final var all = corpus.boundaryValues + corpus.interiorValues;
        System.out.printf(
                "%-16s %9.1f%% %12.2f %12.2f %12.2f %12.2f%n",
                "CORPUS",
                100.0 * corpus.boundaryValues / all,
                (double) corpus.interiorBits / corpus.interiorValues,
                (double) corpus.boundaryBitsContinuous / corpus.boundaryValues,
                (double) corpus.boundaryBitsAbsolute / corpus.boundaryValues,
                (double) corpus.boundaryBitsFirstAnchor / corpus.boundaryValues);

        System.out.printf(
                "%n--- inter-feature vs intra-feature boundaries (bits/value, continuous) ---%n");
        System.out.printf(
                "%-16s %14s %14s %14s %14s%n",
                "layer", "inter n", "inter cont.", "intra n", "intra cont.");
        for (final var row : rows) {
            final var s2 = row.getValue();
            if (s2.featureBoundaryValues == 0 && s2.intraBoundaryValues == 0) {
                continue;
            }
            System.out.printf(
                    "%-16s %,14d %14.2f %,14d %14.2f%n",
                    row.getKey(),
                    s2.featureBoundaryValues,
                    s2.featureBoundaryValues == 0
                            ? 0.0
                            : (double) s2.featureBoundaryBitsContinuous / s2.featureBoundaryValues,
                    s2.intraBoundaryValues,
                    s2.intraBoundaryValues == 0
                            ? 0.0
                            : (double) s2.intraBoundaryBitsContinuous / s2.intraBoundaryValues);
        }
        System.out.printf(
                "%-16s %,14d %14.2f %,14d %14.2f%n",
                "CORPUS",
                corpus.featureBoundaryValues,
                corpus.featureBoundaryValues == 0
                        ? 0.0
                        : (double) corpus.featureBoundaryBitsContinuous / corpus.featureBoundaryValues,
                corpus.intraBoundaryValues,
                corpus.intraBoundaryValues == 0
                        ? 0.0
                        : (double) corpus.intraBoundaryBitsContinuous / corpus.intraBoundaryValues);

        System.out.printf("%n--- block-width inflation (what bit-packing actually charges) ---%n");
        System.out.printf(
                "%-16s %16s %16s %10s%n", "layer", "with bounds", "without", "inflation");
        for (final var row : rows) {
            final var s = row.getValue();
            if (s.boundaryValues == 0 || s.blockBitsWithoutBoundaries == 0) {
                continue;
            }
            System.out.printf(
                    "%-16s %,16d %,16d %9.1f%%%n",
                    row.getKey(),
                    s.blockBitsWithBoundaries / 8,
                    s.blockBitsWithoutBoundaries / 8,
                    100.0
                            * (s.blockBitsWithBoundaries - s.blockBitsWithoutBoundaries)
                            / s.blockBitsWithoutBoundaries);
        }
        System.out.printf(
                "%-16s %,16d %,16d %9.1f%%%n",
                "CORPUS",
                corpus.blockBitsWithBoundaries / 8,
                corpus.blockBitsWithoutBoundaries / 8,
                100.0
                        * (corpus.blockBitsWithBoundaries - corpus.blockBitsWithoutBoundaries)
                        / corpus.blockBitsWithoutBoundaries);

        System.out.printf(
                "%n'without' removes boundary values from the block-width decision only — it is the size%n"
                        + "the interior residuals would pack to if the boundary jumps were coded elsewhere.%n"
                        + "The inflation column is therefore the price of keeping them in the same stream,%n"
                        + "not the price of the relation itself.%n");
    }

}
