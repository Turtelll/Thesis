package org.maplibre.mlt.research.pipeline;

import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.buffer.Encoding;
import org.maplibre.mlt.research.buffer.Encoding.BufferContext;
import org.maplibre.mlt.research.buffer.Encoding.EncodedBuffer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * The selection loop of the pipeline: the best configuration <em>per buffer</em>, found by trial
 * compression the way OpenZL's {@code tryGraph} does. No thresholds are ported, the encoding is
 * offline and the target backend is known, so every candidate is encoded, compressed with that
 * backend and the smallest kept.
 *
 * <p>The candidates are predictor x segmentation (class streams on or off) x terminal (escape width
 * with byte planes, escape width without, split varint), 24 in all; the stages that lost in S3 to
 * S7 (role streams, the fixed 1/4-byte widths, the x/y-separating transposes) are not
 * in the set. The choice is written by the candidate itself, in the two header bytes of {@link
 * PipelineEncoding}, so there is nothing to add: the decoder parses the header and runs the chosen
 * pipeline. The reorder is not a candidate, it moves the property columns and is decided per
 * layer by the S8 figures, so it is a constructor argument of the selector.
 *
 * <p>The trial compresses every candidate buffer on its own, a proxy for the tile the buffer ends
 * up in (S8 measured isolated figures 2% above per-tile ones with the ranking unchanged). The first
 * candidate is {@link PipelineRegistry#DEFAULT}'s choices, ties go to it, so the selection is never
 * larger than the default pipeline under the trial's compressor.
 *
 * <p>Measured and not kept: -0,3 to -0,65% isolated, +0,0 to +0,3% per tile, see {@code
 * docs/results/openzl-pipeline.md}. It stays as the record of that result and for the composition report.
 */
public final class Selector implements Encoding {

    private final Reorder reorder;
    private final String backend;
    private final ToLongFunction<byte[]> size;
    private final List<PipelineEncoding> candidates = new ArrayList<>();

    /**
     * @param reorder the S1 reorder every candidate applies (not signalled)
     * @param backend label of the compressor in the name, e.g. {@code gzip}
     * @param size    the compressed size of the given bytes under that compressor
     */
    public Selector(Reorder reorder, String backend, ToLongFunction<byte[]> size) {
        this.reorder = reorder;
        this.backend = backend;
        this.size = size;
        for (final var config : PipelineRegistry.candidateConfigs(reorder)) {
            candidates.add(new PipelineEncoding(config));
        }
    }

    /** What a selected buffer was written with: the choices in its header (the reorder is {@code NONE} here). */
    public static PipelineConfig choiceOf(byte[] payload) {
        return PayloadHeader.parse(payload[0] & 0xFF, payload[1] & 0xFF, Reorder.NONE);
    }

    @Override
    public String name() {
        return "SELECT" + reorder.suffix() + "_" + backend;
    }

    @Override
    public String toString() {
        return name();
    }

    @Override
    public boolean reordersGeometries() {
        return reorder.reorders();
    }

    @Override
    public BufferContext emitted(BufferContext ctx) {
        return reorder.apply(ctx);
    }

    @Override
    public EncodedBuffer encode(BufferContext ctx) {
        EncodedBuffer best = null;
        var bestSize = Long.MAX_VALUE;
        for (final var candidate : candidates) {
            final var encoded = candidate.encode(ctx);
            final var bytes = size.applyAsLong(encoded.payload());
            if (bytes < bestSize) {
                best = encoded;
                bestSize = bytes;
            }
        }
        return best;
    }

    @Override
    public List<Vertex> decode(EncodedBuffer encoded, BufferContext ctx, int vertexCount) {
        // the header names every choice but the reorder, which the candidates share
        return candidates.get(0).decode(encoded, ctx, vertexCount);
    }
}
