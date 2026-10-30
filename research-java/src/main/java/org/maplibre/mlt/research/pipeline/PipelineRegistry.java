package org.maplibre.mlt.research.pipeline;

import org.maplibre.mlt.research.buffer.Encoding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * The rows of the pipeline, per implementation step. {@code ./gradlew research --args="measure --step s3"}
 * measures {@code step("s3")}; {@link #all()} is the round-trip surface the tests parameterize over.
 *
 * <p>Each step is measured over the winners of the steps before it ({@code AFTER_S0} ...) and varies
 * only its own stage, so re-running a step reproduces the figures of {@code
 * docs/results/openzl-pipeline.md} whatever was decided later. The last winner is {@link #DEFAULT}.
 */
public final class PipelineRegistry {

    private PipelineRegistry() {
    }

    /** S0: the split varint terminal (-0,87% gzip against plain varint), the harness anchor. */
    private static final PipelineConfig AFTER_S0 = PipelineConfig.IDENTITY.withWidth(ByteWidth.VARINT_SPLIT);

    /**
     * S1: Hilbert order of the bounding-box centre with ring rotation (-3,97% gzip of the geometry
     * buffers; applied to a layer only where its property columns do not lose more, see the S1
     * verdict).
     */
    private static final PipelineConfig AFTER_S1 = AFTER_S0.withReorder(Reorder.HILBERT_CENTROID_ROT_MP);

    /**
     * S2: {@code QUAD_CORNER_LINE}, the one predictor within 0,3 points of the best choice on every
     * layer (the per-layer choice is the selector's).
     */
    private static final PipelineConfig AFTER_S2 = AFTER_S1.withPredictor(Prediction.QUAD_CORNER_LINE);

    /** S3: one stream per predictor class (-1,2 points over FLAT); the role split and class sort lose. */
    private static final PipelineConfig AFTER_S3 = AFTER_S2.withSegmentation(Segmentation.CLASS);

    /** S4: zigzag. The bias map was measured and removed. */
    private static final PipelineConfig AFTER_S4 = AFTER_S3;

    /**
     * S6: the escape width (fixed-width bytes, {@code 0xFF} marks an exception): level with the
     * split varint under gzip and the terminal the S7 transposes need. {@code MIN_FIXED},
     * {@code FORCE_4} lose. S5 (GCD) was removed; this still follows S4.
     */
    private static final PipelineConfig AFTER_S6 = AFTER_S4.withWidth(ByteWidth.ESCAPE);

    /**
     * S7: byte planes of the interleaved values (all low bytes, then all high bytes): -0,6 points gzip
     * over the untransposed escape stream, -0,7 brotli, -0,9 zstd; the x/y-separating layouts lose.
     */
    private static final PipelineConfig AFTER_S7 = AFTER_S6.withTranspose(Transpose.INTERLEAVED_PLANES);

    /**
     * The best row measured on the geometry buffers alone: everything through S7, reorder included.
     * It is not the end-to-end choice, see {@link #DEFAULT}.
     */
    public static final PipelineConfig GEOMETRY_BEST = AFTER_S7;

    /**
     * S8: the end-to-end choice. The same pipeline without the S1 reorder: with the predictors and
     * class streams in place the reorder saves 3,0% of the geometry bytes (-323.000 gzip) but its
     * permutation of the property columns costs +736.000 on this corpus, a net loss everywhere
     * except {@code building} and {@code housenumber} (see the S8 verdict).
     */
    private static final PipelineConfig AFTER_S8 = AFTER_S7.withReorder(Reorder.NONE);

    /** What the selection loop builds on: the winners recorded so far. */
    public static final PipelineConfig DEFAULT = AFTER_S8;

    private static Encoding row(PipelineConfig config) {
        return new PipelineEncoding(config);
    }

    /**
     * Whether the Hilbert reorder may be used on this layer. It is allowed on every layer, so the
     * name is not consulted; the property-column cost that used to restrict it to {@code building}
     * and {@code housenumber} is no longer a gate (see the S8 verdict in
     * {@code docs/results/openzl-pipeline.md}).
     */
    @SuppressWarnings("unused")
    public static boolean reorderPays(String layer) {
        return true;
    }

    private static PipelineConfig composition(
            Prediction predictor, Segmentation segmentation, ByteWidth width, Transpose transpose) {
        return DEFAULT.withReorder(Reorder.HILBERT_CENTROID_ROT_MP).withPredictor(predictor)
                .withSegmentation(segmentation).withWidth(width).withTranspose(transpose);
    }

    /**
     * The fixed composition of each layer: per layer the one row of the {@link #fitRows fit grid} with the
     * smallest total over the layer's buffers, each compressed alone under gzip (the project's reference
     * metric), with {@link Reorder#HILBERT_CENTROID_ROT_MP} except on {@link #CHAIN_LAYERS} (the greedy
     * {@link Reorder#CHAIN}). Predictor, segmentation, terminal and reorder were fitted on the Munich
     * corpus with {@code ./gradlew research --args="measure --step fit"} ("best fixed composition per layer":
     * every predictor incl. the ring frame, shared references and heading x flat/class streams x the
     * three byte terminals x Hilbert or chain); {@code building} and {@code transportation} are as before
     * ({@code transportation} was fitted on geometry plus property columns). The chain layers are those
     * where {@code --step fit} picks it and {@code pipelineTileSize --modes NONE,HILBERT_CENTROID_ROT_MP,CHAIN
     * --by-layer} shows the chain's property columns no larger than Hilbert's net of the geometry gain:
     * {@code transportation_name} (+701 property bytes, -2.885 geometry), {@code waterway}, {@code water},
     * {@code aeroway} (smaller on both sides). The fit is geometry only; the property side is the
     * single-layer-tile measure above. A layer not listed takes {@link #DEFAULT}.
     */
    private static final java.util.Map<String, PipelineConfig> BY_LAYER = java.util.Map.ofEntries(
            // (the layers on CHAIN_LAYERS carry .withReorder(Reorder.CHAIN) below)
            java.util.Map.entry("place", composition(Prediction.QUAD_CORNER_LINE,
                    Segmentation.FLAT, ByteWidth.VARINT_SPLIT, Transpose.NONE)),
            // the greedy chain, not Hilbert, and the references to earlier line parts' positions: fitted on
            // geometry plus property columns compressed together (docs/results/line-shared-vertices.md)
            java.util.Map.entry("transportation", composition(Prediction.LINE_CONTINUATION_SHARED_FRAME_HEADING,
                    Segmentation.CLASS, ByteWidth.ESCAPE, Transpose.INTERLEAVED_PLANES)
                    .withReorder(Reorder.CHAIN)),
            java.util.Map.entry("landcover", composition(Prediction.QUAD_CORNER_SHARED_FRAME,
                    Segmentation.CLASS, ByteWidth.ESCAPE, Transpose.INTERLEAVED_PLANES)),
            java.util.Map.entry("transportation_name", composition(Prediction.LINE_CONTINUATION_SHARED_FRAME_HEADING,
                    Segmentation.FLAT, ByteWidth.VARINT_SPLIT, Transpose.NONE).withReorder(Reorder.CHAIN)),
            java.util.Map.entry("landuse", composition(Prediction.QUAD_CORNER_SHARED_FRAME,
                    Segmentation.CLASS, ByteWidth.ESCAPE, Transpose.INTERLEAVED_PLANES)),
            java.util.Map.entry("water", composition(Prediction.QUAD_CORNER_SHARED_FRAME,
                    Segmentation.CLASS, ByteWidth.ESCAPE, Transpose.NONE).withReorder(Reorder.CHAIN)),
            java.util.Map.entry("park", composition(Prediction.QUAD_CORNER_SHARED,
                    Segmentation.FLAT, ByteWidth.VARINT_SPLIT, Transpose.NONE)),
            java.util.Map.entry("boundary", composition(Prediction.LINE_CONTINUATION_SHARED_FRAME_HEADING,
                    Segmentation.FLAT, ByteWidth.VARINT_SPLIT, Transpose.NONE)),
            java.util.Map.entry("poi", composition(Prediction.DELTA,
                    Segmentation.CLASS, ByteWidth.ESCAPE, Transpose.INTERLEAVED_PLANES)),
            java.util.Map.entry("building", composition(Prediction.QUAD_CORNER_SHARED_FRAME,
                    Segmentation.CLASS, ByteWidth.ESCAPE, Transpose.NONE)),
            java.util.Map.entry("water_name", composition(Prediction.LINE_CONTINUATION,
                    Segmentation.FLAT, ByteWidth.VARINT_SPLIT, Transpose.NONE)),
            java.util.Map.entry("housenumber", composition(Prediction.QUAD_CORNER_LINE,
                    Segmentation.FLAT, ByteWidth.ESCAPE, Transpose.INTERLEAVED_PLANES)),
            java.util.Map.entry("waterway", composition(Prediction.LINE_CONTINUATION_SHARED_FRAME_HEADING,
                    Segmentation.FLAT, ByteWidth.VARINT_SPLIT, Transpose.NONE).withReorder(Reorder.CHAIN)),
            java.util.Map.entry("mountain_peak", composition(Prediction.LINE_CONTINUATION_HEADING,
                    Segmentation.FLAT, ByteWidth.VARINT_SPLIT, Transpose.NONE)),
            java.util.Map.entry("aeroway", composition(Prediction.QUAD_CORNER_SHARED_FRAME,
                    Segmentation.FLAT, ByteWidth.VARINT_SPLIT, Transpose.NONE).withReorder(Reorder.CHAIN)),
            java.util.Map.entry("aerodrome_label", composition(Prediction.DELTA,
                    Segmentation.FLAT, ByteWidth.VARINT_SPLIT, Transpose.NONE)));

    /** The layers whose composition uses the greedy chain reorder instead of Hilbert (see {@link #BY_LAYER}). */
    public static final java.util.Set<String> CHAIN_LAYERS =
            java.util.Set.of("transportation", "transportation_name", "waterway", "water", "aeroway");

    /**
     * The pipeline's default: one fixed composition per layer, {@link #DEFAULT} for a layer without an
     * entry (a layer the fit has not seen). The decoder knows the layer, so the choice needs no
     * signalling beyond the buffer header; the reorder is the one choice the header does not carry, and
     * every fitted layer reorders ({@link Reorder#HILBERT_CENTROID_ROT_MP}, or {@link Reorder#CHAIN} for
     * {@link #CHAIN_LAYERS}).
     */
    public static PipelineConfig forLayer(String layer) {
        return BY_LAYER.getOrDefault(layer, DEFAULT);
    }

    /** The layers with a fitted composition. */
    public static java.util.Set<String> fittedLayers() {
        return BY_LAYER.keySet();
    }

    /** One stage of {@link #ladder()}: the label of the decision and the pipeline with it applied. */
    public record Stage(String label, PipelineConfig config) {
    }

    /**
     * The pipeline built up decision by decision, each stage the previous one plus one step's winner:
     * plain varint, S0 split varint, S1 reorder, S2 predictor, S3 class streams, S6 escape width, S7 byte
     * planes, then S8 (the reorder dropped end to end). S4 (zigzag) changes nothing, so it has no stage.
     * Every stage is also a row of a step, hence round-tripped by the tests.
     */
    public static List<Stage> ladder() {
        return List.of(
                new Stage("identity (plain varint)", PipelineConfig.IDENTITY),
                new Stage("S0 split varint", AFTER_S0),
                new Stage("S1 + Hilbert reorder", AFTER_S1),
                new Stage("S2 + QUAD_CORNER_LINE", AFTER_S2),
                new Stage("S3 + class streams", AFTER_S3),
                new Stage("S6 + escape width", AFTER_S6),
                new Stage("S7 + byte planes (GEOMETRY_BEST)", AFTER_S7),
                new Stage("S8 reorder dropped (DEFAULT)", AFTER_S8));
    }

    /** The step names, in order. */
    public static List<String> steps() {
        return List.of("s0", "s1", "s2", "s3", "s4", "s6", "s7", "s8", "selector", "turn", "frame", "framelayers",
                "shared", "framelines", "linesshared");
    }

    /**
     * {@code TURN_SCALAR} against {@code QUAD_CORNER} on the same terminal (S8's {@link #DEFAULT}),
     * per segmentation, without and then with the {@link Reorder#HILBERT_CENTROID_ROT_MP} reorder,
     * then on the {@code building} layer's own composition ({@link #forLayer}: Hilbert reorder,
     * class streams, escape width). The side value (the edge-length scalar) is a stream of its own
     * under every segmentation, so the rows show what separating the near-zero corrections from the
     * scalars is worth, and what the reorder adds on top.
     */
    private static List<Encoding> turnRows() {
        final var rows = new ArrayList<Encoding>();
        for (final var reorder : List.of(Reorder.NONE, Reorder.HILBERT_CENTROID_ROT_MP)) {
            for (final var predictor : List.of(Prediction.QUAD_CORNER, Prediction.TURN_SCALAR)) {
                for (final var segmentation : List.of(Segmentation.FLAT, Segmentation.CLASS, Segmentation.ROLE_CLASS)) {
                    rows.add(row(DEFAULT.withReorder(reorder).withPredictor(predictor).withSegmentation(segmentation)));
                }
            }
        }
        // forLayer("building") uses the ring frame and shared references; QUAD_CORNER is the plain reference
        final var building = forLayer("building");
        rows.add(row(building.withPredictor(Prediction.QUAD_CORNER)));
        rows.add(row(building.withPredictor(Prediction.TURN_SCALAR)));
        return rows;
    }

    /**
     * The ring frame beside its plain predictor on the {@code building} layer: {@code QUAD_CORNER}
     * against {@code QUAD_CORNER_FRAME}, per segmentation, without and then with the
     * {@link Reorder#HILBERT_CENTROID_ROT_MP} reorder, then the frame on the layer's own composition
     * ({@link #forLayer}, which uses the frame) with the other terminals and with the line predictor. The
     * rows after the grid are the previous default ({@code QUAD_CORNER}), the current one, then variants. Rows that equal a grid row (the planes terminal is
     * {@link #DEFAULT}'s) are not repeated: a duplicate name is summed twice by the benchmark.
     */
    private static List<Encoding> frameRows() {
        final var rows = new ArrayList<Encoding>();
        for (final var reorder : List.of(Reorder.NONE, Reorder.HILBERT_CENTROID_ROT_MP)) {
            for (final var predictor : List.of(Prediction.QUAD_CORNER, Prediction.QUAD_CORNER_FRAME)) {
                for (final var segmentation : List.of(Segmentation.FLAT, Segmentation.CLASS, Segmentation.ROLE_CLASS)) {
                    rows.add(row(DEFAULT.withReorder(reorder).withPredictor(predictor).withSegmentation(segmentation)));
                }
            }
        }
        // forLayer("building") has the shared references on top of the frame since the shared step; the framed
        // row is the one the frame step was fitted on, the plain predictor the one before it
        final var building = forLayer("building");
        rows.add(row(building.withPredictor(Prediction.QUAD_CORNER)));
        rows.add(row(building.withPredictor(Prediction.QUAD_CORNER_FRAME)));
        rows.add(row(building.withPredictor(Prediction.QUAD_CORNER_LINE_FRAME)));
        rows.add(row(building.withPredictor(Prediction.QUAD_CORNER_FRAME).withWidth(ByteWidth.VARINT_SPLIT)));
        rows.add(row(building.withPredictor(Prediction.QUAD_CORNER_FRAME).withReorder(Reorder.HILBERT_CENTROID_ROT)));
        return rows;
    }

    /**
     * The shared-vertex references on the {@code building} layer: {@code QUAD_CORNER} and its ring
     * frame against {@code QUAD_CORNER_SHARED} and {@code QUAD_CORNER_SHARED_FRAME}, flat and class
     * streams, without and then with the {@link Reorder#HILBERT_CENTROID_ROT_MP} reorder, on {@link
     * #DEFAULT}'s terminal (escape width, byte planes) and on the terminal {@code building} uses
     * (escape width, no planes). The reorder rotates every ring to start nearest the previous
     * vertex; the references then rotate the rings that share a wall to start where it begins, so
     * the order of the two is what the reorder rows measure.
     */
    private static List<Encoding> sharedRows() {
        final var rows = new ArrayList<Encoding>();
        for (final var terminal : List.of(DEFAULT, forLayer("building"))) {
            for (final var reorder : List.of(Reorder.NONE, Reorder.HILBERT_CENTROID_ROT_MP)) {
                for (final var predictor : List.of(Prediction.QUAD_CORNER, Prediction.QUAD_CORNER_FRAME,
                        Prediction.QUAD_CORNER_SHARED, Prediction.QUAD_CORNER_SHARED_FRAME)) {
                    for (final var segmentation : List.of(Segmentation.FLAT, Segmentation.CLASS)) {
                        rows.add(row(terminal.withReorder(reorder).withPredictor(predictor)
                                .withSegmentation(segmentation)));
                    }
                }
            }
        }
        return rows;
    }

    /**
     * The ring frame on every layer, not just {@code building}: the four quad-corner predictors
     * (plain and framed, with and without the line rule) under each (segmentation, width, transpose) the
     * fitted layers use, all with {@link Reorder#HILBERT_CENTROID_ROT_MP}. Read the by-layer table: a layer
     * compares the rows of its own terminal. The rows are layer-independent, so a layer without rings
     * (or whose predictor is not a quad-corner one) shows the frame doing nothing.
     */
    private static List<Encoding> frameLayerRows() {
        final var rows = new ArrayList<Encoding>();
        final var terminals = List.of(
                composition(Prediction.QUAD_CORNER, Segmentation.CLASS, ByteWidth.ESCAPE,
                        Transpose.INTERLEAVED_PLANES),
                composition(Prediction.QUAD_CORNER, Segmentation.CLASS, ByteWidth.ESCAPE, Transpose.NONE),
                composition(Prediction.QUAD_CORNER, Segmentation.CLASS, ByteWidth.VARINT_SPLIT, Transpose.NONE),
                composition(Prediction.QUAD_CORNER, Segmentation.FLAT, ByteWidth.VARINT_SPLIT, Transpose.NONE));
        for (final var terminal : terminals) {
            for (final var predictor : List.of(Prediction.QUAD_CORNER, Prediction.QUAD_CORNER_FRAME,
                    Prediction.QUAD_CORNER_LINE, Prediction.QUAD_CORNER_LINE_FRAME)) {
                rows.add(row(terminal.withPredictor(predictor)));
            }
        }
        return rows;
    }

    /**
     * The frame on line parts: {@code LINE_CONTINUATION} against {@code LINE_CONTINUATION_FRAME}, the
     * heading rotation of the continued vertices ({@code _HEADING}) and both ({@code _FRAME_HEADING}), per
     * segmentation, without and then with the {@link Reorder#HILBERT_CENTROID_ROT_MP} reorder, on the
     * terminal {@code transportation} uses (escape width, byte planes: {@link #forLayer}) and on the
     * one {@code transportation_name} and {@code waterway} use (split varint, no planes). The rows are
     * layer-independent, so read the by-layer table: a layer without line parts shows the frame doing
     * nothing. The {@code transportation} row of the current default is the Hilbert, class-stream one.
     */
    private static List<Encoding> frameLineRows() {
        final var rows = new ArrayList<Encoding>();
        final var terminals = List.of(
                forLayer("transportation"),
                forLayer("waterway"));
        for (final var terminal : terminals) {
            for (final var reorder : List.of(Reorder.NONE, Reorder.HILBERT_CENTROID_ROT_MP)) {
                for (final var predictor : List.of(Prediction.LINE_CONTINUATION, Prediction.LINE_CONTINUATION_FRAME,
                        Prediction.LINE_CONTINUATION_HEADING, Prediction.LINE_CONTINUATION_FRAME_HEADING)) {
                    for (final var segmentation : List.of(Segmentation.FLAT, Segmentation.CLASS)) {
                        rows.add(row(terminal.withReorder(reorder).withPredictor(predictor)
                                .withSegmentation(segmentation)));
                    }
                }
            }
        }
        return rows;
    }

    /**
     * The references to earlier line parts' positions ({@link Prediction#LINE_CONTINUATION_SHARED}) beside
     * their plain predictors: {@code LINE_CONTINUATION} and the frame and heading one {@code transportation}
     * uses, each without and with the references, per segmentation, without and then with the
     * {@link Reorder#HILBERT_CENTROID_ROT_MP} reorder, on the terminal {@code transportation} uses (escape
     * width, byte planes: {@link #forLayer}) and on the one {@code transportation_name} and {@code
     * waterway} use (split varint, no planes). The rows are layer-independent, so read the by-layer table:
     * a layer without line parts shows the references doing nothing.
     */
    private static List<Encoding> lineSharedRows() {
        final var rows = new ArrayList<Encoding>();
        final var terminals = List.of(
                forLayer("transportation"),
                forLayer("waterway"));
        for (final var terminal : terminals) {
            for (final var reorder : List.of(Reorder.NONE, Reorder.HILBERT_CENTROID_ROT_MP)) {
                for (final var predictor : List.of(
                        Prediction.LINE_CONTINUATION, Prediction.LINE_CONTINUATION_SHARED,
                        Prediction.LINE_CONTINUATION_FRAME_HEADING,
                        Prediction.LINE_CONTINUATION_SHARED_FRAME_HEADING)) {
                    for (final var segmentation : List.of(Segmentation.FLAT, Segmentation.CLASS)) {
                        rows.add(row(terminal.withReorder(reorder).withPredictor(predictor)
                                .withSegmentation(segmentation)));
                    }
                }
            }
        }
        // the old pipeline's greedy nearest-endpoint chaining as the reorder, class streams only, on the
        // transportation terminal: chaining already turns junction starts into short jumps, so the
        // references have less to catch (it never reverses a part)
        for (final var reorder : List.of(Reorder.CHAIN, Reorder.CANON_CHAIN)) {
            for (final var predictor : List.of(
                    Prediction.LINE_CONTINUATION_FRAME_HEADING,
                    Prediction.LINE_CONTINUATION_SHARED_FRAME_HEADING)) {
                rows.add(row(forLayer("transportation").withReorder(reorder).withPredictor(predictor)
                        .withSegmentation(Segmentation.CLASS)));
            }
        }
        return rows;
    }

    /**
     * The grid {@link #forLayer} is fitted on: every predictor (the ring frame, shared references and
     * heading rotation included; {@code TURN_SCALAR} is left out, it lost) x flat or class streams x the
     * three byte terminals x the given reorders ({@code --step fit}: {@link Reorder#HILBERT_CENTROID_ROT_MP}
     * and the greedy {@link Reorder#CHAIN}; {@code --step fithilbert}: Hilbert only).
     * The oracle grid has none of the later predictors, so its per-layer winners cannot replace the
     * fitted compositions. Run {@code ./gradlew research --args="measure --step fit"}: it implies {@code --oracle},
     * and the "best fixed composition per layer" table is the fit. Not part of {@link #all()}.
     */
    static List<Encoding> fitRows(List<Reorder> reorders) {
        final var rows = new ArrayList<Encoding>();
        for (final var reorder : reorders) {
            for (final var predictor : Prediction.values()) {
                if (predictor == Prediction.TURN_SCALAR) {
                    continue;
                }
                for (final var segmentation : List.of(Segmentation.FLAT, Segmentation.CLASS)) {
                    rows.add(row(DEFAULT.withReorder(reorder).withPredictor(predictor)
                            .withSegmentation(segmentation).withWidth(ByteWidth.ESCAPE)
                            .withTranspose(Transpose.INTERLEAVED_PLANES)));
                    rows.add(row(DEFAULT.withReorder(reorder).withPredictor(predictor)
                            .withSegmentation(segmentation).withWidth(ByteWidth.ESCAPE)
                            .withTranspose(Transpose.NONE)));
                    rows.add(row(DEFAULT.withReorder(reorder).withPredictor(predictor)
                            .withSegmentation(segmentation).withWidth(ByteWidth.VARINT_SPLIT)
                            .withTranspose(Transpose.NONE)));
                }
            }
        }
        return rows;
    }

    /**
     * The compositions the oracle chooses among: every combination of the decisions that a buffer's
     * content can change the answer of, not the one-at-a-time variants of the steps. Reorder (none or
     * Hilbert) x predictor x class streams or not x byte terminal (escape with planes, escape, split
     * varint) = 48 rows, the {@link Selector}'s grid with the reorder added. The first row is {@link
     * #DEFAULT}, so a tie goes to it.
     */
    public static List<Encoding> oracle() {
        final var rows = new ArrayList<Encoding>();
        for (final var config : oracleConfigs()) {
            rows.add(row(config));
        }
        return rows;
    }

    /**
     * The grid the per-(tile, layer) oracle of {@code --step best} chooses among: every predictor
     * ({@code TURN_SCALAR} included) x flat or class streams x the three byte terminals (escape with
     * planes, escape, split varint) x the three reorders the registry uses ({@link Reorder#NONE},
     * {@link Reorder#HILBERT_CENTROID_ROT_MP}, {@link Reorder#CHAIN}) = 252 compositions, each on {@link
     * #DEFAULT}'s residual map. It holds {@link #DEFAULT} and every {@link #forLayer} composition. Not part
     * of {@link #all()}, so the golden dump does not grow; {@code PipelineEncodingTest} round-trips it.
     */
    public static List<PipelineConfig> bestGrid() {
        final var configs = new ArrayList<PipelineConfig>();
        for (final var reorder : List.of(Reorder.NONE, Reorder.HILBERT_CENTROID_ROT_MP, Reorder.CHAIN)) {
            for (final var predictor : Prediction.values()) {
                for (final var segmentation : List.of(Segmentation.FLAT, Segmentation.CLASS)) {
                    final var base = DEFAULT.withReorder(reorder).withPredictor(predictor).withSegmentation(segmentation);
                    configs.add(base.withWidth(ByteWidth.ESCAPE).withTranspose(Transpose.INTERLEAVED_PLANES));
                    configs.add(base.withWidth(ByteWidth.ESCAPE).withTranspose(Transpose.NONE));
                    configs.add(base.withWidth(ByteWidth.VARINT_SPLIT).withTranspose(Transpose.NONE));
                }
            }
        }
        return configs;
    }

    /** The reorder variants S1 measures, the identity first. */
    public static List<Reorder> s1Orders() {
        return Arrays.stream(Reorder.values())
                .filter(order -> order != Reorder.CLASS_HILBERT_CENTROID_ROT)
                .toList();
    }

    /** The trial set, the default pipeline's choices first. */
    public static List<PipelineConfig> candidateConfigs(Reorder reorder) {
        final var base = DEFAULT.withReorder(reorder);
        final var predictors = List.of(
                Prediction.QUAD_CORNER_LINE, Prediction.QUAD_CORNER, Prediction.LINE_CONTINUATION, Prediction.DELTA);
        final var segmentations = List.of(Segmentation.CLASS, Segmentation.FLAT);
        final var terminals = List.of(
                base.withWidth(ByteWidth.ESCAPE).withTranspose(Transpose.INTERLEAVED_PLANES),
                base.withWidth(ByteWidth.ESCAPE).withTranspose(Transpose.NONE),
                base.withWidth(ByteWidth.VARINT_SPLIT).withTranspose(Transpose.NONE));
        final var configs = new ArrayList<PipelineConfig>();
        for (final var predictor : predictors) {
            for (final var segmentation : segmentations) {
                for (final var terminal : terminals) {
                    configs.add(terminal.withPredictor(predictor).withSegmentation(segmentation));
                }
            }
        }
        return configs;
    }

    /**
     * The oracle grid: {@link Reorder#NONE} and {@link Reorder#HILBERT_CENTROID_ROT_MP}, each times
     * {@link #candidateConfigs}. The first row is {@link #DEFAULT}.
     */
    public static List<PipelineConfig> oracleConfigs() {
        final var configs = new ArrayList<PipelineConfig>();
        for (final var reorder : List.of(Reorder.NONE, Reorder.HILBERT_CENTROID_ROT_MP)) {
            configs.addAll(candidateConfigs(reorder));
        }
        return configs;
    }

    /** The rows measured by one step. */
    public static List<Encoding> step(String name) {
        return switch (name) {
            case "s0" -> List.of(
                    row(PipelineConfig.IDENTITY), row(PipelineConfig.IDENTITY.withWidth(ByteWidth.VARINT_SPLIT)));
            case "s1" -> s1Orders().stream().map(order -> row(AFTER_S0.withReorder(order))).toList();
            case "s2" -> Arrays.stream(Prediction.values())
                    .map(predictor -> row(AFTER_S1.withPredictor(predictor)))
                    .toList();
            case "s3" -> s3Rows();
            case "s4" -> List.of(row(AFTER_S4));
            case "s6" -> s6Rows();
            case "s7" -> s7Rows();
            case "s8" -> s8Rows();
            // the fixed rows the per-buffer selection is measured against; the selectors themselves
            // need a compressor and are built by PipelineCli
            case "selector" -> List.of(row(DEFAULT), row(GEOMETRY_BEST));
            // not a step of the build-up: TURN_SCALAR beside QUAD_CORNER, for the building layer
            case "turn" -> turnRows();
            // not a step of the build-up: the ring frame beside QUAD_CORNER, for the building layer
            case "frame" -> frameRows();
            // the ring frame on every layer, under each layer terminal
            case "framelayers" -> frameLayerRows();
            // the shared-vertex references beside the plain and framed predictors, for the building layer
            case "shared" -> sharedRows();
            // the ring frame on line parts: LINE_CONTINUATION beside LINE_CONTINUATION_FRAME
            case "framelines" -> frameLineRows();
            // the references to earlier line parts' positions beside the line predictors
            case "linesshared" -> lineSharedRows();
            // not a step of the build-up: the cumulative stages, for the benchmark run
            case "ladder" -> ladder().stream().map(stage -> row(stage.config())).toList();
            // the grid the oracle chooses among; its rows are added to all() below
            case "oracle" -> oracle();
            // the grid forLayer is fitted on (frame, shared and heading predictors, chain reorder included)
            case "fit" -> fitRows(List.of(Reorder.HILBERT_CENTROID_ROT_MP, Reorder.CHAIN));
            // the same without the chain reorder, whose property-column cost is only measured for transportation
            case "fithilbert" -> fitRows(List.of(Reorder.HILBERT_CENTROID_ROT_MP));
            // the previous default, beside which the benchmark sizes the per-layer default (forLayer)
            case "layer" -> List.of(row(DEFAULT));
            // the fixed rows the tile selection is measured against; the TileSelectors need a
            // compressor and are built by PipelineCli
            case "tile" -> List.of(row(DEFAULT));
            // the grid of the per-(tile, layer) oracle; the oracles themselves need a compressor and are
            // built by PipelineCli. Not in steps(), so all() does not take the grid
            case "best" -> bestGrid().stream().map(PipelineRegistry::row).toList();
            default -> throw new IllegalArgumentException("unknown step: " + name + " (known: " + steps() + ")");
        };
    }

    private static List<Encoding> s3Rows() {
        final var rows = new ArrayList<Encoding>();
        // the SegmentPair re-check: plain delta, with and without the role split, over LZ
        for (final var segmentation : List.of(Segmentation.FLAT, Segmentation.CLASS, Segmentation.ROLE)) {
            rows.add(row(AFTER_S2.withPredictor(Prediction.DELTA).withSegmentation(segmentation)));
        }
        for (final var segmentation : Segmentation.values()) {
            rows.add(row(AFTER_S2.withSegmentation(segmentation)));
        }
        // the per-layer winners of S2 under the segmentations that use their classes
        for (final var predictor : List.of(Prediction.QUAD_CORNER, Prediction.LINE_CONTINUATION)) {
            rows.add(row(AFTER_S2.withPredictor(predictor).withSegmentation(Segmentation.CLASS)));
            rows.add(row(AFTER_S2.withPredictor(predictor).withSegmentation(Segmentation.ROLE_CLASS)));
        }
        // the (class, Hilbert) sort: classes contiguous in feature order
        for (final var segmentation : List.of(Segmentation.FLAT, Segmentation.ROLE_CLASS)) {
            rows.add(row(AFTER_S2.withReorder(Reorder.CLASS_HILBERT_CENTROID_ROT).withSegmentation(segmentation)));
        }
        return rows;
    }

    private static List<Encoding> s6Rows() {
        final var rows = new ArrayList<Encoding>();
        rows.add(row(AFTER_S4.withWidth(ByteWidth.VARINT)));
        rows.add(row(AFTER_S4));
        for (final var width : List.of(ByteWidth.MIN_FIXED, ByteWidth.ESCAPE, ByteWidth.FORCE_4)) {
            rows.add(row(AFTER_S4.withWidth(width)));
        }
        // the role split, whose point is a separate width per stream (S3 left it open)
        for (final var width : List.of(ByteWidth.MIN_FIXED, ByteWidth.ESCAPE)) {
            rows.add(row(AFTER_S4.withSegmentation(Segmentation.ROLE_CLASS).withWidth(width)));
        }
        return rows;
    }

    private static List<Encoding> s7Rows() {
        final var rows = new ArrayList<Encoding>();
        rows.add(row(AFTER_S4));
        for (final var width : List.of(ByteWidth.MIN_FIXED, ByteWidth.ESCAPE, ByteWidth.FORCE_4)) {
            for (final var transpose : Transpose.values()) {
                rows.add(row(AFTER_S6.withWidth(width).withTranspose(transpose)));
            }
        }
        for (final var transpose : Transpose.values()) {
            rows.add(row(AFTER_S6.withSegmentation(Segmentation.ROLE_CLASS).withTranspose(transpose)));
        }
        return rows;
    }

    /**
     * S8: the S7 winner against the choices around it, one row per decision the earlier steps took,
     * so the backend that prefers another one shows. Each is a single change to {@code AFTER_S7}.
     */
    private static List<Encoding> s8Rows() {
        return List.of(
                row(AFTER_S7),
                row(AFTER_S7.withReorder(Reorder.NONE)),
                row(AFTER_S7.withReorder(Reorder.HILBERT_CENTROID)),
                row(AFTER_S7.withReorder(Reorder.HILBERT_CENTROID_ROT)),
                row(AFTER_S7.withPredictor(Prediction.DELTA)),
                row(AFTER_S7.withPredictor(Prediction.QUAD_CORNER)),
                row(AFTER_S7.withPredictor(Prediction.LINE_CONTINUATION)),
                row(AFTER_S7.withSegmentation(Segmentation.FLAT)),
                row(AFTER_S7.withWidth(ByteWidth.VARINT_SPLIT).withTranspose(Transpose.NONE)),
                row(AFTER_S7.withWidth(ByteWidth.MIN_FIXED)),
                row(AFTER_S7.withTranspose(Transpose.NONE)));
    }

    /** Every row of every step, once. */
    public static List<Encoding> all() {
        final var byName = new LinkedHashMap<String, Encoding>();
        for (final var step : steps()) {
            for (final var encoding : step(step)) {
                byName.putIfAbsent(encoding.name(), encoding);
            }
        }
        // the oracle's grid holds combinations no step measures; round-tripped with the rest
        for (final var encoding : oracle()) {
            byName.putIfAbsent(encoding.name(), encoding);
        }
        // so are the per-layer compositions (the chain ones pair it with predictors no step measures)
        for (final var layer : fittedLayers()) {
            final var encoding = row(forLayer(layer));
            byName.putIfAbsent(encoding.name(), encoding);
        }
        return new ArrayList<>(byName.values());
    }
}
