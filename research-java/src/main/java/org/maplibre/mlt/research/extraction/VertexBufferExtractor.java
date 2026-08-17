package org.maplibre.mlt.research.extraction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.MultiPoint;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.maplibre.mlt.converter.encodings.GeometryEncoder;
import org.maplibre.mlt.converter.geometry.GeometryType;
import org.maplibre.mlt.converter.geometry.Vertex;

/**
 * Flattens JTS geometries into the same {@code (geometryTypes, numGeometries, numParts,
 * numRings, vertexBuffer)} streams that MLT's {@code GeometryEncoder} produces for a feature
 * table's plain (non-dictionary, non-tessellated) geometry column.
 *
 * <p>This is a verbatim copy of the private {@code GeometryEncoder.prepareGeometry} and its
 * helpers ({@code flatPolygon}, {@code flatLineString}, {@code ringToLineString}, {@code
 * addLineString}, {@code containsPolygon}) from {@code maplibre-tile-spec/java/mlt-core}
 * (org.maplibre.mlt.converter.encodings.GeometryEncoder), copied rather than called because the
 * original method is private. {@link GeometryEncoderFidelityTest} pins this copy against the
 * original via reflection so submodule drift is caught, not hidden.
 *
 * <p>Rules preserved from the original (each a correctness trap if reimplemented from the spec
 * alone, since the spec text does not state them):
 *
 * <ul>
 *   <li>Polygon/ring closing vertices are dropped (last coordinate, which duplicates the first,
 *       is not stored).
 *   <li>{@code numParts} = 1 + interior ring count per polygon; {@code numRings} = vertex count
 *       per ring.
 *   <li>LineString vertex counts go to {@code numRings} if the geometry list contains any
 *       (Multi)Polygon, otherwise to {@code numParts} — decided once per column.
 *   <li>{@code GeometryCollection} is flattened recursively, filtering children without geometry
 *       via {@link GeometryEncoder#hasGeometry(Geometry)}.
 *   <li>Coordinates are truncated to {@code int} — only safe because the MVT decoder upstream
 *       must keep tile-local integer coordinates (no autoscaling).
 * </ul>
 */
public final class VertexBufferExtractor {

  private VertexBufferExtractor() {}

  /** One flattened geometry column, matching MLT's plain VertexBuffer for one feature table. */
  public record ExtractedColumn(
      List<Integer> geometryTypes,
      List<Integer> numGeometries,
      List<Integer> numParts,
      List<Integer> numRings,
      List<Vertex> vertexBuffer) {}

  /**
   * Flattens a list of feature geometries (already filtered to {@link
   * GeometryEncoder#hasGeometry}, in feature order) into one {@link ExtractedColumn}, exactly as
   * MLT would for the plain (non-dictionary) VertexBuffer of a feature table's geometry column.
   */
  public static ExtractedColumn extract(List<Geometry> geometries) {
    final var geometryTypes = new ArrayList<Integer>();
    final var numGeometries = new ArrayList<Integer>();
    final var numParts = new ArrayList<Integer>();
    final var numRings = new ArrayList<Integer>();
    final var vertexBuffer = new ArrayList<Vertex>();
    prepareGeometry(geometries, numGeometries, geometryTypes, vertexBuffer, numParts, numRings);
    return new ExtractedColumn(geometryTypes, numGeometries, numParts, numRings, vertexBuffer);
  }

  // ---- Verbatim copy of GeometryEncoder.prepareGeometry and its helpers below ----
  // (tessellation-related parameters and calls removed: this extractor does not tessellate)

