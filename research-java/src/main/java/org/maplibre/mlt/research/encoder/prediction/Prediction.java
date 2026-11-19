package org.maplibre.mlt.research.encoder.prediction;

import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoder.prediction.mechanism.ClassifiedPredictor;
import org.maplibre.mlt.research.buffer.VertexRuns;
import org.maplibre.mlt.research.buffer.Encoding.BufferContext;
import org.maplibre.mlt.research.encoder.prediction.mechanism.LineContinuation;
import org.maplibre.mlt.research.encoder.reference.LineReferences;
import org.maplibre.mlt.research.encoder.prediction.mechanism.FramePrior;
import org.maplibre.mlt.research.encoder.prediction.mechanism.SegmentQuadCorner;
import org.maplibre.mlt.research.encoder.reference.RingReferences;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 * S2: prediction. Turns absolute coordinates into signed residuals. The delta chain is continuous
 * across part and feature boundaries: a vertex without a geometric prediction is predicted by the
 * previous vertex of the buffer, whatever feature it belongs to.
 *
 * <p>Predictors are the ones that worked in the old pipeline, reused unchanged.
 */
public enum Prediction {

    /** Componentwise first-order delta, the baseline. */
    DELTA(0),

    /** Fourth corner of the previous three in a quad ring (buildings), old {@code QUAD_CORNER}. */
    QUAD_CORNER(1),

    /** Line parts continue the previous segment (roads), old {@code LINE_CONTINUATION}. */
    LINE_CONTINUATION(2),

    /**
     * {@code QUAD_CORNER}, with the plain-delta edges of polygon rings coded in the frame of the
     * nearest decoded ring ({@link FramePrior}): an exactly invertible integer rotation of the
     * residual, so the stream keeps two values per vertex and nothing is signalled.
     */
    QUAD_CORNER_FRAME(5),

    /**
     * {@code QUAD_CORNER} plus references to vertices an earlier polygon ring shares ({@link
     * RingReferences}, old {@code QUAD_CORNER_SHARED}): rings are rotated to start where a shared
     * stretch begins, and a referenced vertex has no residual. Which vertices are referenced is only
     * known while decoding, so the buffer carries the flag and index bits and the stream lengths.
     * A buffer without a gain from the references is written as plain {@link #QUAD_CORNER} (the
     * predictor field of the header says which), so a layer without shared vertices pays nothing.
     */
    QUAD_CORNER_SHARED(7),

    /** {@link #QUAD_CORNER_SHARED} with the ring frame of {@link #QUAD_CORNER_FRAME}. */
    QUAD_CORNER_SHARED_FRAME(8),

    /**
     * {@link #LINE_CONTINUATION} with the frame of {@link #QUAD_CORNER_FRAME} on line parts, and the
     * continuation residual of a continued vertex rotated into the direction of the incoming edge: an
     * along-track component (the step got longer or shorter) and a sideways one (the road bent),
     * instead of both mixed over the tile axes. The plain-delta edges of a line run other than its
     * start (the 2nd vertex of every run, all of a run the continuation gate leaves alone) are coded
     * in the frame of the nearest decoded run, refined by the run's own decoded edges; the heading
     * frame is the two vertices before, so the decoder has it. Both rotations are exact integer
     * bijections ({@link FramePrior#forwardHeading}) on disjoint vertices and nothing is signalled.
     */
    LINE_CONTINUATION_FRAME_HEADING(11),

    /**
     * {@link #LINE_CONTINUATION_FRAME_HEADING} plus references to the positions earlier line parts hold
     * ({@link LineReferences}, old {@code LINE_CONTINUATION_SHARED_DICT}): an entry and an exit flag at
     * the ends of every line part, and a bit-packed index into the dictionary of earlier positions in
     * place of the residual on a hit. A part whose end is in the dictionary and whose start is not is
     * reversed, which changes line direction. Polygon rings and points keep their plain residuals;
     * the frame and heading rotations act on the vertices the references leave, which still count as
     * decoded for them. A buffer the references do not shrink is written as plain {@link
     * #LINE_CONTINUATION_FRAME_HEADING}, so a layer without junctions pays nothing.
     */
    LINE_CONTINUATION_SHARED_FRAME_HEADING(13),

