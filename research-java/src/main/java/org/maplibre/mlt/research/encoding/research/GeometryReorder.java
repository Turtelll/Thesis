package org.maplibre.mlt.research.encoding.research;

import java.util.ArrayList;
import java.util.List;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.PartBoundaries;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;

/**
 * Reorders whole features within a buffer so each one starts near where the previous one ended,
 * shrinking the part-start residuals that a continuous delta cursor pays at every boundary.
 *
 * <p>This is <b>not</b> an {@link org.maplibre.mlt.research.encoding.Encoding}. It rewrites the
 * {@link BufferContext} before any encoding runs, so every existing encoding stays byte-for-byte
 * unchanged and strictly lossless <em>against the reordered context</em>:
 *
 * <pre>ctx --[GeometryReorder]--&gt; ctx' --[any Encoding]--&gt; bytes</pre>
 *
 * <p>The reason this can pay at all, where the junction/reordering experiments in {@code
 * research-java/README.md} could not, is that <b>no permutation is transmitted</b>. Those
 * experiments charged ceil(log2 P)+1 bits per part to describe the new order and were net-positive
 * because of it. The order need not be recovered: no coordinates change, only a geometry's position
 * <em>within</em> the buffer, and the MLT spec makes that explicit ({@code docs/specification.md}):
 *
 * <blockquote>
 * Feature order is not necessarily a stable round-trip property. [...] MLT encoders may preserve
 * order, but they may also sort features by id or spatial locality to improve compression when that
 * optimization is enabled.
 * </blockquote>
 *
 * <p>So the decoder does not reconstruct the original layout and is not asked to — it emits the
 * order it reads. What must be preserved is the <em>set</em> of geometries, which {@link
 * org.maplibre.mlt.research.encoding.GeometryEquivalence} checks.
 *
 * <p>Two constraints the greedy chain respects:
 *
 * <ul>
 *   <li>a multi-part feature's parts stay contiguous and in order, or the feature's geometry
 *       changes;
 *   <li>a buffer whose topology does not yield usable part runs is returned unchanged, matching how
 *       {@link PartBoundaries} degrades rather than guessing at boundaries.
 * </ul>
 */
public final class GeometryReorder {

  /**
   * The identity: the buffer is passed through untouched.
   *
   * <p>This is what makes reordering an <em>optional</em> component of {@link
   * org.maplibre.mlt.research.encoding.VertexBufferCodec} rather than a separate wrapper class —
   * a codec always has a reorder, and one that does not reorder has this one. It also contributes
   * no name suffix, so an unreordered encoding's name is unchanged.
   */
  public static final GeometryReorder NONE = new GeometryReorder(Mode.NONE);

  /** Greedy nearest-endpoint feature chaining, emitting every feature in its original direction. */
  public static final GeometryReorder FEATURE = new GeometryReorder(Mode.FEATURE);

  /**
   * Feature chaining that may also emit a feature backwards. Free for rendering, but it destroys
   * direction semantics (one-way streets, direction-dependent labelling), so it is a separate
   * variant rather than always on.
   */
  public static final GeometryReorder FEATURE_REV = new GeometryReorder(Mode.FEATURE_REV);

  private enum Mode {
    NONE("", false),
    FEATURE("_REORDER", false),
    FEATURE_REV("_REORDER_REV", true);

    private final String suffix;
    private final boolean allowReversal;

    Mode(String suffix, boolean allowReversal) {
      this.suffix = suffix;
      this.allowReversal = allowReversal;
    }
  }

  /** {@code GeometryType} ordinals as persisted in the sidecar. */
  private static final int POINT = 0;

  private static final int POLYGON = 2;
  private static final int MULTIPOINT = 3;
  private static final int MULTILINESTRING = 4;
  private static final int MULTIPOLYGON = 5;

  private final Mode mode;
  private final boolean allowReversal;

  private GeometryReorder(Mode mode) {
    this.mode = mode;
    this.allowReversal = mode.allowReversal;
  }

  /** Suffix this reorder contributes to an encoding's name; empty for {@link #NONE}. */
  public String nameSuffix() {
    return mode.suffix;
  }

  /** Whether this reorder may emit geometries in an order other than the one it received. */
  public boolean reorders() {
    return mode != Mode.NONE;
  }

