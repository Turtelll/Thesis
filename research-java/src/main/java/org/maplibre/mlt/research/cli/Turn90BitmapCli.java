package org.maplibre.mlt.research.cli;

import org.maplibre.mlt.research.encoding.IntCodec;
import org.maplibre.mlt.research.encoding.StreamMetadata;
import org.maplibre.mlt.research.encoding.VertexTransform;
import org.maplibre.mlt.research.encoding.research.failed.Turn90Bitmap;
import org.maplibre.mlt.research.extraction.ExtractionReader;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Splits {@code TURN90_BITMAP}'s cost into its parts, to see which one loses against {@code
 * DELTA_ZIGZAG}: residual bits alone vs flag bits, and residual-stream bytes vs bitmap bytes vs
 * the two stream headers, per codec. Measures only (encodes nothing new).
 *
 * <p>Usage: {@code ./gradlew turn90Bitmap --args="../data/extractedBaseline --layer building"};
 * {@code --layer all} measures the whole corpus.
 */
public final class Turn90BitmapCli {
    private Turn90BitmapCli() {
    }

    private static final IntCodec[] CODECS = {IntCodec.FASTPFOR, IntCodec.VARINT};

    public static void main(String[] args) throws IOException {
        String inArg = null;
        var layer = "building";
        for (var i = 0; i < args.length; i++) {
            if (args[i].equals("--layer") && i + 1 < args.length) {
                layer = args[++i];
            } else if (!args[i].startsWith("--")) {
                inArg = args[i];
            }
        }
        if (inArg == null) {
            throw new IllegalArgumentException("Usage: <extractionDir> [--layer <name>|all]");
        }
        final var inDir = Path.of(inArg);
        final var delta = new VertexTransform.ZigZagDelta();

        var buffers = 0;
        var values = 0L;
        var deltaBits = 0L;
        var turnBits = 0L;
        var flags = 0L;
        var flagsSet = 0L;
        // Over predicted vertices only (2 values each).
        var predDeltaBits = 0L;
        var predTurnBits = 0L;
        var predExact = 0L;
        final var baseBytes = new long[CODECS.length];
        final var residualBytes = new long[CODECS.length];
        final var residualHeader = new long[CODECS.length];
        var bitmapBytes = 0L;
        var bitmapHeader = 0L;

        for (final var entry : ExtractionReader.readManifest(inDir)) {
            if (!layer.equals("all") && !entry.layerName().equals(layer)) {
                continue;
            }
            final var ctx = ExtractionReader.readContext(inDir, entry);
            buffers++;
            final var base = delta.apply(ctx);
            final var streams = Turn90Bitmap.streams(ctx);
            final var residuals = streams.residuals();
            final var turns = streams.turns();

            values += residuals.length;
            for (var j = 0; j < residuals.length; j++) {
                deltaBits += bits(base[j]);
                turnBits += bits(residuals[j]);
            }
            flags += turns.length;
            for (final var t : turns) {
                if (t) {
                    flagsSet++;
                }
            }
            final var predicted = Turn90Bitmap.predicted(ctx, residuals.length / 2);
            for (var i = 0; i < predicted.length; i++) {
                if (!predicted[i]) {
                    continue;
                }
                predDeltaBits += bits(base[2 * i]) + bits(base[2 * i + 1]);
                predTurnBits += bits(residuals[2 * i]) + bits(residuals[2 * i + 1]);
                if (residuals[2 * i] == 0 && residuals[2 * i + 1] == 0) {
                    predExact++;
                }
            }

            for (var c = 0; c < CODECS.length; c++) {
                final var basePayload = CODECS[c].encode(base).length;
                baseBytes[c] += basePayload + StreamMetadata.bytes(base.length, basePayload);
                final var payload = CODECS[c].encode(residuals).length;
                residualBytes[c] += payload;
                residualHeader[c] += StreamMetadata.bytes(residuals.length, payload);
            }
            final var bitmap = (turns.length + 7) / 8;
            bitmapBytes += bitmap;
            bitmapHeader += StreamMetadata.bytes(turns.length, bitmap);
        }

        System.out.printf("layer %s, %,d buffers, %,d values, %,d predicted vertices%n%n", layer, buffers, values, flags);
        System.out.printf("--- bits (mean per value, all values) ---%n");
        System.out.printf("DELTA_ZIGZAG             %6.3f%n", (double) deltaBits / values);
        System.out.printf("TURN90 residual only     %6.3f  %s%n", (double) turnBits / values, pct(turnBits, deltaBits));
        System.out.printf("TURN90 flags only        %6.3f%n", (double) flags / values);
        System.out.printf("TURN90 all-in            %6.3f  %s%n%n", (double) (turnBits + flags) / values, pct(turnBits + flags, deltaBits));

        System.out.printf("--- bits (per predicted vertex, x+y) ---%n");
        System.out.printf("delta                    %6.3f%n", (double) predDeltaBits / flags);
        System.out.printf("turn residual            %6.3f  %s%n", (double) predTurnBits / flags, pct(predTurnBits, predDeltaBits));
        System.out.printf("turn residual + flag     %6.3f  %s%n", (double) (predTurnBits + flags) / flags, pct(predTurnBits + flags, predDeltaBits));
        System.out.printf("exact (zero residual)    %6.1f%%%n", 100.0 * predExact / flags);
        System.out.printf("flag = 1 share           %6.1f%%%n%n", 100.0 * flagsSet / flags);

        System.out.printf("--- bytes ---%n");
        System.out.printf("%-10s %12s %12s %10s %10s %10s %12s %9s%n",
                "codec", "baseline", "residuals", "res hdr", "bitmap", "bmp hdr", "total", "vs base");
        for (var c = 0; c < CODECS.length; c++) {
            final var total = residualBytes[c] + residualHeader[c] + bitmapBytes + bitmapHeader;
            System.out.printf("%-10s %,12d %,12d %,10d %,10d %,10d %,12d %9s%n",
                    CODECS[c].name(), baseBytes[c], residualBytes[c], residualHeader[c], bitmapBytes, bitmapHeader,
                    total, pct(total, baseBytes[c]));
        }
    }

    private static String pct(long value, long reference) {
        return reference == 0 ? "-" : String.format("%+.1f%%", 100.0 * (value - reference) / reference);
    }

    private static int bits(int zigzag) {
        return 32 - Integer.numberOfLeadingZeros(zigzag);
    }
}
