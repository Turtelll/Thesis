package org.maplibre.mlt.research;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import com.google.gson.reflect.TypeToken;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.Baseline;
import org.maplibre.mlt.research.extraction.ExtractCli;
import org.maplibre.mlt.research.extraction.ExtractionWriter.ManifestEntry;

/**
 * Runs every {@link Baseline} variant over each (tile, layer) VertexBuffer produced by
 * {@link ExtractCli}, writing one encoded file per variant and recording output sizes.
 *
 * <p>Usage: {@code --in <extractionDir> --out <dir>}
 */
public final class EncodeCli {
  private EncodeCli() {}

  private static final Gson GSON = new Gson();

  /** One row of the size-benchmark manifest. */
  public record EncodedEntry(
      int z, int x, int y, String layerName, String encoding, int vertexCount, int encodedBytes, String file) {}

  public static void main(String[] args) throws IOException {
    var opts = Options.parse(args);
    var inDir = Path.of(opts.inDir);
    var outDir = Path.of(opts.outDir);
    Files.createDirectories(outDir);

    Type manifestType = new TypeToken<List<ManifestEntry>>() {}.getType();
    List<ManifestEntry> manifest =
        GSON.fromJson(Files.readString(inDir.resolve("manifest.json")), manifestType);

    var encodedManifest = new ArrayList<EncodedEntry>();
    for (ManifestEntry entry : manifest) {
      List<Vertex> vertices = readVertexBuffer(inDir.resolve(entry.binFile()), entry.vertexCount());
      String baseName = entry.z() + "_" + entry.x() + "_" + entry.y() + "_" + entry.layerName();

      for (Baseline encoding : Baseline.values()) {
        byte[] encoded = encoding.codec().encode(vertices);
        String fileName = baseName + "." + encoding.name() + ".bin";
        try (OutputStream out = Files.newOutputStream(outDir.resolve(fileName))) {
          out.write(encoded);
        }
        encodedManifest.add(
            new EncodedEntry(
                entry.z(),
                entry.x(),
                entry.y(),
                entry.layerName(),
                encoding.name(),
                entry.vertexCount(),
                encoded.length,
                fileName));
      }
    }

    Files.writeString(outDir.resolve("encoded-manifest.json"), GSON.toJson(encodedManifest));
    System.out.printf(
        "Encoded %d (tile, layer) extractions with %d variant(s) to %s%n",
        manifest.size(), Baseline.values().length, opts.outDir);
  }

  private static List<Vertex> readVertexBuffer(Path binFile, int vertexCount) throws IOException {
    var buf = ByteBuffer.wrap(Files.readAllBytes(binFile)).order(ByteOrder.LITTLE_ENDIAN);
    var vertices = new ArrayList<Vertex>(vertexCount);
    for (int i = 0; i < vertexCount; i++) {
      vertices.add(new Vertex(buf.getInt(), buf.getInt()));
    }
    return vertices;
  }

  private record Options(String inDir, String outDir) {
    static Options parse(String[] args) {
      String in = null;
      String out = null;
      for (int i = 0; i < args.length; i++) {
        switch (args[i]) {
          case "--in" -> in = args[++i];
          case "--out" -> out = args[++i];
          default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
        }
      }
      if (in == null || out == null) {
        throw new IllegalArgumentException("Usage: --in <extractionDir> --out <dir>");
      }
      return new Options(in, out);
    }
  }
}
