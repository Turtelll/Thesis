package org.maplibre.mlt.research.pipeline;

/**
 * The two-byte header every pipeline payload starts with: the format's self-description. It carries
 * every choice of a {@link PipelineConfig} but the reorder, which the caller supplies when reading.
 */
public final class PayloadHeader {

    /** Header size in bytes; the anchor tests compare the body after it. */
    public static final int HEADER_BYTES = 2;

    private static final int PREDICTOR_BITS = 3;
    private static final int PREDICTOR_MASK = 0b111;
    private static final int PREDICTOR_HIGH_SHIFT = 4;
    private static final int WIDTH_MASK = 0b1111;
    private static final int SEGMENTATION_SHIFT = 3;
    private static final int SEGMENTATION_MASK = 0b11;
    private static final int MAP_SHIFT = 5;
    private static final int MAP_MASK = 0b1;
    private static final int TRANSPOSE_SHIFT = 6;
    private static final int TRANSPOSE_MASK = 0b11;

    private PayloadHeader() {
    }

    /**
     * Predictor (low 3 bits), segmentation, map and transpose in byte 0 (3 bits, 2 bits, 1 bit, 2 bits),
     * the byte-width terminal in byte 1 (low 4 bits) with the predictor's fourth bit above it: the
     * predictors outgrew the three bits byte 0 has for them.
     */
    public static byte[] write(PipelineConfig c) {
        final var predictor = c.predictor().ordinal();
        final var first = (predictor & PREDICTOR_MASK)
                | c.segmentation().ordinal() << SEGMENTATION_SHIFT
                | c.map().ordinal() << MAP_SHIFT
                | c.transpose().ordinal() << TRANSPOSE_SHIFT;
        final var second = c.width().ordinal() | (predictor >> PREDICTOR_BITS) << PREDICTOR_HIGH_SHIFT;
        return new byte[]{(byte) first, (byte) second};
    }

    /** The inverse of {@link #write}; the reorder is not in it, so the caller supplies it. */
    public static PipelineConfig parse(int first, int second, Reorder reorder) {
        return new PipelineConfig(
                reorder,
                Prediction.values()[(first & PREDICTOR_MASK) | (second >> PREDICTOR_HIGH_SHIFT) << PREDICTOR_BITS],
                Segmentation.values()[(first >> SEGMENTATION_SHIFT) & SEGMENTATION_MASK],
                ResidualMap.values()[(first >> MAP_SHIFT) & MAP_MASK],
                ByteWidth.values()[second & WIDTH_MASK],
                Transpose.values()[(first >> TRANSPOSE_SHIFT) & TRANSPOSE_MASK]);
    }
}
