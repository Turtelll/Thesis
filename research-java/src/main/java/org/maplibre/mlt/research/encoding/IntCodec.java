package org.maplibre.mlt.research.encoding;

import me.lemire.integercompression.Composition;
import me.lemire.integercompression.FastPFOR128;
import me.lemire.integercompression.IntWrapper;
import me.lemire.integercompression.IntegerCODEC;
import me.lemire.integercompression.VariableByte;
import org.maplibre.mlt.converter.encodings.EncodingUtils;
import org.maplibre.mlt.decoder.DecodingUtils;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Physical-layer codec: packs an already-transformed {@code int[]} into bytes and back.
 */
public interface IntCodec {

    /**
     * Shared instances — a codec is stateless, so one per kind is enough.
     */
    IntCodec VARINT = new Varint();

    IntCodec FASTPFOR = new FastPfor();

    IntCodec FASTPFOR128 = new FastPfor128();

    IntCodec RAW = new Raw();

    IntCodec VARINT_XY = new VarintXy();

    IntCodec VARINT_SPLIT = new VarintSplit();

    IntCodec BYTESPLIT = new ByteSplit();

    IntCodec UINT16 = new Uint16();

    IntCodec UINT16_PLANES = new Uint16Planes();

    /**
     * Suffix this codec contributes to an encoding's name.
     */
    String name();

    byte[] encode(int[] values);

    int[] decode(byte[] data, int numValues);

    /**
     * Byte-aligned physical codec (protobuf-style varint), delegating to mlt-core.
     */
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

    /**
     * Bit-aligned physical codec (FastPFOR + VariableByte fallback), delegating to mlt-core.
     */
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

    /**
     * {@link FastPfor} with 128-value blocks instead of 256 (the block size the next MLT version
     * adopts). Same framing as mlt-core's {@code encodeFastPfor128}: FastPFOR128 + VariableByte for
     * the tail, big-endian 32-bit words.
     */
    final class FastPfor128 implements IntCodec {

        @Override
        public String name() {
            return "FASTPFOR128";
        }

        @Override
        public byte[] encode(int[] values) {
            final var compressed = new int[values.length + 1024];
            final var outputOffset = new IntWrapper(0);
            codec().compress(values, new IntWrapper(0), values.length, compressed, outputOffset);
            final var bytes = ByteBuffer.allocate(outputOffset.get() * 4);
            bytes.asIntBuffer().put(compressed, 0, outputOffset.get());
            return bytes.array();
        }

        @Override
        public int[] decode(byte[] data, int numValues) {
            final var words = new int[data.length / 4];
            ByteBuffer.wrap(data).asIntBuffer().get(words);
            final var decoded = new int[numValues];
            codec().uncompress(words, new IntWrapper(0), words.length, decoded, new IntWrapper(0));
            return decoded;
        }

        private static IntegerCODEC codec() {
            return new Composition(new FastPFOR128(), new VariableByte());
        }
    }

    /**
     * No physical encoding: every value as a fixed 32-bit little-endian word. Only meaningful under
     * a general-purpose compressor, which is then the only stage packing the residuals.
     */
    final class Raw implements IntCodec {

        @Override
        public String name() {
            return "RAW";
        }

        @Override
        public byte[] encode(int[] values) {
            final var bytes = ByteBuffer.allocate(values.length * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            bytes.asIntBuffer().put(values);
            return bytes.array();
        }

        @Override
        public int[] decode(byte[] data, int numValues) {
            final var decoded = new int[numValues];
            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(decoded);
            return decoded;
        }
    }

    /**
     * {@link Varint} over the values at even indices, then the values at odd indices — every x
     * residual, then every y residual, for the interleaved stream a {@link VertexTransform} emits.
     * Same size as Varint before compression; under a general-purpose compressor a byte's
     * predecessor is the same coordinate's previous residual rather than the other coordinate, and
     * the x and y distributions no longer alternate inside one Huffman block.
     */
    final class VarintXy implements IntCodec {

        @Override
        public String name() {
            return "VARINT_XY";
        }

        @Override
        public byte[] encode(int[] values) {
            final var split = new int[values.length];
            final var evens = (values.length + 1) / 2;
            for (var i = 0; i < values.length; i++) {
                split[(i & 1) == 0 ? i / 2 : evens + i / 2] = values[i];
            }
            return VARINT.encode(split);
        }

        @Override
        public int[] decode(byte[] data, int numValues) {
            final var split = VARINT.decode(data, numValues);
            final var values = new int[numValues];
            final var evens = (numValues + 1) / 2;
            for (var i = 0; i < numValues; i++) {
                values[i] = split[(i & 1) == 0 ? i / 2 : evens + i / 2];
            }
            return values;
        }
    }

    /**
     * Unsigned LEB128 varints with the first byte of every value in one stream and every
     * continuation byte in a second, appended after it. Same size as {@link Varint}; the dominant
     * one-byte values stay contiguous instead of being interrupted by the rare multi-byte ones, so
     * the first stream is a clean run of small symbols for LZ77 and Huffman.
     */
    final class VarintSplit implements IntCodec {

