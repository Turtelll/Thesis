package org.maplibre.mlt.research.encoding;

import org.maplibre.mlt.research.encoding.research.GeometryReorder;
import org.maplibre.mlt.research.encoding.research.LineContinuationEncoding;
import org.maplibre.mlt.research.encoding.research.LineSharedVertexRef;
import org.maplibre.mlt.research.encoding.research.QuadCornerEncoding;
import org.maplibre.mlt.research.encoding.research.QuadCornerLine;
import org.maplibre.mlt.research.encoding.research.Rot90Fixed;
import org.maplibre.mlt.research.encoding.research.SegmentPair;
import org.maplibre.mlt.research.encoding.research.SegmentQuadCorner;
import org.maplibre.mlt.research.encoding.research.SharedVertexRef;
import org.maplibre.mlt.research.encoding.research.failed.BwtMtfCodec;
import org.maplibre.mlt.research.encoding.research.failed.ContextDelta;
import org.maplibre.mlt.research.encoding.research.failed.EvenRingTurn;
import org.maplibre.mlt.research.encoding.research.failed.MidpointReflect;
import org.maplibre.mlt.research.encoding.research.failed.RdpResidual;
import org.maplibre.mlt.research.encoding.research.failed.RdpResidualSegmented;
import org.maplibre.mlt.research.encoding.research.failed.RightAngleTurn;
import org.maplibre.mlt.research.encoding.research.failed.SegmentQuadCornerRot90;
import org.maplibre.mlt.research.encoding.research.failed.SegmentQuadCornerTurn;
import org.maplibre.mlt.research.encoding.research.failed.TurnScalar;
import org.maplibre.mlt.research.encoding.research.failed.StandardDelta;
import org.maplibre.mlt.research.encoding.research.failed.TileStandardDelta;
import org.maplibre.mlt.research.encoding.research.failed.Turn90Bitmap;

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
                // Shared references over features chained nearest-endpoint first.
                new SharedVertexRef("QUAD_CORNER", new SegmentQuadCorner(), GeometryReorder.FEATURE),
                // Rings rotated to a canonical start, then features chained over the rotated starts.
                // The canonical start alone was revisited under split varints and is the smallest
                // row on building; composed with the chaining it is the smallest on the tileset.
                new SharedVertexRef("QUAD_CORNER", new SegmentQuadCorner(), GeometryReorder.RING_CANONICAL_THEN_FEATURE),
                // Quad-corner shared references, plus continuation on line runs longer than 6 vertices.
                new SharedVertexRef("QUAD_CORNER_LINE", new QuadCornerLine()),
                new LineSharedVertexRef(),
                new LineSharedVertexRef(LineSharedVertexRef.Table.MARKED));
    }

    /**
     * Retired ideas re-measured over the segmented {@link IntCodec#VARINT_SPLIT} layout, none the
     * smallest row on any layer; see {@code docs/results/revisit-varint-split.md}.
     */
    private static List<Encoding> revisited() {
        var split = IntCodec.VARINT_SPLIT;
        var rows = new ArrayList<Encoding>();
        rows.add(new VertexBufferCodec(new PredictedTransform(new SegmentPair(), Layout.SEGMENTED), split));
        rows.add(new VertexBufferCodec(new PredictedTransform(new Rot90Fixed(), Layout.SEGMENTED), split));
        rows.add(new VertexBufferCodec(
                new PredictedTransform(new SegmentQuadCornerRot90(), Layout.SEGMENTED), split));
        rows.add(new VertexBufferCodec(new TurnScalar(Layout.SEGMENTED), split));
        rows.add(new VertexBufferCodec(new MidpointReflect(MidpointReflect.Pairing.SYMMETRIC), split));
        rows.add(new VertexBufferCodec(new MidpointReflect(MidpointReflect.Pairing.SPLIT), split));
        rows.add(new VertexBufferCodec(new VertexTransform.ZigZagDelta2(), split));
        rows.add(new ContextDelta("CONTEXT_DELTA_T2_M32_VARINT_SPLIT", split, 4, 2, 32));
        rows.add(new RightAngleTurn("RIGHT_ANGLE_TURN_VARINT_SPLIT", split));
        rows.add(new Turn90Bitmap(split));
        rows.add(new EvenRingTurn("EVEN_RING_TURN_VARINT_SPLIT", split));
        rows.add(new SegmentQuadCornerTurn("QUAD_CORNER_TURN_VARINT_SPLIT", split));
        rows.add(new RdpResidual(split));
        rows.add(new RdpResidualSegmented(split));
        // Dominated by its composition with feature chaining, which is in research().
        rows.add(new SharedVertexRef("QUAD_CORNER", new SegmentQuadCorner(), GeometryReorder.RING_CANONICAL));
        return rows;
    }

    /**
     * Every {@link BwtMtfCodec} stage in both domains over the baseline transform. None beat the
     * plain row, so these are retired; the research encodings were measured the same way and have
     * no codec parameter any more. See {@code docs/results/failed/bwt-mtf.md}.
     */
    private static List<Encoding> bwtMtf() {
        var rows = new ArrayList<Encoding>();
        for (var domain : BwtMtfCodec.Domain.values()) {
            for (var stage : BwtMtfCodec.Stage.values()) {
                rows.add(zigZagDelta(new BwtMtfCodec(domain, stage)));
            }
        }
        return rows;
    }

    /**
     * The byte serializations measured besides {@link IntCodec#VARINT}; see {@code
     * docs/results/general-purpose-compression.md}.
     */
    public static final List<IntCodec> BYTE_SERIALIZERS =
            List.of(IntCodec.VARINT_XY, IntCodec.VARINT_SPLIT, IntCodec.BYTESPLIT);

    /**
     * The fixed 16-bit serializers {@link StandardDelta} was measured over.
     */
    public static final List<IntCodec> UINT16_SERIALIZERS = List.of(IntCodec.UINT16, IntCodec.UINT16_PLANES);

    /**
     * Rows no benchmark run measures any more — rANS, the baseline transform over {@link
     * IntCodec#RAW} and the {@link #BYTE_SERIALIZERS}, the {@link #bwtMtf()} matrix, the {@link
     * #revisited()} ideas and the encodings in {@code failed/}. Kept
     * registered so the tests still round-trip them and {@code DecodeTimeCli} can name them.
     */
    public static List<Encoding> retired() {
        var rows = new ArrayList<Encoding>();
        rows.add(new SegmentedEncoding());
        rows.add(zigZagDelta(IntCodec.RAW));
        for (var codec : BYTE_SERIALIZERS) {
            rows.add(zigZagDelta(codec));
        }
        rows.addAll(bwtMtf());
        rows.addAll(revisited());
        for (var codec : UINT16_SERIALIZERS) {
            for (var decomposition : StandardDelta.Decomposition.all()) {
                rows.add(new StandardDelta(decomposition, codec));
            }
        }
        return rows;
    }

    /**
     * {@link StandardDelta} with the vocabulary learned per tile from all its layers and stored in
     * the tile: both {@link TileStandardDelta.Unit}s, 1, 4 and 16 entries, any number of entries per
     * delta and capped at one, over {@link IntCodec#VARINT_SPLIT} and {@link IntCodec#UINT16_PLANES}.
     * Retired like {@link #retired()}; not an {@link Encoding}, so not part of {@link #all()}.
     */
    public static List<TileStandardDelta> tileStandardDelta() {
        var rows = new ArrayList<TileStandardDelta>();
        for (var unit : TileStandardDelta.Unit.values()) {
            for (var k : List.of(1, 4, 16)) {
                for (var single : List.of(false, true)) {
                    for (var codec : List.of(IntCodec.VARINT_SPLIT, IntCodec.UINT16_PLANES)) {
                        rows.add(new TileStandardDelta(unit, k, single, codec));
                    }
                }
            }
        }
        return rows;
    }

    /**
     * Every registered encoding: {@link #baseline()}, {@link #research()}, {@link #retired()}. This is the round-trip surface the tests
     * parameterize over, not what the benchmark reports.
     */
    public static List<Encoding> all() {
        var all = new ArrayList<Encoding>(baseline());
        all.addAll(research());
        all.addAll(retired());
        return all;
    }
}