  /**
   * The topology streams of the reordered buffer, for a decoder that has not seen the vertices.
   *
   * <p>In a tile these streams are simply <em>read</em>: the encoder writes the reordered
   * {@code numParts}/{@code numRings}/{@code geometryTypes} into the tile and the decoder consumes
   * them, exactly as it consumes the unreordered ones today. Nothing about the ordering is derived
   * or signalled beyond the streams MLT already ships.
   *
   * <p>The benchmark, however, hands {@code decode} the <em>original</em> context, so this
   * reproduces what the decoder would have read. It is the one place the reordered streams are
   * recomputed rather than read, and it deliberately returns a context with <b>no vertices</b>: the
   * inner encoding derives its part boundaries from the streams alone, and passing the reordered
   * vertices here would hand a decoder the buffer it is supposed to be reconstructing.
   */
  public BufferContext topology(BufferContext ctx, int vertexCount) {
    final var reordered = apply(ctx);
    // Under NONE that is ctx itself, so the vertices are stripped here rather than passed on.
    return new BufferContext(
        List.of(),
        reordered.geometryTypes(),
        reordered.numGeometries(),
        reordered.numParts(),
        reordered.numRings());
  }

  /**
   * The reordered context, or {@code ctx} itself when the buffer cannot be reordered safely (no
   * derivable part runs, or topology streams that do not describe this buffer).
   */
  public BufferContext apply(BufferContext ctx) {
    if (mode == Mode.NONE) {
      return ctx;
    }
    final var vertices = ctx.vertices();
    final var starts = PartBoundaries.of(ctx, vertices.size());
    // A single whole-buffer run is PartBoundaries' documented "I cannot locate the boundaries"
    // answer, not a one-part buffer worth reordering.
    if (starts.length < 2) {
      return ctx;
    }

    final var groups = featureGroups(ctx, starts.length);
    if (groups == null) {
      return ctx;
    }

    final var lengths = runLengths(starts, vertices.size());
    final var order = greedyChain(vertices, starts, lengths, groups);
    return rebuild(ctx, vertices, starts, lengths, groups, order);
  }

  // --- feature grouping ---------------------------------------------------

  /**
   * Part indices grouped by owning feature, or {@code null} if the topology streams do not line up
   * with the derived runs — in which case the grouping would be a guess and nothing is reordered.
   *
   * <p>Only the Multi* types consume a {@code numGeometries} entry; a single-part type owns exactly
   * one run. In a polygon column the runs are rings, and {@code numParts} counts the rings of each
   * polygon, so a feature there owns as many runs as its polygons have rings.
   */
  private static List<int[]> featureGroups(BufferContext ctx, int partCount) {
    final var polygonColumn = containsPolygon(ctx.geometryTypes());
    final var groups = new ArrayList<int[]>();
    var part = 0;
    var geometryCursor = 0;
    var partCursor = 0;
    for (final var type : ctx.geometryTypes()) {
      // Points occupy a vertex but contribute no run, so their extent cannot be located and the
      // whole column is left alone.
      if (type == POINT || type == MULTIPOINT) {
        return null;
      }

      var runs = 0;
      if (polygonColumn && (type == POLYGON || type == MULTIPOLYGON)) {
        final int polygons;
        if (type == MULTIPOLYGON) {
          polygons = multiCount(ctx, geometryCursor++);
          if (polygons < 0) {
            return null;
          }
        } else {
          polygons = 1;
        }
        for (var p = 0; p < polygons; p++) {
          if (partCursor >= ctx.numParts().size()) {
            return null;
          }
          runs += ctx.numParts().get(partCursor++);
        }
      } else {
        // A LineString contributes one run; a MultiLineString one per child. In a polygon column
        // those runs live in numRings, but the count is the same either way.
        runs = type == MULTILINESTRING ? multiCount(ctx, geometryCursor++) : 1;
        if (runs < 0) {
          return null;
        }
      }

      if (runs <= 0 || part + runs > partCount) {
        return null;
      }
      final var group = new int[runs];
      for (var i = 0; i < runs; i++) {
        group[i] = part + i;
      }
      groups.add(group);
      part += runs;
    }
    return part == partCount ? groups : null;
  }

  private static int multiCount(BufferContext ctx, int cursor) {
    return cursor < ctx.numGeometries().size() ? ctx.numGeometries().get(cursor) : -1;
  }

