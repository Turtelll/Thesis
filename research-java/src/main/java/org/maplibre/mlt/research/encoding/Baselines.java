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
     * Encodings under test — add one line per new idea. Each is a {@code (transform, codec,
     * reorder)} triple and names itself from those parts.
     */
    public static List<Encoding> research() {
        return List.of(
                // --- FASTPFOR ---
                new VertexBufferCodec(segmented(new SegmentPair()), IntCodec.FASTPFOR),
                new VertexBufferCodec(segmented(new SegmentPair()), IntCodec.FASTPFOR, GeometryReorder.FEATURE),
                new VertexBufferCodec(segmented(new SegmentPair()), IntCodec.FASTPFOR, GeometryReorder.FEATURE_REV),
                new VertexBufferCodec(segmented(new SegmentQuadCorner()), IntCodec.FASTPFOR),
                new VertexBufferCodec(segmented(new SegmentQuadCorner()), IntCodec.FASTPFOR, GeometryReorder.FEATURE),
                new VertexBufferCodec(segmented(new SegmentQuadCorner()), IntCodec.FASTPFOR, GeometryReorder.FEATURE_REV),
                new VertexBufferCodec(segmented(new Rot90Fixed()), IntCodec.FASTPFOR),
                new VertexBufferCodec(segmented(new SegmentQuadCornerRot90()), IntCodec.FASTPFOR, GeometryReorder.FEATURE_REV),

                // --- VARINT ---
                new VertexBufferCodec(segmented(new SegmentQuadCorner()), IntCodec.VARINT),
                new VertexBufferCodec(segmented(new SegmentPair()), IntCodec.VARINT, GeometryReorder.FEATURE_REV),
                new VertexBufferCodec(segmented(new SegmentQuadCorner()), IntCodec.VARINT, GeometryReorder.FEATURE_REV),
                new VertexBufferCodec(segmented(new Rot90Fixed()), IntCodec.VARINT),
                new VertexBufferCodec(segmented(new SegmentQuadCornerRot90()), IntCodec.VARINT),

                // --- RANS ---
                // WP7 follow-up: the rANS coder the cost model estimated but did not build. Paired with the
                // plain baseline transform and with the corpus's best one, so the entropy coder's own
                // contribution is separable from the transform's.
                new VertexBufferCodec(new VertexTransform.ZigZagDelta(), RANS),
                new VertexBufferCodec(segmented(new SegmentQuadCorner()), RANS),
                new VertexBufferCodec(segmented(new SegmentQuadCorner()), RANS, GeometryReorder.FEATURE_REV),

                // --- Segmented (per-region frequency tables) ---
                // Does the region split the transform already performs buy anything once an entropy
                // coder, rather than a bit packer, prices the regions?
                new SegmentedEncoding(GeometryReorder.NONE),
                new SegmentedEncoding(GeometryReorder.FEATURE_REV),

                // --- Interleaved controls (evaluation protocol Stage 1) ---
                // The same predictors as above with no region separation: isolates whether a predictor's
                // win depends on SEGMENTED layout at all, or survives interleaved. See
                // docs/results/segment-quad-corner.md for the documented case where this matters.
                new VertexBufferCodec(interleaved(new SegmentQuadCorner()), IntCodec.FASTPFOR),
                new VertexBufferCodec(interleaved(new SegmentQuadCorner()), RANS));
    }

    /**
     * Everything the benchmark measures: {@link #baseline()} followed by {@link #research()}.
     */
    public static List<Encoding> all() {
        var all = new ArrayList<Encoding>(baseline());
        all.addAll(research());
        return all;
    }
}
