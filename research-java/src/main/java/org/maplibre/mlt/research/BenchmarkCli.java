package org.maplibre.mlt.research;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.Baselines;
import org.maplibre.mlt.research.encoding.Encoding;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.EncodedBuffer;
import org.maplibre.mlt.research.extraction.ExtractionWriter.ManifestEntry;
import org.maplibre.mlt.research.extraction.ExtractionWriter.Sidecar;

/**
 * Encodes every extracted (tile, layer) VertexBuffer with every registered encoding and reports the
 * resulting sizes, overall and per layer.
 *
 * <p>The per-layer breakdown is the point. A corpus total hides the case that decides whether an
 * encoding is useful: one that is much better on {@code transportation} (long smooth polylines) and
 * much worse on {@code building} can be net-negative overall and still worth shipping on
 * {@code transportation} alone. A single corpus number cannot express that; a size per (encoding,
 * layer) can.
 *
 * <p>Every encoding is round-tripped on every buffer — encode, decode, compare against the
 * extraction — and the run fails naming the encoding, tile and layer if any does not reconstruct the
 * buffer exactly. This is not optional and has no flag: an encoding that discards data reports a
 * small size and would look like the best result in the table <em>because</em> it is broken, so a
 * size from an unverified encoding is not a result.
 *
 * <p>Usage: {@code <extractionDir> [--csv <file>] [--layer <name>]}
 */
public final class BenchmarkCli {
  private BenchmarkCli() {}

  private static final Gson GSON = new Gson();

  /** Layers shown individually in the breakdown; the rest are folded into one {@code (other)} row. */
  private static final int TOP_LAYERS = 6;

  /** Running byte totals for one encoding within one group (the whole corpus, or one layer). */
  private static final class Totals {
    private final Map<String, Long> bytes = new LinkedHashMap<>();
    private int buffers;

    void add(String encoding, long encodedBytes) {
      bytes.merge(encoding, encodedBytes, Long::sum);
    }

    long get(String encoding) {
      return bytes.getOrDefault(encoding, 0L);
    }
  }

  public static void main(String[] args) throws IOException {
    var opts = Options.parse(args);
    var inDir = Path.of(opts.inDir);

    Type manifestType = new TypeToken<List<ManifestEntry>>() {}.getType();
    List<ManifestEntry> manifest =
        GSON.fromJson(Files.readString(inDir.resolve("manifest.json")), manifestType);

    var encodings = Baselines.all();
    var names = encodings.stream().map(Encoding::name).toList();

    var overall = new Totals();
    var byLayer = new TreeMap<String, Totals>();
    var byGeometry = new TreeMap<String, Totals>();
    long rawTotal = 0;

    Writer csv = null;
    if (opts.csvFile != null) {
      var csvPath = Path.of(opts.csvFile);
      if (csvPath.getParent() != null) {
        Files.createDirectories(csvPath.getParent());
      }
      csv = Files.newBufferedWriter(csvPath);
      csv.write("z,x,y,layerName,vertexCount,rawBytes,dominantGeometryType," + String.join(",", names));
      csv.write("\n");
    }

    try {
      for (ManifestEntry entry : manifest) {
        if (opts.layer != null && !opts.layer.equals(entry.layerName())) {
          continue;
        }
        var ctx = readContext(inDir, entry);
        if (ctx.vertices().isEmpty()) {
          continue;
        }

        var geometry = dominantGeometryType(ctx.geometryTypes());
        var rawBytes = entry.vertexCount() * 2 * Integer.BYTES;
        rawTotal += rawBytes;

        var layerTotals = byLayer.computeIfAbsent(entry.layerName(), k -> new Totals());
        var geometryTotals = byGeometry.computeIfAbsent(geometry, k -> new Totals());
        overall.buffers++;
        layerTotals.buffers++;
        geometryTotals.buffers++;

        var sizes = new ArrayList<Integer>(encodings.size());
        for (var encoding : encodings) {
          var encoded = encoding.encode(ctx);
          verifyRoundTrip(entry, ctx, encoding, encoded);

          var total = encoded.totalBytes();
          sizes.add(total);
          overall.add(encoding.name(), total);
          layerTotals.add(encoding.name(), total);
          geometryTotals.add(encoding.name(), total);
        }

        if (csv != null) {
          csv.write(
              String.join(
                  ",",
                  String.valueOf(entry.z()),
                  String.valueOf(entry.x()),
                  String.valueOf(entry.y()),
                  entry.layerName(),
                  String.valueOf(entry.vertexCount()),
                  String.valueOf(rawBytes),
                  geometry));
          for (var size : sizes) {
            csv.write("," + size);
          }
          csv.write("\n");
        }
      }
    } finally {
      if (csv != null) {
        csv.close();
      }
    }

    if (overall.buffers == 0) {
      System.out.println("No buffers matched.");
      return;
    }

    report(names, overall, byLayer, byGeometry, rawTotal);
    if (opts.csvFile != null) {
      System.out.printf("%nWrote %s%n", opts.csvFile);
    }
  }

