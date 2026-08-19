package org.maplibre.mlt.research.encoding.research;

import java.util.ArrayList;
import java.util.List;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.decoder.DecodingUtils;
import org.maplibre.mlt.research.encoding.PartBoundaries;
import org.maplibre.mlt.research.encoding.VertexTransform;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;

/**
 * Componentwise delta + zigzag as in the baseline, but emitted as two grouped populations: every
 * part-start residual first, then every within-part delta.
 *
 * <p>The motivation is a property of the {@code transportation} layer specifically. 70% of its
 * parts are exactly two vertices, so part boundaries are not rare events between long runs — they
 * are 30% of all deltas, and they carry 35% of the buffer's zigzag bits at a mean width of 13,1
 * bits against 10,3 for interior deltas. Interleaved, as the baseline emits them, the wide
 * population sets the block width for the narrow one: FastPFOR packs a block to the width of its
 * widest value, and a varint crossing 127 costs another whole byte. Separating them lets each be
 * packed at its own width.
 *
 * <p>The values are the same values in a different order, so this neither adds nor removes
 * information — it only changes what shares a block. Nothing is signalled: the split point is
 * derivable from the topology streams on both sides.
 */
public final class SegmentPair implements VertexTransform {

  @Override
  public int[] apply(BufferContext ctx) {
    final var vertices = ctx.vertices();
    final var starts = PartBoundaries.of(ctx, vertices.size());
    final var isStart = startFlags(starts, vertices.size());

    final var values = new int[vertices.size() * 2];
    // Two cursors over one array: part-starts fill from the front, interior deltas from the
    // boundary between the two populations.
    var head = 0;
    var tail = countStarts(isStart) * 2;
    var prevX = 0;
    var prevY = 0;
    for (var i = 0; i < vertices.size(); i++) {
      final var vertex = vertices.get(i);
      final var dx = EncodingUtils.encodeZigZag(vertex.x() - prevX);
      final var dy = EncodingUtils.encodeZigZag(vertex.y() - prevY);
      if (isStart[i]) {
        values[head++] = dx;
        values[head++] = dy;
      } else {
        values[tail++] = dx;
        values[tail++] = dy;
      }
      prevX = vertex.x();
      prevY = vertex.y();
    }
    return values;
  }

  @Override
  public List<Vertex> invert(int[] values, BufferContext ctx) {
    final var vertexCount = values.length / 2;
    final var starts = PartBoundaries.of(ctx, vertexCount);
    final var isStart = startFlags(starts, vertexCount);

    final var vertices = new ArrayList<Vertex>(vertexCount);
    var head = 0;
    var tail = countStarts(isStart) * 2;
    var prevX = 0;
    var prevY = 0;
    for (var i = 0; i < vertexCount; i++) {
      final int dx;
      final int dy;
      if (isStart[i]) {
        dx = values[head++];
        dy = values[head++];
      } else {
        dx = values[tail++];
        dy = values[tail++];
      }
      final var x = prevX + DecodingUtils.decodeZigZag(dx);
      final var y = prevY + DecodingUtils.decodeZigZag(dy);
      vertices.add(new Vertex(x, y));
      prevX = x;
      prevY = y;
    }
    return vertices;
  }

  /** Marks the first vertex of every run. Vertex 0 is always a start. */
  private static boolean[] startFlags(int[] starts, int vertexCount) {
    final var flags = new boolean[vertexCount];
    for (var start : starts) {
      if (start < vertexCount) {
        flags[start] = true;
      }
    }
    return flags;
  }

  private static int countStarts(boolean[] isStart) {
    var count = 0;
    for (var flag : isStart) {
      if (flag) {
        count++;
      }
    }
    return count;
  }
}
