package org.maplibre.mlt.research.workshop;

import org.maplibre.mlt.research.encoding.VertexTransform;

import java.util.function.Supplier;

/**
 * A workshop predictor: a name and the {@link Rule} giving each vertex's predicted position. Measured
 * interleaved over plain Varint by {@code ./gradlew research --args="workshop"}; see {@link Workshop}.
 *
 * <p>{@code rules} hands out a fresh rule per pass over a buffer (one encode, one decode), and that
 * rule is called for {@code i = 0, 1, ..., n - 1} in order, once each. So a rule may keep state
 * derived from the vertices it has already been given, as long as it rebuilds it from {@code xs}/{@code
 * ys} below {@code i} rather than from anything only the encoder knows.
 */
public record Predictor(String name, Supplier<Rule> rules) {

    /**
     * Writes the predicted {@code (x, y)} of vertex {@code i} into {@code out[0]}, {@code out[1]}.
     * {@code xs}/{@code ys} hold the buffer's coordinates, but only indices {@code < i} are valid: on
     * decode the rest are not reconstructed yet, so a rule reading them fails the round-trip check.
     */
    @FunctionalInterface
    public interface Rule {
        void predict(Topology t, int i, int[] xs, int[] ys, int[] out);
    }

    /** A stateless rule, shared by every pass. */
    public static Predictor of(String name, Rule rule) {
        return new Predictor(name, () -> rule);
    }

    /** A rule with state carried from vertex to vertex, created fresh for every pass. */
    public static Predictor stateful(String name, Supplier<Rule> rules) {
        return new Predictor(name, rules);
    }

    public VertexTransform transform() {
        return new PredictorTransform(this);
    }

    /** The previous vertex of the buffer, {@code (0, 0)} for the first: the baseline delta. */
    public static void prev(int i, int[] xs, int[] ys, int[] out) {
        out[0] = i == 0 ? 0 : xs[i - 1];
        out[1] = i == 0 ? 0 : ys[i - 1];
    }

    /** The previous edge continued: {@code 2 p[i-1] - p[i-2]}. Needs {@code i >= 2}. */
    public static void continuation(int i, int[] xs, int[] ys, int[] out) {
        out[0] = 2 * xs[i - 1] - xs[i - 2];
        out[1] = 2 * ys[i - 1] - ys[i - 2];
    }

    /** The fourth corner of the parallelogram {@code a, b, c}: {@code a + c - b}. */
    public static void parallelogram(int a, int b, int c, int[] xs, int[] ys, int[] out) {
        out[0] = xs[a] + xs[c] - xs[b];
        out[1] = ys[a] + ys[c] - ys[b];
    }
}
