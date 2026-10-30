package org.maplibre.mlt.research.pipeline;

import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.decoder.DecodingUtils;
import org.maplibre.mlt.research.buffer.Encoding.BufferContext;
import org.maplibre.mlt.research.encoding.VertexTransform;
import org.maplibre.mlt.research.encoder.prediction.Prediction;

import java.util.List;

/**
 * A pipeline {@link Prediction} as an interleaved {@link VertexTransform}: the pipeline's own {@link
 * Segmentation#FLAT} split and join, so the residual pairs are the ones the pipeline's {@code FLAT}
 * rows write — one zigzag pair per vertex, in vertex order. It is how the workshop and the probes
 * read the residuals of a pipeline predictor (the ring frame, which rotates the residual, included)
 * without an encoder around them. A predictor with side values (turn scalar) keeps those out of the
 * array, so it is not invertible from it.
 */
public record PredictionTransform(Prediction prediction) implements VertexTransform {

    @Override
    public String name() {
        return prediction.name();
    }

    @Override
    public int[] apply(BufferContext ctx) {
        final var predictor = prediction.predictor();
        final var n = ctx.vertices().size();
        final var layout = Segmentation.FLAT.layout(ctx, predictor, n);
        final var residuals = Segmentation.split(layout, predictor, ctx.vertices())[0];
        final var values = new int[residuals.length];
        for (var i = 0; i < values.length; i++) {
            values[i] = EncodingUtils.encodeZigZag(residuals[i]);
        }
        return values;
    }

    @Override
    public List<Vertex> invert(int[] values, BufferContext ctx) {
        final var predictor = prediction.predictor();
        final var n = values.length / 2;
        final var layout = Segmentation.FLAT.layout(ctx, predictor, n);
        final var residuals = new int[values.length];
        for (var i = 0; i < values.length; i++) {
            residuals[i] = DecodingUtils.decodeZigZag(values[i]);
        }
        return Segmentation.join(layout, predictor, new int[][] {residuals}, n);
    }
}