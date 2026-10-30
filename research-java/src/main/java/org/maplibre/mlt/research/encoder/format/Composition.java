package org.maplibre.mlt.research.encoder.format;

import org.maplibre.mlt.research.encoder.stream.ByteWidth;
import org.maplibre.mlt.research.encoder.stream.ResidualMap;
import org.maplibre.mlt.research.encoder.stream.StreamCodec;
import org.maplibre.mlt.research.encoder.stream.Transpose;
import org.maplibre.mlt.research.encoder.prediction.Prediction;
import org.maplibre.mlt.research.encoder.reorder.Reorder;
import org.maplibre.mlt.research.encoder.segmentation.Segmentation;

/**
 * The per-stage choices of one pipeline row. Everything except {@link #reorder()} is written to the
 * buffer header, so a decoder needs nothing but the bytes and the topology streams; the reorder is
 * not signalled, because the tile carries the reordered topology as it is.
 */
public record Composition(
        Reorder reorder,
        Prediction predictor,
        Segmentation segmentation,
        ResidualMap map,
        ByteWidth width,
        Transpose transpose) {

    /** Every stage at identity, with the temporary varint terminal. */
    public static final Composition IDENTITY = new Composition(
            Reorder.NONE,
            Prediction.DELTA,
            Segmentation.FLAT,
            ResidualMap.ZIGZAG,
            ByteWidth.VARINT,
            Transpose.NONE);

    /** Derived from the choices, so a name can neither drift from what it describes nor collide. */
    public String name() {
        return "PIPE" + reorder.suffix() + "_" + predictor + "_" + segmentation + "_" + map
                + "_" + width + transpose.suffix();
    }

    /** The choices a stream is written with: residual map, byte width and transpose. */
    public StreamCodec.Format stream() {
        return new StreamCodec.Format(map, width, transpose);
    }

    public Composition withReorder(Reorder value) {
        return new Composition(value, predictor, segmentation, map, width, transpose);
    }

    public Composition withPredictor(Prediction value) {
        return new Composition(reorder, value, segmentation, map, width, transpose);
    }

    public Composition withSegmentation(Segmentation value) {
        return new Composition(reorder, predictor, value, map, width, transpose);
    }

    public Composition withWidth(ByteWidth value) {
        return new Composition(reorder, predictor, segmentation, map, value, transpose);
    }

    public Composition withTranspose(Transpose value) {
        return new Composition(reorder, predictor, segmentation, map, width, value);
    }
}
