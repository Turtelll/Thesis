# The pipeline bitstream

What `PipelineEncoding` writes for one `VertexBuffer`, byte by byte, and what a decoder needs
besides the bytes. This is the format every row of `research measure` is sized in; the stage
semantics are in [results/openzl-pipeline.md](results/openzl-pipeline.md), the code in
`encoder/format/PipelineEncoding.java` and `encoder/stream/StreamCodec.java`.

The size of an encoding is `payload.length` under the tile's general-purpose compressor
(gzip, Brotli or zstd, applied per tile outside this format).

## What the decoder already has

Nothing about the geometry is repeated in the payload. The decoder reads, before the vertex
buffer, the topology streams of the tile (`geometryTypes`, `numGeometries`, `numParts`,
`numRings`), and from them derives, identically on both sides:

- the **runs** (rings, line parts, points; `VertexRuns`),
- the **class** of every vertex (the predictor's `classify`),
- the **value count of every stream** (`Segmentation.layout`).

So no stream length, no segment boundary and no class label is ever written, with two
exceptions (the stream byte length of a varint stream that is not last, and the stream lengths
of a shared-reference buffer; both below).

The **reorder** (stage S1) is not in the payload either: the tile already carries the
reordered topology streams, and the decoder emits the vertices in the order it reads. A
decoder is handed the reorder it should assume (`Composition.reorder`).

## Payload

```
header (2 bytes)
[ shared-reference block ]      only when the header's predictor is a *_SHARED one
stream 0 | stream 1 | ...       non-empty streams only, in stream order
```

The payload is consumed exactly: bytes left over after the last stream are an error.

### Header

Two bytes. The reorder is not part of it.

| Byte | Bits | Field | Values |
|---|---|---|---|
| 0 | 0-2 | predictor, low 3 bits | `Prediction` ordinal & 7 |
| 0 | 3-4 | segmentation | `FLAT`=0, `CLASS`=1, `ROLE`=2, `ROLE_CLASS`=3 |
| 0 | 5 | residual map | `ZIGZAG`=0 (the only one) |
| 0 | 6-7 | transpose | `NONE`=0, `INTERLEAVED_PLANES`=1, `PLANE_MAJOR`=2, `COMPONENT_MAJOR`=3 |
| 1 | 0-3 | byte width | `VARINT`=0, `VARINT_SPLIT`=1, `MIN_FIXED`=2, `ESCAPE`=3, `FORCE_4`=4 |
| 1 | 4 | predictor, bit 3 | `Prediction` ordinal >> 3 (the predictors outgrew the 3 bits of byte 0) |
| 1 | 5-7 | free | 0; **three bits of headroom** |

The predictor ordinals are the declaration order of `Prediction` and are append-only: a new
predictor takes the next ordinal, never shifts an existing one. There are 14 today; the four
predictor bits hold 16, and bits 5-7 of byte 1 are the reserve beyond that.

A fixed-width byte width with a transpose other than `NONE` and `INTERLEAVED_PLANES` is applied
to residual streams only; the **side stream** of a predictor with side values (`TURN_SCALAR`'s
`along` scalars) is not an `x, y` pair stream and can have odd length, so it always uses
`INTERLEAVED_PLANES`.

### Shared-reference block

Present only when the predictor is `QUAD_CORNER_SHARED`, `QUAD_CORNER_SHARED_FRAME`,
`LINE_CONTINUATION_SHARED` or `LINE_CONTINUATION_SHARED_FRAME_HEADING`. A shared predictor's
buffer is written as its plain predictor (header id of the plain one, no block) whenever the
references do not make it smaller, so a decoder needs no mode byte.

```
varint  flagsLength      bytes of the flag bit stream
bytes   flags
varint  indicesLength    bytes of the index bit stream
bytes   indices
varint  valueCount[s]    one per stream of the layout (side stream included)
```

The flags and indices are bit-packed with `BitWriter` (most significant bit first within
a byte, last byte zero-padded). Which vertices are references is only known while walking the
buffer, so a referenced vertex's residual is removed from its stream and the stream lengths
cannot be derived from the topology; hence the explicit `valueCount`s.

- **Rings** (`RingReferences`): per ring vertex that can reference an earlier ring in a window
  of `WINDOW`=4 rings, an entry flag and, on entry, a `ceil(log2 candidates)`-bit index into
  the candidate positions; after any vertex whose position an earlier ring holds (a referenced vertex or
  one that coincides with it) a follow flag says "the next vertex is the previous vertex of that ring". Rings are rotated by the encoder to start where a
  shared stretch begins; the rotation is never transmitted (the decoder emits the rotated ring).
- **Lines** (`LineReferences`): at every line part start and end, a flag and on a hit a
  `ceil(log2 dictionarySize)`-bit index into the dictionary of all distinct positions of earlier
  line parts. A part whose end alone is in the dictionary is reversed by the encoder (never
  transmitted; this changes line direction).

### Streams

Each non-empty stream is a row of `x, y`-interleaved signed residuals (a side stream:
one signed value per carrier vertex), written by `StreamCodec.encode`:

1. **Map** (S4): zigzag, `(n << 1) ^ (n >> 31)`.
2. **Width** (S6) and **transpose** (S7):
   - **Varint terminals** (`VARINT`, `VARINT_SPLIT`): the mapped values as `IntCodec` bytes.
     The body is prefixed by `varint bodyLength` unless this is the last non-empty stream, which
     takes the rest of the payload.
   - **`FORCE_4`** (test only): every value as 4 little-endian bytes, laid out by the transpose;
     no descriptor.
   - **`MIN_FIXED` / `ESCAPE`**: one descriptor byte, then the body.

   ```
   descriptor  bits 0-1  main width code   0 = 1 byte, 1 = 2 bytes, 2 = 4 bytes
               bit  2    escape flag
               bits 3-4  exception width code (same codes), meaningful with the escape flag
   ```

   With the escape flag clear the body is `n` values at the main width, little-endian, laid out
   by the transpose. With it set (`ESCAPE` when `n + exceptions * exceptionWidth < n * width`)
   the main stream is `n` single bytes where `0xFF` marks an exception, followed by the
   exceptions (`value - 255`) at the exception width as `INTERLEAVED_PLANES`; the decoder counts
   the `0xFF` markers to size the exception stream.

Stream order is the segmentation's: `FLAT` one stream; `CLASS` one per predictor class in the
predictor's `regionOrder`; `ROLE` jump, edge, body; `ROLE_CLASS` jump, edge, then the body split
by class. A side stream, if the predictor has one, comes after the residual streams.

## Invariants the tests pin

- `PipelineEncodingTest` round-trips every row from the topology alone (`everyRow`) on a single
  square, a multi-part buffer, a mixed point and polygon buffer, one vertex, a constant buffer and
  extreme coordinates, and checks that the header round-trips every choice
  (`headerRoundTripsEveryChoice`).
- `identityBodyIsTheBaselineVarint` and `splitTerminalMatchesTheOldSplitBody` pin the identity row's
  body, after the 2-byte header, to the baseline's bytes.
- `PredictionMechanismTest.headerCarriesThePredictor` round-trips the header for every predictor.
- `./gradlew research --args="dump --diff <old> <new>"` compares per-buffer sizes of two dumps
  and is how a change to this format is proven not to move a number.
