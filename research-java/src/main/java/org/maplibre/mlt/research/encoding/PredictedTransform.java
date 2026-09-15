package org.maplibre.mlt.research.encoding;

import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.decoder.DecodingUtils;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a {@link ClassifiedPredictor} into a {@link VertexTransform} under a chosen {@link
 * Layout}, so the same prediction logic can be measured interleaved or region-packed without
 * being written twice. See {@link ClassifiedPredictor} for why that split matters.
 *
 * <p>Componentwise zigzag residual against the predictor's (x, y), exactly as every predictor
 * here already computed it: {@code residual = actual - predicted}, zigzag-encoded per component.
 * {@code prevX}/{@code prevY} — used only for the interior/default class, which every predictor
 * treats as first-order delta — advance continuously through the actual vertices regardless of
 * class, so there are no reset seeds.
 */
public final class PredictedTransform implements VertexTransform {

    private final ClassifiedPredictor predictor;
    private final Layout layout;

    public PredictedTransform(ClassifiedPredictor predictor, Layout layout) {
        this.predictor = predictor;
        this.layout = layout;
    }

    @Override
    public String name() {
        // SEGMENTED matches every predictor's own historical name (no suffix); INTERLEAVED is a new
        // variant and must not collide with it in the benchmark's column names.
        return layout == Layout.SEGMENTED ? predictor.name() : predictor.name() + "_INTERLEAVED";
    }

    @Override
    public int[] apply(BufferContext ctx) {
        final var vertices = ctx.vertices();
        final var classes = predictor.classify(ctx, vertices.size());
        final var slots = slots(classes);

        final var values = new int[vertices.size() * 2];
        var prevX = 0;
        var prevY = 0;
        for (var i = 0; i < vertices.size(); i++) {
            final var vertex = vertices.get(i);
            final var vertexClass = classes[i];
            final int predX;
            final int predY;
            if (predictor.hasPrediction(vertexClass)) {
                predX = predictor.predictX(vertexClass, i, vertices);
                predY = predictor.predictY(vertexClass, i, vertices);
            } else {
                predX = prevX;
                predY = prevY;
            }
            final var slot = slots.next(vertexClass);
            values[slot] = EncodingUtils.encodeZigZag(vertex.x() - predX);
            values[slot + 1] = EncodingUtils.encodeZigZag(vertex.y() - predY);
            prevX = vertex.x();
            prevY = vertex.y();
        }
        return values;
    }

    @Override
    public List<Vertex> invert(int[] values, BufferContext ctx) {
        final var vertexCount = values.length / 2;
        final var classes = predictor.classify(ctx, vertexCount);
        final var slots = slots(classes);

        final var vertices = new ArrayList<Vertex>(vertexCount);
        var prevX = 0;
        var prevY = 0;
        for (var i = 0; i < vertexCount; i++) {
            final var vertexClass = classes[i];
            final int predX;
            final int predY;
            if (predictor.hasPrediction(vertexClass)) {
                predX = predictor.predictX(vertexClass, i, vertices);
                predY = predictor.predictY(vertexClass, i, vertices);
            } else {
                predX = prevX;
                predY = prevY;
            }
            final var slot = slots.next(vertexClass);
            final var x = predX + DecodingUtils.decodeZigZag(values[slot]);
            final var y = predY + DecodingUtils.decodeZigZag(values[slot + 1]);
            vertices.add(new Vertex(x, y));
            prevX = x;
            prevY = y;
        }
        return vertices;
    }

    /**
     * Per-class write cursor, advanced two ints (x, y) at a time.
     */
    private interface Cursors {
        int next(int vertexClass);
    }

    private Cursors slots(int[] classes) {
        return switch (layout) {
            case INTERLEAVED -> {
                final var i = new int[]{0};
                yield vertexClass -> {
                    final var slot = i[0];
                    i[0] += 2;
                    return slot;
                };
            }
            case SEGMENTED -> {
                final var order = predictor.regionOrder();
                final var counts = new int[predictor.classCount()];
                for (final var c : classes) {
                    counts[c]++;
                }
                final var starts = new int[predictor.classCount()];
                var offset = 0;
                for (final var c : order) {
                    starts[c] = offset;
                    offset += counts[c] * 2;
                }
                final var cursors = starts.clone();
                yield vertexClass -> {
                    final var slot = cursors[vertexClass];
                    cursors[vertexClass] += 2;
                    return slot;
                };
            }
        };
    }
}
