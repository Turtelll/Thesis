package org.maplibre.mlt.research.encoder.prediction;

import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoder.prediction.mechanism.ClassifiedPredictor;
import org.maplibre.mlt.research.buffer.PartBoundaries;
import org.maplibre.mlt.research.buffer.Encoding.BufferContext;
import org.maplibre.mlt.research.encoder.prediction.mechanism.LineContinuation;
import org.maplibre.mlt.research.encoder.reference.LineSharedVertexRef;
import org.maplibre.mlt.research.encoder.prediction.mechanism.QuadCornerLine;
import org.maplibre.mlt.research.encoder.prediction.mechanism.RingFramePrior;
import org.maplibre.mlt.research.encoder.prediction.mechanism.SegmentQuadCorner;
import org.maplibre.mlt.research.encoder.reference.SharedVertexRef;
import org.maplibre.mlt.research.encoder.prediction.mechanism.TurnScalar;

import java.util.List;

/**
 * S2: prediction. Turns absolute coordinates into signed residuals. The delta chain is continuous
 * across part and feature boundaries: a vertex without a geometric prediction is predicted by the
 * previous vertex of the buffer, whatever feature it belongs to.
 *
 * <p>Predictors are the ones that worked in the old pipeline, reused unchanged.
 */
public enum Prediction {

    /** Componentwise first-order delta, the baseline. */
    DELTA,

    /** Fourth corner of the previous three in a quad ring (buildings), old {@code QUAD_CORNER}. */
    QUAD_CORNER,

    /** Line parts continue the previous segment (roads), old {@code LINE_CONTINUATION}. */
    LINE_CONTINUATION,

    /** Both of the above, one class set, old {@code QUAD_CORNER_LINE}. */
    QUAD_CORNER_LINE,

    /**
     * Buildings: {@code QUAD_CORNER}'s corner on four-vertex rings, and on the vertices of even
     * {@code >4}-vertex polygon rings a 90-degree turn against the previous edge, stored as one edge
     * length scalar (the {@link SideValues side value}, its own stream) plus a correction, old
     * {@code TURN_SCALAR}. Needs a class-separating segmentation to pay.
     */
    TURN_SCALAR,

    /**
     * {@code QUAD_CORNER}, with the plain-delta edges of polygon rings coded in the frame of the
     * nearest decoded ring ({@link RingFramePrior}): an exactly invertible integer rotation of the
     * residual, so the stream keeps two values per vertex and nothing is signalled.
     */
    QUAD_CORNER_FRAME,

    /** {@code QUAD_CORNER_LINE} with the same ring frame. */
    QUAD_CORNER_LINE_FRAME,

    /**
     * {@code QUAD_CORNER} plus references to vertices an earlier polygon ring shares ({@link
     * SharedVertexRef}, old {@code QUAD_CORNER_SHARED}): rings are rotated to start where a shared
     * stretch begins, and a referenced vertex has no residual. Which vertices are referenced is only
     * known while decoding, so the buffer carries the flag and index bits and the stream lengths.
     * A buffer without a gain from the references is written as plain {@link #QUAD_CORNER} (the
     * predictor field of the header says which), so a layer without shared vertices pays nothing.
     */
    QUAD_CORNER_SHARED,

    /** {@link #QUAD_CORNER_SHARED} with the ring frame of {@link #QUAD_CORNER_FRAME}. */
    QUAD_CORNER_SHARED_FRAME,

    /**
     * {@link #LINE_CONTINUATION} with the frame of {@link #QUAD_CORNER_FRAME} on line parts: the
     * plain-delta edges of a line run other than its start (the 2nd vertex of every run, all of a run
     * the continuation gate leaves alone) are coded in the frame of the nearest decoded run, refined
     * by the run's own decoded edges. The continued vertices keep their residual. Appended last so
     * the ordinals of the other predictors stay put.
     */
    LINE_CONTINUATION_FRAME,

    /**
     * {@link #LINE_CONTINUATION} with the continuation residual of a continued vertex rotated into the
     * direction of the incoming edge: an along-track component (the step got longer or shorter) and a
     * sideways one (the road bent), instead of both mixed over the tile axes. The frame is the two
     * vertices before, so the decoder has it; the rotation is an exact integer bijection ({@link
     * RingFramePrior#forwardHeading}) and nothing is signalled.
     */
    LINE_CONTINUATION_HEADING,

