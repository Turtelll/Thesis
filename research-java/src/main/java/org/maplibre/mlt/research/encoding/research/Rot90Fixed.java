package org.maplibre.mlt.research.encoding.research;

import java.util.ArrayList;
import java.util.List;

import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.decoder.DecodingUtils;
import org.maplibre.mlt.research.encoding.PartBoundaries;
import org.maplibre.mlt.research.encoding.VertexTransform;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;

/**
 * Predicts each interior vertex of an <b>even-length</b>, {@code &gt;4}-vertex polygon ring as a
 * fixed 90°-turn off the previous edge — {@code pred = p[i-1] + rot90ccw(prevEdge)} — with the turn
 * direction fixed by winding (CCW, since {@code building} is 99,3% CCW-wound per {@code
 * BuildingShapeCli}) rather than signalled per vertex.
 *
 * <p>This exists because the pooled {@code >4}-vertex measurement in {@code RingPriorCli} made fixed
 * rot90 look worthless (8,81 -&gt; 8,71 bits on {@code building}, a 0,10-bit win): a closed rectilinear
 * ring must have an even vertex count, so pooling odd-length rings in with even ones dilutes the
 * effect. Resolved to ring size, fixed {@code rot90-ccw} is -0,91 bit on 6-vertex rings (9,13 -&gt;
 * 8,22) and -0,32 bit on 8-vertex rings (8,89 -&gt; 8,57) — see {@code research-java/README.md}. This
 * measures whether that median win survives FastPFOR's block-maximum pricing, the same test that
 * sank {@code CONTEXT_DELTA} and the delta-of-delta family.
 *
 * <p>Unlike {@link RightAngleTurn}-style predictors, the direction here is fixed rather than chosen
 * per vertex, so nothing is signalled and this is a plain {@link VertexTransform}: the class of every
 * vertex (start / interior / turn) is derivable from the topology streams on both sides, and residuals
 * are only reordered into three contiguous regions — starts, interior, turns — so the near-zero turn
 * population is packed at its own FastPFOR width instead of being widened by the populations around it
 * (the same block-width lever {@code SEGMENT_QUAD_CORNER}'s corner residuals use).
 *
 * <p>A vertex is <b>turn-predicted</b> iff its ring has more than four vertices, an even length, and
 * the vertex has two in-ring predecessors ({@code i - start >= 2}), so the edge {@code
 * p[i-1]-p[i-2]} is defined. Everything else — every vertex of an odd-length or {@code &lt;=4}-vertex
 * ring, and the first two vertices of an even long ring — stays first-order delta. {@code
 * prevX/prevY} advance continuously through the actual vertices regardless of class; no reset seeds.
 */
public final class Rot90Fixed implements VertexTransform {

  // INTERIOR is 0 so the default-zeroed class array already means "interior".
  private static final int INTERIOR = 0;
  private static final int START = 1;
  private static final int TURN = 2;

  @Override
  public String name() {
    return "ROT90_FIXED";
  }

  @Override
  public int[] apply(BufferContext ctx) {
    final var vertices = ctx.vertices();
    final var classes = classify(PartBoundaries.of(ctx, vertices.size()), vertices.size());

    final var values = new int[vertices.size() * 2];
    final var cursors = cursors(classes);
    var prevX = 0;
    var prevY = 0;
    for (var i = 0; i < vertices.size(); i++) {
      final var vertex = vertices.get(i);
      final int predX;
      final int predY;
      if (classes[i] == TURN) {
        final var p1 = vertices.get(i - 1);
        final var edgeX = p1.x() - vertices.get(i - 2).x();
        final var edgeY = p1.y() - vertices.get(i - 2).y();
        // rot90 ccw of (edgeX,edgeY) is (-edgeY, edgeX).
        predX = p1.x() - edgeY;
        predY = p1.y() + edgeX;
      } else {
        predX = prevX;
        predY = prevY;
      }
      final var slot = cursors[classes[i]];
      values[slot] = EncodingUtils.encodeZigZag(vertex.x() - predX);
      values[slot + 1] = EncodingUtils.encodeZigZag(vertex.y() - predY);
      cursors[classes[i]] += 2;
      prevX = vertex.x();
      prevY = vertex.y();
    }
    return values;
  }

  @Override
  public List<Vertex> invert(int[] values, BufferContext ctx) {
    final var vertexCount = values.length / 2;
    final var classes = classify(PartBoundaries.of(ctx, vertexCount), vertexCount);

    final var vertices = new ArrayList<Vertex>(vertexCount);
    final var cursors = cursors(classes);
    var prevX = 0;
    var prevY = 0;
    for (var i = 0; i < vertexCount; i++) {
      final var slot = cursors[classes[i]];
      final int predX;
      final int predY;
      if (classes[i] == TURN) {
        final var p1 = vertices.get(i - 1);
        final var edgeX = p1.x() - vertices.get(i - 2).x();
        final var edgeY = p1.y() - vertices.get(i - 2).y();
        predX = p1.x() - edgeY;
        predY = p1.y() + edgeX;
      } else {
        predX = prevX;
        predY = prevY;
      }
      final var x = predX + DecodingUtils.decodeZigZag(values[slot]);
      final var y = predY + DecodingUtils.decodeZigZag(values[slot + 1]);
      cursors[classes[i]] += 2;
      vertices.add(new Vertex(x, y));
      prevX = x;
      prevY = y;
    }
    return vertices;
  }

  /**
   * Per-vertex class: START (run start), TURN (interior vertex, {@code i >= start+2}, of an
   * even-length run longer than four vertices), INTERIOR otherwise.
   */
  static int[] classify(int[] starts, int vertexCount) {
    final var classes = new int[vertexCount];
    for (var s = 0; s < starts.length; s++) {
      final var start = starts[s];
      final var end = s + 1 < starts.length ? starts[s + 1] : vertexCount;
      if (start < vertexCount) {
        classes[start] = START;
      }
      final var length = end - start;
      if (length > 4 && length % 2 == 0) {
        for (var i = start + 2; i < end; i++) {
          classes[i] = TURN;
        }
      }
    }
    return classes;
  }

  /** Start offset (in int slots) of each class's region: starts, interior, turns. */
  private static int[] cursors(int[] classes) {
    var starts = 0;
    var turns = 0;
    for (var c : classes) {
      if (c == START) {
        starts++;
      } else if (c == TURN) {
        turns++;
      }
    }
    final var interior = classes.length - starts - turns;
    final var cursors = new int[3];
    cursors[START] = 0;
    cursors[INTERIOR] = starts * 2;
    cursors[TURN] = (starts + interior) * 2;
    return cursors;
  }
}