    /**
     * {@link #QUAD_CORNER_FRAME} with the integer frame ({@link FramePrior#FramePrior(boolean)}): the
     * same prior from the nearest decoded ring and the same rotation of the residual, with no
     * trigonometry on the decoder's side. The residuals differ from the float frame's in the last unit
     * of a rotation, so it is its own predictor, not a replacement of the code 5.
     */
    QUAD_CORNER_IFRAME(16),

    /** {@link #QUAD_CORNER_SHARED} with the integer ring frame ({@link #QUAD_CORNER_IFRAME}). */
    QUAD_CORNER_SHARED_IFRAME(17),

    /** {@link #LINE_CONTINUATION_FRAME_HEADING} with the integer frame and the integer heading. */
    LINE_CONTINUATION_IFRAME_HEADING(18),

    /** {@link #LINE_CONTINUATION_SHARED_FRAME_HEADING} with the integer frame and heading. */
    LINE_CONTINUATION_SHARED_IFRAME_HEADING(19),

    /**
     * {@link #QUAD_CORNER_IFRAME} with the frame of the <em>previous ring</em> instead of the nearest
     * one refined by the ring's own walls ({@link FramePrior#previousRun()}): no neighbour search, no
     * per-edge vote, one frame index per ring. Meant to follow a Hilbert reorder, where the previous ring
     * is a spatial neighbour; the reorder is not part of the payload, so nothing here enforces it. Retired
     * ({@link #retiredRingFrame()}): out of {@link #grid()} and the per-layer defaults, still decodable
     * (docs/results/previous-frame.md).
     */
    QUAD_CORNER_PREVFRAME(20),

    /**
     * {@link #QUAD_CORNER_PREVFRAME} with the ring's own decoded edges voting on top of the previous
     * ring's frame, as {@link #QUAD_CORNER_IFRAME} does on top of the nearest ring's ({@link
     * FramePrior#previousRunVoting()}): the control that separates what the neighbour search buys from
     * what the votes buy.
     */
    QUAD_CORNER_PREVFRAME_VOTE(21),

    /**
     * One vote per ring ({@link FramePrior#previousRunFirstEdge()}): the first edge of a ring in the previous
     * ring's frame, the edges after it in the frame of the ring's own first edge. The ring is remembered by
     * the votes of all its edges.
     */
    QUAD_CORNER_PREVFRAME_FIRST(22),

    /**
     * {@link #QUAD_CORNER_PREVFRAME_FIRST} with the ring remembered by its first edge as well ({@link
     * FramePrior#previousRunFirstEdgeOnly()}): a ring is voted for exactly once. The ring frame of the grid and of
     * the per-layer defaults, in the place of {@link #QUAD_CORNER_IFRAME}: it keeps 91% (Munich) and 82% (Tokyo) of
     * that frame's gain on {@code building} at about 40% of its decode overhead (docs/results/previous-frame.md).
     * Meant to follow a Hilbert reorder, as the layers that use it do.
     */
    QUAD_CORNER_PREVFRAME_FIRST_ONLY(23),

    /**
     * {@link #QUAD_CORNER_PREVFRAME_FIRST_ONLY} where the first edge votes on top of the previous ring's
     * frame (weight 50, as the prior of the nearest-ring frame) instead of replacing it ({@link
     * FramePrior#previousRunFirstEdgeBlended()}): a short first edge cannot override the neighbour's frame.
     */
    QUAD_CORNER_PREVFRAME_FIRST_BLEND(24),