    /** {@link #LINE_CONTINUATION_FRAME} and {@link #LINE_CONTINUATION_HEADING}: both, on disjoint vertices. */
    LINE_CONTINUATION_FRAME_HEADING,

    /**
     * {@link #LINE_CONTINUATION} plus references to the positions earlier line parts hold ({@link
     * LineSharedVertexRef}, old {@code LINE_CONTINUATION_SHARED_DICT}): an
     * entry and an exit flag at the ends of every line part, and a bit-packed index into the
     * dictionary of earlier positions in place of the residual on a hit. A part whose end is in the
     * dictionary and whose start is not is reversed, which changes line direction. Polygon rings and
     * points keep their plain residuals. A buffer the references do not shrink is written as plain
     * {@link #LINE_CONTINUATION}, so a layer without junctions pays nothing. Appended last so the
     * ordinals of the other predictors stay put.
     */
    LINE_CONTINUATION_SHARED,

    /**
     * {@link #LINE_CONTINUATION_SHARED} on top of {@link #LINE_CONTINUATION_FRAME_HEADING}: the frame
     * and heading rotations act on the vertices the references leave, which still count as decoded
     * for them.
     */
    LINE_CONTINUATION_SHARED_FRAME_HEADING;

    /** Whether this predictor codes shared-vertex references (see {@link #QUAD_CORNER_SHARED}). */
    public boolean shared() {
        return plain() != this;
    }

    /** The predictor a buffer without references falls back to: itself unless {@link #shared()}. */
    public Prediction plain() {
        return switch (this) {
            case QUAD_CORNER_SHARED -> QUAD_CORNER;
            case QUAD_CORNER_SHARED_FRAME -> QUAD_CORNER_FRAME;
            case LINE_CONTINUATION_SHARED -> LINE_CONTINUATION;
            case LINE_CONTINUATION_SHARED_FRAME_HEADING -> LINE_CONTINUATION_FRAME_HEADING;
            default -> this;
        };
    }

    /**
     * The references of a buffer under this shared predictor, with its rings (or line parts) already
     * rotated (or reversed), or {@code null} when its runs are not derivable from the topology.
     * {@code ctx} holds the vertices.
     */
    public SharedVertexRef.Scan scan(BufferContext ctx) {
        return switch (this) {
            case QUAD_CORNER_SHARED, QUAD_CORNER_SHARED_FRAME -> SharedVertexRef.scan(ctx);
            case LINE_CONTINUATION_SHARED, LINE_CONTINUATION_SHARED_FRAME_HEADING ->
                    LineSharedVertexRef.scan(ctx);
            default -> throw new UnsupportedOperationException(this + " has no references");
        };
    }

    /** The decoder's side of {@link #scan}: needs the topology streams and the flag and index bits. */
    public SharedVertexRef.ReferenceWalk walkForDecoding(BufferContext topo, int vertexCount, byte[] flags, byte[] indices) {
        return switch (this) {
            case QUAD_CORNER_SHARED, QUAD_CORNER_SHARED_FRAME ->
                    SharedVertexRef.Walk.forDecoding(topo, vertexCount, flags, indices);
            case LINE_CONTINUATION_SHARED, LINE_CONTINUATION_SHARED_FRAME_HEADING ->
                    LineSharedVertexRef.walkForDecoding(
                            topo, vertexCount, flags, indices);
            default -> throw new UnsupportedOperationException(this + " has no references");
        };
    }

