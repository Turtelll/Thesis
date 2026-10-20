package org.maplibre.mlt.research.encoding.research.failed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;

/**
 * Pins RDP_RESIDUAL's key mechanisms: which vertices it can drop, that dropped vertices still
 * round-trip exactly via their interpolated-point residual, and that it never touches polygon
 * rings — the same trap {@code LineContinuationMechanismTest} exists for.
 *
 * <p>Kept only for this negative result's mechanism — see {@code docs/results/rdp-residual.md}.
 */
class RdpResidualMechanismTest {

  /** A straight LineString: every interior vertex lies exactly on the A-B line, so RDP drops all
   * of them at any positive tolerance and the perpendicular residual is exactly zero. */
  private static BufferContext straightLineColumn() {
    return new BufferContext(
        List.of(
            new Vertex(0, 0),
            new Vertex(10, 10),
            new Vertex(20, 20),
            new Vertex(30, 30),
            new Vertex(40, 40)),
        List.of(1), // LINESTRING
        List.of(),
        List.of(5),
        List.of());
  }

  @Test
  void roundTripsAStraightLineWithEveryInteriorVertexDropped() {
    final var ctx = straightLineColumn();
    final var encoding = new RdpResidual();

    final var encoded = encoding.encode(ctx);
    final var decoded = encoding.decode(encoded, topologyOnly(ctx), ctx.vertices().size());

    assertEquals(ctx.vertices(), decoded);
  }

  /** A zigzag: RDP must keep the peak (or the round-trip proves it did not need to — either way
   * losslessness is what this test actually pins, via full reconstruction). */
  private static BufferContext zigzagColumn() {
    return new BufferContext(
        List.of(
            new Vertex(0, 0),
            new Vertex(10, 1000),
            new Vertex(20, 0),
            new Vertex(30, 1000),
            new Vertex(40, 0)),
        List.of(1),
        List.of(),
        List.of(5),
        List.of());
  }

  @Test
  void roundTripsAZigzagLine() {
    final var ctx = zigzagColumn();
    final var encoding = new RdpResidual();

    final var encoded = encoding.encode(ctx);
    final var decoded = encoding.decode(encoded, topologyOnly(ctx), ctx.vertices().size());

    assertEquals(ctx.vertices(), decoded);
  }

  /** Column contains a polygon, so both runs' lengths live in {@code numRings} — the same trap
   * {@code LineContinuationMechanismTest.mixedColumn} exists for. */
  private static BufferContext mixedColumn() {
    return new BufferContext(
        List.of(
            // LineString: straight, every interior vertex droppable.
            new Vertex(0, 0),
            new Vertex(10, 10),
            new Vertex(20, 20),
            new Vertex(30, 30),
            new Vertex(40, 40),
            // Polygon ring (closing vertex dropped, per the extractor's convention).
            new Vertex(100, 100),
            new Vertex(110, 100),
            new Vertex(110, 110),
            new Vertex(100, 110)),
        List.of(1, 2), // LINESTRING, POLYGON
        List.of(),
        List.of(1), // polygon: 1 ring
        List.of(5, 4));
  }

  @Test
  void roundTripsMixedLineAndPolygonColumn() {
    final var ctx = mixedColumn();
    final var encoding = new RdpResidual();

    final var encoded = encoding.encode(ctx);
    final var decoded = encoding.decode(encoded, topologyOnly(ctx), ctx.vertices().size());

    assertEquals(ctx.vertices(), decoded);
  }

  /** A 2-vertex LineString part is below the gate: no interior vertex exists to drop, so the
   * bitmap carries zero bits for it, and the round-trip must still hold. */
  private static BufferContext twoVertexPartColumn() {
    return new BufferContext(
        List.of(new Vertex(0, 0), new Vertex(10, 10)),
        List.of(1),
        List.of(),
        List.of(2),
        List.of());
  }

  @Test
  void roundTripsTwoVertexPart() {
    final var ctx = twoVertexPartColumn();
    final var encoding = new RdpResidual();

    final var encoded = encoding.encode(ctx);
    final var decoded = encoding.decode(encoded, topologyOnly(ctx), ctx.vertices().size());

    assertEquals(ctx.vertices(), decoded);
  }

  @Test
  void residualLengthIsTwiceVertexCount() {
    final var ctx = mixedColumn();
    final var transform = new RdpResidual().transform();

    final var residuals = transform.apply(ctx);

    assertEquals(ctx.vertices().size() * 2, residuals.length);
  }

  private static BufferContext topologyOnly(BufferContext ctx) {
    return new BufferContext(
        List.of(), ctx.geometryTypes(), ctx.numGeometries(), ctx.numParts(), ctx.numRings());
  }
}
