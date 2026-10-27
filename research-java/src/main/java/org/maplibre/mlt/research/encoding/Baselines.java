package org.maplibre.mlt.research.encoding;

import org.maplibre.mlt.research.encoding.research.GeometryReorder;
import org.maplibre.mlt.research.encoding.research.LineContinuationEncoding;
import org.maplibre.mlt.research.encoding.research.LineSharedVertexRef;
import org.maplibre.mlt.research.encoding.research.QuadCornerEncoding;
import org.maplibre.mlt.research.encoding.research.QuadCornerLine;
import org.maplibre.mlt.research.encoding.research.SegmentQuadCorner;
import org.maplibre.mlt.research.encoding.research.SharedVertexRef;

import java.util.ArrayList;
import java.util.List;

public final class Baselines {

    private Baselines() {
    }

    private static Encoding zigZagDelta(IntCodec codec) {
        return new VertexBufferCodec(new VertexTransform.ZigZagDelta(), codec);
    }

    /**
     * The two baselines (see {@code docs/architecture.md}): componentwise delta + zigzag over a
     * bit-aligned (FastPFOR) and a byte-aligned (Varint) physical codec. The benchmark compares both
     * under gzip; the smaller is the reference every new encoding is measured against.
     */
    public static List<Encoding> baseline() {
        return List.of(zigZagDelta(IntCodec.FASTPFOR), zigZagDelta(IntCodec.VARINT));
    }

    /**
     * What the benchmark measures and the research oracle selects from: one self-contained encoding
     * per idea, each owning its region layout, any geometry reordering and its byte serialization —
     * add one line per idea.
     */
    public static List<Encoding> research() {
        return List.of(
                new QuadCornerEncoding(),
                new LineContinuationEncoding(),
                new SharedVertexRef(),
                // Ring edges coded in the frame of the nearest decoded ring, refined by the ring's own
                // walls (RingFramePrior); nothing transmitted. docs/results/orientation-prior.md
                new SharedVertexRef("QUAD_CORNER", new SegmentQuadCorner(), GeometryReorder.NONE, true),
                // Shared references over features chained nearest-endpoint first.
                new SharedVertexRef("QUAD_CORNER", new SegmentQuadCorner(), GeometryReorder.FEATURE),
                // Rings rotated to a canonical start, then features chained over the rotated starts.
                // The canonical start alone was revisited under split varints and is the smallest
                // row on building; composed with the chaining it is the smallest on the tileset.
                new SharedVertexRef("QUAD_CORNER", new SegmentQuadCorner(), GeometryReorder.RING_CANONICAL_THEN_FEATURE),
                // The frame prior over the same reordered buffer: how much of it survives chaining.
                new SharedVertexRef(
                        "QUAD_CORNER", new SegmentQuadCorner(), GeometryReorder.RING_CANONICAL_THEN_FEATURE, true),
                // Quad-corner shared references, plus continuation on line runs longer than 6 vertices.
                new SharedVertexRef("QUAD_CORNER_LINE", new QuadCornerLine()),
                new LineSharedVertexRef(),
                new LineSharedVertexRef(LineSharedVertexRef.Table.MARKED));
    }

    /**
     * Every registered encoding: {@link #baseline()} and {@link #research()}. This is the round-trip
     * surface the tests parameterize over, not what the benchmark reports.
     */
    public static List<Encoding> all() {
        var all = new ArrayList<Encoding>(baseline());
        all.addAll(research());
        return all;
    }
}