    /**
     * The predictor behind this choice. Stateless across buffers, so a fresh instance per call is
     * cheap; {@link #TURN_SCALAR}'s instance remembers the winding of the last layout it classified,
     * so one instance serves one buffer.
     */
    public ClassifiedPredictor predictor() {
        return switch (this) {
            case DELTA -> new Delta();
            case QUAD_CORNER -> new SegmentQuadCorner();
            case LINE_CONTINUATION -> new LineContinuation();
            case QUAD_CORNER_LINE -> new QuadCornerLine();
            case TURN_SCALAR -> new TurnScalarPredictor();
            case QUAD_CORNER_FRAME ->
                    new FramedPredictor(new SegmentQuadCorner(), "QUAD_CORNER_FRAME", true, false, false);
            case QUAD_CORNER_LINE_FRAME ->
                    new FramedPredictor(new QuadCornerLine(), "QUAD_CORNER_LINE_FRAME", true, false, false);
            case LINE_CONTINUATION_FRAME ->
                    new FramedPredictor(new LineContinuation(), "LINE_CONTINUATION_FRAME", false, true, false);
            case LINE_CONTINUATION_HEADING ->
                    new FramedPredictor(new LineContinuation(), "LINE_CONTINUATION_HEADING", false, false, true);
            case LINE_CONTINUATION_FRAME_HEADING ->
                    new FramedPredictor(new LineContinuation(), "LINE_CONTINUATION_FRAME_HEADING", false, true, true);
            // the residual predictor of a shared row is its plain one; the references are PipelineEncoding's
            case QUAD_CORNER_SHARED, QUAD_CORNER_SHARED_FRAME, LINE_CONTINUATION_SHARED,
                    LINE_CONTINUATION_SHARED_FRAME_HEADING -> plain().predictor();
        };
    }

    /**
     * A predictor whose plain-delta ring edges are coded in a frame the decoder predicts from the rings
     * it has decoded. Unlike a prediction, the frame rotates the <em>residual</em> ({@code R(v - p)}
     * instead of {@code v - p}), so {@link Segmentation} asks a {@link Pass} per vertex and applies it.
     * One instance serves one buffer, like {@link SideValues}: it remembers the runs of the last layout
     * it classified.
     */
    public interface FramedEdges extends ClassifiedPredictor {

        /** A fresh pass over the buffer last classified; one for encoding, one for decoding. */
        Pass pass();

        /** The frame state of one pass; vertices are visited in order, {@link #accept} after each. */
        interface Pass {

            /** Whether the residual of vertex {@code i} is rotated into the frame. */
            boolean framed(int i);

            /** Rotates a residual into the current frame; result in {@link #x()}, {@link #y()}. */
            void forward(int dx, int dy);

            /** The exact inverse of {@link #forward}. */
            void inverse(int rx, int ry);

            int x();

            int y();

            /** Vertex {@code i} is known ({@code vertices} holds at least {@code 0..i}). */
            void accept(int i, List<Vertex> vertices);
        }
    }

    /**
     * Any predictor plus the frame: vertices of the framed kinds of run (polygon rings, line parts, or
     * both) that the inner predictor codes as plain delta, other than a run's start, are framed. Runs
     * and ring/line are derived from the topology streams alone, as {@code SharedVertexRef} does; a
     * buffer whose runs are not derivable (or a single run) is not framed.
     */
    private static final class FramedPredictor implements FramedEdges {

        private final ClassifiedPredictor inner;
        private final String name;
        private final boolean framesRings;
        private final boolean framesLines;
        private final boolean heading;

        private int[] starts = new int[0];
        private boolean[] ring = new boolean[0];
        private boolean[] line = new boolean[0];

        /** Per run, whether it is of a framed kind (a ring or a line part, as constructed). */
        private boolean[] framedRun = new boolean[0];
        private int[] classes = new int[0];
        private int vertexCount;

