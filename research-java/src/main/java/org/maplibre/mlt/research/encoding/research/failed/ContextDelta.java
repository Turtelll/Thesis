package org.maplibre.mlt.research.encoding.research.failed;

import java.util.ArrayList;
import java.util.List;

import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.decoder.DecodingUtils;
import org.maplibre.mlt.research.encoding.Encoding;
import org.maplibre.mlt.research.encoding.IntCodec;
import org.maplibre.mlt.research.encoding.PartBoundaries;
import org.maplibre.mlt.research.encoding.StreamMetadata;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.EncodedBuffer;

/**
 * Builds a per-vertex context from the recent delta history of a LineString and uses it twice: to
 * choose <em>how much</em> of that history to predict from, and to choose <em>which</em> FastPFOR
 * region the residual lands in.
 *
 * <p>The starting point is a measured result in {@code ideen.md}. On NY z14 edge continuation
 * ({@code pred = p[i-1] + (p[i-1]-p[i-2])}) beats the baseline by <b>-10,1% in mean zigzag bits</b>
 * with nothing signalled — but as bytes under FastPFOR it is a <b>regression</b> (+1,9% at z14).
 * The mechanism is recorded there exactly: continuation is smaller at 53,0% of positions and 0,6
 * bit narrower in the mean, yet <b>p99,9 rises from 11 to 12 bits</b>. FastPFOR packs a block to
 * the width of its widest value, so a handful of wider residuals per thousand lifts whole
 * 128-value blocks and eats the mean gain. Continuation wins the median and loses the tail.
 *
 * <p>This encoding is the "derived, not signalled" answer to that, which {@code ideen.md} leaves
 * open as <i>Idee 2</i>: apply continuation only where a decoder-reproducible test says it should
 * win, and keep the populations in separate blocks so the rough one cannot set the smooth one's
 * width. Per vertex:
 *
 * <pre>
 *   run  = number of consecutive "smooth" steps ending at p[i-1]
 *   c    = min(run, K)                       // K+1 contexts
 *   pred = c == 0 ? p[i-1]                   // no usable history: baseline delta
 *                 : p[i-1] + meanEdge(c)     // mean of the last c edges
 *   residual -> region[c]                    // its own contiguous FastPFOR region
 * </pre>
 *
 * <p>A step is <b>smooth</b> when the turn between the last two edges is shallow, by an integer
 * test on their cross and dot products — no trigonometry and no floating point, so both halves
 * compute bit-identical results. {@code turnShift} sets the threshold: a larger shift demands a
 * straighter run. A non-smooth step resets {@code run} to 0, so that vertex falls back to a plain
 * delta <em>and</em> joins the other rough residuals in region 0.
 *
 * <p>At {@code c=1} the prediction is exactly edge continuation; at {@code c>=2} it is the mean of
 * the last {@code c} edges, which damps the per-vertex quantization noise that widened
 * continuation's tail — map coordinates are snapped to the tile grid, so a single edge carries a
 * rounding error that averaging over several edges partly cancels.
 *
 * <p><b>No reset seeds.</b> {@code prevX/prevY} advance through every vertex regardless of context,
 * so no run ever restarts against a raw coordinate. That matters: the {@code DELTA_RESET_*} family
 * measured +12,5% purely from paying full-magnitude seeds at run starts. Only which predictor and
 * which region a residual uses ever changes.
 *
 * <p><b>Why this is an {@link Encoding} and not a {@code VertexTransform}.</b> Every other
 * region-splitting transform here ({@link SegmentPair}, {@link SegmentQuadCorner}) classifies each
 * vertex from the topology streams alone, so a decoder can compute every region's offset before it
 * decodes anything. Here the context depends on the vertices themselves, which the decoder is still
 * reconstructing — it cannot know how many vertices land in each region until it has decoded them
 * all, and it cannot decode them without the offsets. That circularity is broken by writing the
 * per-context <b>counts</b> into the payload: {@code maxContext} varints, whose cost is carried in
 * {@link EncodedBuffer#totalBytes()} like any other signalled data. It is a handful of bytes per
 * buffer against regions of thousands of values, but it is real and it is accounted for.
 *
 * <p>Beyond those counts nothing is signalled: which context each individual vertex takes is
 * derived on both sides from already-reconstructed vertices. Contrast {@code RIGHT_ANGLE_TURN},
 * which signals one bit <em>per vertex</em> and is a regression on every layer.
 *
 * <p><b>Unregistered — measured and rejected.</b> On the munich corpus (8366 buffers) all three
 * sweep points are a regression everywhere except {@code transportation} (T2_M8 -0,7%, T2_M32
 * -1,4%), and are larger than the baseline on {@code building} (+1,0%), {@code landcover} (+2,5%),
 * {@code boundary} (+1,4%) and {@code water} (+2,8%). The per-buffer oracle picks a context-delta
 * variant for 149 of 8366 buffers, worth 2.434 bytes of 2.164.740 total. Composing it with the
 * reorder ({@code REORDER_REV_CONTEXT_DELTA}) is strictly worse than {@code
 * REORDER_REV_SEGMENT_PAIR} on every LineString layer (transportation -17,7% vs -20,0%, boundary
 * -20,8% vs -35,6%), so the run-length context and the reorder attack the same redundancy rather
 * than composing. Kept, with its mechanism test, so the negative result need not be re-derived.
 */