    /**
     * {@link #QUAD_CORNER_SHARED} with the one-vote previous-ring frame of {@link
     * #QUAD_CORNER_PREVFRAME_FIRST_ONLY}: the shared-vertex references of the rings, and the frame of the
     * residuals the references leave. The references rotate rings to start where a shared stretch begins and
     * remove the referenced vertices from the streams; they still count as decoded for the frame, as under {@link
     * #QUAD_CORNER_SHARED_IFRAME}. A buffer the references do not shrink is written as plain {@link
     * #QUAD_CORNER_PREVFRAME_FIRST_ONLY}. The shared ring frame of the grid and of the per-layer defaults, in the
     * place of {@link #QUAD_CORNER_SHARED_IFRAME}.
     */
    QUAD_CORNER_SHARED_PREVFRAME_FIRST_ONLY(25);

    // Retired codes, free: 3 QUAD_CORNER_LINE, 4 TURN_SCALAR, 6 QUAD_CORNER_LINE_FRAME, 9 LINE_CONTINUATION_FRAME,
    // 10 LINE_CONTINUATION_HEADING, 12 LINE_CONTINUATION_SHARED (see encoder.retired and docs/results/tile-oracle.md).

    private final int code;

    Prediction(int code) {
        this.code = code;
    }

    /**
     * The value of the predictor field in the payload header. It is fixed per predictor, not the ordinal,
     * so retiring one leaves the codes of the others (and every payload written with them) as they were.
     */
    public int code() {
        return code;
    }

