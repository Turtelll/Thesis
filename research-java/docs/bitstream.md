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
| 0 | 0-2 | predictor, low 3 bits | `Prediction` code & 7 |
| 0 | 3-4 | segmentation | `FLAT`=0, `CLASS`=1, `ROLE`=2, `ROLE_CLASS`=3 |
| 0 | 5 | residual map | `ZIGZAG`=0 (the only one) |
| 0 | 6-7 | transpose | `NONE`=0, `INTERLEAVED_PLANES`=1, `PLANE_MAJOR`=2, `COMPONENT_MAJOR`=3 |
| 1 | 0-3 | byte width | `VARINT`=0, `VARINT_SPLIT`=1, `MIN_FIXED`=2, `ESCAPE`=3, `FORCE_4`=4 |
| 1 | 4-7 | predictor, high bits | `Prediction` code >> 3 (the predictors outgrew the 3 bits of byte 0; codes up to 127) |

The predictor field holds an explicit, stable **code** per `Prediction` constant
(`Prediction.code()`, not the declaration order), so retiring a predictor never shifts the others.
Seventeen are live. The grids and the per-layer defaults use the first three, `QUAD_CORNER_SHARED`, the two
integer line-frame predictors (codes 18, 19) and the two one-vote ring frames (codes 23 and 25,
[results/previous-frame.md](results/previous-frame.md)). The other ring frames are retired for now (marked below): the
float frame (5, 8), the nearest ring's integer frame (16, 17) and the other previous-ring variants (20, 21, 22, 24) stay decodable
and writable by name but are in no grid, default or selector. The float line frames (11, 13) stay out of the grids as before
(see [results/frame-decode-time.md](results/frame-decode-time.md)):

