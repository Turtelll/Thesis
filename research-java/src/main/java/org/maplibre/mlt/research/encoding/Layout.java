package org.maplibre.mlt.research.encoding;

/**
 * Where a {@link ClassifiedPredictor}'s residuals land in the output array.
 *
 * <p>{@code SEGMENTED} groups residuals into contiguous per-class regions (region order per
 * {@link ClassifiedPredictor#regionOrder}), so a block-packing codec like FastPFOR sizes each
 * region independently. {@code INTERLEAVED} writes every residual at its vertex position, the
 * layout the plain baseline uses — the control that isolates whether a predictor's win depends
 * on region separation at all. See {@code docs/evaluation-protocol.md}.
 */
public enum Layout {
    INTERLEAVED,
    SEGMENTED
}