    /** The predictor with the header code {@code code}; a retired or unknown code is an error. */
    public static Prediction ofCode(int code) {
        for (final var value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("no predictor has the header code " + code + " (retired or unknown)");
    }

    /** Whether this predictor codes shared-vertex references (see {@link #QUAD_CORNER_SHARED}). */
    public boolean shared() {
        return plain() != this;
    }

    /**
     * Whether this predictor is one of the four of {@link #integerFrames()} (codes 16 to 19): the integer frame of the
     * nearest ring or line run. The one-vote frames (codes 23 and 25) use an integer frame too and are not among them.
     */
    public boolean integerFrame() {
        return switch (this) {
            case QUAD_CORNER_IFRAME, QUAD_CORNER_SHARED_IFRAME, LINE_CONTINUATION_IFRAME_HEADING,
                    LINE_CONTINUATION_SHARED_IFRAME_HEADING -> true;
            default -> false;
        };
    }

    /** Whether this predictor uses the float frame (codes 5, 8, 11, 13), superseded by the integer frame. */
    public boolean floatFrame() {
        return switch (this) {
            case QUAD_CORNER_FRAME, QUAD_CORNER_SHARED_FRAME, LINE_CONTINUATION_FRAME_HEADING,
                    LINE_CONTINUATION_SHARED_FRAME_HEADING -> true;
            default -> false;
        };
    }

    /**
     * Whether this predictor is a ring frame retired for now in favour of {@link #QUAD_CORNER_PREVFRAME_FIRST_ONLY}
     * and {@link #QUAD_CORNER_SHARED_PREVFRAME_FIRST_ONLY}: the float frame (codes 5, 8), the integer frame of the
     * nearest ring (16, 17) and the other previous-ring variants (20, 21, 22, 24). They stay decodable and writable
     * by name and are rows of the steps that measured them, but are in no grid, per-layer default or selector.
     */
    public boolean retiredRingFrame() {
        return switch (this) {
            case QUAD_CORNER_FRAME, QUAD_CORNER_SHARED_FRAME, QUAD_CORNER_IFRAME, QUAD_CORNER_SHARED_IFRAME,
                    QUAD_CORNER_PREVFRAME, QUAD_CORNER_PREVFRAME_VOTE, QUAD_CORNER_PREVFRAME_FIRST,
                    QUAD_CORNER_PREVFRAME_FIRST_BLEND -> true;
            default -> false;
        };
    }

    /**
     * The grid's predictors in the order the oracle iterates (a tie goes to the first): the ring frame is in the slot
     * the float and then the integer ring frame had, the line frames in those of their integer frame.
     */
    private static final List<Prediction> GRID = List.of(
            DELTA, QUAD_CORNER, LINE_CONTINUATION, QUAD_CORNER_PREVFRAME_FIRST_ONLY, QUAD_CORNER_SHARED,
            QUAD_CORNER_SHARED_PREVFRAME_FIRST_ONLY, LINE_CONTINUATION_IFRAME_HEADING,
            LINE_CONTINUATION_SHARED_IFRAME_HEADING);

    /**
     * The predictors of the grids ({@code --step best}, the fit, the selectors): eight, the one-vote ring frames
     * (codes 23 and 25) in the slots of the float and later the integer ring frame, the integer line frames (18, 19)
     * as before. The retired frames ({@link #retiredRingFrame()}) and the float line frames are measured by the steps
     * that name them, {@link #floatFrames()}, {@link #integerFrames()}.
     */
    public static List<Prediction> grid() {
        return GRID;
    }

    /** The index of this predictor in {@link #grid()}, {@code -1} when it is not a grid predictor. */
    public int gridIndex() {
        return GRID.indexOf(this);
    }

    /** The predictors with the integer frame. */
    public static List<Prediction> integerFrames() {
        return Arrays.stream(values()).filter(Prediction::integerFrame).toList();
    }

    /** The predictors with the float frame, out of the grids. */
    public static List<Prediction> floatFrames() {
        return Arrays.stream(values()).filter(Prediction::floatFrame).toList();
    }

    /** The predictor a buffer without references falls back to: itself unless {@link #shared()}. */
    public Prediction plain() {
        return switch (this) {
            case QUAD_CORNER_SHARED -> QUAD_CORNER;
            case QUAD_CORNER_SHARED_FRAME -> QUAD_CORNER_FRAME;
            case LINE_CONTINUATION_SHARED_FRAME_HEADING -> LINE_CONTINUATION_FRAME_HEADING;
            case QUAD_CORNER_SHARED_IFRAME -> QUAD_CORNER_IFRAME;
            case LINE_CONTINUATION_SHARED_IFRAME_HEADING -> LINE_CONTINUATION_IFRAME_HEADING;
            case QUAD_CORNER_SHARED_PREVFRAME_FIRST_ONLY -> QUAD_CORNER_PREVFRAME_FIRST_ONLY;
            default -> this;
        };
    }

    /**
     * The references of a buffer under this shared predictor, with its rings (or line parts) already
     * rotated (or reversed), or {@code null} when its runs are not derivable from the topology.
     * {@code ctx} holds the vertices.
     */
    public RingReferences.Scan scan(BufferContext ctx) {
        return switch (this) {
            case QUAD_CORNER_SHARED, QUAD_CORNER_SHARED_FRAME, QUAD_CORNER_SHARED_IFRAME,
                    QUAD_CORNER_SHARED_PREVFRAME_FIRST_ONLY -> RingReferences.scan(ctx);
            case LINE_CONTINUATION_SHARED_FRAME_HEADING, LINE_CONTINUATION_SHARED_IFRAME_HEADING ->
                    LineReferences.scan(ctx);
            default -> throw new UnsupportedOperationException(this + " has no references");
        };
    }

    /** The decoder's side of {@link #scan}: needs the topology streams and the flag and index bits. */
    public RingReferences.ReferenceWalk walkForDecoding(BufferContext topo, int vertexCount, byte[] flags, byte[] indices) {
        return switch (this) {
            case QUAD_CORNER_SHARED, QUAD_CORNER_SHARED_FRAME, QUAD_CORNER_SHARED_IFRAME,
                    QUAD_CORNER_SHARED_PREVFRAME_FIRST_ONLY ->
                    RingReferences.Walk.forDecoding(topo, vertexCount, flags, indices);
            case LINE_CONTINUATION_SHARED_FRAME_HEADING, LINE_CONTINUATION_SHARED_IFRAME_HEADING ->
                    LineReferences.walkForDecoding(topo, vertexCount, flags, indices);
            default -> throw new UnsupportedOperationException(this + " has no references");
        };
    }

    /**
     * The predictor behind this choice. Stateless across buffers, so a fresh instance per call is
     * cheap; the framed predictors remember the runs of the last layout they classified, so one
     * instance serves one buffer.
     */
    public ClassifiedPredictor predictor() {
        return switch (this) {
            case DELTA -> new Delta();
            case QUAD_CORNER -> new SegmentQuadCorner();
            case LINE_CONTINUATION -> new LineContinuation();
            case QUAD_CORNER_FRAME ->
                    new FramedPredictor(new SegmentQuadCorner(), "QUAD_CORNER_FRAME", true, false, false,
                            FramePrior::new);
            case LINE_CONTINUATION_FRAME_HEADING ->
                    new FramedPredictor(
                            new LineContinuation(), "LINE_CONTINUATION_FRAME_HEADING", false, true, true,
                            FramePrior::new);
            case QUAD_CORNER_IFRAME ->
                    new FramedPredictor(new SegmentQuadCorner(), "QUAD_CORNER_IFRAME", true, false, false,
                            () -> new FramePrior(true));
            case QUAD_CORNER_PREVFRAME ->
                    new FramedPredictor(new SegmentQuadCorner(), "QUAD_CORNER_PREVFRAME", true, false, false,
                            FramePrior::previousRun);
            case QUAD_CORNER_PREVFRAME_VOTE ->
                    new FramedPredictor(new SegmentQuadCorner(), "QUAD_CORNER_PREVFRAME_VOTE", true, false, false,
                            FramePrior::previousRunVoting);
            case QUAD_CORNER_PREVFRAME_FIRST ->
                    new FramedPredictor(new SegmentQuadCorner(), "QUAD_CORNER_PREVFRAME_FIRST", true, false, false,
                            FramePrior::previousRunFirstEdge);
            case QUAD_CORNER_PREVFRAME_FIRST_ONLY ->
                    new FramedPredictor(new SegmentQuadCorner(), "QUAD_CORNER_PREVFRAME_FIRST_ONLY", true, false, false,
                            FramePrior::previousRunFirstEdgeOnly);
            case QUAD_CORNER_PREVFRAME_FIRST_BLEND ->
                    new FramedPredictor(new SegmentQuadCorner(), "QUAD_CORNER_PREVFRAME_FIRST_BLEND", true, false,
                            false, FramePrior::previousRunFirstEdgeBlended);
            case LINE_CONTINUATION_IFRAME_HEADING ->
                    new FramedPredictor(
                            new LineContinuation(), "LINE_CONTINUATION_IFRAME_HEADING", false, true, true,
                            () -> new FramePrior(true));
            // the residual predictor of a shared row is its plain one; the references are PipelineEncoding's
            case QUAD_CORNER_SHARED, QUAD_CORNER_SHARED_FRAME, LINE_CONTINUATION_SHARED_FRAME_HEADING,
                    QUAD_CORNER_SHARED_IFRAME, LINE_CONTINUATION_SHARED_IFRAME_HEADING,
                    QUAD_CORNER_SHARED_PREVFRAME_FIRST_ONLY -> plain().predictor();
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
     * and ring/line are derived from the topology streams alone, as {@code RingReferences} does; a
     * buffer whose runs are not derivable (or a single run) is not framed.
     */
    private static final class FramedPredictor implements FramedEdges {

        private final ClassifiedPredictor inner;
        private final String name;
        private final boolean framesRings;
        private final boolean framesLines;
        private final boolean heading;
        private final Supplier<FramePrior> priors;

        private int[] starts = new int[0];
        private boolean[] ring = new boolean[0];
        private boolean[] line = new boolean[0];

        /** Per run, whether it is of a framed kind (a ring or a line part, as constructed). */
        private boolean[] framedRun = new boolean[0];
        private int[] classes = new int[0];
        private int vertexCount;

        FramedPredictor(
                ClassifiedPredictor inner,
                String name,
                boolean framesRings,
                boolean framesLines,
                boolean heading,
                Supplier<FramePrior> priors) {
            this.priors = priors;
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
            final var runs = VertexRuns.of(ctx, vertexCount);
            final var isLine = runs.length < 2 ? null : VertexRuns.lineRuns(ctx, runs.length);
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

            private final FramePrior prior = priors.get();
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