  private static boolean containsPolygon(List<Integer> geometryTypes) {
    for (final var type : geometryTypes) {
      if (type == POLYGON || type == MULTIPOLYGON) {
        return true;
      }
    }
    return false;
  }

  // --- the chain ----------------------------------------------------------

  /** One feature's placement in the emitted order. */
  private record Placed(int group, boolean reversed) {}

  /**
   * Greedily takes the unused feature whose entry endpoint is nearest the cursor, then advances the
   * cursor to that feature's exit endpoint.
   *
   * <p>Manhattan distance rather than Euclidean because the cost being minimised is a componentwise
   * zigzag residual: {@code |dx| + |dy|} is what the two delta cursors actually pay.
   *
   * <p>The nearest unused endpoint is found through a {@link EndpointGrid uniform grid} rather than
   * by scanning every candidate. A linear scan makes the chain quadratic in the feature count,
   * which is not merely slow on this corpus but unusable: buffers here reach ~65.000 vertices, and
   * the largest are exactly the ones a reordering experiment most wants to measure. The grid
   * returns the same answer — ties included — because it widens its search ring until no unscanned
   * cell could hold anything closer.
   */
  private List<Placed> greedyChain(
      List<Vertex> vertices, int[] starts, int[] lengths, List<int[]> groups) {
    // Precomputed once: looking a part's endpoints up inside the selection loop would make this
    // O(features * parts) instead of O(features).
    final var firstVertex = new Vertex[starts.length];
    final var lastVertex = new Vertex[starts.length];
    for (var p = 0; p < starts.length; p++) {
      firstVertex[p] = vertices.get(starts[p]);
      lastVertex[p] = vertices.get(starts[p] + lengths[p] - 1);
    }

    // Each feature contributes its entry endpoint, and its exit endpoint too when reversal may
    // pick it up; both map back to the same feature index.
    final var grid = new EndpointGrid(groups.size() * (allowReversal ? 2 : 1));
    for (var g = 0; g < groups.size(); g++) {
      final var group = groups.get(g);
      grid.add(g, false, firstVertex[group[0]]);
      if (allowReversal) {
        grid.add(g, true, lastVertex[group[group.length - 1]]);
      }
    }
    grid.build();

    final var used = new boolean[groups.size()];
    final var order = new ArrayList<Placed>(groups.size());
    var cursorX = 0;
    var cursorY = 0;
    for (var placed = 0; placed < groups.size(); placed++) {
      final var hit = grid.nearest(cursorX, cursorY, used);
      final var best = hit.feature();
      used[best] = true;
      order.add(new Placed(best, hit.reversed()));
      final var group = groups.get(best);
      final var exit = hit.reversed() ? firstVertex[group[0]] : lastVertex[group[group.length - 1]];
      cursorX = exit.x();
      cursorY = exit.y();
    }
    return order;
  }

  private static long distance(Vertex vertex, int x, int y) {
    return Math.abs((long) vertex.x() - x) + Math.abs((long) vertex.y() - y);
  }

  // --- rebuilding the context ---------------------------------------------

