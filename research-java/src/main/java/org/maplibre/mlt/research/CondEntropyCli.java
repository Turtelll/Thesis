package org.maplibre.mlt.research;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.maplibre.mlt.research.encoding.Layout;
import org.maplibre.mlt.research.encoding.PartBoundaries;
import org.maplibre.mlt.research.encoding.PredictedTransform;
import org.maplibre.mlt.research.encoding.VertexTransform;
import org.maplibre.mlt.research.encoding.research.SegmentQuadCorner;
import org.maplibre.mlt.research.extraction.ExtractionReader;

/**
 * Measures whether an rANS coder over {@link SegmentQuadCorner} residuals could beat the order-0
 * ceiling {@link EntropyCli} reports, by testing the three structural claims a context-modelled
 * coder rests on. Each is measured separately, because they are independent and any of them can
 * turn out to be worth nothing:
 *
 * <ul>
 *   <li><b>C1 — y given x.</b> H(y | coarse(x)) vs H(y), within each region. This is the
 *       axis-alignment claim: for building walls a zero x should predict a large |y|. A drop near
 *       zero means the two components are effectively independent, and coding them as separate
 *       streams costs nothing.
 *   <li><b>C2 — bucket/raw split.</b> Entropy of the bucket-class alphabet vs the raw low-order
 *       bits it drops. A coder only compresses bucket classes; the raw bits pass through uncoded,
 *       so a stream whose entropy is mostly raw bits cannot reach the order-0 ceiling.
 *   <li><b>C3 — order-1 bucket context.</b> H(bucket | previous bucket of the same component),
 *       which is what an order-1 coder over the bucket alphabet would exploit.
 * </ul>
 *
 * <p>All figures are ideal entropies with no table cost, so each is an upper bound on what a real
 * coder achieves. Usage: {@code ./gradlew condEntropy --args="../data/extracted --layer building"}
 */
public final class CondEntropyCli {
  private CondEntropyCli() {}

  private static final String[] REGION_NAMES = {"starts", "interior", "corners"};

  public static void main(String[] args) throws IOException {
    String inArg = null;
    var layer = "building";
    var baseline = false;
    for (var i = 0; i < args.length; i++) {
      if (args[i].equals("--layer") && i + 1 < args.length) {
        layer = args[++i];
      } else if (args[i].equals("--baseline")) {
        baseline = true;
      } else if (!args[i].startsWith("--")) {
        inArg = args[i];
      }
    }
    if (inArg == null) {
      throw new IllegalArgumentException("Usage: <extractionDir> [--layer <name>]");
    }
    final var inDir = Path.of(inArg);
    final var manifest = ExtractionReader.readManifest(inDir);

    // Per region, the x and y residual of every vertex kept paired, so C1 can condition one on the
    // other. Region indices match SegmentQuadCorner: 0=starts, 1=interior, 2=corners.
    final List<List<int[]>> regionPairs =
        List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    var buffers = 0;

    final VertexTransform transform =
        baseline
            ? new VertexTransform.ZigZagDelta()
            : new PredictedTransform(new SegmentQuadCorner(), Layout.SEGMENTED);
    for (final var entry : manifest) {
      if (!entry.layerName().equals(layer)) {
        continue;
      }
      final var ctx = ExtractionReader.readContext(inDir, entry);
      if (ctx.vertices().isEmpty()) {
        continue;
      }
      buffers++;

      final var values = transform.apply(ctx);
      final var starts = PartBoundaries.of(ctx, ctx.vertices().size());
      final var classes = classify(starts, ctx.vertices().size());
      var startCount = 0;
      var cornerCount = 0;
      for (final var c : classes) {
        if (c == 1) {
          startCount++;
        } else if (c == 2) {
          cornerCount++;
        }
      }
      final var interiorCount = classes.length - startCount - cornerCount;

      if (baseline) {
        collect(values, 0, values.length / 2, regionPairs.get(1));
      } else {
        var slot = 0;
        slot = collect(values, slot, startCount, regionPairs.get(0));
        slot = collect(values, slot, interiorCount, regionPairs.get(1));
        collect(values, slot, cornerCount, regionPairs.get(2));
      }
    }

    report(layer, buffers, regionPairs, transform.name());
  }

  /** Takes {@code count} (x,y) pairs starting at {@code slot}; returns the slot after them. */
  private static int collect(int[] values, int slot, int count, List<int[]> out) {
    for (var i = 0; i < count; i++) {
      out.add(new int[] {values[slot], values[slot + 1]});
      slot += 2;
    }
    return slot;
  }

  /** Mirrors {@code SegmentQuadCorner.classify}: 0=interior, 1=start, 2=corner. */
  private static int[] classify(int[] starts, int vertexCount) {
    final var classes = new int[vertexCount];
    for (var s = 0; s < starts.length; s++) {
      final var start = starts[s];
      final var end = s + 1 < starts.length ? starts[s + 1] : vertexCount;
      if (start < vertexCount) {
        classes[start] = 1;
      }
      if (end - start == 4) {
        classes[start + 3] = 2;
      }
    }
    return classes;
  }

