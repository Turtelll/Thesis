package org.maplibre.mlt.research.encoding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
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

  /** A context carrying the real topology streams, as {@code BenchmarkCli} builds from a sidecar. */
  private static BufferContext contextOf(VertexBufferExtractor.ExtractedColumn column) {
    return new BufferContext(
        column.vertexBuffer(),
        column.geometryTypes(),
        column.numGeometries(),
        column.numParts(),
        column.numRings());
  }

  @ParameterizedTest
  @MethodSource("everyEncoding")
  void roundTripsExtractedVertexBuffer(Encoding encoding) {
    var column = VertexBufferExtractor.extract(List.of(square(100, 200, 50)));

    assertRoundTripsWithoutTopology(encoding, column.vertexBuffer());
  }

  /**
   * Round-trips a vertex list through a {@link BufferContext#of} context — no topology streams, so
   * any codec conditioning on structure degrades to a single run. The multi-part and mixed cases
   * above are what exercise the structural paths.
   */
  private static void assertRoundTripsWithoutTopology(Encoding encoding, List<Vertex> vertices) {
    var ctx = BufferContext.of(vertices);
    var encoded = encoding.encode(ctx);

    assertEquals(vertices, encoding.decode(encoded, ctx, vertices.size()));
  }

  /**
   * A multi-part column, so that any codec conditioning on part boundaries (the forced reset points
   * of the {@code ideen.md} predictor design) is exercised on more than one part.
   *
   * <p>Passes the real topology streams rather than {@link BufferContext#of}, so the reset path is
   * actually taken — with an empty context a resetting codec silently degrades to one run and the
   * boundary logic goes untested.
   */
  @ParameterizedTest
  @MethodSource("everyEncoding")
  void roundTripsMultiPartBuffer(Encoding encoding) {
    var column =
        VertexBufferExtractor.extract(
            List.of(square(0, 0, 10), square(500, 500, 20), square(-300, 40, 5)));
    var ctx = contextOf(column);

    var encoded = encoding.encode(ctx);

    assertReconstructs(encoding, ctx, encoded);
  }

  /**
   * Asserts the decode reconstructed the buffer, by the contract the encoding actually claims.
   *
   * <p>Mirrors {@code BenchmarkCli.verifyRoundTrip}: an encoding declaring {@link
   * Encoding#reordersGeometries()} reconstructs an <em>equivalent</em> buffer rather than an
   * identical one, so it is checked for geometry preservation plus a faithful decode of what it
   * emitted. Everything else is held to exact equality. Without the split these tests would either
   * fail on a correct reordering encoding or, if loosened for all, stop catching a lossy one.
   */
  private static void assertReconstructs(
      Encoding encoding, BufferContext ctx, VertexBufferCodec.EncodedBuffer encoded) {
    var decoded = encoding.decode(encoded, ctx, ctx.vertices().size());
    if (encoding.reordersGeometries()) {
      var emitted = encoding.emitted(ctx);
      assertEquals(emitted.vertices(), decoded, "must decode to the buffer it emitted");
      assertTrue(
          GeometryEquivalence.holds(ctx, emitted),
          "reordering must preserve every geometry: " + GeometryEquivalence.describe(ctx, emitted));
    } else {
      assertEquals(ctx.vertices(), decoded);
    }
  }

  /**
   * Points contribute vertices but no run-length entry, so the run stream covers fewer vertices
   * than the buffer holds and the remainder must still be reconstructed.
   */
  @ParameterizedTest
  @MethodSource("everyEncoding")
  void roundTripsMixedPointAndPolygonBuffer(Encoding encoding) {
    var column =
        VertexBufferExtractor.extract(
            List.of(
                square(0, 0, 10),
                GF.createPoint(new Coordinate(700, 800)),
                square(200, 200, 15),
                GF.createPoint(new Coordinate(-40, -50))));
    var ctx = contextOf(column);

    var encoded = encoding.encode(ctx);

    assertReconstructs(encoding, ctx, encoded);
  }

  @ParameterizedTest
  @MethodSource("everyEncoding")
  void roundTripsSingleVertexBuffer(Encoding encoding) {
    var column = VertexBufferExtractor.extract(List.of(GF.createPoint(new Coordinate(7, 9))));

    assertRoundTripsWithoutTopology(encoding, column.vertexBuffer());
  }

  /** A constant buffer — the degenerate case where every delta is zero. */
  @ParameterizedTest
  @MethodSource("everyEncoding")
  void roundTripsConstantBuffer(Encoding encoding) {
    var vertices = Collections.nCopies(40, new Vertex(12, 34));

    assertRoundTripsWithoutTopology(encoding, vertices);
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

    var ctx = contextOf(column);

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
