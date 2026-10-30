package org.maplibre.mlt.research.measurement.selection;

import org.maplibre.mlt.research.buffer.Encoding.BufferContext;
import org.maplibre.mlt.research.encoder.reorder.Reorder;
import org.maplibre.mlt.research.encoder.format.PipelineConfig;
import org.maplibre.mlt.research.encoder.segmentation.Segmentation;

import java.util.function.Predicate;

/**
 * Two stages fixed from the buffer instead of searched: the reorder and the class streams, the two
 * whose choice by the gzip tile search a single {@link BufferFeatures} threshold predicts (84,8% and
 * 77,2% of the buffers, fitted on one half of the Munich tiles and scored on the other; the
 * thresholds are the fit on all of them). Predictor and terminal are left to the search.
 */
public final class StageRules {

    /** Reorder when the Hilbert order shortens the mean jump between runs below this share. */
    static final double JUMP_RATIO = 0.996;

    /** Class streams when the share of point geometries is below this, i.e. the buffer has none. */
    static final double POINT_SHARE = 0.0025;

    private StageRules() {
    }

    /** The reorder for this buffer; one whose runs cannot be located is not reordered. */
    public static Reorder reorder(BufferFeatures features) {
        return features.jumpRatio() < JUMP_RATIO ? Reorder.HILBERT_CENTROID_ROT_MP : Reorder.NONE;
    }

    public static Segmentation segmentation(BufferFeatures features) {
        return features.pointShare() < POINT_SHARE ? Segmentation.CLASS : Segmentation.FLAT;
    }

    /** For {@link TileSelector}: the candidates with this buffer's reorder and segmentation. */
    public static Predicate<PipelineConfig> restrict(BufferContext ctx) {
        final var features = BufferFeatures.of(ctx);
        final var reorder = reorder(features);
        final var segmentation = segmentation(features);
        return config -> config.reorder() == reorder && config.segmentation() == segmentation;
    }
}
