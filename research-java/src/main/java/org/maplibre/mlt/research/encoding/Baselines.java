package org.maplibre.mlt.research.encoding;

import java.util.ArrayList;
import java.util.List;

import org.maplibre.mlt.research.encoding.research.GeometryReorder;
import org.maplibre.mlt.research.encoding.research.Rot90Fixed;
import org.maplibre.mlt.research.encoding.research.SegmentPair;
import org.maplibre.mlt.research.encoding.research.SegmentQuadCorner;
import org.maplibre.mlt.research.encoding.research.SegmentQuadCornerRot90;

public final class Baselines {

    private Baselines() {
    }

    private static final IntCodec RANS = new RansCodec();

    /**
     * Baselines 1 and 2 from the root {@code README.md}: componentwise delta + zigzag over a
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
                new VertexBufferCodec(new SegmentPair(), IntCodec.FASTPFOR),
                new VertexBufferCodec(new SegmentPair(), IntCodec.FASTPFOR, GeometryReorder.FEATURE),
                new VertexBufferCodec(new SegmentPair(), IntCodec.FASTPFOR, GeometryReorder.FEATURE_REV),
                new VertexBufferCodec(new SegmentQuadCorner(), IntCodec.FASTPFOR),
                new VertexBufferCodec(new SegmentQuadCorner(), IntCodec.FASTPFOR, GeometryReorder.FEATURE),
                new VertexBufferCodec(new SegmentQuadCorner(), IntCodec.FASTPFOR, GeometryReorder.FEATURE_REV),
                new VertexBufferCodec(new Rot90Fixed(), IntCodec.FASTPFOR),
                new VertexBufferCodec(new SegmentQuadCornerRot90(), IntCodec.FASTPFOR, GeometryReorder.FEATURE_REV),

                // --- VARINT ---
                new VertexBufferCodec(new SegmentQuadCorner(), IntCodec.VARINT),
                new VertexBufferCodec(new SegmentPair(), IntCodec.VARINT, GeometryReorder.FEATURE_REV),
                new VertexBufferCodec(new SegmentQuadCorner(), IntCodec.VARINT, GeometryReorder.FEATURE_REV),
                new VertexBufferCodec(new Rot90Fixed(), IntCodec.VARINT),
                new VertexBufferCodec(new SegmentQuadCornerRot90(), IntCodec.VARINT),

                // --- RANS ---
                // WP7 follow-up: the rANS coder the cost model estimated but did not build. Paired with the
                // plain baseline transform and with the corpus's best one, so the entropy coder's own
                // contribution is separable from the transform's.
                new VertexBufferCodec(new VertexTransform.ZigZagDelta(), RANS),
                new VertexBufferCodec(new SegmentQuadCorner(), RANS),
                new VertexBufferCodec(new SegmentQuadCorner(), RANS, GeometryReorder.FEATURE_REV),

                // --- Segmented (per-region frequency tables) ---
                // Does the region split the transform already performs buy anything once an entropy
                // coder, rather than a bit packer, prices the regions?
                new SegmentedEncoding(GeometryReorder.NONE),
                new SegmentedEncoding(GeometryReorder.FEATURE_REV));
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
