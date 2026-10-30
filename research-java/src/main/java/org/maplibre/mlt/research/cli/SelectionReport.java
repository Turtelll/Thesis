package org.maplibre.mlt.research.cli;

import org.maplibre.mlt.research.extraction.ExtractionWriter.ManifestEntry;
import org.maplibre.mlt.research.encoder.stream.ByteWidth;
import org.maplibre.mlt.research.pipeline.PipelineConfig;
import org.maplibre.mlt.research.pipeline.Prediction;
import org.maplibre.mlt.research.pipeline.Segmentation;
import org.maplibre.mlt.research.encoder.stream.Transpose;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the {@link org.maplibre.mlt.research.pipeline.Selector} and the {@link
 * org.maplibre.mlt.research.pipeline.TileSelector} chose, per selector row (one per backend and
 * reorder, and the per-(tile, layer) oracles), per layer and per zoom: the share of buffers that took
 * a curve reorder (Hilbert) or the greedy chain, each predictor, the class streams, and each terminal.
 * A share is of buffers, not of bytes.
 */
public final class SelectionReport {

    private static final Prediction[] PREDICTORS = Prediction.values();

    /** buffers, curve reorder, chain reorder, then one slot per predictor, class streams, planes, escape without planes, split varint. */
    private static final int BUFFERS = 0;
    private static final int CURVE = 1;
    private static final int CHAIN = 2;
    private static final int FIRST_PREDICTOR = 3;
    private static final int CLASS = FIRST_PREDICTOR + PREDICTORS.length;
    private static final int PLANES = CLASS + 1;
    private static final int ESCAPE = PLANES + 1;
    private static final int SPLIT = ESCAPE + 1;
    private static final int SLOTS = SPLIT + 1;

    private final Map<String, Map<String, long[]>> byLayer = new LinkedHashMap<>();
    private final Map<String, Map<String, long[]>> byZoom = new LinkedHashMap<>();

    public void record(String row, ManifestEntry entry, PipelineConfig choice) {
        tally(byLayer, row, entry.layerName(), choice);
        tally(byZoom, row, String.format("z%02d", entry.z()), choice);
    }

    public boolean isEmpty() {
        return byLayer.isEmpty();
    }

    private static void tally(Map<String, Map<String, long[]>> table, String row, String group, PipelineConfig c) {
        final var slots = table.computeIfAbsent(row, k -> new TreeMap<>()).computeIfAbsent(group, k -> new long[SLOTS]);
        slots[BUFFERS]++;
        if (c.reorder().keyed()) {
            slots[CURVE]++;
        } else if (c.reorder().reorders()) {
            slots[CHAIN]++;
        }
        slots[FIRST_PREDICTOR + c.predictor().ordinal()]++;
        if (c.segmentation() == Segmentation.CLASS) {
            slots[CLASS]++;
        }
        if (c.width() == ByteWidth.VARINT_SPLIT) {
            slots[SPLIT]++;
        } else if (c.transpose() == Transpose.INTERLEAVED_PLANES) {
            slots[PLANES]++;
        } else {
            slots[ESCAPE]++;
        }
    }

    public void print() {
        System.out.printf("%n--- selection: share of buffers per choice, by layer and by zoom, per selector row ---%n");
        for (final var row : byLayer.keySet()) {
            System.out.printf("%n%s%n", row);
            printTable("layer", byLayer.get(row));
            printTable("zoom", byZoom.get(row));
        }
    }

    private static void printTable(String label, Map<String, long[]> groups) {
        final var header = new StringBuilder(String.format("  %-22s %8s %8s %8s", label, "buffers", "curve", "chain"));
        for (final var predictor : PREDICTORS) {
            header.append(String.format(" %8s", abbreviation(predictor)));
        }
        header.append(String.format(" %8s %8s %8s %8s", "class", "planes", "escape", "split"));
        System.out.println(header);
        final var total = new long[SLOTS];
        for (final var entry : groups.entrySet()) {
            printRow(entry.getKey(), entry.getValue());
            for (var i = 0; i < SLOTS; i++) {
                total[i] += entry.getValue()[i];
            }
        }
        printRow("all", total);
    }

    private static void printRow(String name, long[] slots) {
        final var row = new StringBuilder(String.format("  %-22s %8d", name, slots[BUFFERS]));
        for (var i = 1; i < SLOTS; i++) {
            row.append(String.format(" %7.1f%%", 100.0 * slots[i] / slots[BUFFERS]));
        }
        System.out.println(row);
    }

    private static String abbreviation(Prediction predictor) {
        return switch (predictor) {
            case DELTA -> "delta";
            case QUAD_CORNER -> "qc";
            case LINE_CONTINUATION -> "lc";
            case QUAD_CORNER_LINE -> "qcl";
            case TURN_SCALAR -> "turn";
            case QUAD_CORNER_FRAME -> "qcf";
            case QUAD_CORNER_LINE_FRAME -> "qclf";
            case QUAD_CORNER_SHARED -> "qcs";
            case QUAD_CORNER_SHARED_FRAME -> "qcsf";
            case LINE_CONTINUATION_FRAME -> "lcf";
            case LINE_CONTINUATION_HEADING -> "lch";
            case LINE_CONTINUATION_FRAME_HEADING -> "lcfh";
            case LINE_CONTINUATION_SHARED -> "lcs";
            case LINE_CONTINUATION_SHARED_FRAME_HEADING -> "lcsfh";
        };
    }
}