  /**
   * Emits vertices and topology streams in the new order. The streams are permuted with the
   * features, so {@link PartBoundaries} derives the same run lengths from the result — deriving
   * them wrongly is silent, which is why they are rebuilt here rather than carried over.
   */
  private BufferContext rebuild(
      BufferContext ctx,
      List<Vertex> vertices,
      int[] starts,
      int[] lengths,
      List<int[]> groups,
      List<Placed> order) {

    final var polygonColumn = containsPolygon(ctx.geometryTypes());
    final var reordered = new ArrayList<Vertex>(vertices.size());
    final var geometryTypes = new ArrayList<Integer>(ctx.geometryTypes().size());
    final var numGeometries = new ArrayList<Integer>(ctx.numGeometries().size());
    final var numParts = new ArrayList<Integer>(ctx.numParts().size());
    final var numRings = new ArrayList<Integer>(ctx.numRings().size());

    // Per-feature cursors into the original numGeometries/numParts streams, so each feature's
    // entries can be copied in the new order.
    final var geometryAt = new int[groups.size()];
    final var partAt = new int[groups.size()];
    final var partRun = new int[groups.size()];
    indexStreams(ctx, groups, polygonColumn, geometryAt, partAt, partRun);

    for (final var placed : order) {
      final var feature = placed.group();
      final var group = groups.get(feature);
      geometryTypes.add(ctx.geometryTypes().get(feature));

      if (geometryAt[feature] >= 0) {
        numGeometries.add(ctx.numGeometries().get(geometryAt[feature]));
      }
      for (var i = 0; i < partRun[feature]; i++) {
        numParts.add(ctx.numParts().get(partAt[feature] + i));
      }

      // Parts of a multi-part feature stay contiguous and in order; reversing the feature walks
      // them backwards so the geometry it describes is unchanged.
      for (var i = 0; i < group.length; i++) {
        final var part = placed.reversed() ? group[group.length - 1 - i] : group[i];
        final var from = starts[part];
        final var length = lengths[part];
        if (placed.reversed()) {
          for (var v = length - 1; v >= 0; v--) {
            reordered.add(vertices.get(from + v));
          }
        } else {
          for (var v = 0; v < length; v++) {
            reordered.add(vertices.get(from + v));
          }
        }
        if (polygonColumn) {
          numRings.add(length);
        } else {
          numParts.add(length);
        }
      }
    }

    return new BufferContext(reordered, geometryTypes, numGeometries, numParts, numRings);
  }

  /**
   * Records, per feature, which {@code numGeometries} entry it owns ({@code -1} for none) and which
   * span of {@code numParts} entries — in a polygon column that stream holds ring counts, which are
   * distinct from the run lengths and must travel with their feature.
   */
  private static void indexStreams(
      BufferContext ctx,
      List<int[]> groups,
      boolean polygonColumn,
      int[] geometryAt,
      int[] partAt,
      int[] partRun) {
    var geometryCursor = 0;
    var partCursor = 0;
    for (var f = 0; f < groups.size(); f++) {
      final var type = ctx.geometryTypes().get(f);
      final var multi = type == MULTILINESTRING || type == MULTIPOLYGON;
      geometryAt[f] = multi ? geometryCursor++ : -1;

      partAt[f] = partCursor;
      if (polygonColumn && (type == POLYGON || type == MULTIPOLYGON)) {
        final var polygons =
            type == MULTIPOLYGON ? ctx.numGeometries().get(geometryAt[f]) : 1;
        partRun[f] = polygons;
        partCursor += polygons;
      } else {
        partRun[f] = 0;
      }
    }
  }

  /** Vertex count of each run, from its start offsets. */
  private static int[] runLengths(int[] starts, int vertexCount) {
    final var lengths = new int[starts.length];
    for (var i = 0; i < starts.length; i++) {
      lengths[i] = (i + 1 < starts.length ? starts[i + 1] : vertexCount) - starts[i];
    }
    return lengths;
  }

  // --- nearest-endpoint index --------------------------------------------

  /**
   * A uniform grid over candidate endpoints, answering "nearest unused endpoint to (x,y)" in
   * Manhattan distance.
   *
   * <p>It exists purely to keep {@link #greedyChain} out of quadratic time; it does not change
   * which feature the chain picks. Cells are sized so the average occupancy is a small constant,
   * and the search expands ring by ring around the cursor cell, stopping only once the best
   * distance found is no greater than the closest possible distance to any unvisited ring — so a
   * nearer endpoint cannot be sitting in a cell that was skipped.
   *
   * <p>Stored as parallel arrays in CSR form rather than per-cell lists: this runs over every
   * buffer in the corpus, and the buffers that matter carry tens of thousands of endpoints.
   */
  private static final class EndpointGrid {

    /** Target average endpoints per cell — small enough that a hit cell is cheap to scan. */
    private static final int TARGET_PER_CELL = 2;

    private final int[] feature;
    private final boolean[] reversed;
    private final int[] pointX;
    private final int[] pointY;
    private int size;

    private int minX = Integer.MAX_VALUE;
    private int minY = Integer.MAX_VALUE;
    private int maxX = Integer.MIN_VALUE;
    private int maxY = Integer.MIN_VALUE;

    private int cols;
    private int rows;
    private int cellSize;
    private int[] cellStart;
    private int[] cellItems;

    EndpointGrid(int capacity) {
      feature = new int[capacity];
      reversed = new boolean[capacity];
      pointX = new int[capacity];
      pointY = new int[capacity];
    }

