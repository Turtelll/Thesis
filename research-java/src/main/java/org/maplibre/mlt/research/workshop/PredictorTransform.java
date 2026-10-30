package org.maplibre.mlt.research.workshop;

import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.decoder.DecodingUtils;
import org.maplibre.mlt.research.buffer.Encoding.BufferContext;
import org.maplibre.mlt.research.baseline.VertexTransform;
import org.maplibre.mlt.research.buffer.VertexRunIndex;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link Predictor} as an interleaved {@link VertexTransform}: values {@code 2i}, {@code 2i + 1}
 * are vertex {@code i}'s zigzag residual {@code actual - predicted} per component.
 *
 * <p>The encoder hands the rule every coordinate, the decoder only the ones reconstructed so far,
 * so a rule that looks ahead decodes to different geometry instead of silently winning.
 */
final class PredictorTransform implements VertexTransform {

    private final Predictor predictor;

    PredictorTransform(Predictor predictor) {
        this.predictor = predictor;
    }

    @Override
    public String name() {
        return predictor.name();
    }

    @Override
    public int[] apply(BufferContext ctx) {
        final var vertices = ctx.vertices();
        final var n = vertices.size();
        final var topology = VertexRunIndex.of(ctx, n);
        final var xs = new int[n];
        final var ys = new int[n];
        for (var i = 0; i < n; i++) {
            xs[i] = vertices.get(i).x();
            ys[i] = vertices.get(i).y();
        }
        final var values = new int[n * 2];
        final var out = new int[2];
        final var rule = predictor.rules().get();
        for (var i = 0; i < n; i++) {
            rule.predict(topology, i, xs, ys, out);
            values[2 * i] = EncodingUtils.encodeZigZag(xs[i] - out[0]);
            values[2 * i + 1] = EncodingUtils.encodeZigZag(ys[i] - out[1]);
        }
        return values;
    }

    @Override
    public List<Vertex> invert(int[] values, BufferContext ctx) {
        final var n = values.length / 2;
        final var topology = VertexRunIndex.of(ctx, n);
        final var xs = new int[n];
        final var ys = new int[n];
        final var out = new int[2];
        final var vertices = new ArrayList<Vertex>(n);
        final var rule = predictor.rules().get();
        for (var i = 0; i < n; i++) {
            rule.predict(topology, i, xs, ys, out);
            xs[i] = out[0] + DecodingUtils.decodeZigZag(values[2 * i]);
            ys[i] = out[1] + DecodingUtils.decodeZigZag(values[2 * i + 1]);
            vertices.add(new Vertex(xs[i], ys[i]));
        }
        return vertices;
    }
}