        FramedPredictor(
                ClassifiedPredictor inner, String name, boolean framesRings, boolean framesLines, boolean heading) {
            this.inner = inner;
            this.name = name;
            this.framesRings = framesRings;
            this.framesLines = framesLines;
            this.heading = heading;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public int[] classify(BufferContext ctx, int vertexCount) {
            classes = inner.classify(ctx, vertexCount);
            this.vertexCount = vertexCount;
            final var runs = PartBoundaries.of(ctx, vertexCount);
            final var isLine = runs.length < 2 ? null : PartBoundaries.lineRuns(ctx, runs.length);
            starts = runs;
            ring = new boolean[runs.length];
            line = new boolean[runs.length];
            framedRun = new boolean[runs.length];
            if (isLine != null) {
                for (var r = 0; r < runs.length; r++) {
                    ring[r] = !isLine[r];
                    line[r] = isLine[r];
                    framedRun[r] = ring[r] ? framesRings : framesLines;
                }
            }
            return classes;
        }

        @Override
        public int classCount() {
            return inner.classCount();
        }

        @Override
        public int[] regionOrder() {
            return inner.regionOrder();
        }

        @Override
        public boolean hasPrediction(int vertexClass) {
            return inner.hasPrediction(vertexClass);
        }

        @Override
        public int predictX(int vertexClass, int i, List<Vertex> vertices) {
            return inner.predictX(vertexClass, i, vertices);
        }

        @Override
        public int predictY(int vertexClass, int i, List<Vertex> vertices) {
            return inner.predictY(vertexClass, i, vertices);
        }

        @Override
        public Pass pass() {
            return new FramePass();
        }

        private final class FramePass implements Pass {

            private final RingFramePrior prior = new RingFramePrior();
            private int run;

            /** The last edge accepted, the heading of the vertex after it. */
            private int edgeX;
            private int edgeY;

            /** Whether the vertex last asked about is rotated by the heading of the edge before it. */
            private boolean byHeading;

            private void advance(int i) {
                while (run + 1 < starts.length && starts[run + 1] <= i) {
                    run++;
                }
            }

            @Override
            public boolean framed(int i) {
                advance(i);
                final var predicted = inner.hasPrediction(classes[i]);
                // a continued vertex has two vertices of its own run before it, so a heading
                byHeading = heading && line[run] && predicted && i >= starts[run] + 2;
                return byHeading || framedRun[run] && i > starts[run] && !predicted;
            }

            @Override
            public void forward(int dx, int dy) {
                if (byHeading) {
                    prior.forwardHeading(edgeX, edgeY, dx, dy);
                } else {
                    prior.forward(dx, dy);
                }
            }

            @Override
            public void inverse(int rx, int ry) {
                if (byHeading) {
                    prior.inverseHeading(edgeX, edgeY, rx, ry);
                } else {
                    prior.inverse(rx, ry);
                }
            }

            @Override
            public int x() {
                return prior.x();
            }

            @Override
            public int y() {
                return prior.y();
            }

            @Override
            public void accept(int i, List<Vertex> vertices) {
                advance(i);
                if (i > 0) {
                    edgeX = vertices.get(i).x() - vertices.get(i - 1).x();
                    edgeY = vertices.get(i).y() - vertices.get(i - 1).y();
                }
                if (!framedRun[run]) {
                    return;
                }
                final var start = starts[run];
                if (i == start) {
                    prior.beginRing(vertices.get(i));
                } else {
                    final var previous = vertices.get(i - 1);
                    prior.edge(vertices.get(i).x() - previous.x(), vertices.get(i).y() - previous.y());
                }
                final var end = run + 1 < starts.length ? starts[run + 1] : vertexCount;
                if (i == end - 1) {
                    if (ring[run]) {
                        prior.endRing(vertices, start, end);
                    } else {
                        prior.endLine(vertices, start, end);
                    }
                }
            }
        }
    }

    /**
     * A predictor whose residual needs one extra value chosen by the encoder: the class's vertices
     * write it to a stream of their own (one value per such vertex, vertex order), and the decoder
     * reads it back before predicting. {@link Segmentation} appends that stream after the residual
     * streams.
     */
    public interface SideValues extends ClassifiedPredictor {

        /** Whether vertices of {@code vertexClass} carry a side value. */
        boolean hasSide(int vertexClass);

        /** The side value of vertex {@code i}; encoder only, reads {@code vertices} up to and including {@code i}. */
        int side(int vertexClass, int i, List<Vertex> vertices);

        /** Predicted x of a vertex that carries {@code side}; reads {@code vertices} below {@code i} only. */
        int predictX(int vertexClass, int i, List<Vertex> vertices, int side);

        /** Predicted y, see {@link #predictX(int, int, List, int)}. */
        int predictY(int vertexClass, int i, List<Vertex> vertices, int side);
    }

