package org.maplibre.mlt.research.registry;

import org.maplibre.mlt.research.encoder.format.Composition;
import org.maplibre.mlt.research.encoder.prediction.Prediction;
import org.maplibre.mlt.research.encoder.reorder.Reorder;
import org.maplibre.mlt.research.encoder.segmentation.Segmentation;
import org.maplibre.mlt.research.encoder.stream.ByteWidth;
import org.maplibre.mlt.research.encoder.stream.Transpose;

import java.util.Map;
import java.util.Set;

/**
 * The per-layer composition of the pipeline: which {@link Composition} each MVT layer of the Munich
 * corpus is encoded with. The layer names (the OpenMapTiles schema's) live here and nowhere else in
 * the registry.
 */
public final class LayerCompositions {

    private LayerCompositions() {
    }

    // the MVT layer names the fit knows
    public static final String PLACE = "place";
    public static final String TRANSPORTATION_NAME = "transportation_name";
    public static final String TRANSPORTATION = "transportation";
    public static final String LANDCOVER = "landcover";
    public static final String LANDUSE = "landuse";
    public static final String WATER_NAME = "water_name";
    public static final String WATER = "water";
    public static final String PARK = "park";
    public static final String BOUNDARY = "boundary";
    public static final String POI = "poi";
    public static final String BUILDING = "building";
    public static final String HOUSENUMBER = "housenumber";
    public static final String WATERWAY = "waterway";
    public static final String MOUNTAIN_PEAK = "mountain_peak";
    public static final String AEROWAY = "aeroway";
    public static final String AERODROME_LABEL = "aerodrome_label";

    static Composition composition(
            Prediction predictor, Segmentation segmentation, ByteWidth width, Transpose transpose) {
        return PipelineRegistry.DEFAULT.withReorder(Reorder.HILBERT_CENTROID_ROT_MP).withPredictor(predictor)
                .withSegmentation(segmentation).withWidth(width).withTranspose(transpose);
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

    /**
     * The fixed composition of each layer: per layer the one row of the {@link PipelineRegistry#fitRows fit grid} with the
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
     * single-layer-tile measure above. A layer not listed takes {@link PipelineRegistry#DEFAULT}.
     */
    private static final Map<String, Composition> BY_LAYER = Map.ofEntries(
            // (the layers on CHAIN_LAYERS carry .withReorder(Reorder.CHAIN) below)
            Map.entry(PLACE, composition(Prediction.QUAD_CORNER_LINE,
                    Segmentation.FLAT, ByteWidth.VARINT_SPLIT, Transpose.NONE)),
            // the greedy chain, not Hilbert, and the references to earlier line parts' positions: fitted on
            // geometry plus property columns compressed together (docs/results/line-shared-vertices.md)
            Map.entry(TRANSPORTATION, composition(Prediction.LINE_CONTINUATION_SHARED_FRAME_HEADING,
                    Segmentation.CLASS, ByteWidth.ESCAPE, Transpose.INTERLEAVED_PLANES)
                    .withReorder(Reorder.CHAIN)),
            Map.entry(LANDCOVER, composition(Prediction.QUAD_CORNER_SHARED_FRAME,
                    Segmentation.CLASS, ByteWidth.ESCAPE, Transpose.INTERLEAVED_PLANES)),
            Map.entry(TRANSPORTATION_NAME, composition(Prediction.LINE_CONTINUATION_SHARED_FRAME_HEADING,
                    Segmentation.FLAT, ByteWidth.VARINT_SPLIT, Transpose.NONE).withReorder(Reorder.CHAIN)),
            Map.entry(LANDUSE, composition(Prediction.QUAD_CORNER_SHARED_FRAME,
                    Segmentation.CLASS, ByteWidth.ESCAPE, Transpose.INTERLEAVED_PLANES)),
            Map.entry(WATER, composition(Prediction.QUAD_CORNER_SHARED_FRAME,
                    Segmentation.CLASS, ByteWidth.ESCAPE, Transpose.NONE).withReorder(Reorder.CHAIN)),
            Map.entry(PARK, composition(Prediction.QUAD_CORNER_SHARED,
                    Segmentation.FLAT, ByteWidth.VARINT_SPLIT, Transpose.NONE)),
            Map.entry(BOUNDARY, composition(Prediction.LINE_CONTINUATION_SHARED_FRAME_HEADING,
                    Segmentation.FLAT, ByteWidth.VARINT_SPLIT, Transpose.NONE)),
            Map.entry(POI, composition(Prediction.DELTA,
                    Segmentation.CLASS, ByteWidth.ESCAPE, Transpose.INTERLEAVED_PLANES)),
            Map.entry(BUILDING, composition(Prediction.QUAD_CORNER_SHARED_FRAME,
                    Segmentation.CLASS, ByteWidth.ESCAPE, Transpose.NONE)),
            Map.entry(WATER_NAME, composition(Prediction.LINE_CONTINUATION,
                    Segmentation.FLAT, ByteWidth.VARINT_SPLIT, Transpose.NONE)),
            Map.entry(HOUSENUMBER, composition(Prediction.QUAD_CORNER_LINE,
                    Segmentation.FLAT, ByteWidth.ESCAPE, Transpose.INTERLEAVED_PLANES)),
            Map.entry(WATERWAY, composition(Prediction.LINE_CONTINUATION_SHARED_FRAME_HEADING,
                    Segmentation.FLAT, ByteWidth.VARINT_SPLIT, Transpose.NONE).withReorder(Reorder.CHAIN)),
            Map.entry(MOUNTAIN_PEAK, composition(Prediction.LINE_CONTINUATION_HEADING,
                    Segmentation.FLAT, ByteWidth.VARINT_SPLIT, Transpose.NONE)),
            Map.entry(AEROWAY, composition(Prediction.QUAD_CORNER_SHARED_FRAME,
                    Segmentation.FLAT, ByteWidth.VARINT_SPLIT, Transpose.NONE).withReorder(Reorder.CHAIN)),
            Map.entry(AERODROME_LABEL, composition(Prediction.DELTA,
                    Segmentation.FLAT, ByteWidth.VARINT_SPLIT, Transpose.NONE)));

    /** The layers whose composition uses the greedy chain reorder instead of Hilbert (see {@link #BY_LAYER}). */
    public static final Set<String> CHAIN_LAYERS =
            Set.of(TRANSPORTATION, TRANSPORTATION_NAME, WATERWAY, WATER, AEROWAY);

    /**
     * The pipeline's default: one fixed composition per layer, {@link PipelineRegistry#DEFAULT} for a layer without an
     * entry (a layer the fit has not seen). The decoder knows the layer, so the choice needs no
     * signalling beyond the buffer header; the reorder is the one choice the header does not carry, and
     * every fitted layer reorders ({@link Reorder#HILBERT_CENTROID_ROT_MP}, or {@link Reorder#CHAIN} for
     * {@link #CHAIN_LAYERS}).
     */
    public static Composition forLayer(String layer) {
        return BY_LAYER.getOrDefault(layer, PipelineRegistry.DEFAULT);
    }

    /** The layers with a fitted composition. */
    public static Set<String> fittedLayers() {
        return BY_LAYER.keySet();
    }
}
