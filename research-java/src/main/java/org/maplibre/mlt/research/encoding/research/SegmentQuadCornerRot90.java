package org.maplibre.mlt.research.encoding.research;

import java.util.List;

import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.ClassifiedPredictor;
import org.maplibre.mlt.research.encoding.PartBoundaries;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;

/**
 * Extends {@link SegmentQuadCorner} by giving <b>even-length</b> rings longer than four vertices —
 * the population its parallelogram predictor cannot reach — {@link Rot90Fixed}'s fixed-ccw 90°-turn
 * predictor, while exactly-four-vertex rings keep the parallelogram corner ({@code p0+p2-p1}) that
 * wins {@code building} outright.
 *
 * <p>Both component predictors are already measured standalone: {@code SEGMENT_QUAD_CORNER_FASTPFOR}
 * is -12,2% on {@code building} but only reaches {@code n=4} rings (76,4% of the population);
 * {@code ROT90_FIXED_FASTPFOR} is -5,0% but touches every ring, so most of its win is diluted by
 * the {@code n=4} rings it shares with quad-corner and does not improve on. This composition targets
 * disjoint populations exactly the way {@code SEGMENT_QUAD_CORNER} itself composes {@link SegmentPair}
 * and the corner predictor — see {@code research-java/README.md}.
 *
 * <p>Unlike {@link org.maplibre.mlt.research.encoding.research.failed.SegmentQuadCornerTurn}, the
 * turn direction here is <b>fixed</b> by winding, not signalled, so this needs no side channel.
 * Every vertex falls in exactly one class, each residual packed into its own contiguous FastPFOR
 * region:
 *
 * <ol>
 *   <li><b>start</b> — the first vertex of each ring run, delta against the previous vertex.
 *   <li><b>interior</b> — a within-ring vertex with no better predictor: the first two vertices of
 *       any ring, and every vertex of an odd-length or {@code &lt;=4}-vertex ring.
 *   <li><b>corner</b> — the fourth vertex of an exactly-four-vertex run, predicted as the
 *       parallelogram corner {@code p0+p2-p1}.
 *   <li><b>turn</b> — an interior vertex ({@code i &gt;= start+2}) of an even-length ring longer than
 *       four vertices, predicted as {@code p[i-1] + rot90ccw(prevEdge)}.
 * </ol>
 *
 * <p>The class of every vertex is derivable from the topology streams on both sides; {@code
 * prevX/prevY} advance continuously through the actual vertices regardless of class, no reset seeds.
 */
public final class SegmentQuadCornerRot90 implements ClassifiedPredictor {

  // INTERIOR is 0 so the default-zeroed class array already means "interior".
  private static final int INTERIOR = 0;
  private static final int START = 1;
  private static final int CORNER = 2;
  private static final int TURN = 3;

  @Override
  public String name() {
    return "SEGMENT_QUAD_CORNER_ROT90";
  }

  @Override
  public int classCount() {
    return 4;
  }

  @Override
  public int[] regionOrder() {
    return new int[] {START, INTERIOR, CORNER, TURN};
  }

  @Override
  public int[] classify(BufferContext ctx, int vertexCount) {
    return classify(PartBoundaries.of(ctx, vertexCount), vertexCount);
  }

  @Override
  public boolean hasPrediction(int vertexClass) {
    return vertexClass == CORNER || vertexClass == TURN;
  }

  @Override
  public int predictX(int vertexClass, int i, List<Vertex> vertices) {
    if (vertexClass == CORNER) {
      return vertices.get(i - 3).x() + vertices.get(i - 1).x() - vertices.get(i - 2).x();
    }
    final var p1 = vertices.get(i - 1);
    final var edgeY = p1.y() - vertices.get(i - 2).y();
    // rot90 ccw of (edgeX,edgeY) is (-edgeY, edgeX).
    return p1.x() - edgeY;
  }

  @Override
  public int predictY(int vertexClass, int i, List<Vertex> vertices) {
    if (vertexClass == CORNER) {
      return vertices.get(i - 3).y() + vertices.get(i - 1).y() - vertices.get(i - 2).y();
    }
    final var p1 = vertices.get(i - 1);
    final var edgeX = p1.x() - vertices.get(i - 2).x();
    return p1.y() + edgeX;
  }

  /**
   * Per-vertex class: START (run start), CORNER (4th vertex of a 4-run), TURN (interior vertex,
   * {@code i >= start+2}, of an even-length run longer than four vertices), INTERIOR otherwise.
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
      if (length == 4) {
        classes[start + 3] = CORNER;
      } else if (length > 4 && length % 2 == 0) {
        for (var i = start + 2; i < end; i++) {
          classes[i] = TURN;
        }
      }
    }
    return classes;
  }
}