    /**
     * The old {@code TURN_SCALAR}: classes START, INTERIOR, CORNER and TURN, from the topology alone.
     * A turn vertex is predicted at {@code p1 + round(along * u / |u|)}, with {@code u} the previous
     * edge rotated 90 degrees (counter-clockwise on outer rings, clockwise on inner rings) and {@code
     * along} the side value; the residual is the old correction. Every other class is delta or the
     * parallelogram corner.
     */
    private static final class TurnScalarPredictor implements SideValues {

        /** Winding of every vertex of the last classified buffer; the class alone does not carry it. */
        private boolean[] ccw = new boolean[0];

        @Override
        public String name() {
            return "TURN_SCALAR";
        }

        @Override
        public int[] classify(BufferContext ctx, int vertexCount) {
            final var classes = TurnScalar.classify(ctx, vertexCount);
            ccw = classes.ccw();
            return classes.classes();
        }

        @Override
        public int classCount() {
            return TurnScalar.TURN + 1;
        }

        @Override
        public int[] regionOrder() {
            return new int[]{TurnScalar.START, TurnScalar.INTERIOR, TurnScalar.CORNER, TurnScalar.TURN};
        }

        @Override
        public boolean hasPrediction(int vertexClass) {
            return vertexClass == TurnScalar.CORNER || vertexClass == TurnScalar.TURN;
        }

        @Override
        public boolean hasSide(int vertexClass) {
            return vertexClass == TurnScalar.TURN;
        }

        @Override
        public int side(int vertexClass, int i, List<Vertex> vertices) {
            final var p1 = vertices.get(i - 1);
            final var ux = ux(i, vertices);
            final var uy = uy(i, vertices);
            final var len = Math.hypot(ux, uy);
            if (len == 0) {
                return 0;
            }
            final var wx = vertices.get(i).x() - p1.x();
            final var wy = vertices.get(i).y() - p1.y();
            return (int) Math.round(((double) ux * wx + (double) uy * wy) / len);
        }

        @Override
        public int predictX(int vertexClass, int i, List<Vertex> vertices) {
            if (vertexClass != TurnScalar.CORNER) {
                throw new UnsupportedOperationException("class " + vertexClass + " carries a side value");
            }
            return TurnScalar.cornerX(i, vertices);
        }

        @Override
        public int predictY(int vertexClass, int i, List<Vertex> vertices) {
            if (vertexClass != TurnScalar.CORNER) {
                throw new UnsupportedOperationException("class " + vertexClass + " carries a side value");
            }
            return TurnScalar.cornerY(i, vertices);
        }

        @Override
        public int predictX(int vertexClass, int i, List<Vertex> vertices, int side) {
            final var u = ux(i, vertices);
            return vertices.get(i - 1).x() + TurnScalar.scaled(side, u, Math.hypot(u, uy(i, vertices)));
        }

        @Override
        public int predictY(int vertexClass, int i, List<Vertex> vertices, int side) {
            final var u = uy(i, vertices);
            return vertices.get(i - 1).y() + TurnScalar.scaled(side, u, Math.hypot(ux(i, vertices), u));
        }

        private int ux(int i, List<Vertex> vertices) {
            return TurnScalar.ux(ccw[i], vertices.get(i - 1), vertices.get(i - 2));
        }

        private int uy(int i, List<Vertex> vertices) {
            return TurnScalar.uy(ccw[i], vertices.get(i - 1), vertices.get(i - 2));
        }
    }

    /** First-order delta only: one class, never a geometric prediction. */
    private static final class Delta implements ClassifiedPredictor {

        @Override
        public String name() {
            return "DELTA";
        }

        @Override
        public int[] classify(BufferContext ctx, int vertexCount) {
            return new int[vertexCount];
        }

        @Override
        public int classCount() {
            return 1;
        }

        @Override
        public int[] regionOrder() {
            return new int[]{0};
        }

        @Override
        public boolean hasPrediction(int vertexClass) {
            return false;
        }

        @Override
        public int predictX(int vertexClass, int i, List<Vertex> vertices) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int predictY(int vertexClass, int i, List<Vertex> vertices) {
            throw new UnsupportedOperationException();
        }
    }
}
