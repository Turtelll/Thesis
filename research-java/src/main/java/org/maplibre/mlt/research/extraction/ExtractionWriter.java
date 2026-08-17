package org.maplibre.mlt.research.extraction;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.extraction.VertexBufferExtractor.ExtractedColumn;

/**
 * Persists one {@link ExtractedColumn} per (tile, layer) as a raw int32 binary vertex buffer plus
 * a JSON sidecar carrying the topology streams, and accumulates a manifest across all writes.
 */
public final class ExtractionWriter {

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  /** Sidecar content for one extracted column. Mirrors {@link ExtractedColumn} plus tile/layer identity. */
  public record Sidecar(
      int z,
      int x,
      int y,
      String layerName,
      int tileExtent,
      List<Integer> geometryTypes,
      List<Integer> numGeometries,
      List<Integer> numParts,
      List<Integer> numRings,
      int vertexCount) {}

  /** One row of the top-level manifest. */
  public record ManifestEntry(
      int z, int x, int y, String layerName, int featureCount, int vertexCount, String binFile, String jsonFile) {}

  private final Path outDir;
  private final List<ManifestEntry> manifest = new ArrayList<>();

  public ExtractionWriter(Path outDir) throws IOException {
    this.outDir = outDir;
    Files.createDirectories(outDir);
  }

  /**
   * Writes the .bin (interleaved x,y int32 little-endian, raw undelta'd coordinates) and .json
   * sidecar for one extracted column, and records it in the manifest.
   */
  public void write(int z, int x, int y, String layerName, int tileExtent, int featureCount, ExtractedColumn column)
      throws IOException {
    String baseName = z + "_" + x + "_" + y + "_" + layerName;
    String binFile = baseName + ".bin";
    String jsonFile = baseName + ".json";

    List<Vertex> vertices = column.vertexBuffer();
    ByteBuffer buf = ByteBuffer.allocate(vertices.size() * 2 * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
    for (Vertex v : vertices) {
      buf.putInt(v.x());
      buf.putInt(v.y());
    }
    try (OutputStream out = Files.newOutputStream(outDir.resolve(binFile))) {
      out.write(buf.array());
    }

    var sidecar =
        new Sidecar(
            z,
            x,
            y,
            layerName,
            tileExtent,
            column.geometryTypes(),
            column.numGeometries(),
            column.numParts(),
            column.numRings(),
            vertices.size());
    Files.writeString(outDir.resolve(jsonFile), GSON.toJson(sidecar));

    manifest.add(new ManifestEntry(z, x, y, layerName, featureCount, vertices.size(), binFile, jsonFile));
  }

  /** Writes the accumulated manifest.json. Call once after all {@link #write} calls. */
  public void writeManifest() throws IOException {
    Files.writeString(outDir.resolve("manifest.json"), GSON.toJson(manifest));
  }
}
