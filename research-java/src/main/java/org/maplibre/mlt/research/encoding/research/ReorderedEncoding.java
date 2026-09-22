package org.maplibre.mlt.research.encoding.research;

import org.maplibre.mlt.converter.geometry.Vertex;
import org.maplibre.mlt.research.encoding.Encoding;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.BufferContext;
import org.maplibre.mlt.research.encoding.VertexBufferCodec.EncodedBuffer;

import java.util.List;

/**
 * Any {@link Encoding} with a {@link GeometryReorder} applied to the buffer first.
 *
 * <p>Reordering is an optional add-on, not part of an encoding: it is applied benchmark-wide
 * ({@code --reorder}) to every registered row, so the registry never holds a reordered variant and
 * every idea is measured with and without it under the same name plus {@link
 * GeometryReorder#nameSuffix()}. The inner encoding is unaware of the reorder — it sees a buffer
 * like any other, and on decode is handed the reordered topology exactly as a real decoder would
 * read it from the tile (see {@link GeometryReorder#topology}).
 */
public final class ReorderedEncoding implements Encoding {

    private final Encoding inner;
    private final GeometryReorder reorder;

    public ReorderedEncoding(Encoding inner, GeometryReorder reorder) {
        this.inner = inner;
        this.reorder = reorder;
    }

    @Override
    public String name() {
        return inner.name() + reorder.nameSuffix();
    }

    @Override
    public String toString() {
        return name();
    }

    @Override
    public boolean reordersGeometries() {
        return reorder.reorders();
    }

    @Override
    public BufferContext emitted(BufferContext ctx) {
        return reorder.apply(ctx);
    }

    @Override
    public EncodedBuffer encode(BufferContext ctx) {
        return inner.encode(reorder.apply(ctx));
    }

    @Override
    public List<Vertex> decode(EncodedBuffer encoded, BufferContext ctx, int vertexCount) {
        return inner.decode(encoded, reorder.topology(ctx, vertexCount), vertexCount);
    }

    /**
     * The wrapped encoding, for measurements that need to reach its transform (e.g. residual-bits
     * reporting) against the reordered buffer this class actually encodes.
     */
    public Encoding inner() {
        return inner;
    }

    /**
     * The reorder mode itself, so a measurement can read {@link GeometryReorder#nameSuffix()} without
     * re-deriving it from {@link #name()}.
     */
    public GeometryReorder reorder() {
        return reorder;
    }
}
