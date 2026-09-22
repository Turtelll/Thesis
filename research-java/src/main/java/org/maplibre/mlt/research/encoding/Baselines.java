package org.maplibre.mlt.research.encoding;

import org.maplibre.mlt.research.encoding.research.*;

import java.util.ArrayList;
import java.util.List;

public final class Baselines {

    private static final IntCodec RANS = new RansCodec();

    private Baselines() {
    }

    private static VertexTransform segmented(ClassifiedPredictor predictor) {
        return new PredictedTransform(predictor, Layout.SEGMENTED);
    }

    private static VertexTransform interleaved(ClassifiedPredictor predictor) {
        return new PredictedTransform(predictor, Layout.INTERLEAVED);
    }

    /**
     * The two baselines (see {@code docs/architecture.md}): componentwise delta + zigzag over a
     * bit-aligned (FastPFOR) and a byte-aligned (Varint) physical codec. Every new encoding is
     * measured against these.
     */
    public static List<Encoding> baseline() {
        return List.of(
                new VertexBufferCodec(new VertexTransform.ZigZagDelta(), IntCodec.FASTPFOR),
                new VertexBufferCodec(new VertexTransform.ZigZagDelta(), IntCodec.VARINT));
    }

    /**
     * The encoding ideas themselves — add one line per new idea.
     *
     * <p>Every row here is a {@code (transform, codec)} pair under {@link Layout#INTERLEAVED} and in
     * the buffer's original order, because this is the set the default benchmark run reports and it
     * must answer one question only: <em>is the idea itself better?</em> Segmentation and reordering
     * are separately-measured add-ons ({@link #segmented()}, {@code --reorder}) — never register a
     * row that has either baked in, or its uplift becomes unattributable to the idea under test.
     */
    public static List<Encoding> core() {
        return List.of(
                new VertexBufferCodec(interleaved(new SegmentQuadCorner()), IntCodec.VARINT),
                new VertexBufferCodec(interleaved(new SegmentQuadCorner()), IntCodec.FASTPFOR),
                new VertexBufferCodec(interleaved(new LineContinuation()), IntCodec.VARINT),
                new VertexBufferCodec(interleaved(new LineContinuation()), IntCodec.FASTPFOR));
                //new VertexBufferCodec(interleaved(new Rot90Fixed()), IntCodec.VARINT),
    }

    /**
     * The segmentation add-on: the same predictors under {@link Layout#SEGMENTED}, which groups
     * residuals into contiguous per-class regions so a block-packing codec sizes each independently.
     *
     * <p>Measured only under {@code --segmented}, because the win is specific to a codec that prices
     * a block's maximum (FastPFOR) and does not carry over to a byte-aligned or general-purpose one —
     * so folding it into a headline number would credit the idea for the packaging. Only a {@link
     * ClassifiedPredictor} can have a row here, and it should have a {@link #core()} row beside it.
     */
    public static List<Encoding> segmented() {
        return List.of(
                new VertexBufferCodec(segmented(new SegmentPair()), IntCodec.FASTPFOR),
                new VertexBufferCodec(segmented(new SegmentQuadCorner()), IntCodec.FASTPFOR),
                new VertexBufferCodec(segmented(new LineContinuation()), IntCodec.FASTPFOR),
                new SegmentedEncoding());
    }

    /**
     * Everything the benchmark can measure without an add-on flag: {@link #baseline()} then {@link
     * #core()} then {@link #segmented()}. This is the round-trip surface the tests parameterize over,
     * not the default benchmark set — {@code BenchmarkCli} selects per run.
     */
    public static List<Encoding> all() {
        var all = new ArrayList<Encoding>(baseline());
        all.addAll(core());
        all.addAll(segmented());
        return all;
    }
}