  // --- reporting ----------------------------------------------------------

  /**
   * Prints the corpus totals, then the same numbers per layer and per dominant geometry type.
   *
   * <p>Two different questions are answered per row, and they are deliberately separate columns.
   * The percentages are measured against {@code ref}, the best <em>baseline</em> for that group —
   * that is the honest yardstick, since crediting an encoding for beating a baseline that was not
   * the one to beat would overstate it. The starred cell and the {@code best} column name the
   * smallest encoding overall, which is what you would actually ship for that group and may well be
   * a research encoding rather than a baseline.
   */
  private static void report(
      List<String> names,
      Totals overall,
      Map<String, Totals> byLayer,
      Map<String, Totals> byGeometry,
      long rawTotal) {

    System.out.printf(
        "%d buffers, %s raw bytes%n%n", overall.buffers, String.format("%,d", rawTotal));

    var width = names.stream().mapToInt(String::length).max().orElse(20);
    System.out.printf(
        "%-" + width + "s %14s %12s %12s%n", "encoding", "bytes", "vs raw", "vs ref");
    var reference = bestBaseline(overall);
    var winner = winner(names, overall);
    for (var name : names) {
      var bytes = overall.get(name);
      System.out.printf(
          "%-" + width + "s %14s %11.1f%% %12s%s%n",
          name,
          String.format("%,d", bytes),
          100.0 * bytes / rawTotal,
          delta(bytes, reference),
          name.equals(winner) ? "  *" : "");
    }
    System.out.printf(
        "%n* smallest overall. \"ref\" is the best baseline (%s), what percentages compare to.%n",
        referenceName(overall));

    segment("layer", names, byLayer, TOP_LAYERS);
    segment("geometry", names, byGeometry, Integer.MAX_VALUE);
  }

  /**
   * One breakdown table: a row per group, a column per encoding, sorted by group size and cut to
   * {@code limit} rows. Everything past the cut is folded into a single {@code (other)} row rather
   * than dropped, so the column totals still add up to the corpus figures above.
   */
  private static void segment(
      String label, List<String> names, Map<String, Totals> groups, int limit) {
    System.out.printf("%n--- by %s ---%n", label);

    /* Ranked by bytes, not by buffer count: a buffer is one (tile, layer) pair, so counting them
     * measures how many tiles a layer appears in, not how much data it is. `building` is 311
     * buffers and 3.2 MB; `place` is 1090 buffers and 0.24 MB. Since the question is where an
     * encoding can save bytes, the big layers are the byte-heavy ones. */
    var sorted = new ArrayList<>(groups.entrySet());
    sorted.sort((a, b) -> Long.compare(bestBaseline(b.getValue()), bestBaseline(a.getValue())));

    var shown = new ArrayList<Map.Entry<String, Totals>>(sorted.subList(0, Math.min(limit, sorted.size())));
    if (sorted.size() > limit) {
      var rest = new Totals();
      for (var group : sorted.subList(limit, sorted.size())) {
        rest.buffers += group.getValue().buffers;
        for (var name : names) {
          rest.add(name, group.getValue().get(name));
        }
      }
      shown.add(Map.entry("(other, " + (sorted.size() - limit) + ")", rest));
    }

    var width =
        Math.max(label.length(), shown.stream().mapToInt(g -> g.getKey().length()).max().orElse(8));
    var nameWidth = names.stream().mapToInt(String::length).max().orElse(20);
    var corpus = shown.stream().mapToLong(g -> bestBaseline(g.getValue())).sum();
    var header =
        new StringBuilder(String.format("%-" + width + "s %8s %7s", label, "buffers", "share"));
    for (var name : names) {
      header.append(String.format(" %22s", abbreviate(name)));
    }
    header.append(String.format("   %-" + nameWidth + "s", "best"));
    System.out.println(header);

    for (var group : shown) {
      var totals = group.getValue();
      var reference = bestBaseline(totals);
      var winner = winner(names, totals);
      var row =
          new StringBuilder(
              String.format(
                  "%-" + width + "s %8d %6.1f%%",
                  group.getKey(),
                  totals.buffers,
                  corpus == 0 ? 0.0 : 100.0 * reference / corpus));
      for (var name : names) {
        var bytes = totals.get(name);
        /* The winning cell is starred; the percentages are all against the baseline reference,
         * which is a different question and can land on a different column. */
        row.append(
            String.format(
                " %13s %7s%s",
                String.format("%,d", bytes),
                delta(bytes, reference),
                name.equals(winner) ? "*" : " "));
      }
      row.append(String.format("   %-" + nameWidth + "s", winner));
      System.out.println(row);
    }
  }

  /** The smallest encoding for this group — what you would actually ship for it. */
  private static String winner(List<String> names, Totals totals) {
    return names.stream()
        .filter(name -> totals.get(name) > 0)
        .min((a, b) -> Long.compare(totals.get(a), totals.get(b)))
        .orElse("-");
  }

