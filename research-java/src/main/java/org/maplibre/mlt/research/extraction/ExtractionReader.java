package org.maplibre.mlt.research.extraction;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.Encoding.BufferContext;
import org.maplibre.mlt.research.extraction.ExtractionWriter.ManifestEntry;
import org.maplibre.mlt.research.extraction.ExtractionWriter.Sidecar;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads back what {@link ExtractionWriter} wrote: the manifest, and each entry's {@code .bin} plus
 * {@code .json} sidecar as a {@link BufferContext}.
 *
 * <p>Every CLI over an extraction directory ({@code BenchmarkCli}, {@code RingPriorCli}, and the
 * measurement-only probes) needs exactly this, and each used to carry its own copy. It lives next
 * to the writer so the two file formats stay described in one place.
 */
public final class ExtractionReader {
    private ExtractionReader() {
    }

    private static final Gson GSON = new Gson();

    /**
     * Reads {@code manifest.json} from an extraction directory.
     */
    public static List<ManifestEntry> readManifest(Path inDir) throws IOException {
        final Type manifestType = new TypeToken<List<ManifestEntry>>() {
        }.getType();
        return GSON.fromJson(Files.readString(inDir.resolve("manifest.json")), manifestType);
    }

    /**
     * Rebuilds a {@link BufferContext} from an extraction's {@code .bin} plus its sidecar.
     */
    public static BufferContext readContext(Path inDir, ManifestEntry entry) throws IOException {
        final var buf =
                ByteBuffer.wrap(Files.readAllBytes(inDir.resolve(entry.binFile())))
                        .order(ByteOrder.LITTLE_ENDIAN);
        final var vertices = new ArrayList<Vertex>(entry.vertexCount());
        for (var i = 0; i < entry.vertexCount(); i++) {
            vertices.add(new Vertex(buf.getInt(), buf.getInt()));
        }
        final var sidecar =
                GSON.fromJson(Files.readString(inDir.resolve(entry.jsonFile())), Sidecar.class);
        return new BufferContext(
                vertices,
                sidecar.geometryTypes(),
                sidecar.numGeometries(),
                sidecar.numParts(),
                sidecar.numRings());
    }
}