  // ---------------------------------------------------------------------------
  // Bucketing
  // ---------------------------------------------------------------------------

  /**
   * Exponential bucket of a zigzag residual: its number of significant bits. This is the symbol a
   * real coder would entropy-code, and the {@code bucket-1} bits below it are the raw bits it
   * passes through uncoded — the split C2 measures.
   */
  private static int bucket(int zigzag) {
    return 32 - Integer.numberOfLeadingZeros(zigzag);
  }

  /** Raw bits carried below the bucket symbol, which a coder cannot compress. */
  private static int rawBits(int zigzag) {
    final var b = bucket(zigzag);
    return b <= 1 ? 0 : b - 1;
  }

  /** Coarse class of x, used as the conditioning context for y in C1. */
  private static int coarse(int zigzag) {
    final var b = bucket(zigzag);
    if (b == 0) {
      return 0; // exactly zero: the axis-aligned case
    }
    return b <= 3 ? 1 : (b <= 6 ? 2 : 3);
  }

  // ---------------------------------------------------------------------------
  // Entropy
  // ---------------------------------------------------------------------------

  private static double entropy(Map<Integer, Long> freq, long n) {
    if (n == 0) {
      return 0;
    }
    var bits = 0.0;
    for (final var c : freq.values()) {
      final var p = (double) c / n;
      bits -= p * (Math.log(p) / Math.log(2));
    }
    return bits;
  }

  private static double entropyOf(List<Integer> values) {
    final Map<Integer, Long> freq = new HashMap<>();
    for (final var v : values) {
      freq.merge(v, 1L, Long::sum);
    }
    return entropy(freq, values.size());
  }

  /** H(symbol | context), averaged over contexts by observed weight. Pairs are {context, symbol}. */
  private static double conditionalEntropy(List<int[]> contextSymbolPairs) {
    final Map<Integer, Map<Integer, Long>> byContext = new HashMap<>();
    final Map<Integer, Long> contextTotals = new HashMap<>();
    for (final var p : contextSymbolPairs) {
      byContext.computeIfAbsent(p[0], k -> new HashMap<>()).merge(p[1], 1L, Long::sum);
      contextTotals.merge(p[0], 1L, Long::sum);
    }
    final var n = (double) contextSymbolPairs.size();
    var bits = 0.0;
    for (final var e : byContext.entrySet()) {
      final long total = contextTotals.get(e.getKey());
      bits += (total / n) * entropy(e.getValue(), total);
    }
    return bits;
  }

  // ---------------------------------------------------------------------------
  // Reporting
  // ---------------------------------------------------------------------------

  private static void report(
      String layer, int buffers, List<List<int[]>> regionPairs, String transformName) {
    var totalValues = 0L;
    for (final var r : regionPairs) {
      totalValues += r.size() * 2L;
    }
    System.out.printf(
        "Conditional-entropy probe for %s on layer '%s' (%,d buffers, %,d values).%n",
        transformName, layer, buffers, totalValues);
    System.out.printf(
        "Ideal entropies, no table cost - every figure is an upper bound on a real coder.%n%n");

    reportC1(regionPairs);
    reportC2(regionPairs);
    reportC3(regionPairs);
    reportC4(regionPairs);
  }

  private static void reportC1(List<List<int[]>> regionPairs) {
    System.out.printf("--- C1: does conditioning y on x help? ---%n");
    System.out.printf(
        "%-10s %12s %12s %12s %14s%n", "region", "H(y)", "H(y|ctx(x))", "drop b/val", "saved bytes");
    var totalSaved = 0.0;
    for (var r = 0; r < 3; r++) {
      final var pairs = regionPairs.get(r);
      if (pairs.isEmpty()) {
        continue;
      }
      final var ys = new ArrayList<Integer>(pairs.size());
      final var ctxY = new ArrayList<int[]>(pairs.size());
      for (final var p : pairs) {
        ys.add(p[1]);
        ctxY.add(new int[] {coarse(p[0]), p[1]});
      }
      final var hY = entropyOf(ys);
      final var hYgivenX = conditionalEntropy(ctxY);
      final var savedBytes = (hY - hYgivenX) * pairs.size() / 8.0;
      totalSaved += savedBytes;
      System.out.printf(
          "%-10s %12.3f %12.3f %12.3f %,14.0f%n",
          REGION_NAMES[r], hY, hYgivenX, hY - hYgivenX, savedBytes);
    }
    System.out.printf("total saved by y|x conditioning: %,.0f bytes%n%n", totalSaved);
  }

