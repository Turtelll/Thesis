package org.maplibre.mlt.research.encoding;

import java.util.ArrayList;
import java.util.List;

import org.maplibre.mlt.research.encoding.research.SegmentPair;

/**
 * The encodings the harness runs: the fixed baselines, plus whatever is under test.
 */
public final class Baselines {

    private Baselines() {
    }

    /**
     * Baselines 1 and 2 from the root {@code README.md}: componentwise delta + zigzag over a
     * bit-aligned (FastPFOR) and a byte-aligned (Varint) physical codec. Every new encoding is
     * measured against these.
     */
    public static List<Encoding> baseline() {
        return List.of(
                new VertexBufferCodec(
                        "DELTA_ZIGZAG_FASTPFOR", new VertexTransform.ZigZagDelta(), new IntCodec.FastPfor()),
                new VertexBufferCodec(
                        "DELTA_ZIGZAG_VARINT", new VertexTransform.ZigZagDelta(), new IntCodec.Varint()));
    }

    /**
     * Encodings under test — add one line per new idea. Empty means a baseline-only run, which is the
     * null result that shows the harness works before any new encoding is judged by it.
     */
    public static List<Encoding> research() {
        return List.of(
                new VertexBufferCodec("SEGMENT_PAIR_FASTPFOR", new SegmentPair(), new IntCodec.FastPfor()));
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