  private static void prepareGeometry(
      List<Geometry> geometries,
      ArrayList<Integer> numGeometries,
      ArrayList<Integer> geometryTypes,
      ArrayList<Vertex> vertexBuffer,
      ArrayList<Integer> numParts,
      ArrayList<Integer> numRings) {
    final var containsPolygon = containsPolygon(geometries);
    for (var geometry : geometries) {
      switch (geometry) {
        case Point point -> {
          geometryTypes.add(GeometryType.POINT.ordinal());
          vertexBuffer.add(new Vertex((int) point.getX(), (int) point.getY()));
        }
        case LineString lineString -> {
          geometryTypes.add(GeometryType.LINESTRING.ordinal());
          final var numVertices = lineString.getCoordinates().length;
          addLineString(containsPolygon, numVertices, numParts, numRings);
          vertexBuffer.addAll(flatLineString(lineString));
        }
        case Polygon polygon -> {
          geometryTypes.add(GeometryType.POLYGON.ordinal());
          flatPolygon(polygon, vertexBuffer, numParts, numRings);
        }
        case MultiLineString multiLineString -> {
          geometryTypes.add(GeometryType.MULTILINESTRING.ordinal());
          final var numLineStrings = multiLineString.getNumGeometries();
          numGeometries.add(numLineStrings);
          for (var i = 0; i < numLineStrings; i++) {
            final var lineString = (LineString) multiLineString.getGeometryN(i);
            final var numVertices = lineString.getCoordinates().length;
            addLineString(containsPolygon, numVertices, numParts, numRings);
            vertexBuffer.addAll(flatLineString(lineString));
          }
        }
        case MultiPolygon multiPolygon -> {
          geometryTypes.add(GeometryType.MULTIPOLYGON.ordinal());
          final var numPolygons = multiPolygon.getNumGeometries();
          numGeometries.add(numPolygons);
          for (var i = 0; i < numPolygons; i++) {
            final var polygon = (Polygon) multiPolygon.getGeometryN(i);
            flatPolygon(polygon, vertexBuffer, numParts, numRings);
          }
        }
        case MultiPoint multiPoint -> {
          geometryTypes.add(GeometryType.MULTIPOINT.ordinal());
          final var numPoints = multiPoint.getNumGeometries();
          numGeometries.add(numPoints);
          for (var i = 0; i < numPoints; i++) {
            final var point = (Point) multiPoint.getGeometryN(i);
            vertexBuffer.add(new Vertex((int) point.getX(), (int) point.getY()));
          }
        }
        case GeometryCollection geometryCollection -> {
          final var nestedGeometries =
              IntStream.range(0, geometryCollection.getNumGeometries())
                  .mapToObj(geometryCollection::getGeometryN)
                  .filter(GeometryEncoder::hasGeometry)
                  .toList();
          prepareGeometry(
              nestedGeometries, numGeometries, geometryTypes, vertexBuffer, numParts, numRings);
        }
        default ->
            throw new IllegalArgumentException(
                "Specified geometry type is not (yet) supported: " + geometry.getGeometryType());
      }
    }
  }

  private static boolean containsPolygon(List<Geometry> geometries) {
    return geometries.stream()
        .map(Geometry::getGeometryType)
        .anyMatch(
            t -> t.equals(Geometry.TYPENAME_MULTIPOLYGON) || t.equals(Geometry.TYPENAME_POLYGON));
  }

  private static void addLineString(
      boolean containsPolygon, int numVertices, List<Integer> numParts, List<Integer> numRings) {
    /* Depending on the max geometry type in the column add to the numRings or numParts stream */
    if (containsPolygon) {
      numRings.add(numVertices);
    } else {
      numParts.add(numVertices);
    }
  }

  private static List<Vertex> flatLineString(LineString lineString) {
    return Arrays.stream(lineString.getCoordinates())
        .map(v -> new Vertex((int) v.x, (int) v.y))
        .toList();
  }

  private static LineString ringToLineString(LinearRing ring, GeometryFactory factory) {
    return factory.createLineString(
        Arrays.copyOf(ring.getCoordinates(), ring.getCoordinates().length - 1));
  }

  private static void flatPolygon(
      Polygon polygon, ArrayList<Vertex> vertices, List<Integer> partSize, List<Integer> ringSize) {
    final var factory = new GeometryFactory();

    // 1 for the outline, 1 for each interior ring
    partSize.add(1 + polygon.getNumInteriorRing());

    final var exteriorRing = polygon.getExteriorRing();
    // If the ring isn't closed, our assumptions about the number of vertices will be incorrect.
    assert (exteriorRing.isClosed());

    final var shell = ringToLineString(exteriorRing, factory);
    vertices.addAll(flatLineString(shell));
    ringSize.add(shell.getNumPoints());

    for (var i = 0; i < polygon.getNumInteriorRing(); i++) {
      final var interiorRing = polygon.getInteriorRingN(i);
      assert (interiorRing.isClosed());

      final var ring = ringToLineString(interiorRing, factory);
      vertices.addAll(flatLineString(ring));
      ringSize.add(ring.getNumPoints());
    }
  }
}
