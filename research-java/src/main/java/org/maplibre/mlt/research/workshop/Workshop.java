package org.maplibre.mlt.research.workshop;

import org.maplibre.mlt.research.encoding.ClassifiedPredictor;
import org.maplibre.mlt.research.encoding.Encoding;
import org.maplibre.mlt.research.encoding.IntCodec;
import org.maplibre.mlt.research.encoding.Layout;
import org.maplibre.mlt.research.encoding.PredictedTransform;
import org.maplibre.mlt.research.encoding.VertexBufferCodec;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.VertexTransform;
import org.maplibre.mlt.research.encoding.research.LineContinuation;
import org.maplibre.mlt.research.encoding.research.QuadCornerLine;
import org.maplibre.mlt.research.encoding.research.Rot90Fixed;
import org.maplibre.mlt.research.encoding.research.SegmentQuadCorner;
import org.maplibre.mlt.research.encoding.research.failed.ContextDelta;
import org.maplibre.mlt.research.encoding.research.failed.RightAngleTurn;
import org.maplibre.mlt.research.encoding.research.failed.SegmentQuadCornerRot90;
import org.maplibre.mlt.research.encoding.research.failed.TurnScalar;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The prediction workshop's rows, measured interleaved over plain {@link IntCodec#VARINT} by {@code
 * ./gradlew workshop} — mean x/y residual size, Varint bytes and per-tile gzip against the {@code
 * DELTA_ZIGZAG} baselines. To try an idea, add one {@link Predictor} line to {@link #experiments()};
 * the CLI and {@code VertexBufferCodecTest} round-trip it.
 *
 * <p>Two known rows write a side value besides each vertex's residual pair, and pay for it in their
 * bytes: {@code TURN_SCALAR} an {@code along} scalar per turn vertex, {@code RIGHT_ANGLE_TURN} a
 * signalled direction bit per turn vertex (chosen by the encoder, so not expressible as a rule).
 *
 * <p>Not here, because they are not predictors over a residual pair: {@code SegmentPair} (identical to
 * {@code DELTA} interleaved), {@code Turn90Bitmap}, {@code EvenRingTurn} and {@code
 * SegmentQuadCornerTurn} (signalling schemes measured against their segmented layouts), the RDP
 * residual encoders (side information), and {@code SharedVertexRef}, {@code LineSharedVertexRef} and
 * {@code StandardDelta} (references or tokens).
 */
public final class Workshop {

    private Workshop() {
    }

    /**
     * One row: its name in the tables, the encoding sized and round-tripped, and per buffer the
     * {@link Residuals} its statistics are taken over.
     */
    public record Row(String name, Encoding encoding, Function<BufferContext, Residuals> residuals) {
    }

    /**
     * Every vertex's zigzag {@code (x, y)} residual pair, interleaved in vertex order, plus the number
     * of side values the row writes outside those pairs.
     */
    public record Residuals(int[] pairs, int sideValues) {
    }

    /** {@code MidpointReflect}'s threshold: shorter runs have no meaningful half. */
    private static final int MIDPOINT_MIN_RING = 8;

    /**
     * The predictors measured so far, without segmentation. {@code DELTA} is the baseline transform
     * written as a rule, so its Varint bytes equal {@code DELTA_ZIGZAG_VARINT}'s.
     */
    public static List<Row> known() {
        var turnScalar = new TurnScalar(Layout.INTERLEAVED);
        var rightAngleTurn = new RightAngleTurn("RIGHT_ANGLE_TURN_VARINT", IntCodec.VARINT);
        return List.of(
                row(Predictor.of("DELTA", (t, i, xs, ys, out) -> Predictor.prev(i, xs, ys, out)).transform()),
                row(interleaved(new SegmentQuadCorner())),
                row(interleaved(new LineContinuation())),
                row(interleaved(new QuadCornerLine())),
                row(interleaved(new Rot90Fixed())),
                row(interleaved(new SegmentQuadCornerRot90())),
                row(new VertexTransform.ZigZagDelta2()),
                row(midpointReflect(true).transform()),
                row(midpointReflect(false).transform()),
                row(contextDelta(4, 2, 32).transform()),
                new Row(
                        turnScalar.name(),
                        new VertexBufferCodec(turnScalar, IntCodec.VARINT),
                        ctx -> new Residuals(turnScalar.residualPairs(ctx), turnScalar.alongCount(ctx))),
                new Row(
                        "RIGHT_ANGLE_TURN",
                        rightAngleTurn,
                        ctx -> new Residuals(rightAngleTurn.residuals(ctx), rightAngleTurn.turnBits(ctx))));
    }

    /**
     * New ideas. A rule predicts vertex {@code i} from vertices {@code < i} and the {@link Topology},
     * e.g. continuation on every line vertex past the second:
     *
     * <pre>{@code
     * Predictor.of("LINE_CONT_ALL", (t, i, xs, ys, out) -> {
     *     if (t.isLine(i) && t.indexInRun(i) >= 2) Predictor.continuation(i, xs, ys, out);
     *     else Predictor.prev(i, xs, ys, out);
     * })
     * }</pre>
     *
     * A rule with memory across vertices goes through {@link Predictor#stateful}; see {@link
     * #contextDelta}.
     */
    public static List<Predictor> experiments() {
        return List.of();
    }

    /** {@link #known()} then {@link #experiments()}. */
    public static List<Row> rows() {
        var all = new ArrayList<>(known());
        for (var predictor : experiments()) {
            all.add(row(predictor.transform()));
        }
        return all;
    }

    /** Every row's encoding, for the round-trip tests. */
    public static List<Encoding> encodings() {
        return rows().stream().map(Row::encoding).toList();
    }

    /** A residual-only transform over Varint: its stream is the residual pairs. */
    private static Row row(VertexTransform transform) {
        return new Row(
                transform.name(),
                new VertexBufferCodec(transform, IntCodec.VARINT),
                ctx -> new Residuals(transform.apply(ctx), 0));
    }

    private static VertexTransform interleaved(ClassifiedPredictor predictor) {
        return new PredictedTransform(predictor, Layout.INTERLEAVED);
    }

    /**
     * {@code MidpointReflect} without its segmented regions: the second half of a run of at least
     * {@value #MIDPOINT_MIN_RING} vertices, past the apex at {@code length / 2}, predicted as {@code
     * start + apex - partner}; the partner is {@code length - r} ({@code symmetric}) or {@code r -
     * length / 2}. Everything else is plain delta.
     */
    static Predictor midpointReflect(boolean symmetric) {
        return Predictor.of(
                "MIDPOINT_REFLECT_" + (symmetric ? "SYMMETRIC" : "SPLIT"),
                (t, i, xs, ys, out) -> {
                    final var length = t.runLength(i);
                    final var r = t.indexInRun(i);
                    if (length < MIDPOINT_MIN_RING || r <= length / 2) {
                        Predictor.prev(i, xs, ys, out);
                        return;
                    }
                    final var start = t.runStart(i);
                    final var apex = start + length / 2;
                    final var partner = start + (symmetric ? length - r : r - length / 2);
                    out[0] = xs[start] + xs[apex] - xs[partner];
                    out[1] = ys[start] + ys[apex] - ys[partner];
                });
    }

    /**
     * {@code ContextDelta} without its per-context regions, and so without the count header they
     * needed: the same {@link ContextDelta.State} picks each vertex's context {@code c}, and the vertex
     * is predicted as the previous one plus the mean of the last {@code c} edges ({@code c = 0}: plain
     * delta). The state is folded forward from vertex {@code i - 1} before predicting {@code i}, the
     * order {@code ContextDelta} fixes.
     */
    static Predictor contextDelta(int contexts, int turnShift, int missThreshold) {
        return Predictor.stateful(
                "CONTEXT_DELTA_T" + turnShift + "_M" + missThreshold,
                () -> new Predictor.Rule() {
                    private ContextDelta.State state;
                    private int context;
                    private int predX;
                    private int predY;

                    @Override
                    public void predict(Topology t, int i, int[] xs, int[] ys, int[] out) {
                        if (i == 0) {
                            state = new ContextDelta.State(t.runStarts(), turnShift, contexts - 1, missThreshold);
                        } else {
                            state.advance(i - 1, xs[i - 1], ys[i - 1]);
                            state.penalise(context, xs[i - 1] - predX, ys[i - 1] - predY);
                        }
                        context = state.context();
                        Predictor.prev(i, xs, ys, out);
                        if (context > 0) {
                            out[0] += state.meanEdgeX(context);
                            out[1] += state.meanEdgeY(context);
                        }
                        predX = out[0];
                        predY = out[1];
                    }
                });
    }
}
