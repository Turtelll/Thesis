package org.maplibre.mlt.research.encoder.prediction.mechanism;

import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.decoder.DecodingUtils;
import org.maplibre.mlt.research.buffer.Encoding.BufferContext;
import org.maplibre.mlt.research.encoder.segmentation.Segmentation;

import java.util.Arrays;
import java.util.List;

/**
 * Test support: a predictor's residuals split by class ({@link Segmentation#CLASS}) and written as
 * one zigzag array, the regions in the predictor's region order and a side stream last, with the
 * inverse from the topology alone. It is the layout the mechanism tests pin positions in.
 */
public final class ClassSegmented {

    private ClassSegmented() {
    }

    public static int[] apply(ClassifiedPredictor predictor, BufferContext ctx) {
        final var layout = Segmentation.CLASS.layout(ctx, predictor, ctx.vertices().size());
        final var streams = Segmentation.split(layout, predictor, ctx.vertices());
        return Arrays.stream(streams)
                .flatMapToInt(stream -> Arrays.stream(stream).map(EncodingUtils::encodeZigZag))
                .toArray();
    }

    public static List<Vertex> invert(ClassifiedPredictor predictor, int[] values, BufferContext topology, int vertexCount) {
        final var layout = Segmentation.CLASS.layout(topology, predictor, vertexCount);
        final var streams = new int[layout.streamCount()][];
        var cursor = 0;
        for (var s = 0; s < streams.length; s++) {
            streams[s] = new int[layout.valueCounts()[s]];
            for (var i = 0; i < streams[s].length; i++) {
                streams[s][i] = DecodingUtils.decodeZigZag(values[cursor++]);
            }
        }
        return Segmentation.join(layout, predictor, streams, vertexCount);
    }
}
