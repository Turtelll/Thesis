package org.maplibre.mlt.research.encoding.research.failed;

import org.maplibre.mlt.research.encoding.research.SegmentQuadCorner;
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
 * Extends {@link SegmentQuadCorner} by giving rings <b>longer</b> than four vertices — the population
 * its parallelogram predictor cannot reach — a signalled 90°-turn predictor, while exactly-four-vertex
 * rings keep the parallelogram corner ({@code p0+p2-p1}) that wins {@code building} outright.
 *
 * <p>Every vertex falls in exactly one class, each residual packed into its own contiguous FastPFOR
 * region so a wide population never sets a narrow one's block width:
 *
 * <ol>
 *   <li><b>start</b> — the first vertex of each ring run, delta against the previous vertex.
 *   <li><b>interior</b> — a within-ring vertex with no better predictor (short rings, and the first
 *       two vertices of a long ring), delta against the previous vertex.
 *   <li><b>corner</b> — the fourth vertex of an exactly-four-vertex run, predicted as the
 *       parallelogram corner {@code p0+p2-p1}. Near-zero for the exact-parallelogram building quads.
 *   <li><b>turn</b> — an interior vertex of a &gt;4-vertex ring ({@code i >= start+2}, so the previous
 *       edge is defined), predicted as {@code p[i-1] + rot90(prevEdge)} in the cheaper of the two turn
 *       directions. The chosen direction is <b>signalled</b>, one bit per turn vertex.
 * </ol>
 *
 * <p>The turn class is the reason this is an {@link Encoding} directly rather than a {@code
 * VertexTransform}: unlike {@code SegmentQuadCorner}, which only reorders information, the turn
 * direction is real signalled data, carried in a side stream with its own {@link StreamMetadata}
 * header so {@link EncodedBuffer#totalBytes()} reflects its cost. Everything else — the class of every
 * vertex, the region boundaries — is derivable from the topology streams on both sides; {@code
 * prevX/prevY} advance continuously through the actual vertices regardless of class, no reset seeds.
 *
 * <p>The 90°-turn predictor is itself a regression on this corpus (see {@link RightAngleTurn} and
 * {@code research-java/README.md}); this composition measures whether pairing it with the winning
 * quad-corner path recovers a net gain, keeping the parts test-pinned so the result need not be
 * re-derived.
 */
public final class SegmentQuadCornerTurn implements Encoding {

  // INTERIOR is 0 so the default-zeroed class array already means "interior".
  private static final int INTERIOR = 0;
  private static final int START = 1;
  private static final int CORNER = 2;
  private static final int TURN = 3;

  private final String name;
  private final IntCodec codec;

  public SegmentQuadCornerTurn(String name, IntCodec codec) {
    this.name = name;
    this.codec = codec;
  }

  @Override
  public String name() {
    return name;
  }

  /** The residual value stream plus the chosen turn-direction bits, before physical coding. */
  record Transformed(int[] values, List<Boolean> turns) {}

  @Override
  public EncodedBuffer encode(BufferContext ctx) {
    final var vertices = ctx.vertices();
    final var transformed =
        transform(vertices, classify(PartBoundaries.of(ctx, vertices.size()), vertices.size()));

    final var residual = codec.encode(transformed.values());
    final var flags = packBits(transformed.turns());
    final var payload = concat(residual, flags);
    final var metadataBytes =
        StreamMetadata.bytes(transformed.values().length, residual.length)
            + StreamMetadata.bytes(transformed.turns().size(), flags.length);
    return new EncodedBuffer(payload, metadataBytes);
  }

  /** Applies the per-class predictor and packs each class into its contiguous region. */
  static Transformed transform(List<Vertex> vertices, int[] classes) {
    final var values = new int[vertices.size() * 2];
    final var cursors = cursors(classes);
    final var turns = new ArrayList<Boolean>();
    var prevX = 0;
    var prevY = 0;
    for (var i = 0; i < vertices.size(); i++) {
      final var vertex = vertices.get(i);
      final var slot = cursors[classes[i]];
      if (classes[i] == TURN) {
        final var p1 = vertices.get(i - 1);
        final var edgeX = p1.x() - vertices.get(i - 2).x();
        final var edgeY = p1.y() - vertices.get(i - 2).y();
        // rot90 ccw of (edgeX,edgeY) is (-edgeY, edgeX); cw is (edgeY, -edgeX).
        final var ccwX = EncodingUtils.encodeZigZag(vertex.x() - (p1.x() - edgeY));
        final var ccwY = EncodingUtils.encodeZigZag(vertex.y() - (p1.y() + edgeX));
        final var cwX = EncodingUtils.encodeZigZag(vertex.x() - (p1.x() + edgeY));
        final var cwY = EncodingUtils.encodeZigZag(vertex.y() - (p1.y() - edgeX));
        // Cheaper direction wins; tie goes to ccw. Bit true == cw.
        final var cw = (long) cwX + cwY < (long) ccwX + ccwY;
        turns.add(cw);
        values[slot] = cw ? cwX : ccwX;
        values[slot + 1] = cw ? cwY : ccwY;
      } else {
        final int predX;
        final int predY;
        if (classes[i] == CORNER) {
          predX = vertices.get(i - 3).x() + vertices.get(i - 1).x() - vertices.get(i - 2).x();
          predY = vertices.get(i - 3).y() + vertices.get(i - 1).y() - vertices.get(i - 2).y();
        } else {
          predX = prevX;
          predY = prevY;
        }
        values[slot] = EncodingUtils.encodeZigZag(vertex.x() - predX);
        values[slot + 1] = EncodingUtils.encodeZigZag(vertex.y() - predY);
      }
      cursors[classes[i]] += 2;
      prevX = vertex.x();
      prevY = vertex.y();
    }
    return new Transformed(values, turns);
  }

  @Override
  public List<Vertex> decode(EncodedBuffer encoded, BufferContext ctx, int vertexCount) {
    final var classes = classify(PartBoundaries.of(ctx, vertexCount), vertexCount);

    final var payload = encoded.payload();
    final var residualLen = readLengthPrefix(payload);
    final var values = codec.decode(sliceResidual(payload), vertexCount * 2);
    final var flags = sliceFlags(payload, residualLen);

    final var vertices = new ArrayList<Vertex>(vertexCount);
    final var cursors = cursors(classes);
    var turnCursor = 0;
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
        final var cw = readBit(flags, turnCursor++);
        predX = cw ? p1.x() + edgeY : p1.x() - edgeY;
        predY = cw ? p1.y() - edgeX : p1.y() + edgeX;
      } else if (classes[i] == CORNER) {
        predX = vertices.get(i - 3).x() + vertices.get(i - 1).x() - vertices.get(i - 2).x();
        predY = vertices.get(i - 3).y() + vertices.get(i - 1).y() - vertices.get(i - 2).y();
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
   * Per-vertex class: START (run start), CORNER (4th vertex of a 4-run), TURN (interior vertex of a
   * &gt;4-run with two in-ring predecessors), INTERIOR otherwise.
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
      } else if (length > 4) {
        for (var i = start + 2; i < end; i++) {
          classes[i] = TURN;
        }
      }
    }
    return classes;
  }

  /** Start offset (in int slots) of each class's region: starts, interior, corners, turns. */
  private static int[] cursors(int[] classes) {
    var starts = 0;
    var corners = 0;
    var turns = 0;
    for (var c : classes) {
      if (c == START) {
        starts++;
      } else if (c == CORNER) {
        corners++;
      } else if (c == TURN) {
        turns++;
      }
    }
    final var interior = classes.length - starts - corners - turns;
    final var cursors = new int[4];
    cursors[START] = 0;
    cursors[INTERIOR] = starts * 2;
    cursors[CORNER] = (starts + interior) * 2;
    cursors[TURN] = (starts + interior + corners) * 2;
    return cursors;
  }

  // --- turn-direction bit stream ------------------------------------------------

  private static byte[] packBits(List<Boolean> bits) {
    final var bytes = new byte[(bits.size() + 7) / 8];
    for (var i = 0; i < bits.size(); i++) {
      if (bits.get(i)) {
        bytes[i / 8] |= (byte) (1 << (i % 8));
      }
    }
    return bytes;
  }

  private static boolean readBit(byte[] bytes, int i) {
    return (bytes[i / 8] & (1 << (i % 8))) != 0;
  }

  // --- payload framing: [4-byte residualLen][residual][flags] -------------------

  private static byte[] concat(byte[] residual, byte[] flags) {
    final var out = new byte[4 + residual.length + flags.length];
    out[0] = (byte) (residual.length >>> 24);
    out[1] = (byte) (residual.length >>> 16);
    out[2] = (byte) (residual.length >>> 8);
    out[3] = (byte) residual.length;
    System.arraycopy(residual, 0, out, 4, residual.length);
    System.arraycopy(flags, 0, out, 4 + residual.length, flags.length);
    return out;
  }

  private static int readLengthPrefix(byte[] payload) {
    return ((payload[0] & 0xFF) << 24)
        | ((payload[1] & 0xFF) << 16)
        | ((payload[2] & 0xFF) << 8)
        | (payload[3] & 0xFF);
  }

  private static byte[] sliceResidual(byte[] payload) {
    final var len = readLengthPrefix(payload);
    final var residual = new byte[len];
    System.arraycopy(payload, 4, residual, 0, len);
    return residual;
  }

  private static byte[] sliceFlags(byte[] payload, int residualLen) {
    final var start = 4 + residualLen;
    final var flags = new byte[payload.length - start];
    System.arraycopy(payload, start, flags, 0, flags.length);
    return flags;
  }
}
