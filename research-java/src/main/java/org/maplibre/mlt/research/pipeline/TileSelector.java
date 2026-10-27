package org.maplibre.mlt.research.pipeline;

import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.EncodedBuffer;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;
import java.util.stream.IntStream;

/**
 * The selection loop on the tile: one composition per (tile, layer), chosen by the compressed size of
 * the whole tile, not of the buffer alone. {@link Selector} sized each buffer on its own and lost per
 * tile, since buffers in different formats share fewer matches in the tile's window; here every trial
 * is the tile's concatenated buffers, sized with the target backend or with an estimate that
 * compresses nothing (a byte-entropy bound), so the tile is compressed only once, after the choice.
 *
 * <p>The candidates are the oracle grid: reorder (none or {@link Reorder#HILBERT_CENTROID_ROT_MP}) x
 * predictor x class streams on or off x terminal, 48 per layer, plus the per-layer defaults the grid lacks (the {@code building} ring-frame
 * row). Nothing is signalled beyond the
 * buffer header: the reorder is not reversed by a decoder, the tile carries the features in the
 * order they were written.
 *
 * <p>The search is coordinate descent: every layer starts at {@link PipelineRegistry#forLayer}, then
 * one layer at a time takes the candidate that makes the tile smallest with the other layers held,
 * until a full pass changes nothing (or {@link #MAX_PASSES}). A change is only taken when the tile
 * shrinks, so without a restriction the result is never larger than the per-layer default under the
 * trial's compressor. A restriction narrows each buffer's candidates before the search, and only the
 * allowed ones are encoded.
 * The trial sees the geometry buffers only: what the reorder does to a layer's property columns is
 * not in it.
 */
public final class TileSelector {

    /** Passes over the layers; the descent stops earlier once a pass takes no change. */
    static final int MAX_PASSES = 3;

    /** What one layer of the tile was written with, and the bytes. */
    public record Choice(PipelineConfig config, EncodedBuffer encoded) {
    }

    private final String backend;
    private final ToLongFunction<byte[]> size;
    private final Function<BufferContext, Predicate<PipelineConfig>> restriction;
    private final List<PipelineConfig> candidates;

    /**
     * @param backend label of the compressor in the name, e.g. {@code gzip}
     * @param size    the compressed size of the given bytes under that compressor
     */
    public TileSelector(String backend, ToLongFunction<byte[]> size) {
        this(backend, size, ctx -> config -> true);
    }

    /**
     * @param restriction per buffer, which candidates it may choose among (e.g. {@link StageRules}
     *                    fixing some stages from the buffer's features); at least one must remain
     */
    public TileSelector(String backend, ToLongFunction<byte[]> size,
                        Function<BufferContext, Predicate<PipelineConfig>> restriction) {
        this.backend = backend;
        this.size = size;
        this.restriction = restriction;
        // the oracle grid, then the layer defaults it does not contain (the ring-frame predictors are not
        // in the grid), so every layer can start from its default and is never worse than it
        final var all = new java.util.ArrayList<>(Selector.oracleConfigs());
        for (final var layer : PipelineRegistry.fittedLayers()) {
            final var layerDefault = PipelineRegistry.forLayer(layer);
            if (!all.contains(layerDefault)) {
                all.add(layerDefault);
            }
        }
        this.candidates = List.copyOf(all);
    }

    public String name() {
        return "TILE_SELECT_" + backend;
    }

    /** The label of what the trial sizes the tile with. */
    public String backend() {
        return backend;
    }

    @Override
    public String toString() {
        return name();
    }

    /** The trial set: every buffer of a tile chooses among these. */
    public List<PipelineConfig> candidates() {
        return candidates;
    }

    /**
     * One choice per buffer of the tile, in the order given, which is the order the buffers are
     * concatenated in.
     *
     * @param layers  the layer name of each buffer, for its starting composition
     * @param buffers the tile's buffers
     */
    public List<Choice> select(List<String> layers, List<BufferContext> buffers) {
        final var n = buffers.size();
        final var allowed = new boolean[n][candidates.size()];
        IntStream.range(0, n).parallel().forEach(b -> {
            final var allows = restriction.apply(buffers.get(b));
            for (var c = 0; c < candidates.size(); c++) {
                allowed[b][c] = allows.test(candidates.get(c));
            }
        });
        final var encoded = new EncodedBuffer[n][candidates.size()];
        final var payloads = new byte[n][candidates.size()][];
        IntStream.range(0, n * candidates.size()).parallel().forEach(task -> {
            final var b = task / candidates.size();
            final var c = task % candidates.size();
            if (allowed[b][c]) {
                encoded[b][c] = new PipelineEncoding(candidates.get(c)).encode(buffers.get(b));
                payloads[b][c] = encoded[b][c].payload();
            }
        });

        final var chosen = new int[n];
        for (var b = 0; b < n; b++) {
            chosen[b] = start(PipelineRegistry.forLayer(layers.get(b)), allowed[b]);
        }
        var best = size.applyAsLong(concat(payloads, chosen, -1, 0));
        for (var pass = 0; pass < MAX_PASSES; pass++) {
            var changed = false;
            for (var b = 0; b < n; b++) {
                final var buffer = b;
                final var sizes = IntStream.range(0, candidates.size()).parallel()
                        .mapToLong(c -> c == chosen[buffer] || !allowed[buffer][c]
                                ? Long.MAX_VALUE
                                : size.applyAsLong(concat(payloads, chosen, buffer, c)))
                        .toArray();
                var winner = chosen[b];
                for (var c = 0; c < sizes.length; c++) {
                    if (sizes[c] < best) {
                        best = sizes[c];
                        winner = c;
                    }
                }
                if (winner != chosen[b]) {
                    chosen[b] = winner;
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }

        final var choices = new ArrayList<Choice>(n);
        for (var b = 0; b < n; b++) {
            choices.add(new Choice(candidates.get(chosen[b]), encoded[b][chosen[b]]));
        }
        return choices;
    }

    /**
     * Where a buffer's descent starts: the layer's default if allowed, else the allowed candidate with
     * the default's predictor and terminal (the restriction fixed the other stages), else the first
     * allowed one.
     */
    private int start(PipelineConfig layerDefault, boolean[] allowed) {
        final var exact = candidates.indexOf(layerDefault);
        if (exact >= 0 && allowed[exact]) {
            return exact;
        }
        var firstAllowed = -1;
        for (var c = 0; c < candidates.size(); c++) {
            if (!allowed[c]) {
                continue;
            }
            if (firstAllowed < 0) {
                firstAllowed = c;
            }
            final var candidate = candidates.get(c);
            if (candidate.predictor() == layerDefault.predictor()
                    && candidate.width() == layerDefault.width()
                    && candidate.transpose() == layerDefault.transpose()) {
                return c;
            }
        }
        if (firstAllowed < 0) {
            throw new IllegalStateException(name() + ": the restriction leaves a buffer no candidate");
        }
        return firstAllowed;
    }

    /** The tile's bytes with buffer {@code swap} written by candidate {@code with}, the others as chosen. */
    private static byte[] concat(byte[][][] payloads, int[] chosen, int swap, int with) {
        final var out = new ByteArrayOutputStream();
        for (var b = 0; b < payloads.length; b++) {
            out.writeBytes(payloads[b][b == swap ? with : chosen[b]]);
        }
        return out.toByteArray();
    }
}
