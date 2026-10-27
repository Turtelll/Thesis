package org.maplibre.mlt.research.workshop;

import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.decoder.DecodingUtils;
import org.maplibre.mlt.research.encoding.ClassifiedPredictor;
import org.maplibre.mlt.research.encoding.Encoding;
import org.maplibre.mlt.research.encoding.IntCodec;
import org.maplibre.mlt.research.encoding.Layout;
import org.maplibre.mlt.research.encoding.PredictedTransform;
import org.maplibre.mlt.research.encoding.VertexBufferCodec;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.EncodedBuffer;
import org.maplibre.mlt.research.encoding.VertexTransform;
import org.maplibre.mlt.research.encoding.research.GeometryReorder;
import org.maplibre.mlt.research.encoding.research.LineContinuation;
import org.maplibre.mlt.research.encoding.research.QuadCornerLine;
import org.maplibre.mlt.research.encoding.research.Rot90Fixed;
import org.maplibre.mlt.research.encoding.research.SegmentQuadCorner;
import org.maplibre.mlt.research.encoding.research.SharedVertexRef;
import org.maplibre.mlt.research.encoding.research.failed.ContextDelta;
import org.maplibre.mlt.research.encoding.research.failed.RightAngleTurn;
import org.maplibre.mlt.research.encoding.research.failed.SegmentQuadCornerRot90;
import org.maplibre.mlt.research.encoding.research.failed.TurnScalar;
import org.maplibre.mlt.research.pipeline.Prediction;
import org.maplibre.mlt.research.pipeline.Reorder;
import org.maplibre.mlt.research.pipeline.Segmentation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * The prediction workshop's rows, measured interleaved over plain {@link IntCodec#VARINT} by {@code
 * ./gradlew workshop} — mean x/y residual size, Varint bytes and per-tile gzip against the {@code
 * DELTA_ZIGZAG} baselines. To try an idea, add one {@link Predictor} line to {@link #experiments()};
 * the CLI and {@code VertexBufferCodecTest} round-trip it.
 *
 * <p>The pipeline's feature reorderings are measured separately from the predictors: {@link
 * #reorderRows()} runs each on its own (plain {@code DELTA} over the reordered buffer), and {@link
 * #withReorder} puts one in front of the predictor rows ({@code --reorder}). {@link #partChainRows()}
 * chain the parts inside each MultiLineString instead ({@link GeometryReorder#PART_CHAIN}), and {@link
 * #roadChainRows()} chain whole line features end to start ({@link GeometryReorder#ROAD_CHAIN}).
 *
 * <p>Three known rows write side values besides the residual pairs, and pay for them in their
 * bytes: {@code TURN_SCALAR} an {@code along} scalar per turn vertex, {@code RIGHT_ANGLE_TURN} a
 * signalled direction bit per turn vertex (chosen by the encoder, so not expressible as a rule), and
 * {@code QUAD_CORNER_SHARED} ({@link SharedVertexRef}) its reference flags, one per vertex that
 * can reference an earlier ring. It is the full encoding, segmented by predictor class and with its
 * own rotation and plain fallback, not an interleaved rule; a referenced vertex has no residual
 * pair, so its mean and median are over the unreferenced vertices only and its {@code side}
 * counts flags only (the entry indices are not counted).
 *
 * <p>{@code QUAD_CORNER_FRAME} and {@code QUAD_CORNER_LINE_FRAME} are the pipeline's ring-frame
 * predictors ({@link Prediction}) through its own {@code FLAT} split: a frame rotates the residual, so
 * it is not a {@link Predictor} rule, but its residual pairs are one per vertex like the others'.
 * They compose with {@code --reorder} like any known row; a new rule cannot be framed from here.
 *
 * <p>Not here, because they are not predictors over a residual pair: {@code SegmentPair} (identical to
 * {@code DELTA} interleaved), {@code Turn90Bitmap}, {@code EvenRingTurn} and {@code
 * SegmentQuadCornerTurn} (signalling schemes measured against their segmented layouts), the RDP
 * residual encoders (side information), and {@code LineSharedVertexRef} and
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
        var sharedVertexRef = new SharedVertexRef();
        return List.of(
                deltaRow(),
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
                        ctx -> new Residuals(rightAngleTurn.residuals(ctx), rightAngleTurn.turnBits(ctx))),
                new Row(
                        sharedVertexRef.name(),
                        sharedVertexRef,
                        ctx -> {
                            var shared = sharedVertexRef.residuals(ctx);
                            return new Residuals(shared.pairs(), shared.flags());
                        }),
                row(new FramedTransform(Prediction.QUAD_CORNER_FRAME)),
                row(new FramedTransform(Prediction.QUAD_CORNER_LINE_FRAME)));
    }

    /**
     * A pipeline predictor with the ring frame ({@link Prediction#QUAD_CORNER_FRAME}, {@link
     * Prediction#QUAD_CORNER_LINE_FRAME}) as an interleaved transform: the pipeline's own {@link
     * Segmentation#FLAT} split and join, so its residual pairs are the ones the pipeline's {@code FLAT}
     * rows write, and the ring frame is {@code RingFramePrior}'s. The frame rotates the residual of a
     * ring's plain-delta vertices into the frame of the nearest decoded ring (nothing signalled), which
     * a {@link Predictor} rule, which only predicts a position, cannot express; so this is a known row
     * and not an experiment.
     */
    private record FramedTransform(Prediction prediction) implements VertexTransform {

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
        return List.of(chainContinuation());
    }

    /** {@code LineContinuation}'s run-length gate: shorter unjoined runs stay on plain delta. */
    private static final int MIN_LINE_RUN_LENGTH = 4;

    /**
     * {@code LINE_CONTINUATION} carried across joined line parts: a part whose start repeats the
     * previous vertex (the previous part ended there) is a continuation of it, so its second vertex
     * is predicted as the previous part's last edge continued through the join, {@code 2 p[start] -
     * p[start - 2]}, skipping the duplicate, and its later vertices by continuation whatever the
     * part's length. The join is read off the decoded prefix, so nothing is signalled. Unjoined parts
     * are predicted as {@code LINE_CONTINUATION} predicts them; a part start is always plain delta,
     * which is {@code (0, 0)} on a join. Meant for buffers {@link GeometryReorder#PART_CHAIN} has
     * chained.
     */
    static Predictor chainContinuation() {
        return Predictor.of("CHAIN_CONTINUATION", (t, i, xs, ys, out) -> {
            if (!t.isLine(i) || t.isRunStart(i)) {
                Predictor.prev(i, xs, ys, out);
                return;
            }
            final var start = t.runStart(i);
            final var k = i - start;
            if (k >= 2 && t.runLength(i) >= MIN_LINE_RUN_LENGTH) {
                Predictor.continuation(i, xs, ys, out);
                return;
            }
            final var joined = start >= 2
                    && t.isLine(start - 1)
                    && !t.isRunStart(start - 1)
                    && xs[start] == xs[start - 1]
                    && ys[start] == ys[start - 1];
            if (!joined) {
                Predictor.prev(i, xs, ys, out);
            } else if (k == 1) {
                out[0] = 2 * xs[start] - xs[start - 2];
                out[1] = 2 * ys[start] - ys[start - 2];
            } else {
                Predictor.continuation(i, xs, ys, out);
            }
        });
    }

    /** {@link #known()} then {@link #experiments()}. */
    public static List<Row> rows() {
        var all = new ArrayList<>(known());
        for (var predictor : experiments()) {
            all.add(row(predictor.transform()));
        }
        return all;
    }

    /**
     * The pipeline's feature reorderings ({@link Reorder}) on their own: one row per variant but
     * {@link Reorder#NONE}, each the plain {@code DELTA} predictor over the reordered buffer, named
     * {@code REORDER_<variant>}. They are not combined with any other predictor, so a row isolates what
     * the reordering alone buys and can be set against the prediction rows (and against the unordered
     * {@code DELTA} baseline). Like the pipeline's, a reordering transmits nothing: the decoder emits
     * features in the order it reads them.
     */
    public static List<Row> reorderRows() {
        var delta = deltaRow();
        return Arrays.stream(Reorder.values())
                .filter(Reorder::reorders)
                .map(reorder -> reordered("REORDER" + reorder.suffix(), reorder::apply, delta))
                .toList();
    }

    /**
     * {@code DELTA}, {@code LINE_CONTINUATION} and {@link #chainContinuation CHAIN_CONTINUATION} over
     * the buffer {@link GeometryReorder#PART_CHAIN} and {@link GeometryReorder#PART_CHAIN_REV} emit,
     * named {@code <row><suffix>}. Feature order is kept; only the parts inside each MultiLineString
     * are chained. Not run behind {@code --reorder}: the feature reorder hands its inner encoding a
     * context without vertices, which a second reorder could not chain.
     */
    public static List<Row> partChainRows() {
        var inner = List.of(deltaRow(), row(interleaved(new LineContinuation())), row(chainContinuation().transform()));
        var rows = new ArrayList<Row>();
        for (var chain : List.of(GeometryReorder.PART_CHAIN, GeometryReorder.PART_CHAIN_REV)) {
            for (var row : inner) {
                rows.add(reordered(row.name() + chain.nameSuffix(), chain::apply, row));
            }
        }
        return rows;
    }

    /**
     * {@code DELTA}, {@code LINE_CONTINUATION} and {@link #chainContinuation CHAIN_CONTINUATION} over
     * the buffer {@link GeometryReorder#ROAD_CHAIN} emits, named {@code <row>_ROAD_CHAIN}: line features
     * chained end to start, forward only, so a road of many features follows one cursor. The rows are
     * interleaved and signal nothing, so a join is a {@code (0, 0)} start pair that {@code
     * CHAIN_CONTINUATION} reads off the decoded prefix and continues through; the lossless {@code
     * RoadChainEncoding} instead drops that vertex and pays a join bit (see {@code
     * docs/results/road-chain.md}). Like {@link #partChainRows()}, not run behind {@code --reorder}.
     */
    public static List<Row> roadChainRows() {
        var chain = GeometryReorder.ROAD_CHAIN;
        var inner = List.of(deltaRow(), row(interleaved(new LineContinuation())), row(chainContinuation().transform()));
        var rows = new ArrayList<Row>();
        for (var row : inner) {
            rows.add(reordered(row.name() + chain.nameSuffix(), chain::apply, row));
        }
        return rows;
    }

    /**
     * {@code rows} with {@code reorder} applied to every buffer before its predictor runs, each named
     * {@code <row><suffix>}; the rows themselves are unchanged. {@link Reorder#NONE} returns them as
     * they are. This is what {@code ./gradlew workshop --reorder} measures the prediction rows
     * through.
     */
    public static List<Row> withReorder(Reorder reorder, List<Row> rows) {
        if (!reorder.reorders()) {
            return rows;
        }
        return rows.stream()
                .map(row -> reordered(row.name() + reorder.suffix(), reorder::apply, row))
                .toList();
    }

    /**
     * Every row's encoding, for the round-trip tests: the predictors, the reorder-only rows, the
     * part-chain and road-chain rows, and the predictors behind {@link Reorder#HILBERT_CENTROID_ROT_MP}.
     */
    public static List<Encoding> encodings() {
        var all = new ArrayList<>(rows());
        all.addAll(reorderRows());
        all.addAll(partChainRows());
        all.addAll(roadChainRows());
        all.addAll(withReorder(Reorder.HILBERT_CENTROID_ROT_MP, rows()));
        return all.stream().map(Row::encoding).toList();
    }

    /**
     * The residuals of the {@code DELTA_ZIGZAG} baselines (both codecs share them), for the
     * workshop's statistics columns.
     */
    public static Function<BufferContext, Residuals> baselineResiduals() {
        return deltaRow().residuals();
    }

    /** The baseline delta as a rule, which {@link #reorderRows()} runs over each reordering. */
    private static Row deltaRow() {
        return row(Predictor.of("DELTA", (t, i, xs, ys, out) -> Predictor.prev(i, xs, ys, out)).transform());
    }

    /**
     * {@code inner} measured over the buffer {@code reorder} emits, under {@code name}. Its residual
     * statistics are taken over the reordered buffer too.
     */
    private static Row reordered(String name, UnaryOperator<BufferContext> reorder, Row inner) {
        return new Row(
                name,
                new Reordered(name, reorder, inner.encoding()),
                ctx -> inner.residuals().apply(reorder.apply(ctx)));
    }

    /**
     * An encoding behind a reordering ({@link Reorder} or {@link GeometryReorder}): the buffer is
     * reordered, then {@code inner} encodes it. The decoder is handed the reordered topology streams
     * with no vertices (what the tile carries), so {@code inner} cannot decode by reading the buffer,
     * and the result is the reordered buffer, which {@link #emitted} names for the round-trip check.
     */
    private record Reordered(String name, UnaryOperator<BufferContext> reorder, Encoding inner) implements Encoding {

        @Override
        public EncodedBuffer encode(BufferContext ctx) {
            return inner.encode(reorder.apply(ctx));
        }

        @Override
        public List<Vertex> decode(EncodedBuffer encoded, BufferContext ctx, int vertexCount) {
            final var reordered = reorder.apply(ctx);
            final var topology = new BufferContext(
                    List.of(),
                    reordered.geometryTypes(),
                    reordered.numGeometries(),
                    reordered.numParts(),
                    reordered.numRings());
            return inner.decode(encoded, topology, vertexCount);
        }

        @Override
        public boolean reordersGeometries() {
            return true;
        }

        @Override
        public BufferContext emitted(BufferContext ctx) {
            // An inner encoding may reorder further (SharedVertexRef rotates rings): what the decoder
            // emits is the inner one's emitted buffer over the reordered one.
            return inner.emitted(reorder.apply(ctx));
        }

        @Override
        public String toString() {
            return name;
        }
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
