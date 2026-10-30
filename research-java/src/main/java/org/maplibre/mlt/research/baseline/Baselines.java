package org.maplibre.mlt.research.baseline;

import org.maplibre.mlt.research.buffer.Encoding;
import org.maplibre.mlt.research.encoder.stream.IntCodec;
import java.util.List;

public final class Baselines {

    private Baselines() {
    }

    private static Encoding zigZagDelta(IntCodec codec) {
        return new VertexBufferCodec(new VertexTransform.ZigZagDelta(), codec);
    }

    /**
     * The two baselines (see {@code docs/architecture.md}): componentwise delta + zigzag over a
     * bit-aligned (FastPFOR) and a byte-aligned (Varint) physical codec. The harness compares both
     * under gzip; the smaller is the reference every new encoding is measured against. The research
     * rows are the pipeline's ({@code PipelineRegistry}).
     */
    public static List<Encoding> baseline() {
        return List.of(zigZagDelta(IntCodec.FASTPFOR), zigZagDelta(IntCodec.VARINT));
    }

    /** Every registered baseline: the round-trip surface of {@code VertexBufferCodecTest}. */
    public static List<Encoding> all() {
        return baseline();
    }
}