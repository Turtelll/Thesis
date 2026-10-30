package org.maplibre.mlt.research.encoder.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
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
import org.maplibre.mlt.research.buffer.Encoding;
import org.maplibre.mlt.research.encoder.stream.IntCodec;
import org.maplibre.mlt.research.buffer.Encoding.BufferContext;
import org.maplibre.mlt.research.buffer.Encoding.EncodedBuffer;
import org.maplibre.mlt.research.encoding.VertexTransform;
import org.maplibre.mlt.research.extraction.VertexBufferExtractor;
import org.maplibre.mlt.research.encoder.stream.ByteWidth;
import org.maplibre.mlt.research.pipeline.PipelineRegistry;
import org.maplibre.mlt.research.pipeline.Verification;

/**
 * Round-trips every registered pipeline row over the same hand-built buffers as {@code
 * VertexBufferCodecTest}, and pins the anchor the rest of the pipeline is verified against: the
 * identity configuration is byte-identical to the old {@code DELTA_ZIGZAG_VARINT} body.
 */
public class PipelineEncodingTest {

  private static final GeometryFactory GF = new GeometryFactory();

  /** Every registered row, then the rows of the {@code --step best} grid that no step registers. */
  static List<Encoding> everyRow() {
    var byName = new java.util.LinkedHashMap<String, Encoding>();
    PipelineRegistry.all().forEach(row -> byName.putIfAbsent(row.name(), row));
    PipelineRegistry.step("best").forEach(row -> byName.putIfAbsent(row.name(), row));
    return List.copyOf(byName.values());
  }

  public static BufferContext contextOf(VertexBufferExtractor.ExtractedColumn column) {
    return new BufferContext(
        column.vertexBuffer(),
        column.geometryTypes(),
        column.numGeometries(),
        column.numParts(),
        column.numRings());
  }

  public static void assertReconstructs(Encoding encoding, BufferContext ctx, EncodedBuffer encoded) {
    var decoded = encoding.decode(encoded, ctx, ctx.vertices().size());
    if (encoding.reordersGeometries()) {
      var emitted = encoding.emitted(ctx);
      assertEquals(emitted.vertices(), decoded, "must decode to the buffer it emitted");
      assertTrue(
          Verification.holds(ctx, emitted),
          "reordering must preserve every geometry: " + Verification.describe(ctx, emitted));
    } else {
      assertEquals(ctx.vertices(), decoded);
    }
  }

  @ParameterizedTest
  @MethodSource("everyRow")
  void roundTripsSingleSquareWithoutTopology(Encoding encoding) {
    var column = VertexBufferExtractor.extract(List.of(square(100, 200, 50)));
    var ctx = BufferContext.of(column.vertexBuffer());
    assertEquals(ctx.vertices(), encoding.decode(encoding.encode(ctx), ctx, ctx.vertices().size()));
  }

  @ParameterizedTest
  @MethodSource("everyRow")
  void roundTripsMultiPartBuffer(Encoding encoding) {
    var column =
        VertexBufferExtractor.extract(
            List.of(square(0, 0, 10), square(500, 500, 20), square(-300, 40, 5)));
    var ctx = contextOf(column);
    assertReconstructs(encoding, ctx, encoding.encode(ctx));
  }

  @ParameterizedTest
  @MethodSource("everyRow")
  void roundTripsMixedPointAndPolygonBuffer(Encoding encoding) {
    var column =
        VertexBufferExtractor.extract(
            List.of(
                square(0, 0, 10),
                GF.createPoint(new Coordinate(700, 800)),
                square(200, 200, 15),
                GF.createPoint(new Coordinate(-40, -50))));
    var ctx = contextOf(column);
    assertReconstructs(encoding, ctx, encoding.encode(ctx));
  }

  @ParameterizedTest
  @MethodSource("everyRow")
  void roundTripsSingleVertexBuffer(Encoding encoding) {
    var column = VertexBufferExtractor.extract(List.of(GF.createPoint(new Coordinate(7, 9))));
    var ctx = BufferContext.of(column.vertexBuffer());
    assertEquals(ctx.vertices(), encoding.decode(encoding.encode(ctx), ctx, 1));
  }

  @ParameterizedTest
  @MethodSource("everyRow")
  void roundTripsConstantBuffer(Encoding encoding) {
    var ctx = BufferContext.of(Collections.nCopies(40, new Vertex(12, 34)));
    assertEquals(ctx.vertices(), encoding.decode(encoding.encode(ctx), ctx, 40));
  }

  @ParameterizedTest
  @MethodSource("everyRow")
  void roundTripsExtremeCoordinates(Encoding encoding) {
    var ctx =
        BufferContext.of(
            List.of(
                new Vertex(Integer.MAX_VALUE, Integer.MIN_VALUE),
                new Vertex(Integer.MIN_VALUE, Integer.MAX_VALUE),
                new Vertex(0, 0),
                new Vertex(Integer.MAX_VALUE, Integer.MAX_VALUE),
                new Vertex(Integer.MIN_VALUE, Integer.MIN_VALUE)));
    assertEquals(ctx.vertices(), encoding.decode(encoding.encode(ctx), ctx, 5));
  }

  /** The identity configuration's body is exactly what the old baseline puts in front of the codec. */
  @Test
  void identityBodyIsTheBaselineVarint() {
    var column =
        VertexBufferExtractor.extract(
            List.of(square(0, 0, 10), square(500, 500, 20), square(-300, 40, 5)));
    var ctx = contextOf(column);

    var payload = new PipelineEncoding(PipelineConfig.IDENTITY).encode(ctx).payload();
    var body = Arrays.copyOfRange(payload, PayloadHeader.HEADER_BYTES, payload.length);

    assertArrayEquals(
        IntCodec.VARINT.encode(new VertexTransform.ZigZagDelta().apply(ctx)),
        body,
        "S0 anchor: DELTA / FLAT / ZIGZAG / VARINT must equal DELTA_ZIGZAG_VARINT after the header");
  }

  @Test
  void splitTerminalMatchesTheOldSplitBody() {
    var column = VertexBufferExtractor.extract(List.of(square(0, 0, 10), square(900, 900, 700)));
    var ctx = contextOf(column);

    var payload =
        new PipelineEncoding(PipelineConfig.IDENTITY.withWidth(ByteWidth.VARINT_SPLIT))
            .encode(ctx)
            .payload();
    var body = Arrays.copyOfRange(payload, PayloadHeader.HEADER_BYTES, payload.length);

    assertArrayEquals(
        IntCodec.VARINT_SPLIT.encode(new VertexTransform.ZigZagDelta().apply(ctx)), body);
  }

  @Test
  void headerRoundTripsEveryChoice() {
    var config = PipelineConfig.IDENTITY.withWidth(ByteWidth.VARINT_SPLIT);
    var header = PayloadHeader.write(config);
    assertEquals(
        config,
        PayloadHeader.parse(header[0] & 0xFF, header[1] & 0xFF, config.reorder()));
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