  /** Which baseline the percentages in this group are measured against. */
  private static String referenceName(Totals totals) {
    return Baselines.baseline().stream()
        .map(Encoding::name)
        .filter(name -> totals.get(name) > 0)
        .min((a, b) -> Long.compare(totals.get(a), totals.get(b)))
        .orElse("-");
  }

  /**
   * The smaller of the two baselines for this group — what a new encoding has to beat. Comparing
   * against the better baseline rather than a fixed one avoids crediting an encoding for beating an
   * opponent that was never the one to beat.
   */
  private static long bestBaseline(Totals totals) {
    return Baselines.baseline().stream()
        .map(Encoding::name)
        .mapToLong(totals::get)
        .filter(bytes -> bytes > 0)
        .min()
        .orElse(0);
  }

  /**
   * A signed percentage against the baseline reference; negative means smaller, which is the goal.
   * {@code ref} marks the reference cell itself — the best <em>baseline</em>, which is not
   * necessarily the smallest encoding in the row (that one is starred instead).
   */
  private static String delta(long bytes, long reference) {
    if (reference == 0) {
      return "-";
    }
    if (bytes == reference) {
      return "ref";
    }
    return String.format("%+.1f%%", 100.0 * (bytes - reference) / reference);
  }

  private static String abbreviate(String name) {
    return name.length() <= 22 ? name : name.substring(0, 21) + "…";
  }

  // --- per-buffer work ----------------------------------------------------

  /**
   * Decodes what was just encoded and fails the run if it does not reconstruct the buffer exactly.
   *
   * <p>Runs unconditionally: a size from an encoding that loses data is not a result, and a lossy
   * encoder would otherwise report the smallest number in the table precisely <em>because</em> it
   * discards information.
   */
  private static void verifyRoundTrip(
      ManifestEntry entry, BufferContext ctx, Encoding encoding, EncodedBuffer encoded) {
    var decoded = encoding.decode(encoded, ctx.vertices().size());
    if (!ctx.vertices().equals(decoded)) {
      throw new IllegalStateException(
          String.format(
              "%s is not lossless on %d/%d/%d %s (%d vertices, %d payload bytes): decoded buffer "
                  + "differs from the extraction",
              encoding.name(),
              entry.z(),
              entry.x(),
              entry.y(),
              entry.layerName(),
              ctx.vertices().size(),
              encoded.payload().length));
    }
  }

  /** Rebuilds a {@link BufferContext} from an extraction's {@code .bin} plus its sidecar. */
  private static BufferContext readContext(Path inDir, ManifestEntry entry) throws IOException {
    var buf =
        ByteBuffer.wrap(Files.readAllBytes(inDir.resolve(entry.binFile())))
            .order(ByteOrder.LITTLE_ENDIAN);
    var vertices = new ArrayList<Vertex>(entry.vertexCount());
    for (var i = 0; i < entry.vertexCount(); i++) {
      vertices.add(new Vertex(buf.getInt(), buf.getInt()));
    }

    var sidecar = GSON.fromJson(Files.readString(inDir.resolve(entry.jsonFile())), Sidecar.class);
    return new BufferContext(
        vertices,
        sidecar.geometryTypes(),
        sidecar.numGeometries(),
        sidecar.numParts(),
        sidecar.numRings());
  }

  /**
   * The most frequent geometry type in the column. Buffers with mixed geometry types are common
   * (~20% of this corpus), so this is a label, not a partition.
   */
  private static String dominantGeometryType(List<Integer> geometryTypes) {
    if (geometryTypes == null || geometryTypes.isEmpty()) {
      return "UNKNOWN";
    }
    var counts = new int[8];
    for (var type : geometryTypes) {
      if (type >= 0 && type < counts.length) {
        counts[type]++;
      }
    }
    var best = 0;
    for (var i = 1; i < counts.length; i++) {
      if (counts[i] > counts[best]) {
        best = i;
      }
    }
    var names =
        new String[] {
          "POINT", "LINESTRING", "POLYGON", "MULTIPOINT", "MULTILINESTRING", "MULTIPOLYGON"
        };
    return best < names.length ? names[best] : "UNKNOWN";
  }

  private record Options(String inDir, String csvFile, String layer) {

    private static final String USAGE = "Usage: <extractionDir> [--csv <file>] [--layer <name>]";

    static Options parse(String[] args) {
      String in = null;
      String csv = null;
      String layer = null;
      for (var i = 0; i < args.length; i++) {
        switch (args[i]) {
          case "--csv" -> csv = args[++i];
          case "--layer" -> layer = args[++i];
          case "--help", "-h" -> throw new IllegalArgumentException(USAGE);
          default -> {
            if (args[i].startsWith("-")) {
              throw new IllegalArgumentException("Unknown argument: " + args[i] + "\n" + USAGE);
            }
            if (in != null) {
              throw new IllegalArgumentException(
                  "Unexpected second input directory: " + args[i] + "\n" + USAGE);
            }
            in = args[i];
          }
        }
      }
      if (in == null) {
        throw new IllegalArgumentException(USAGE);
      }
      return new Options(in, csv, layer);
    }
  }
}
