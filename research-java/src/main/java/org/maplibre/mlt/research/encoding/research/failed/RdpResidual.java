package org.maplibre.mlt.research.encoding.research.failed;

import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.decoder.DecodingUtils;
import org.maplibre.mlt.research.encoding.Encoding;
import org.maplibre.mlt.research.encoding.IntCodec;
import org.maplibre.mlt.research.encoding.PartBoundaries;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.EncodedBuffer;
import org.maplibre.mlt.research.encoding.VertexTransform;
import org.maplibre.mlt.research.encoding.research.LineContinuation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Ramer-Douglas-Peucker line simplification with a lossless residual: each LineString/
 * MultiLineString run of at least {@value #MIN_LINE_RUN_LENGTH} vertices is reduced to a set of
 * "key" vertices (always including both endpoints), and every dropped vertex is stored as its
 * offset against the point interpolated between the key-to-key segment it fell between (see
 * {@link #interpolate}). Decoding replays the simplified polyline and adds the residual back, so
 * this is lossless regardless of which vertices RDP kept — RDP only chooses <em>where the bits
 * go</em>, never whether they are sent.
 *
 * <p><b>Negative result — not registered in {@link org.maplibre.mlt.research.encoding.Baselines}.
 * Kept only for {@code RdpResidualMechanismTest}.</b> See {@code docs/results/rdp-residual.md}: the
 * per-vertex residual is genuinely smaller than plain delta on the vertices RDP drops (-24% mean
 * bits), but transportation is dominated by parts too short to have an interior vertex to drop
 * (70,4% are 2-vertex — see {@code docs/results/segment-pair.md}), so the win is diluted below the
 * cost of the key/drop bitmap corpus-wide, interleaved or segmented ({@link RdpResidualSegmented}).
 *
 * <p>The decoder cannot infer which vertices were kept from topology alone (unlike {@link
 * LineContinuation}'s classes), so the key/drop choice is signalled explicitly: one bit per interior
 * vertex of every eligible run, packed ahead of the residual stream. Runs shorter than the gate (and
 * every non-line run — polygon rings, points, short parts) contribute no bitmap bits and fall back to
 * plain continuous delta, exactly like {@link LineContinuation}'s ungated classes.
 *
 * <p>The tolerance {@code epsilon} is chosen per buffer, trying a fixed candidate ladder and keeping
 * whichever gives the smallest encoded payload. It is never stored: the decoder does not need it,
 * only the bitmap it produced.
 *
 * <p>Residual: a dropped vertex's along-segment position is never stored — the decoder already knows
 * its index {@code i} and the bounding keys' indices {@code a, b} (index order survives simplification
 * unchanged), so it interpolates the same predicted point {@code A + round((B - A) * (i - a) / (b -
 * a))} the encoder used (see {@link #interpolate}), in exact integer arithmetic so both sides agree
 * bit for bit. Only the residual against that point — {@code actual - predicted}, both components —
 * is sent. For a vertex RDP kept (within tolerance of the chord), this is small regardless of how long
 * the simplified segment is, unlike anchoring the along-component to the segment start.
 */
public final class RdpResidual implements Encoding {

    // Below this run length there is at most one interior vertex to drop, so RDP has nothing to do
    // and the bitmap would only add cost — the same shape of gate as LineContinuation's. Package-
    // visible: shared with RdpResidualSegmented, which must gate identically to agree on the bitmap.
    static final int MIN_LINE_RUN_LENGTH = 3;

    // Tried in order; the smallest resulting payload wins. Integer.MAX_VALUE means "keep only the
    // run's endpoints" (maximal simplification). Package-visible: shared with RdpResidualSegmented.
    static final long[] EPSILON_SQUARED_CANDIDATES = {
        1L, 4L, 16L, 64L, 256L, 1024L, 4096L, Long.MAX_VALUE
    };

    private final IntCodec codec;

    /** The original measurement: FastPFOR over the residuals. */
    public RdpResidual() {
        this(IntCodec.FASTPFOR);
    }

    /** Residuals packed (and the epsilon ladder priced) with {@code codec}. */
    public RdpResidual(IntCodec codec) {
        this.codec = codec;
    }

    @Override
    public String name() {
        return "RDP_RESIDUAL_" + codec.name();
    }

    @Override
    public EncodedBuffer encode(BufferContext ctx) {
        final var vertices = ctx.vertices();
        final var runs = Runs.of(ctx, vertices.size());

        byte[] best = null;
        for (final var epsilonSquared : EPSILON_SQUARED_CANDIDATES) {
            final var candidate = encodeWith(vertices, runs, epsilonSquared, codec);
            if (best == null || candidate.length < best.length) {
                best = candidate;
            }
        }
        return new EncodedBuffer(best, org.maplibre.mlt.research.encoding.StreamMetadata.bytes(vertices.size() * 2, best.length));
    }

    private static byte[] encodeWith(List<Vertex> vertices, Runs runs, long epsilonSquared, IntCodec codec) {
        final var kept = runs.keptMask(vertices, epsilonSquared);
        final var bitmap = runs.packBitmap(kept);
        final var residuals = residualsOf(vertices, runs, kept);
        final var fastPfor = codec.encode(residuals);

        try {
            final var bytes = new ByteArrayOutputStream();
            final var out = new DataOutputStream(bytes);
            EncodingUtils.putVarInt(out, bitmap.length);
            out.write(bitmap);
            out.write(fastPfor);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<Vertex> decode(EncodedBuffer encoded, BufferContext ctx, int vertexCount) {
        final var runs = Runs.of(ctx, vertexCount);
        try {
            final var in = new ByteArrayInputStream(encoded.payload());
            final var bitmapLength = DecodingUtils.decodeVarint(in);
            final var bitmap = in.readNBytes(bitmapLength);
            final var fastPfor = in.readAllBytes();

            final var kept = runs.unpackKeptMask(bitmap);
            final var residuals = codec.decode(fastPfor, vertexCount * 2);
            return reconstruct(runs, kept, residuals, vertexCount);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The residual stream alone, for the benchmark's residual-bits table — see {@code
     * org.maplibre.mlt.research.cli.ResidualBitsCli.residualSourceOf}. Recomputes the same epsilon search as
     * {@link #encode}, so the reported residuals are exactly the ones that would be FastPFOR-packed.
     * {@link VertexTransform#invert} is not meaningful without the bitmap, which this alone cannot
     * carry; it is never called by the benchmark (only {@link VertexTransform#apply} feeds the table).
     */
    public VertexTransform transform() {
        return new VertexTransform() {
            @Override
            public String name() {
                return "RDP_RESIDUAL";
            }

            @Override
            public int[] apply(BufferContext ctx) {
                final var vertices = ctx.vertices();
                final var runs = Runs.of(ctx, vertices.size());

                int[] best = null;
                var bestBytes = Integer.MAX_VALUE;
                for (final var epsilonSquared : EPSILON_SQUARED_CANDIDATES) {
                    final var kept = runs.keptMask(vertices, epsilonSquared);
                    final var residuals = residualsOf(vertices, runs, kept);
                    final var bytes = codec.encode(residuals).length;
                    if (bytes < bestBytes) {
                        bestBytes = bytes;
                        best = residuals;
                    }
                }
                return best;
            }

            @Override
            public List<Vertex> invert(int[] values, BufferContext ctx) {
                throw new UnsupportedOperationException(
                        "RDP_RESIDUAL's bare transform cannot invert without the key bitmap; use RdpResidual.decode");
            }
        };
    }

    // --- residual computation, shared by encode() and transform() -----------------------------

    private static int[] residualsOf(List<Vertex> vertices, Runs runs, boolean[] kept) {
        final var values = new int[vertices.size() * 2];
        var prevX = 0;
        var prevY = 0;
        for (var r = 0; r < runs.starts.length; r++) {
            final var start = runs.starts[r];
            final var end = runs.end(r);
            if (runs.isLine[r] && end - start >= MIN_LINE_RUN_LENGTH) {
                final var startVertex = vertices.get(start);
                values[2 * start] = EncodingUtils.encodeZigZag(startVertex.x() - prevX);
                values[2 * start + 1] = EncodingUtils.encodeZigZag(startVertex.y() - prevY);
                prevX = startVertex.x();
                prevY = startVertex.y();

                var keyIndex = start;
                for (var i = start + 1; i < end; i++) {
                    if (kept[i]) {
                        final var a = vertices.get(keyIndex);
                        final var b = vertices.get(i);
                        writeSegmentResiduals(vertices, values, keyIndex, i, a, b);
                        // The key vertex itself is plain delta from whatever preceded it (the last key).
                        values[2 * i] = EncodingUtils.encodeZigZag(b.x() - prevX);
                        values[2 * i + 1] = EncodingUtils.encodeZigZag(b.y() - prevY);
                        prevX = b.x();
                        prevY = b.y();
                        keyIndex = i;
                    }
                }
            } else {
                for (var i = start; i < end; i++) {
                    final var v = vertices.get(i);
                    values[2 * i] = EncodingUtils.encodeZigZag(v.x() - prevX);
                    values[2 * i + 1] = EncodingUtils.encodeZigZag(v.y() - prevY);
                    prevX = v.x();
                    prevY = v.y();
                }
            }
        }
        return values;
    }

    private static void writeSegmentResiduals(
            List<Vertex> vertices, int[] values, int keyIndex, int nextKeyIndex, Vertex a, Vertex b) {
        for (var i = keyIndex + 1; i < nextKeyIndex; i++) {
            final var v = vertices.get(i);
            final var predicted = interpolate(a, b, i - keyIndex, nextKeyIndex - keyIndex);
            values[2 * i] = EncodingUtils.encodeZigZag(v.x() - predicted.x());
            values[2 * i + 1] = EncodingUtils.encodeZigZag(v.y() - predicted.y());
        }
    }

    /**
     * The point a fraction {@code numerator/denominator} of the way from {@code a} to {@code b},
     * rounded to the nearest integer coordinate (ties away from zero) — in exact integer arithmetic,
     * so encode and decode compute bit-identical predictions from the shared index span, and no
     * along-position ever needs to be sent: the decoder already knows {@code i}, {@code keyIndex} and
     * {@code nextKeyIndex} from the bitmap alone.
     */
    static Vertex interpolate(Vertex a, Vertex b, int numerator, int denominator) {
        return new Vertex(
                a.x() + roundedFraction((long) (b.x() - a.x()) * numerator, denominator),
                a.y() + roundedFraction((long) (b.y() - a.y()) * numerator, denominator));
    }

    private static int roundedFraction(long numerator, int denominator) {
        return (int) Math.floorDiv(2 * numerator + Long.signum(numerator) * denominator, 2L * denominator);
    }

    // --- reconstruction -------------------------------------------------------------------------

    private static List<Vertex> reconstruct(Runs runs, boolean[] kept, int[] residuals, int vertexCount) {
        final var vertices = new Vertex[vertexCount];
        var prevX = 0;
        var prevY = 0;
        for (var r = 0; r < runs.starts.length; r++) {
            final var start = runs.starts[r];
            final var end = runs.end(r);
            if (runs.isLine[r] && end - start >= MIN_LINE_RUN_LENGTH) {
                final var startX = prevX + DecodingUtils.decodeZigZag(residuals[2 * start]);
                final var startY = prevY + DecodingUtils.decodeZigZag(residuals[2 * start + 1]);
                vertices[start] = new Vertex(startX, startY);
                prevX = startX;
                prevY = startY;

                var keyIndex = start;
                var i = start + 1;
                while (i < end) {
                    if (kept[i]) {
                        final var x = prevX + DecodingUtils.decodeZigZag(residuals[2 * i]);
                        final var y = prevY + DecodingUtils.decodeZigZag(residuals[2 * i + 1]);
                        vertices[i] = new Vertex(x, y);
                        prevX = x;
                        prevY = y;
                        keyIndex = i;
                        i++;
                    } else {
                        // Dropped run between keyIndex and the next kept vertex: find it, then fill in.
                        var nextKey = i;
                        while (!kept[nextKey]) {
                            nextKey++;
                        }
                        final var a = vertices[keyIndex];
                        // The next key hasn't been reconstructed yet; decode it first from the stream
                        // position it occupies (2*nextKey), independent of this loop's cursor.
                        final var bx = prevX + DecodingUtils.decodeZigZag(residuals[2 * nextKey]);
                        final var by = prevY + DecodingUtils.decodeZigZag(residuals[2 * nextKey + 1]);
                        final var b = new Vertex(bx, by);
                        vertices[nextKey] = b;
                        prevX = bx;
                        prevY = by;
                        for (var j = i; j < nextKey; j++) {
                            final var rx = DecodingUtils.decodeZigZag(residuals[2 * j]);
                            final var ry = DecodingUtils.decodeZigZag(residuals[2 * j + 1]);
                            final var predicted = interpolate(a, b, j - keyIndex, nextKey - keyIndex);
                            vertices[j] = new Vertex(predicted.x() + rx, predicted.y() + ry);
                        }
                        keyIndex = nextKey;
                        i = nextKey + 1;
                    }
                }
            } else {
                for (var v = start; v < end; v++) {
                    final var x = prevX + DecodingUtils.decodeZigZag(residuals[2 * v]);
                    final var y = prevY + DecodingUtils.decodeZigZag(residuals[2 * v + 1]);
                    vertices[v] = new Vertex(x, y);
                    prevX = x;
                    prevY = y;
                }
            }
        }
        final var result = new ArrayList<Vertex>(vertexCount);
        for (final var v : vertices) {
            result.add(v);
        }
        return result;
    }

    // --- run topology + RDP + bitmap packing -----------------------------------------------------

    static final class Runs {
        final int[] starts;
        final boolean[] isLine;
        final int vertexCount;

        private Runs(int[] starts, boolean[] isLine, int vertexCount) {
            this.starts = starts;
            this.isLine = isLine;
            this.vertexCount = vertexCount;
        }

        static Runs of(BufferContext ctx, int vertexCount) {
            final var starts = PartBoundaries.of(ctx, vertexCount);
            final var isLineRaw = PartBoundaries.lineRuns(ctx, starts.length);
            final var isLine = isLineRaw != null ? isLineRaw : new boolean[starts.length];
            return new Runs(starts, isLine, vertexCount);
        }

        int end(int runIndex) {
            return runIndex + 1 < starts.length ? starts[runIndex + 1] : vertexCount;
        }

        /**
         * Per-vertex "is this a key vertex RDP kept" flag, for a given tolerance. Endpoints of every
         * eligible run are always kept; non-eligible runs and vertices outside a run are marked kept
         * (so the encoder's segment walker in {@link RdpResidual#residualsOf} never treats them as
         * droppable) but contribute no bitmap bits.
         */
        boolean[] keptMask(List<Vertex> vertices, long epsilonSquared) {
            final var kept = new boolean[vertexCount];
            java.util.Arrays.fill(kept, true);
            for (var r = 0; r < starts.length; r++) {
                final var start = starts[r];
                final var end = end(r);
                if (isLine[r] && end - start >= MIN_LINE_RUN_LENGTH) {
                    java.util.Arrays.fill(kept, start + 1, end - 1, false);
                    simplify(vertices, start, end - 1, epsilonSquared, kept);
                }
            }
            return kept;
        }

        /** Classic recursive RDP, run iteratively with an explicit stack to avoid deep recursion. */
        private static void simplify(
                List<Vertex> vertices, int lo, int hi, long epsilonSquared, boolean[] kept) {
            final var stack = new ArrayDeque();
            stack.push(lo, hi);
            while (!stack.isEmpty()) {
                final var range = stack.pop();
                final var a = vertices.get(range[0]);
                final var b = vertices.get(range[1]);
                var farthestIndex = -1;
                var farthestDistSq = -1L;
                for (var i = range[0] + 1; i < range[1]; i++) {
                    final var d = perpendicularDistanceSquared(a, b, vertices.get(i));
                    if (d > farthestDistSq) {
                        farthestDistSq = d;
                        farthestIndex = i;
                    }
                }
                if (farthestIndex >= 0 && farthestDistSq > epsilonSquared) {
                    kept[farthestIndex] = true;
                    stack.push(range[0], farthestIndex);
                    stack.push(farthestIndex, range[1]);
                }
            }
        }

        private static long perpendicularDistanceSquared(Vertex a, Vertex b, Vertex p) {
            final long dx = (long) b.x() - a.x();
            final long dy = (long) b.y() - a.y();
            if (dx == 0 && dy == 0) {
                final long px = (long) p.x() - a.x();
                final long py = (long) p.y() - a.y();
                return px * px + py * py;
            }
            final long cross = dx * ((long) a.y() - p.y()) - dy * ((long) a.x() - p.x());
            return (cross * cross) / (dx * dx + dy * dy);
        }

        /** Packs one bit per interior vertex of every eligible run, run by run, MSB-first per byte. */
        byte[] packBitmap(boolean[] kept) {
            final var bits = new ArrayList<Boolean>();
            for (var r = 0; r < starts.length; r++) {
                final var start = starts[r];
                final var end = end(r);
                if (isLine[r] && end - start >= MIN_LINE_RUN_LENGTH) {
                    for (var i = start + 1; i < end - 1; i++) {
                        bits.add(kept[i]);
                    }
                }
            }
            final var bytes = new byte[(bits.size() + 7) / 8];
            for (var i = 0; i < bits.size(); i++) {
                if (bits.get(i)) {
                    bytes[i / 8] |= (byte) (1 << (7 - (i % 8)));
                }
            }
            return bytes;
        }

        /** Inverse of {@link #packBitmap}: reconstructs the full per-vertex kept mask from the bits. */
        boolean[] unpackKeptMask(byte[] bitmap) {
            final var kept = new boolean[vertexCount];
            java.util.Arrays.fill(kept, true);
            var bitIndex = 0;
            for (var r = 0; r < starts.length; r++) {
                final var start = starts[r];
                final var end = end(r);
                if (isLine[r] && end - start >= MIN_LINE_RUN_LENGTH) {
                    for (var i = start + 1; i < end - 1; i++) {
                        final var bit = (bitmap[bitIndex / 8] >> (7 - (bitIndex % 8))) & 1;
                        kept[i] = bit != 0;
                        bitIndex++;
                    }
                }
            }
            return kept;
        }
    }

    /** Minimal int-pair stack, avoiding boxing for the RDP recursion. */
    private static final class ArrayDeque {
        private int[] data = new int[64];
        private int size = 0;

        void push(int a, int b) {
            if (size + 2 > data.length) {
                data = java.util.Arrays.copyOf(data, data.length * 2);
            }
            data[size++] = a;
            data[size++] = b;
        }

        int[] pop() {
            size -= 2;
            return new int[]{data[size], data[size + 1]};
        }

        boolean isEmpty() {
            return size == 0;
        }
    }
}