  private static void reportC2(List<List<int[]>> regionPairs) {
    System.out.printf("--- C2: codeable (bucket) vs raw passthrough bits ---%n");
    System.out.printf(
        "%-10s %12s %12s %14s %14s%n", "region", "H(bucket)", "raw b/val", "bucket bytes", "raw bytes");
    var bucketTotal = 0.0;
    var rawTotal = 0.0;
    for (var r = 0; r < 3; r++) {
      final var pairs = regionPairs.get(r);
      if (pairs.isEmpty()) {
        continue;
      }
      final var buckets = new ArrayList<Integer>(pairs.size() * 2);
      var raw = 0L;
      for (final var p : pairs) {
        for (final var v : p) {
          buckets.add(bucket(v));
          raw += rawBits(v);
        }
      }
      final var hBucket = entropyOf(buckets);
      final var bucketBytes = hBucket * buckets.size() / 8.0;
      final var rawBytes = raw / 8.0;
      bucketTotal += bucketBytes;
      rawTotal += rawBytes;
      System.out.printf(
          "%-10s %12.3f %12.3f %,14.0f %,14.0f%n",
          REGION_NAMES[r], hBucket, (double) raw / buckets.size(), bucketBytes, rawBytes);
    }
    System.out.printf(
        "bucket+raw total: %,.0f bytes (codeable share %.1f%%)%n%n",
        bucketTotal + rawTotal, 100.0 * bucketTotal / (bucketTotal + rawTotal));
  }

  private static void reportC3(List<List<int[]>> regionPairs) {
    System.out.printf("--- C3: order-1 over the bucket alphabet ---%n");
    System.out.printf("%-10s %12s %12s %14s%n", "region", "H(bucket)", "H(b|prev b)", "saved bytes");
    var totalSaved = 0.0;
    for (var r = 0; r < 3; r++) {
      final var pairs = regionPairs.get(r);
      if (pairs.isEmpty()) {
        continue;
      }
      // Per component: x is conditioned on the previous vertex's x bucket, y on the previous y.
      final var buckets = new ArrayList<Integer>(pairs.size() * 2);
      final var withPrev = new ArrayList<int[]>(pairs.size() * 2);
      final var prev = new int[] {0, 0};
      for (final var p : pairs) {
        for (var c = 0; c < 2; c++) {
          final var b = bucket(p[c]);
          buckets.add(b);
          withPrev.add(new int[] {prev[c], b});
          prev[c] = b;
        }
      }
      final var hBucket = entropyOf(buckets);
      final var hCond = conditionalEntropy(withPrev);
      final var savedBytes = (hBucket - hCond) * buckets.size() / 8.0;
      totalSaved += savedBytes;
      System.out.printf(
          "%-10s %12.3f %12.3f %,14.0f%n", REGION_NAMES[r], hBucket, hCond, savedBytes);
    }
    System.out.printf("total saved by order-1 bucket context: %,.0f bytes%n", totalSaved);
  }

  /**
   * C4 — the region split, priced on the alphabet rANS actually codes. {@link EntropyCli}'s mixture
   * penalty is computed over the full residual alphabet, but a bucket coder never sees that
   * alphabet: it codes bit-length buckets and passes the sub-bucket bits through raw. The raw bits
   * are unaffected by any split, so the only thing a per-region table can improve is the bucket
   * entropy — which is what this measures. A gain here is the entire case for giving each region
   * its own frequency table.
   */
  private static void reportC4(List<List<int[]>> regionPairs) {
    System.out.printf("%n--- C4: does the region split help the bucket alphabet? ---%n");
    final var pooled = new ArrayList<Integer>();
    var perRegionBits = 0.0;
    var totalSymbols = 0;
    for (var r = 0; r < 3; r++) {
      final var pairs = regionPairs.get(r);
      if (pairs.isEmpty()) {
        continue;
      }
      final var buckets = new ArrayList<Integer>(pairs.size() * 2);
      for (final var p : pairs) {
        buckets.add(bucket(p[0]));
        buckets.add(bucket(p[1]));
      }
      pooled.addAll(buckets);
      perRegionBits += entropyOf(buckets) * buckets.size();
      totalSymbols += buckets.size();
    }
    if (totalSymbols == 0) {
      return;
    }
    final var pooledBytes = entropyOf(pooled) * pooled.size() / 8.0;
    final var splitBytes = perRegionBits / 8.0;
    // Each region needs its own table; the shared-table case ships one.
    final var extraTableBytes = 2 * MAX_SYMBOL * 2;
    System.out.printf("one shared bucket table   -> %,12.0f bytes%n", pooledBytes);
    System.out.printf("per-region bucket tables  -> %,12.0f bytes (+ %d bytes extra tables)%n",
        splitBytes, extraTableBytes);
    System.out.printf("net gain from splitting:     %,12.0f bytes (%.2f%%)%n",
        pooledBytes - splitBytes - extraTableBytes,
        100.0 * (pooledBytes - splitBytes - extraTableBytes) / pooledBytes);
  }

  /** Bucket alphabet size, mirroring {@code RansCodec}. */
  private static final int MAX_SYMBOL = 33;

}
