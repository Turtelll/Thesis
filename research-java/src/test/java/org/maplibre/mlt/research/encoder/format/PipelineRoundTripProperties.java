package org.maplibre.mlt.research.encoder.format;

import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Combinators;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.maplibre.mlt.research.buffer.Encoding;
import org.maplibre.mlt.research.buffer.Encoding.BufferContext;
import org.maplibre.mlt.research.extraction.VertexBufferExtractor;
import org.maplibre.mlt.research.pipeline.PipelineRegistry;

/**
 * Property: every row of the pipeline, on random buffers of points, lines, rectangles and
 * triangles, decodes from the topology alone to the buffer it emitted (and, when it reorders, to a
 * geometry-preserving permutation). The hand-built fixtures of {@code PipelineEncodingTest} cover
 * the shapes someone thought of; this covers the ones nobody did.
 */
class PipelineRoundTripProperties {

  private static final GeometryFactory GF = new GeometryFactory();

  @Provide
  Arbitrary<Encoding> rows() {
    return Arbitraries.of(PipelineRegistry.all());
  }

  private static Arbitrary<Integer> coordinate() {
    return Arbitraries.integers().between(-3000, 7000);
  }

  private static Arbitrary<Geometry> point() {
    return Combinators.combine(coordinate(), coordinate())
        .as((x, y) -> GF.createPoint(new Coordinate(x, y)));
  }

  private static Arbitrary<Geometry> line() {
    return Combinators.combine(coordinate(), coordinate())
        .as(Coordinate::new)
        .list()
        .ofMinSize(2)
        .ofMaxSize(8)
        .map(points -> GF.createLineString(points.toArray(new Coordinate[0])));
  }

  private static Arbitrary<Geometry> rectangle() {
    return Combinators.combine(
            coordinate(),
            coordinate(),
            Arbitraries.integers().between(1, 600),
            Arbitraries.integers().between(1, 600))
        .as(
            (x, y, w, h) ->
                GF.createPolygon(
                    new Coordinate[] {
                      new Coordinate(x, y),
                      new Coordinate(x + w, y),
                      new Coordinate(x + w, y + h),
                      new Coordinate(x, y + h),
                      new Coordinate(x, y)
                    }));
  }

  private static Arbitrary<Geometry> triangle() {
    return Combinators.combine(coordinate(), coordinate(), coordinate(), coordinate())
        .as(
            (x, y, dx, dy) ->
                GF.createPolygon(
                    new Coordinate[] {
                      new Coordinate(x, y),
                      new Coordinate(x + 10 + Math.abs(dx) % 400, y),
                      new Coordinate(x, y + 10 + Math.abs(dy) % 400),
                      new Coordinate(x, y)
                    }));
  }

  /** A column is one kind of geometry, as in a tile: points, lines or polygons. */
  @Provide
  Arbitrary<BufferContext> buffers() {
    return Arbitraries.oneOf(
            point().list().ofMinSize(1).ofMaxSize(12),
            line().list().ofMinSize(1).ofMaxSize(10),
            Arbitraries.oneOf(rectangle(), triangle()).list().ofMinSize(1).ofMaxSize(10))
        .map(geometries -> PipelineEncodingTest.contextOf(VertexBufferExtractor.extract(new ArrayList<>(geometries))));
  }

  @Property(tries = 400)
  void everyRowRoundTripsRandomBuffers(
      @ForAll("rows") Encoding encoding, @ForAll("buffers") BufferContext ctx) {
    PipelineEncodingTest.assertReconstructs(encoding, ctx, encoding.encode(ctx));
  }
}
