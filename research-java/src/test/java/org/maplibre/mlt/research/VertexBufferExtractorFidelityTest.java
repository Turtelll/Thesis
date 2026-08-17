package org.maplibre.mlt.research;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import me.lemire.integercompression.IntWrapper;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.maplibre.mlt.converter.encodings.GeometryEncoder;
import org.maplibre.mlt.decoder.DecodingUtils;
import org.maplibre.mlt.research.VertexBufferExtractor.ExtractedColumn;

/**
 * Proves {@link VertexBufferExtractor} matches the real, private {@code
 * GeometryEncoder.prepareGeometry} it was copied from — this is what makes the extracted
 * baseline trustworthy: without it, submodule drift (or a copy-paste slip) would silently corrupt
 * every later encoding benchmark.
 */
class VertexBufferExtractorFidelityTest {

  private static final GeometryFactory GF = new GeometryFactory();

  // ---- (a) Pin against the real encoder via reflection ----

  private record ReferenceResult(
      List<Integer> geometryTypes,
      List<Integer> numGeometries,
      List<Integer> numParts,
      List<Integer> numRings,
      List<org.maplibre.mlt.converter.geometry.Vertex> vertexBuffer) {}

  /** Reflectively invokes the real, private {@code GeometryEncoder.prepareGeometry}. */
  private static ReferenceResult runReferencePrepareGeometry(List<Geometry> geometries)
      throws Exception {
    Method method =
        GeometryEncoder.class.getDeclaredMethod(
            "prepareGeometry",
            List.class,
            ArrayList.class,
            ArrayList.class,
            ArrayList.class,
            ArrayList.class,
            ArrayList.class,
            ArrayList.class,
            ArrayList.class,
            URI.class);
    method.setAccessible(true);

    var numGeometries = new ArrayList<Integer>();
    var geometryTypes = new ArrayList<Integer>();
    var vertexBuffer = new ArrayList<org.maplibre.mlt.converter.geometry.Vertex>();
    var numParts = new ArrayList<Integer>();
    var numRings = new ArrayList<Integer>();
    var numTriangles = new ArrayList<Integer>();
    var indexBuffer = new ArrayList<Integer>();

    method.invoke(
        null,
        geometries,
        numGeometries,
        geometryTypes,
        vertexBuffer,
        numParts,
        numRings,
        numTriangles,
        indexBuffer,
        (URI) null);

    return new ReferenceResult(geometryTypes, numGeometries, numParts, numRings, vertexBuffer);
  }

  private static void assertMatchesReference(List<Geometry> geometries) throws Exception {
    var reference = runReferencePrepareGeometry(geometries);
    var extracted = VertexBufferExtractor.extract(geometries);

    assertEquals(reference.geometryTypes(), extracted.geometryTypes(), "geometryTypes");
    assertEquals(reference.numGeometries(), extracted.numGeometries(), "numGeometries");
    assertEquals(reference.numParts(), extracted.numParts(), "numParts");
    assertEquals(reference.numRings(), extracted.numRings(), "numRings");
    assertEquals(reference.vertexBuffer(), extracted.vertexBuffer(), "vertexBuffer");
  }

  @Test
  void matchesReference_simplePolygon() throws Exception {
    assertMatchesReference(List.of(square(0, 0, 10)));
  }

  @Test
  void matchesReference_polygonWithHole() throws Exception {
    var shell = ring(0, 0, 10);
    var hole = ring(2, 2, 4);
    var polygon = GF.createPolygon(shell, new LinearRing[] {hole});
    assertMatchesReference(List.of(polygon));
  }

  @Test
  void matchesReference_multiPolygon() throws Exception {
    var multi = GF.createMultiPolygon(new Polygon[] {square(0, 0, 5), square(20, 20, 5)});
    assertMatchesReference(List.of(multi));
  }

  @Test
  void matchesReference_mixedLineStringAndPolygon() throws Exception {
    var line = GF.createLineString(new Coordinate[] {coord(0, 0), coord(5, 5), coord(10, 0)});
    assertMatchesReference(List.of(line, square(0, 0, 10)));
  }

  @Test
  void matchesReference_lineStringOnlyColumn_usesNumPartsNotNumRings() throws Exception {
    var line = GF.createLineString(new Coordinate[] {coord(0, 0), coord(5, 5), coord(10, 0)});
    var column = VertexBufferExtractor.extract(List.of(line));
    // No polygon in the column -> length goes to numParts, numRings stays empty.
    assertEquals(List.of(3), column.numParts());
    assertEquals(List.of(), column.numRings());
    assertMatchesReference(List.of(line));
  }