| Code | Predictor |
|---|---|
| 0 | `DELTA` |
| 1 | `QUAD_CORNER` |
| 2 | `LINE_CONTINUATION` |
| 5 | `QUAD_CORNER_FRAME` (float frame; retired) |
| 7 | `QUAD_CORNER_SHARED` |
| 8 | `QUAD_CORNER_SHARED_FRAME` (float frame; retired) |
| 11 | `LINE_CONTINUATION_FRAME_HEADING` (float frame) |
| 13 | `LINE_CONTINUATION_SHARED_FRAME_HEADING` (float frame) |
| 16 | `QUAD_CORNER_IFRAME` (integer frame of the nearest ring; retired, replaced by 23) |
| 17 | `QUAD_CORNER_SHARED_IFRAME` (retired, replaced by 25) |
| 18 | `LINE_CONTINUATION_IFRAME_HEADING` |
| 19 | `LINE_CONTINUATION_SHARED_IFRAME_HEADING` |
| 20 | `QUAD_CORNER_PREVFRAME` (retired: integer frame of the previous ring, fixed per ring) |
| 21 | `QUAD_CORNER_PREVFRAME_VOTE` (retired: the previous ring's frame plus the ring's own votes) |
| 22 | `QUAD_CORNER_PREVFRAME_FIRST` (retired: previous ring's frame for the first edge, the ring's own first edge after it) |
| 23 | `QUAD_CORNER_PREVFRAME_FIRST_ONLY` (the ring frame of the grids and the per-layer defaults: as 22, a ring remembered by its first edge, one vote per ring) |
| 24 | `QUAD_CORNER_PREVFRAME_FIRST_BLEND` (retired: as 23, the first edge votes on top of the previous ring's frame) |
| 25 | `QUAD_CORNER_SHARED_PREVFRAME_FIRST_ONLY` (the shared ring frame of the grids and the per-layer defaults: 23 on the shared-vertex references, as 17 is 16; a buffer the references do not shrink is written as 23) |
| 26 | `QUAD_CORNER_SHARED_RAW` (experimental: 7 with the raw entry index, see Rings below; a buffer the references do not shrink is written as 1) |
| 27 | `QUAD_CORNER_SHARED_RAW_PREVFRAME_FIRST_ONLY` (experimental: 25 with the raw entry index; falls back to 23) |
| 28 | `LINE_CONTINUATION_SHARED_RAW` (Rust only, the line references of its grid and per-layer defaults: 12 with the index a slot into the earlier line parts' raw vertices, see Lines below; falls back to 2) |

The codes 3 (`QUAD_CORNER_LINE`), 4 (`TURN_SCALAR`), 6 (`QUAD_CORNER_LINE_FRAME`), 9
(`LINE_CONTINUATION_FRAME`), 10 (`LINE_CONTINUATION_HEADING`) and 12 (`LINE_CONTINUATION_SHARED`)
belong to predictors that were retired (the code of the first two is in `encoder/retired/`, see
[results/tile-oracle.md](results/tile-oracle.md)); a payload carrying one no longer decodes, and the
codes are not reused. The bytes of every kept row are unchanged (the grid's rows with codes 16 and 17 became rows with codes 23 and 25, a different row, not a changed one). The five bits that hold the code
(byte 0 bits 0-2, byte 1 bits 4-5 in use) leave room up to code 127 without a header change.

### The integer frame (codes 16-19)

The frame predictors rotate the plain-delta edges of a run into the frame of the nearest decoded run
(`FramePrior`). The float frame (5, 8, 11, 13) needs `atan2`, `sin`, `cos`, `tan` of the platform's libm
for its frame index and shears, so two implementations agree only by matching fdlibm. The integer frame
computes the same thing without libm in the decoder:

- **Votes** are unchanged: `|z| e^(4i phi)` of every edge, summed with IEEE `+ - * /` and `sqrt` only
  (correctly rounded, hence portable), plus the prior of weight 50 from the nearest decoded run's unit vector.
- **Frame index**: 12 bits, 4096 frames over 90 degrees, zero frame 2048:
  `(((direction(sumSin, sumCos) + 2) >> 2) + 2048) & 4095`. `direction(y, x)` is the angle in units of
  1/16384 turn from the octant and quadrant, one division and an integer `ATAN` table of 4097 entries
  (`ATAN[(int)(slope * 4096 + 0.5)]`; pi = 8192).
- **Rotation**: three lifting shears with Q16 integer tables `TAN_HALF` and `SIN` (rounded from fdlibm
  once, at start): `x1 = dx - ((t*dy + 32768) >> 16)`, `y1 = dy + ((s*x1 + 32768) >> 16)`,
  `x = x1 - ((t*y1 + 32768) >> 16)`; an exact bijection like the float one, so nothing is signalled.
- **Run record**: the unit vector of the run's own votes (`own / |own|`, default `(1, 0)`) instead of an
  angle, so the prior needs no `sin`/`cos` per run.
- **Heading** (`LINE_CONTINUATION_IFRAME_HEADING`): `shifted = direction(ey, ex) + 2048`, quarter turns
  `floorDiv(shifted, 4096) mod 4`, index `shifted - quarters * 4096`, rotated with the same shears.

The tables are derived once from fdlibm values (`StrictMath` in Java, `libm` in Rust) rounded to integers; they
are the only place a floating-point function enters, and a different rounding of one entry would change bytes,
which `golden-check` and the Java golden dump `grid-best-151` pin.

A fixed-width byte width with a transpose other than `NONE` and `INTERLEAVED_PLANES` is applied
to residual streams only; the **side stream** of a predictor with side values (none is live; the retired `TURN_SCALAR`'s
`along` scalars were one) is not an `x, y` pair stream and can have odd length, so it always uses
`INTERLEAVED_PLANES`.

### Shared-reference block

Present only when the predictor is `QUAD_CORNER_SHARED`, `QUAD_CORNER_SHARED_FRAME`,
`QUAD_CORNER_SHARED_IFRAME`, `LINE_CONTINUATION_SHARED_FRAME_HEADING` or
`LINE_CONTINUATION_SHARED_IFRAME_HEADING`. A shared predictor's
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
  the candidate positions (the window's distinct positions, newest ring first, first occurrence kept; under codes 26
  and 27 a `ceil(log2 windowVertices)`-bit slot into the window's raw vertices, newest ring first, the first slot
  holding the position, which the decoder reads without a table); after any vertex whose position an earlier ring holds (a referenced vertex or
  one that coincides with it) a follow flag says "the next vertex is the previous vertex of that ring". Rings are rotated by the encoder to start where a
  shared stretch begins; the rotation is never transmitted (the decoder emits the rotated ring).
- **Lines** (`LineReferences`): at every line part start and end, a flag and on a hit a
  `ceil(log2 dictionarySize)`-bit index into the dictionary of all distinct positions of earlier
  line parts (under code 28 a `ceil(log2 slots)`-bit slot into the earlier line parts' raw vertices, in buffer
  order, the first slot holding the position, which the decoder reads without a table). A part whose end alone
  is in the dictionary is reversed by the encoder (never transmitted; this changes line direction).

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