public final class ContextDelta implements Encoding {

  /** Bound on the count prefix, so a corrupt payload cannot make the decoder allocate wildly. */
  private static final int MAX_CONTEXTS = 16;

  private final String name;
  private final IntCodec codec;

  /** Largest context index; {@code c} ranges over {@code 0..maxContext}. */
  private final int maxContext;

  /**
   * Shallowness threshold as a right shift: a turn is smooth when {@code |cross| <= |dot| >>
   * turnShift}. 0 admits turns up to 45°, 1 up to ~26,6°, 2 up to ~14,0°, 3 up to ~7,1°.
   */
  private final int turnShift;

  /**
   * Largest residual component, in absolute value, that still counts as a successful prediction.
   * A wider one collapses the run — see {@code State.penalise}. Expressed as a magnitude rather
   * than a bit width so the threshold is readable in tile units.
   */
  private final int missThreshold;

  /**
   * @param contexts number of contexts, i.e. {@code K+1} — 4 gives {@code c = min(run,3)}, the
   *     shape {@code ideen.md} specifies. At least 2, or there is no context to adapt on.
   * @param turnShift the smoothness threshold; see above.
   * @param missThreshold the mispredict threshold; see above.
   */
  public ContextDelta(String name, IntCodec codec, int contexts, int turnShift, int missThreshold) {
    if (contexts < 2 || contexts > MAX_CONTEXTS) {
      throw new IllegalArgumentException("contexts must be in [2," + MAX_CONTEXTS + "], got " + contexts);
    }
    if (turnShift < 0 || turnShift > 30) {
      throw new IllegalArgumentException("turnShift must be in [0,30], got " + turnShift);
    }
    this.name = name;
    this.codec = codec;
    if (missThreshold < 0) {
      throw new IllegalArgumentException("missThreshold must be >= 0, got " + missThreshold);
    }
    this.maxContext = contexts - 1;
    this.turnShift = turnShift;
    this.missThreshold = missThreshold;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public EncodedBuffer encode(BufferContext ctx) {
    final var vertices = ctx.vertices();
    final var vertexCount = vertices.size();
    final var starts = PartBoundaries.of(ctx, vertexCount);

    /* Two passes, because a region's offset depends on how many vertices land in every earlier
     * region -- not known until the whole buffer has been walked. Pass 1 runs the state machine
     * and records each vertex's context; pass 2 replays the same predictions and files each
     * residual into its region. Both passes drive the identical State, so they cannot disagree. */
    final var contexts = classify(vertices, starts, vertexCount, turnShift, maxContext, missThreshold);
    final var counts = counts(contexts, maxContext);
    final var cursors = cursorsFromCounts(counts);

    final var values = new int[vertexCount * 2];
    final var state = new State(starts, turnShift, maxContext, missThreshold);
    var prevX = 0;
    var prevY = 0;
    for (var i = 0; i < vertexCount; i++) {
      final var vertex = vertices.get(i);
      final var c = state.context();
      final var predX = c == 0 ? prevX : prevX + state.meanEdgeX(c);
      final var predY = c == 0 ? prevY : prevY + state.meanEdgeY(c);

      final var slot = cursors[c];
      values[slot] = EncodingUtils.encodeZigZag(vertex.x() - predX);
      values[slot + 1] = EncodingUtils.encodeZigZag(vertex.y() - predY);
      cursors[c] += 2;

      state.advance(i, vertex.x(), vertex.y());
      state.penalise(c, vertex.x() - predX, vertex.y() - predY);
      prevX = vertex.x();
      prevY = vertex.y();
    }

    final var residual = codec.encode(values);
    final var header = encodeCounts(counts);
    final var payload = concat(header, residual);
    final var metadataBytes =
        StreamMetadata.bytes(values.length, residual.length)
            + StreamMetadata.bytes(counts.length, header.length);
    return new EncodedBuffer(payload, metadataBytes);
  }

  @Override
  public List<Vertex> decode(EncodedBuffer encoded, BufferContext ctx, int vertexCount) {
    final var starts = PartBoundaries.of(ctx, vertexCount);
    final var payload = encoded.payload();

    final var counts = decodeCounts(payload);
    final var cursors = cursorsFromCounts(counts);
    final var values = codec.decode(sliceResidual(payload), vertexCount * 2);

    /* One forward pass, in the order ideen.md fixes as normative: context before decoding, state
     * transition after reconstruction. A decoder that advances the state early throws no error --
     * it emits plausible-looking wrong geometry to the end of the buffer, which is why the
     * mechanism test pins this ordering explicitly. */
    final var vertices = new ArrayList<Vertex>(vertexCount);
    final var state = new State(starts, turnShift, maxContext, missThreshold);
    var prevX = 0;
    var prevY = 0;
    for (var i = 0; i < vertexCount; i++) {
      final var c = state.context();
      final var predX = c == 0 ? prevX : prevX + state.meanEdgeX(c);
      final var predY = c == 0 ? prevY : prevY + state.meanEdgeY(c);

      final var slot = cursors[c];
      final var x = predX + DecodingUtils.decodeZigZag(values[slot]);
      final var y = predY + DecodingUtils.decodeZigZag(values[slot + 1]);
      cursors[c] += 2;

      vertices.add(new Vertex(x, y));
      state.advance(i, x, y);
      state.penalise(c, x - predX, y - predY);
      prevX = x;
      prevY = y;
    }
    return vertices;
  }

  // --- context derivation --------------------------------------------------

  /**
   * Per-vertex context, driven by the same {@link State} both halves use.
   *
   * <p>Package-visible and static so the mechanism test can inspect the classification directly,
   * as {@code RightAngleTurn} and {@code SegmentQuadCornerTurn} expose theirs.
   */
  static int[] classify(
      List<Vertex> vertices,
      int[] starts,
      int vertexCount,
      int turnShift,
      int maxContext,
      int missThreshold) {
    final var contexts = new int[vertexCount];
    final var state = new State(starts, turnShift, maxContext, missThreshold);
    var prevX = 0;
    var prevY = 0;
    for (var i = 0; i < vertexCount; i++) {
      final var c = state.context();
      contexts[i] = c;
      final var vertex = vertices.get(i);
      // The same three steps, in the same order, as both coding loops: predict, then advance the
      // structural state, then feed the residual back. Any divergence here would put the encoder's
      // region counts out of step with the decoder's.
      final var predX = c == 0 ? prevX : prevX + state.meanEdgeX(c);
      final var predY = c == 0 ? prevY : prevY + state.meanEdgeY(c);
      state.advance(i, vertex.x(), vertex.y());
      state.penalise(c, vertex.x() - predX, vertex.y() - predY);
      prevX = vertex.x();
      prevY = vertex.y();
    }
    return contexts;
  }

  /** How many vertices fall in each context. */
  static int[] counts(int[] contexts, int maxContext) {
    final var counts = new int[maxContext + 1];
    for (var c : contexts) {
      counts[c]++;
    }
    return counts;
  }

  /** Start offset (in int slots) of each context's contiguous region, in context order. */
  private static int[] cursorsFromCounts(int[] counts) {
    final var cursors = new int[counts.length];
    var offset = 0;
    for (var c = 0; c < counts.length; c++) {
      cursors[c] = offset;
      offset += counts[c] * 2;
    }
    return cursors;
  }

  // --- payload framing: [1-byte contextCount][varint counts...][residual] ---

  private static byte[] encodeCounts(int[] counts) {
    final var out = new java.io.ByteArrayOutputStream();
    out.write(counts.length);
    for (var count : counts) {
      var value = count;
      while ((value & ~0x7F) != 0) {
        out.write((value & 0x7F) | 0x80);
        value >>>= 7;
      }
      out.write(value);
    }
    return out.toByteArray();
  }

  private int[] decodeCounts(byte[] payload) {
    final var contextCount = payload[0] & 0xFF;
    if (contextCount != maxContext + 1) {
      throw new IllegalStateException(
          "payload declares " + contextCount + " contexts, decoder configured for " + (maxContext + 1));
    }
    final var counts = new int[contextCount];
    var pos = 1;
    for (var c = 0; c < contextCount; c++) {
      var value = 0;
      var shift = 0;
      while (true) {
        final var b = payload[pos++] & 0xFF;
        value |= (b & 0x7F) << shift;
        if ((b & 0x80) == 0) {
          break;
        }
        shift += 7;
      }
      counts[c] = value;
    }
    return counts;
  }

  /** Byte length of the count prefix, so the residual can be sliced off behind it. */
  private int headerLength(byte[] payload) {
    final var contextCount = payload[0] & 0xFF;
    var pos = 1;
    for (var c = 0; c < contextCount; c++) {
      while ((payload[pos++] & 0x80) != 0) {
        // advance past continuation bytes
      }
    }
    return pos;
  }

  private static byte[] concat(byte[] header, byte[] residual) {
    final var out = new byte[header.length + residual.length];
    System.arraycopy(header, 0, out, 0, header.length);
    System.arraycopy(residual, 0, out, header.length, residual.length);
    return out;
  }

  private byte[] sliceResidual(byte[] payload) {
    final var start = headerLength(payload);
    final var residual = new byte[payload.length - start];
    System.arraycopy(payload, start, residual, 0, residual.length);
    return residual;
  }

  // --- the state machine ---------------------------------------------------

  /**
   * The run-length state machine, shared verbatim by both halves so they cannot drift apart.
   *
   * <p>It holds the last {@code maxContext} edges in a small ring buffer, the current run length,
   * and a cursor into the run starts. All arithmetic is integer with fixed semantics — {@code
   * cross}/{@code dot} in {@code long} to survive full-extent coordinates, and the edge mean by
   * truncating division applied identically on both sides. {@code ideen.md} names this the
   * conformance point where implementations diverge, which is why there is exactly one copy of it.
   */
  private static final class State {

    private final int[] starts;
    private final int turnShift;
    private final int maxContext;
    private final int missThreshold;

    private final int[] edgeX;
    private final int[] edgeY;
    private int edges;
    private int run;
    private int startCursor;

    private int lastX;
    private int lastY;
    private boolean hasLast;

    State(int[] starts, int turnShift, int maxContext, int missThreshold) {
      this.starts = starts;
      this.turnShift = turnShift;
      this.maxContext = maxContext;
      this.missThreshold = missThreshold;
      this.edgeX = new int[maxContext];
      this.edgeY = new int[maxContext];
    }

    /** The context of the vertex about to be coded — valid before it is decoded. */
    int context() {
      return Math.min(run, maxContext);
    }

    int meanEdgeX(int c) {
      return mean(edgeX, c);
    }

    int meanEdgeY(int c) {
      return mean(edgeY, c);
    }

    /** Sum of the last {@code c} edges divided by {@code c}, truncating toward zero. */
    private int mean(int[] edge, int c) {
      var sum = 0L;
      for (var k = 0; k < c; k++) {
        sum += edge[slot(k)];
      }
      return (int) (sum / c);
    }

    /** Ring-buffer slot of the k-th most recent edge, k=0 being the newest. */
    private int slot(int k) {
      return Math.floorMod(edges - 1 - k, maxContext);
    }

    /**
     * Folds vertex {@code i} into the state, after it has been reconstructed.
     *
     * <p>A run start is a forced reset point in {@code ideen.md}'s terms: the run restarts at 0 and
     * the edge history is dropped, because an edge spanning two parts describes no direction. This
     * costs nothing — such a vertex's residual is a plain delta against the previous vertex,
     * exactly as in the baseline, and it lands in region 0. No seed is ever stored.
     *
     * <p>The angle test alone is <b>not sufficient</b>, and the reason is causal. The context of
     * vertex {@code i} is read before {@code i} is decoded, so it describes the run as of {@code
     * p[i-1]} — it cannot know that {@code i} is where the line turns. At a corner the run is
     * therefore still long, continuation predicts straight ahead, and the residual is <em>wider
     * than a plain delta would have been</em> and lands in the narrowest region. One such vertex
     * raises the packed width of a whole 128-value block, which is exactly the tail-widening that
     * made unconditional continuation a +1,9% regression.
     *
     * <p>What a decoder does have is the residual of the vertex it just reconstructed. A large one
     * means the prediction has just failed, so the same prediction should not be trusted at full
     * strength again. {@link #penalise} feeds that back — the "Streikzähler" of {@code ideen.md}'s
     * decoder state — and it is the only mechanism here that reacts to a turn at the vertex where
     * the turn actually costs something.
     */
    void advance(int i, int x, int y) {
      // Run starts arrive in ascending order, so a single cursor suffices.
      while (startCursor < starts.length && starts[startCursor] < i) {
        startCursor++;
      }
      final var beginsRun = startCursor < starts.length && starts[startCursor] == i;
      if (beginsRun) {
        startCursor++;
      }

      if (!hasLast || beginsRun) {
        // The first vertex of a run: no edge ends here, so there is nothing to extend.
        run = 0;
        edges = 0;
      } else {
        final var ex = x - lastX;
        final var ey = y - lastY;
        final var smooth = edges > 0 && isSmooth(edgeX[slot(0)], edgeY[slot(0)], ex, ey);
        edgeX[edges % maxContext] = ex;
        edgeY[edges % maxContext] = ey;
        edges++;
        run = smooth ? run + 1 : 1;
      }

      // The edge into the next vertex crosses a part boundary, so it is not a direction: drop the
      // history now rather than letting it predict across the seam.
      if (startCursor < starts.length && starts[startCursor] == i + 1) {
        run = 0;
        edges = 0;
      }

      lastX = x;
      lastY = y;
      hasLast = true;
    }

    /**
     * Feeds the just-coded residual back into the run, after the vertex is reconstructed.
     *
     * <p>A residual wider than {@code missBits} in either component means continuation has just
     * mispredicted badly — the line turned at a vertex the angle test could not see coming. The run
     * collapses to 0, so the <em>next</em> vertex is delta-coded in region 0 rather than being
     * predicted from a direction that has already been shown wrong.
     *
     * <p>Both halves call this with the same residual: the encoder has it directly, the decoder has
     * it from the value it just read. So the feedback is symmetric and nothing is signalled.
     */
    void penalise(int codedContext, int residualX, int residualY) {
      /* Only a prediction that actually used history can have mispredicted. A context-0 residual is
       * a plain delta -- an ordinary edge length, routinely far wider than this threshold -- so
       * judging it here would collapse the run at every run start and degenerate the whole encoding
       * to the baseline. */
      if (codedContext == 0) {
        return;
      }
      if (Math.abs(residualX) > missThreshold || Math.abs(residualY) > missThreshold) {
        run = 0;
        edges = 0;
      }
    }

    /**
     * Whether the turn from one edge to the next is shallow enough that continuing the run is
     * expected to predict better than a plain delta.
     *
     * <p>{@code |cross| <= |dot| >> turnShift} with {@code dot > 0}: the cross product measures how
     * far the new edge departs from the old direction and the dot product how far it follows it, so
     * the ratio is a tangent threshold without ever computing an angle. Requiring a positive dot
     * rejects reversals and doubling-back, which continuation predicts worst of all. A zero-length
     * edge yields {@code dot == 0} and is not smooth, which is right: a repeated vertex carries no
     * direction.
     */
    private boolean isSmooth(int px, int py, int cx, int cy) {
      final var dot = (long) px * cx + (long) py * cy;
      if (dot <= 0) {
        return false;
      }
      final var cross = Math.abs((long) px * cy - (long) py * cx);
      return cross <= (dot >> turnShift);
    }
  }
}