        @Override
        public String name() {
            return "VARINT_SPLIT";
        }

        @Override
        public byte[] encode(int[] values) {
            final var heads = new byte[values.length];
            final var tails = new ByteArrayOutputStream();
            for (var i = 0; i < values.length; i++) {
                var rest = values[i] >>> 7;
                heads[i] = (byte) ((values[i] & 0x7F) | (rest != 0 ? 0x80 : 0));
                while (rest != 0) {
                    final var low = rest & 0x7F;
                    rest >>>= 7;
                    tails.write(low | (rest != 0 ? 0x80 : 0));
                }
            }
            final var out = new byte[heads.length + tails.size()];
            System.arraycopy(heads, 0, out, 0, heads.length);
            System.arraycopy(tails.toByteArray(), 0, out, heads.length, tails.size());
            return out;
        }

        @Override
        public int[] decode(byte[] data, int numValues) {
            final var values = new int[numValues];
            var tail = numValues;
            for (var i = 0; i < numValues; i++) {
                final var head = data[i];
                var value = head & 0x7F;
                var more = (head & 0x80) != 0;
                for (var shift = 7; more; shift += 7) {
                    final var b = data[tail++];
                    value |= (b & 0x7F) << shift;
                    more = (b & 0x80) != 0;
                }
                values[i] = value;
            }
            return values;
        }
    }

    /**
     * Every value as a 32-bit little-endian word, transposed into four byte planes (all low bytes,
     * then all second bytes, ...) — Parquet's {@code BYTE_STREAM_SPLIT}. {@link Raw}'s size before
     * compression and as meaningless; under a general-purpose compressor the high planes of small
     * residuals are long zero runs that cost almost nothing, where {@link Raw} scatters those zeros
     * between the low bytes.
     */
    final class ByteSplit implements IntCodec {

        @Override
        public String name() {
            return "BYTESPLIT";
        }

        @Override
        public byte[] encode(int[] values) {
            final var n = values.length;
            final var out = new byte[n * Integer.BYTES];
            for (var i = 0; i < n; i++) {
                for (var plane = 0; plane < Integer.BYTES; plane++) {
                    out[plane * n + i] = (byte) (values[i] >>> (8 * plane));
                }
            }
            return out;
        }

        @Override
        public int[] decode(byte[] data, int numValues) {
            final var values = new int[numValues];
            for (var i = 0; i < numValues; i++) {
                var value = 0;
                for (var plane = 0; plane < Integer.BYTES; plane++) {
                    value |= (data[plane * numValues + i] & 0xFF) << (8 * plane);
                }
                values[i] = value;
            }
            return values;
        }
    }

    /**
     * Every value as a fixed 16-bit little-endian word. Rejects a value outside {@code [0, 0xFFFF]}
     * rather than truncating it: an encoding emitting larger values has to escape them itself (see
     * {@link org.maplibre.mlt.research.encoding.research.failed.StandardDelta}).
     */
    final class Uint16 implements IntCodec {

        @Override
        public String name() {
            return "UINT16";
        }

        @Override
        public byte[] encode(int[] values) {
            final var out = new byte[values.length * 2];
            for (var i = 0; i < values.length; i++) {
                final var value = checkUint16(values[i]);
                out[2 * i] = (byte) value;
                out[2 * i + 1] = (byte) (value >>> 8);
            }
            return out;
        }

        @Override
        public int[] decode(byte[] data, int numValues) {
            final var values = new int[numValues];
            for (var i = 0; i < numValues; i++) {
                values[i] = (data[2 * i] & 0xFF) | (data[2 * i + 1] & 0xFF) << 8;
            }
            return values;
        }
    }

    /**
     * {@link Uint16} transposed into two byte planes: every low byte, then every high byte. The
     * 2-byte analogue of {@link ByteSplit}; the high plane of small values is a zero run.
     */
    final class Uint16Planes implements IntCodec {

        @Override
        public String name() {
            return "UINT16_PLANES";
        }

        @Override
        public byte[] encode(int[] values) {
            final var n = values.length;
            final var out = new byte[n * 2];
            for (var i = 0; i < n; i++) {
                final var value = checkUint16(values[i]);
                out[i] = (byte) value;
                out[n + i] = (byte) (value >>> 8);
            }
            return out;
        }

        @Override
        public int[] decode(byte[] data, int numValues) {
            final var values = new int[numValues];
            for (var i = 0; i < numValues; i++) {
                values[i] = (data[i] & 0xFF) | (data[numValues + i] & 0xFF) << 8;
            }
            return values;
        }
    }

    private static int checkUint16(int value) {
        if ((value & ~0xFFFF) != 0) {
            throw new IllegalArgumentException("value does not fit a uint16: " + Integer.toUnsignedString(value));
        }
        return value;
    }
}
