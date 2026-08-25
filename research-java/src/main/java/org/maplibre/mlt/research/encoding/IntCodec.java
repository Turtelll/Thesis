package org.maplibre.mlt.research.encoding;

import me.lemire.integercompression.IntWrapper;
import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.decoder.DecodingUtils;

/** Physical-layer codec: packs an already-transformed {@code int[]} into bytes and back. */
public interface IntCodec {

  /** Shared instances — a codec is stateless, so one per kind is enough. */
  IntCodec VARINT = new Varint();

  IntCodec FASTPFOR = new FastPfor();

  /** Suffix this codec contributes to an encoding's name. */
  String name();

  byte[] encode(int[] values);

  int[] decode(byte[] data, int numValues);

  /** Byte-aligned physical codec (protobuf-style varint), delegating to mlt-core. */
  final class Varint implements IntCodec {

    @Override
    public String name() {
      return "VARINT";
    }

    @Override
    public byte[] encode(int[] values) {
      try {
        return EncodingUtils.encodeVarints(values, false, false);
      } catch (java.io.IOException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public int[] decode(byte[] data, int numValues) {
      try {
        return DecodingUtils.decodeVarints(data, new IntWrapper(0), numValues);
      } catch (java.io.IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  /** Bit-aligned physical codec (FastPFOR + VariableByte fallback), delegating to mlt-core. */
  final class FastPfor implements IntCodec {

    @Override
    public String name() {
      return "FASTPFOR";
    }

    @Override
    public byte[] encode(int[] values) {
      return EncodingUtils.encodeFastPfor128(values, false, false);
    }

    @Override
    public int[] decode(byte[] data, int numValues) {
      return DecodingUtils.decodeFastPfor(data, numValues, data.length, new IntWrapper(0));
    }
  }
}