  @Test
  void matchesReference_multiLineString() throws Exception {
    var multi =
        GF.createMultiLineString(
            new org.locationtech.jts.geom.LineString[] {
              GF.createLineString(new Coordinate[] {coord(0, 0), coord(1, 1)}),
              GF.createLineString(new Coordinate[] {coord(2, 2), coord(3, 3), coord(4, 4)}),
            });
    assertMatchesReference(List.of(multi));
  }

  @Test
  void matchesReference_multiPoint() throws Exception {
    var multi = GF.createMultiPoint(new Coordinate[] {coord(1, 1), coord(2, 2), coord(3, 3)});
    assertMatchesReference(List.of(multi));
  }

  @Test
  void matchesReference_geometryCollection() throws Exception {
    var collection =
        GF.createGeometryCollection(
            new Geometry[] {GF.createPoint(coord(1, 1)), square(0, 0, 10)});
    assertMatchesReference(List.of(collection));
  }

  // ---- (b) Round-trip to geometry: reconstruct and compare to the input ----

  @Test
  void polygonWithHole_reconstructsToSameShellAndHoleVertexCounts() {
    var shell = ring(0, 0, 10);
    var hole = ring(2, 2, 4);
    var polygon = GF.createPolygon(shell, new LinearRing[] {hole});
    ExtractedColumn column = VertexBufferExtractor.extract(List.of(polygon));

    // numParts = 1 outline + 1 hole; numRings = vertex count per ring with closing point
    // dropped (5-vertex closed ring -> 4 stored + re-added on decode).
    assertEquals(List.of(2), column.numParts());
    assertEquals(List.of(4, 4), column.numRings());
    assertEquals(8, column.vertexBuffer().size());

    // Re-add the dropped closing vertex per ring and confirm it matches the original ring.
    var reconstructedShell = closeRing(column.vertexBuffer().subList(0, 4));
    assertArrayEquals(shell.getCoordinates(), reconstructedShell);
  }

  private static Coordinate[] closeRing(List<org.maplibre.mlt.converter.geometry.Vertex> ring) {
    var coords = new Coordinate[ring.size() + 1];
    for (int i = 0; i < ring.size(); i++) {
      coords[i] = new Coordinate(ring.get(i).x(), ring.get(i).y());
    }
    coords[ring.size()] = coords[0];
    return coords;
  }

  // ---- (c) Interleaving/delta round-trip through the real encoder/decoder ----

  @Test
  void vertexBuffer_roundTripsThroughZigZagDeltaVarint() throws Exception {
    var polygon = square(100, 200, 50);
    ExtractedColumn column = VertexBufferExtractor.extract(List.of(polygon));
    var vertices = column.vertexBuffer();

    int[] encoded = GeometryEncoder.zigZagDeltaEncodeVertices(vertices);

    byte[] varintBytes =
        org.maplibre.mlt.converter.encodings.EncodingUtils.encodeVarints(encoded, false, false);
    int[] decodedZigZagDeltas =
        DecodingUtils.decodeVarints(varintBytes, new IntWrapper(0), encoded.length);
    assertArrayEquals(encoded, decodedZigZagDeltas);

    // Undo zigzag + delta manually (componentwise, continuous across the whole buffer) and
    // confirm we get back the original vertex coordinates.
    int prevX = 0;
    int prevY = 0;
    for (int i = 0; i < vertices.size(); i++) {
      int dx = DecodingUtils.decodeZigZag(decodedZigZagDeltas[2 * i]);
      int dy = DecodingUtils.decodeZigZag(decodedZigZagDeltas[2 * i + 1]);
      int x = prevX + dx;
      int y = prevY + dy;
      assertEquals(vertices.get(i).x(), x, "x at vertex " + i);
      assertEquals(vertices.get(i).y(), y, "y at vertex " + i);
      prevX = x;
      prevY = y;
    }
  }

  // ---- helpers ----

  private static Coordinate coord(double x, double y) {
    return new Coordinate(x, y);
  }

  private static LinearRing ring(double originX, double originY, double size) {
    return GF.createLinearRing(
        new Coordinate[] {
          coord(originX, originY),
          coord(originX + size, originY),
          coord(originX + size, originY + size),
          coord(originX, originY + size),
          coord(originX, originY),
        });
  }

  private static Polygon square(double originX, double originY, double size) {
    return GF.createPolygon(ring(originX, originY, size));
  }
}
