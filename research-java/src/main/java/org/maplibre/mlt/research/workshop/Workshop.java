package org.maplibre.mlt.research.workshop;

import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.Encoding;
import org.maplibre.mlt.research.encoding.IntCodec;
import org.maplibre.mlt.research.encoding.VertexBufferCodec;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.EncodedBuffer;
import org.maplibre.mlt.research.encoding.VertexTransform;
import org.maplibre.mlt.research.pipeline.Prediction;
import org.maplibre.mlt.research.pipeline.PredictionTransform;
import org.maplibre.mlt.research.pipeline.Reorder;
import org.maplibre.mlt.research.pipeline.mechanism.GeometryReorder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * The prediction workshop's rows, measured interleaved over plain {@link IntCodec#VARINT} by {@code
 * research workshop} — mean x/y residual size, Varint bytes and per-tile gzip against the {@code
 * DELTA_ZIGZAG} baselines. To try an idea, add one {@link Predictor} line to {@link #experiments()};
 * the CLI and {@code VertexBufferCodecTest} round-trip it.
 *
 * <p>The pipeline's feature reorderings are measured separately from the predictors: {@link
 * #reorderRows()} runs each on its own (plain {@code DELTA} over the reordered buffer), and {@link
 * #withReorder} puts one in front of the predictor rows ({@code --reorder}). {@link #partChainRows()}
 * chain the parts inside each MultiLineString instead ({@link GeometryReorder#PART_CHAIN}).
 *
 * <p>{@code QUAD_CORNER_FRAME} and {@code QUAD_CORNER_LINE_FRAME} are the pipeline's ring-frame
 * predictors ({@link Prediction}) through its own {@code FLAT} split: a frame rotates the residual, so
 * it is not a {@link Predictor} rule, but its residual pairs are one per vertex like the others'.
 * They compose with {@code --reorder} like any known row; a new rule cannot be framed from here.
 *
 * <p>Retired predictors (right-angle turn, context delta, midpoint reflect, fixed rotation, the road
 * chain) and the whole-encoding rows (turn scalar, shared references: the pipeline measures them)
 * are history: their numbers are in {@code docs/results/failed/}, and the code is at the
 * {@code pre-consolidation} tag.
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

    /**
     * The predictors measured so far, without segmentation. {@code DELTA} is the baseline transform
     * written as a rule, so its Varint bytes equal {@code DELTA_ZIGZAG_VARINT}'s. The others are the
     * pipeline's own predictors ({@link Prediction}) through its {@code FLAT} split ({@link
     * PredictionTransform}), so a row here and the {@code FLAT} row of {@code research measure} write
     * the same residual pairs; the ring-frame ones rotate the residual and so are not a {@link
     * Predictor} rule.
     */
    public static List<Row> known() {
        return List.of(
                deltaRow(),
                predictionRow(Prediction.QUAD_CORNER),
                predictionRow(Prediction.LINE_CONTINUATION),
                predictionRow(Prediction.QUAD_CORNER_LINE),
                predictionRow(Prediction.QUAD_CORNER_FRAME),
                predictionRow(Prediction.QUAD_CORNER_LINE_FRAME));
    }

    private static Row predictionRow(Prediction prediction) {
        return row(new PredictionTransform(prediction));
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
     * A rule with memory across vertices goes through {@link Predictor#stateful}.
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
        var inner = List.of(deltaRow(), predictionRow(Prediction.LINE_CONTINUATION), row(chainContinuation().transform()));
        var rows = new ArrayList<Row>();
        for (var chain : List.of(GeometryReorder.PART_CHAIN, GeometryReorder.PART_CHAIN_REV)) {
            for (var row : inner) {
                rows.add(reordered(row.name() + chain.nameSuffix(), chain::apply, row));
            }
        }
        return rows;
    }

    /**
     * {@code rows} with {@code reorder} applied to every buffer before its predictor runs, each named
     * {@code <row><suffix>}; the rows themselves are unchanged. {@link Reorder#NONE} returns them as
     * they are. This is what {@code ./gradlew research --args="workshop" --reorder} measures the prediction rows
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
     * part-chain rows, and the predictors behind {@link Reorder#HILBERT_CENTROID_ROT_MP}.
     */
    public static List<Encoding> encodings() {
        var all = new ArrayList<>(rows());
        all.addAll(reorderRows());
        all.addAll(partChainRows());
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
            // An inner encoding may reorder further (the shared-reference predictors rotate rings): what the decoder
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

}
