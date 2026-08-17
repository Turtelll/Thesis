package org.maplibre.mlt.research;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import org.apache.commons.compress.compressors.deflate.DeflateCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.imintel.mbtiles4j.MBTilesReadException;
import org.imintel.mbtiles4j.MBTilesReader;
import org.imintel.mbtiles4j.Tile;

/**
 * Reads MVT tile bytes out of an .mbtiles file, following the same read path as {@code
 * mlt-cli}'s {@code encodeMBTiles} (maplibre-tile-spec/java/mlt-cli/.../MBTiles.kt). {@code
 * MBTilesReader.getTiles()} / {@code Tile.getData()} return the tile bytes exactly as stored in
 * the database — mbtiles4j does NOT decompress them. MBTiles tiles are conventionally
 * gzip-compressed, so this ports {@code Conversion.kt#decompress}'s header-sniffing (zlib/deflate
 * vs gzip, else pass through raw) rather than assuming gzip unconditionally.
 *
 * <p>MBTiles rows use TMS row numbering (row 0 = southernmost); this converts to XYZ (row 0 =
 * northernmost) via {@code y = (1 << z) - 1 - tmsRow}, matching the standard XYZ/TMS relation.
 */
public final class MbtilesSource {

  private MbtilesSource() {}

  public record TileRef(int z, int x, int y, byte[] mvtBytes) {}

  /**
   * Streams every tile in the mbtiles file matching the given zoom filter (inclusive on both
   * ends; pass {@code Integer.MIN_VALUE}/{@code MAX_VALUE} for no bound) to {@code consumer}.
   */
  public static void forEachTile(
      File mbtilesFile, int minZoom, int maxZoom, Consumer<TileRef> consumer)
      throws MBTilesReadException, IOException {
    // mbtiles4j's MBTilesReader does not implement AutoCloseable (close() returns File, not
    // void), so it can't be used in try-with-resources; close it in a finally block instead.
    var reader = new MBTilesReader(mbtilesFile);
    try {
      var tiles = reader.getTiles();
      while (tiles.hasNext()) {
        Tile tile = tiles.next();
        int z = tile.getZoom();
        if (z < minZoom || z > maxZoom) {
          continue;
        }
        int x = tile.getColumn();
        int tmsRow = tile.getRow();
        int y = (1 << z) - 1 - tmsRow;
        byte[] bytes = decompress(tile.getData());
        consumer.accept(new TileRef(z, x, y, bytes));
      }
    } finally {
      reader.close();
    }
  }

  /**
   * Auto-detects and reverses zlib/deflate or gzip compression by sniffing the header bytes;
   * returns the input unchanged if neither magic matches. Ported from {@code
   * mlt-cli}'s {@code Conversion.kt#decompress}.
   */
  private static byte[] decompress(InputStream srcStream) throws IOException {
    InputStream decompressStream = null;
    var readStream = new BufferedInputStream(srcStream);
    if (readStream.available() > 3) {
      readStream.mark(4);
      byte[] header = readStream.readNBytes(4);
      readStream.reset();

      if (DeflateCompressorInputStream.matches(header, header.length)) {
        decompressStream = new InflaterInputStream(readStream, new Inflater(false));
      } else if ((header[0] & 0xff) == 0x1f && (header[1] & 0xff) == 0x8b) {
        decompressStream = new GzipCompressorInputStream(readStream);
      }
    }

    if (decompressStream != null) {
      try (var out = new ByteArrayOutputStream()) {
        decompressStream.transferTo(out);
        return out.toByteArray();
      }
    }
    return readStream.readAllBytes();
  }
}
