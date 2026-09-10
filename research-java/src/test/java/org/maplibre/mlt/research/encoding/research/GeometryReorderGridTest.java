package org.maplibre.mlt.research.encoding.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.GeometryEquivalence;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;

/**
 * Pins the spatial index against the definition it replaces.
 *
 * <p>{@link GeometryReorder} finds each next feature through a uniform grid rather than by scanning
 * every candidate, because the scan is quadratic and this corpus has buffers of ~65.000 vertices.
 * That is an optimisation, so the only thing that makes it safe is that it returns <em>the same
 * chain</em> as the scan — including on ties, where a different but equally-near pick would silently
 * change the measured byte totals and make the experiment unreproducible.
 *
 * <p>The reference implementation here is the straightforward O(n²) greedy chain. It is duplicated
 * deliberately: a test that reused the production nearest-neighbour lookup could not detect a bug
 * in it.
 */
class GeometryReorderGridTest {

  @Test
  void gridChainMatchesTheBruteForceChainOnRandomBuffers() {
    final var random = new Random(20260824L);
    for (var trial = 0; trial < 60; trial++) {
      // Coordinate spreads that stress the grid differently: dense clusters, wide sparse
      // scatter, and collinear/duplicate points where ties are common.
      final var spread = switch (trial % 3) {
        case 0 -> 40;
        case 1 -> 4096;
        default -> 6;
      };
      final var parts = 2 + random.nextInt(40);
      final var ctx = randomLineColumn(random, parts, spread);

      for (final var reversal : List.of(false, true)) {
        final var actual = (reversal ? GeometryReorder.FEATURE_REV : GeometryReorder.FEATURE).apply(ctx).vertices();
        final var expected = bruteForceChain(ctx, reversal);
        assertEquals(
            expected,
            actual,
            "grid chain diverged from the brute-force chain (trial "
                + trial
                + ", parts "
                + parts
                + ", spread "
                + spread
                + ", reversal "
                + reversal
                + ")");
      }
    }
  }

  @Test
  void preservesGeometryOnRandomBuffers() {
    final var random = new Random(7L);
    for (var trial = 0; trial < 40; trial++) {
      final var ctx = randomLineColumn(random, 2 + random.nextInt(30), 512);
      assertTrue(GeometryEquivalence.holds(ctx, GeometryReorder.FEATURE_REV.apply(ctx)));
      assertTrue(GeometryEquivalence.holds(ctx, GeometryReorder.FEATURE.apply(ctx)));
    }
  }

  /** A single-part-per-feature LineString column, so features and parts coincide. */
  private static BufferContext randomLineColumn(Random random, int parts, int spread) {
    final var vertices = new ArrayList<Vertex>();
    final var numParts = new ArrayList<Integer>();
    final var geometryTypes = new ArrayList<Integer>();
    for (var p = 0; p < parts; p++) {
      final var length = 2 + random.nextInt(3);
      for (var v = 0; v < length; v++) {
        vertices.add(new Vertex(random.nextInt(spread), random.nextInt(spread)));
      }
      numParts.add(length);
      geometryTypes.add(1);
    }
    return new BufferContext(vertices, geometryTypes, List.of(), numParts, List.of());
  }

  /**
   * The definition {@link GeometryReorder}'s grid must reproduce: scan every unused feature, take
   * the nearest entry endpoint in Manhattan distance, ties going to the lowest feature index (which
   * is what a first-wins linear scan yields).
   */
  private static List<Vertex> bruteForceChain(BufferContext ctx, boolean allowReversal) {
    final var vertices = ctx.vertices();
    final var starts = new int[ctx.numParts().size()];
    final var lengths = new int[ctx.numParts().size()];
    var offset = 0;
    for (var p = 0; p < ctx.numParts().size(); p++) {
      starts[p] = offset;
      lengths[p] = ctx.numParts().get(p);
      offset += lengths[p];
    }

    final var used = new boolean[starts.length];
    final var out = new ArrayList<Vertex>(vertices.size());
    var cursorX = 0;
    var cursorY = 0;
    for (var placed = 0; placed < starts.length; placed++) {
      var best = -1;
      var bestReversed = false;
      var bestDistance = Long.MAX_VALUE;
      for (var p = 0; p < starts.length; p++) {
        if (used[p]) {
          continue;
        }
        final var head = vertices.get(starts[p]);
        final var forward = distance(head, cursorX, cursorY);
        if (forward < bestDistance) {
          best = p;
          bestReversed = false;
          bestDistance = forward;
        }
        if (allowReversal) {
          final var tail = vertices.get(starts[p] + lengths[p] - 1);
          final var backward = distance(tail, cursorX, cursorY);
          if (backward < bestDistance) {
            best = p;
            bestReversed = true;
            bestDistance = backward;
          }
        }
      }

      used[best] = true;
      final var from = starts[best];
      final var length = lengths[best];
      if (bestReversed) {
        for (var v = length - 1; v >= 0; v--) {
          out.add(vertices.get(from + v));
        }
      } else {
        for (var v = 0; v < length; v++) {
          out.add(vertices.get(from + v));
        }
      }
      final var exit = out.get(out.size() - 1);
      cursorX = exit.x();
      cursorY = exit.y();
    }
    return out;
  }

  private static long distance(Vertex vertex, int x, int y) {
    return Math.abs((long) vertex.x() - x) + Math.abs((long) vertex.y() - y);
  }
}
