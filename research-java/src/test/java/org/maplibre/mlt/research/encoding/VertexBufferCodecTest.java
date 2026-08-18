package org.maplibre.mlt.research.encoding;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.extraction.VertexBufferExtractor;

/**
 * Round-trips real extracted vertex buffers through every registered encoding and compares against
 * the original MVT extraction, per the thesis testing convention (encode, decode, compare).
 *
 * <p>Parameterized over {@link Baselines#all()}, so an encoding added to {@code
 * Baselines.research()} is covered here without writing a new test.
 */
class VertexBufferCodecTest {

  private static final GeometryFactory GF = new GeometryFactory();

  private static List<Encoding> everyEncoding() {
    return Baselines.all();
  }

  @ParameterizedTest
  @MethodSource("everyEncoding")
  void roundTripsExtractedVertexBuffer(Encoding encoding) {
    var column = VertexBufferExtractor.extract(List.of(square(100, 200, 50)));
    var vertices = column.vertexBuffer();

    var encoded = encoding.encode(BufferContext.of(vertices));

    assertEquals(vertices, encoding.decode(encoded, vertices.size()));
  }

  /**
   * A multi-part column, so that any codec conditioning on part boundaries (the forced reset points
   * of the {@code ideen.md} predictor design) is exercised on more than one part.
   */
  @ParameterizedTest
  @MethodSource("everyEncoding")
  void roundTripsMultiPartBuffer(Encoding encoding) {
    var column =
        VertexBufferExtractor.extract(
            List.of(square(0, 0, 10), square(500, 500, 20), square(-300, 40, 5)));
    var vertices = column.vertexBuffer();

    var encoded = encoding.encode(BufferContext.of(vertices));

    assertEquals(vertices, encoding.decode(encoded, vertices.size()));
  }

  @ParameterizedTest
  @MethodSource("everyEncoding")
  void roundTripsSingleVertexBuffer(Encoding encoding) {
    var column = VertexBufferExtractor.extract(List.of(GF.createPoint(new Coordinate(7, 9))));
    var vertices = column.vertexBuffer();

    var encoded = encoding.encode(BufferContext.of(vertices));

    assertEquals(vertices, encoding.decode(encoded, vertices.size()));
  }

  /** A constant buffer — the degenerate case where every delta is zero. */
  @ParameterizedTest
  @MethodSource("everyEncoding")
  void roundTripsConstantBuffer(Encoding encoding) {
    var vertices = new ArrayList<Vertex>();
    for (var i = 0; i < 40; i++) {
      vertices.add(new Vertex(12, 34));
    }

    var encoded = encoding.encode(BufferContext.of(vertices));

    assertEquals(vertices, encoding.decode(encoded, vertices.size()));
  }

  /** The two baselines the whole benchmark is measured against must stay stable and named. */
  @Test
  void baselineNamesAreStable() {
    assertEquals(
        List.of("DELTA_ZIGZAG_FASTPFOR", "DELTA_ZIGZAG_VARINT"),
        Baselines.baseline().stream().map(Encoding::name).toList());
  }

  /** The metadata header is part of the reported size, so it must be counted, not dropped. */
  @Test
  void reportedSizeIncludesStreamMetadata() {
    var column = VertexBufferExtractor.extract(List.of(square(0, 0, 10)));
    var encoded = Baselines.baseline().get(0).encode(BufferContext.of(column.vertexBuffer()));

    assertEquals(encoded.payload().length + encoded.metadataBytes(), encoded.totalBytes());
  }

  /** A {@link BufferContext} built from an extraction carries the topology streams alongside it. */
  @Test
  void bufferContextCarriesTopologyFromExtraction() {
    var column = VertexBufferExtractor.extract(List.of(square(0, 0, 10), square(100, 100, 10)));

    var ctx =
        new BufferContext(
            column.vertexBuffer(),
            column.geometryTypes(),
            column.numGeometries(),
            column.numParts(),
            column.numRings());

    assertEquals(column.vertexBuffer(), ctx.vertices());
    assertEquals(2, ctx.geometryTypes().size());
    assertEquals(column.numParts(), ctx.numParts());
  }

  private static Polygon square(double originX, double originY, double size) {
    return GF.createPolygon(ring(originX, originY, size));
  }

  private static LinearRing ring(double originX, double originY, double size) {
    return GF.createLinearRing(
        new Coordinate[] {
          new Coordinate(originX, originY),
          new Coordinate(originX + size, originY),
          new Coordinate(originX + size, originY + size),
          new Coordinate(originX, originY + size),
          new Coordinate(originX, originY),
        });
  }
}
