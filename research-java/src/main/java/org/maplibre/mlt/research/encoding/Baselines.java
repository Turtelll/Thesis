package org.maplibre.mlt.research.encoding;

import java.util.ArrayList;
import java.util.List;
import org.maplibre.mlt.research.encoding.research.GeometryReorder;
import org.maplibre.mlt.research.encoding.research.SegmentPair;
import org.maplibre.mlt.research.encoding.research.SegmentQuadCorner;

/** The encodings the harness runs: the fixed baselines, plus whatever is under test. */
public final class Baselines {

  private Baselines() {}

  /**
   * Baselines 1 and 2 from the root {@code README.md}: componentwise delta + zigzag over a
   * bit-aligned (FastPFOR) and a byte-aligned (Varint) physical codec. Every new encoding is
   * measured against these.
   *
   * <p>Never reordered: the baseline is the yardstick every percentage is computed against, so
   * applying a research lever to it would hide the very gain being measured.
   */
  public static List<Encoding> baseline() {
    return List.of(
        new VertexBufferCodec(new VertexTransform.ZigZagDelta(), IntCodec.FASTPFOR),
        new VertexBufferCodec(new VertexTransform.ZigZagDelta(), IntCodec.VARINT));
  }

  /**
   * Encodings under test — add one line per new idea. Each is a {@code (transform, codec,
   * reorder)} triple and names itself from those parts.
   *
   * <p>The encoder picks FastPFOR or Varint per stream, so a winner that only wins under one of
   * them is not usable — the candidates are therefore measured on both codecs.
   */
  public static List<Encoding> research() {
    return List.of(
        new VertexBufferCodec(new SegmentPair(), IntCodec.FASTPFOR),
        new VertexBufferCodec(new SegmentQuadCorner(), IntCodec.FASTPFOR),
        new VertexBufferCodec(new SegmentQuadCorner(), IntCodec.VARINT),
        new VertexBufferCodec(new SegmentPair(), IntCodec.FASTPFOR, GeometryReorder.FEATURE),
        new VertexBufferCodec(new SegmentPair(), IntCodec.FASTPFOR, GeometryReorder.FEATURE_REV),
        new VertexBufferCodec(new SegmentQuadCorner(), IntCodec.FASTPFOR, GeometryReorder.FEATURE),
        new VertexBufferCodec(
            new SegmentQuadCorner(), IntCodec.FASTPFOR, GeometryReorder.FEATURE_REV),
        new VertexBufferCodec(new SegmentPair(), IntCodec.VARINT, GeometryReorder.FEATURE_REV),
        new VertexBufferCodec(
            new SegmentQuadCorner(), IntCodec.VARINT, GeometryReorder.FEATURE_REV));
  }

  /** Everything the benchmark measures: {@link #baseline()} followed by {@link #research()}. */
  public static List<Encoding> all() {
    var all = new ArrayList<Encoding>(baseline());
    all.addAll(research());
    return all;
  }
}
