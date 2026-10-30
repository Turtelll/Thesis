package org.maplibre.mlt.research.pipeline;

import org.maplibre.mlt.research.buffer.Encoding.BufferContext;
import org.maplibre.mlt.research.buffer.Encoding.EncodedBuffer;

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
 *
 * <p>{@link #oracle} is the same search over a caller's grid ({@link PipelineRegistry#bestGrid}) run to
 * convergence, which also sizes every candidate alone: the descent then starts from whichever tile is
 * smaller, every buffer on its layer default or every buffer on its alone-best, so its result is never
 * larger than either. It is a local optimum of the tile, not the exact one (that is every assignment of
 * candidates to the tile's buffers).
 */
public final class TileSelector {

    /** Passes over the layers; the descent stops earlier once a pass takes no change. */
    static final int MAX_PASSES = 3;

    /** The pass cap of {@link #oracle}: in practice the descent converges well before it. */
    static final int ORACLE_MAX_PASSES = 10;

    private static final Function<BufferContext, Predicate<PipelineConfig>> UNRESTRICTED = ctx -> config -> true;

    /** What one layer of the tile was written with, and the bytes. */
    public record Choice(PipelineConfig config, EncodedBuffer encoded) {
    }

    /**
     * Every candidate encoded on every buffer of one tile (those the restriction allows), so selectors
     * with the same candidates and restriction can search the tile without encoding it again.
     */
    public static final class Trial {
        private final List<PipelineConfig> candidates;
        private final Function<BufferContext, Predicate<PipelineConfig>> restriction;
        private final boolean[][] allowed;
        private final EncodedBuffer[][] encoded;
        private final byte[][][] payloads;

        private Trial(List<PipelineConfig> candidates, Function<BufferContext, Predicate<PipelineConfig>> restriction,
                      boolean[][] allowed, EncodedBuffer[][] encoded, byte[][][] payloads) {
            this.candidates = candidates;
            this.restriction = restriction;
            this.allowed = allowed;
            this.encoded = encoded;
            this.payloads = payloads;
        }

        /** The payload of candidate {@code candidate} on buffer {@code buffer}, {@code null} where not allowed. */
        public byte[] payload(int buffer, int candidate) {
            return payloads[buffer][candidate];
        }
    }

    /**
     * The result of a search: per buffer the tile's choice and, for {@link #oracle} only, the candidate
     * smallest alone under the trial's compressor and every candidate's alone size ({@code Long.MAX_VALUE}
     * where not allowed); {@code alone} and {@code aloneSizes} are {@code null} otherwise.
     */
    public record Search(List<Choice> tile, List<Choice> alone, long[][] aloneSizes) {
    }

    private final String backend;
    private final ToLongFunction<byte[]> size;
    private final Function<BufferContext, Predicate<PipelineConfig>> restriction;
    private final List<PipelineConfig> candidates;
    private final int maxPasses;
    private final boolean startFromAlone;

    /**
     * @param backend label of the compressor in the name, e.g. {@code gzip}
     * @param size    the compressed size of the given bytes under that compressor
     */
    public TileSelector(String backend, ToLongFunction<byte[]> size) {
        this(backend, size, UNRESTRICTED);
    }

    /**
     * @param restriction per buffer, which candidates it may choose among (e.g. {@link StageRules}
     *                    fixing some stages from the buffer's features); at least one must remain
     */
    public TileSelector(String backend, ToLongFunction<byte[]> size,
                        Function<BufferContext, Predicate<PipelineConfig>> restriction) {
        this(backend, size, restriction, defaultCandidates(), MAX_PASSES, false);
    }

    private TileSelector(String backend, ToLongFunction<byte[]> size,
                         Function<BufferContext, Predicate<PipelineConfig>> restriction,
                         List<PipelineConfig> candidates, int maxPasses, boolean startFromAlone) {
        this.backend = backend;
        this.size = size;
        this.restriction = restriction;
        this.candidates = List.copyOf(candidates);
        this.maxPasses = maxPasses;
        this.startFromAlone = startFromAlone;
    }

    /**
     * The per-(tile, layer) oracle over {@code candidates}: unrestricted, run until a pass changes nothing
     * (at most {@link #ORACLE_MAX_PASSES}), starting from the smaller of the per-layer default tile and
     * the alone-best tile. Every {@link PipelineRegistry#forLayer} composition must be a candidate.
     */
    public static TileSelector oracle(String backend, ToLongFunction<byte[]> size, List<PipelineConfig> candidates) {
        return new TileSelector(backend, size, UNRESTRICTED, candidates, ORACLE_MAX_PASSES, true);
    }

    /**
     * The oracle grid, then the layer defaults it does not contain (the ring-frame predictors are not
     * in the grid), so every layer can start from its default and is never worse than it.
     */
    private static List<PipelineConfig> defaultCandidates() {
        final var all = new ArrayList<>(PipelineRegistry.oracleConfigs());
        for (final var layer : PipelineRegistry.fittedLayers()) {
            final var layerDefault = PipelineRegistry.forLayer(layer);
            if (!all.contains(layerDefault)) {
                all.add(layerDefault);
            }
        }
        return all;
    }

    public String name() {
        return (startFromAlone ? "ORACLE TILE " : "TILE_SELECT_") + backend;
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
        return search(layers, encode(buffers)).tile();
    }

    /** Every allowed candidate encoded on every buffer of the tile, in parallel (the encodings are stateless). */
    public Trial encode(List<BufferContext> buffers) {
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
        return new Trial(candidates, restriction, allowed, encoded, payloads);
    }

    /**
     * The search over a tile encoded by {@link #encode} of this selector or of one with the same
     * candidates and restriction.
     *
     * @param layers the layer name of each buffer, for its starting composition
     */
    public Search search(List<String> layers, Trial trial) {
        if (!trial.candidates.equals(candidates) || trial.restriction != restriction) {
            throw new IllegalArgumentException(name() + ": the trial was encoded for other candidates");
        }
        final var n = trial.payloads.length;
        final var allowed = trial.allowed;
        final var payloads = trial.payloads;

        var chosen = new int[n];
        for (var b = 0; b < n; b++) {
            chosen[b] = start(PipelineRegistry.forLayer(layers.get(b)), allowed[b]);
        }
        var best = size.applyAsLong(concat(payloads, chosen, -1, 0));

        int[] aloneBest = null;
        long[][] aloneSizes = null;
        if (startFromAlone) {
            final var sizes = new long[n][candidates.size()];
            IntStream.range(0, n * candidates.size()).parallel().forEach(task -> {
                final var b = task / candidates.size();
                final var c = task % candidates.size();
                sizes[b][c] = allowed[b][c] ? size.applyAsLong(payloads[b][c]) : Long.MAX_VALUE;
            });
            aloneSizes = sizes;
            aloneBest = new int[n];
            for (var b = 0; b < n; b++) {
                for (var c = 1; c < candidates.size(); c++) {
                    if (sizes[b][c] < sizes[b][aloneBest[b]]) {
                        aloneBest[b] = c;
                    }
                }
            }
            final var fromAlone = size.applyAsLong(concat(payloads, aloneBest, -1, 0));
            if (fromAlone < best) {
                chosen = aloneBest.clone();
                best = fromAlone;
            }
        }

        for (var pass = 0; pass < maxPasses; pass++) {
            var changed = false;
            for (var b = 0; b < n; b++) {
                final var buffer = b;
                final var held = chosen;
                final var sizes = IntStream.range(0, candidates.size()).parallel()
                        .mapToLong(c -> c == held[buffer] || !allowed[buffer][c]
                                ? Long.MAX_VALUE
                                : size.applyAsLong(concat(payloads, held, buffer, c)))
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

        return new Search(choices(trial, chosen), aloneBest == null ? null : choices(trial, aloneBest), aloneSizes);
    }

    private List<Choice> choices(Trial trial, int[] chosen) {
        final var choices = new ArrayList<Choice>(chosen.length);
        for (var b = 0; b < chosen.length; b++) {
            choices.add(new Choice(candidates.get(chosen[b]), trial.encoded[b][chosen[b]]));
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