    void add(int featureIndex, boolean isReversed, Vertex vertex) {
      feature[size] = featureIndex;
      reversed[size] = isReversed;
      pointX[size] = vertex.x();
      pointY[size] = vertex.y();
      minX = Math.min(minX, vertex.x());
      minY = Math.min(minY, vertex.y());
      maxX = Math.max(maxX, vertex.x());
      maxY = Math.max(maxY, vertex.y());
      size++;
    }

    void build() {
      final var spanX = (long) maxX - minX + 1;
      final var spanY = (long) maxY - minY + 1;
      // One cell per TARGET_PER_CELL endpoints, as a square over the bounding box.
      final var target = Math.max(1, size / TARGET_PER_CELL);
      final var side = Math.max(1.0, Math.sqrt((double) spanX * spanY / target));
      cellSize = (int) Math.min(Integer.MAX_VALUE, Math.max(1, (long) Math.ceil(side)));
      cols = (int) Math.max(1, (spanX + cellSize - 1) / cellSize);
      rows = (int) Math.max(1, (spanY + cellSize - 1) / cellSize);

      final var counts = new int[cols * rows + 1];
      for (var i = 0; i < size; i++) {
        counts[cellOf(pointX[i], pointY[i]) + 1]++;
      }
      for (var c = 0; c < cols * rows; c++) {
        counts[c + 1] += counts[c];
      }
      cellStart = counts;
      cellItems = new int[size];
      final var cursor = counts.clone();
      for (var i = 0; i < size; i++) {
        cellItems[cursor[cellOf(pointX[i], pointY[i])]++] = i;
      }
    }

    private int cellOf(int x, int y) {
      final var cx = Math.min(cols - 1, (x - minX) / cellSize);
      final var cy = Math.min(rows - 1, (y - minY) / cellSize);
      return cy * cols + cx;
    }

    /** The chosen endpoint: which feature, and whether it was entered from its far end. */
    record Hit(int feature, boolean reversed) {}

    Hit nearest(int x, int y, boolean[] used) {
      final var originX = Math.min(cols - 1, Math.max(0, (int) (((long) x - minX) / cellSize)));
      final var originY = Math.min(rows - 1, Math.max(0, (int) (((long) y - minY) / cellSize)));

      var bestDistance = Long.MAX_VALUE;
      var bestFeature = -1;
      var bestReversed = false;

      final var maxRing = Math.max(cols, rows);
      for (var ring = 0; ring <= maxRing; ring++) {
        // Everything outside the already-scanned area is at least (ring-1)*cellSize away. The
        // bound is >, not >=: a cell whose closest possible point ties the current best may still
        // hold a tied endpoint with a lower feature index, and the tie-break below must see it.
        if (bestFeature >= 0 && (long) (ring - 1) * cellSize > bestDistance) {
          break;
        }
        for (var cy = originY - ring; cy <= originY + ring; cy++) {
          if (cy < 0 || cy >= rows) {
            continue;
          }
          final var edgeRow = cy == originY - ring || cy == originY + ring;
          for (var cx = originX - ring; cx <= originX + ring; cx++) {
            if (cx < 0 || cx >= cols) {
              continue;
            }
            // Interior cells were covered by an earlier ring.
            if (!edgeRow && cx != originX - ring && cx != originX + ring) {
              continue;
            }
            final var cell = cy * cols + cx;
            for (var s = cellStart[cell]; s < cellStart[cell + 1]; s++) {
              final var i = cellItems[s];
              if (used[feature[i]]) {
                continue;
              }
              final var d =
                  Math.abs((long) pointX[i] - x) + Math.abs((long) pointY[i] - y);
              // Ties break on the lower feature index, and on the forward endpoint within a
              // feature — what a first-wins linear scan yields. Without this the grid's cell
              // order would decide, so the same corpus could chain differently between runs and
              // the measured byte totals would not be reproducible.
              final var better =
                  d < bestDistance
                      || (d == bestDistance
                          && (feature[i] < bestFeature
                              || (feature[i] == bestFeature && bestReversed && !reversed[i])));
              if (better) {
                bestDistance = d;
                bestFeature = feature[i];
                bestReversed = reversed[i];
              }
            }
          }
        }
      }
      return new Hit(bestFeature, bestReversed);
    }
  }
}
