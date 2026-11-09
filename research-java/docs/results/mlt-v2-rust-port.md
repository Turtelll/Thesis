# The tile-oracle pipeline as the MLT v2 vertex encoder (Rust)

**Status:** measured (`research-rust`, `mlt-oracle compare`); the codec is bit-exact with this repository's Java
**Corpus:** `munich-plantiler.mbtiles`, 358 tiles, 4.368 (tile, layer) buffers (the current baseline corpus)

## Question

The pipeline codec ([bitstream.md](../bitstream.md)) wins against `DELTA_ZIGZAG_VARINT` inside the Java
harness, on vertex buffers cut out of the tile. Does it still win as the **VertexBuffer encoder of MapLibre Tile v2**
(`mlt-core`, `unstable-v2`), measured on the **whole tile** the way a client receives it: every layer written by the real
encoder, the layers concatenated, compressed with gzip?

## What was built

`research-rust/` ([README](../../../research-rust/README.md)):

- `mlt-pipeline`: the codec in Rust, all 96 compositions of `CompositionGrids.bestGrid()`.
- `mlt-core-pipeline`: `mlt-core` 0.22.1 at the submodule's pinned commit with one patch: the v2 vertex stream
  is written by the codec when `EncoderConfig::with_vertex_pipeline(Some(composition))` is set, and read back by
  the decoder. The submodule is untouched. The stream code of rANS vertices carries the payload (no new
  `VertexLogical` variant, see the deviation in the README): files from this copy do not decode with stock `mlt-core`.
- `mlt-oracle compare`: per tile, the stock encoder and the tile oracle.

**Bit-exactness.** `mlt-oracle golden-check` encodes every buffer with every composition and compares payload SHA-256,
payload length, the emitted topology and the decode against the Java golden dump.

| golden | buffers | (row, buffer) pairs | differ |
|---|---|---|---|
| `data/golden/v-final-full` (the grid rows it holds, 61 of 96) | 4.368 | 266.448 | **0** |
| `data/golden/grid-best-150` (`research dump --grid best`, all 96 rows, first 150 tiles) | 1.732 | 166.272 | **0** |

## What is compared

All encoders write wire version 2, the default profile (`Profile::Native`), `Tessellation::Off` (the vertex stream is
the subject; triangles would add streams the codec does not touch) and the `mlt-convert --mlt-version 2` defaults of
the v2 codecs (ALP floats, float dictionaries, bit-packed dictionary codes). The tile is the layers' bytes concatenated,
the size is gzip level 6 (real zlib, as `java.util.zip` deflates), summed over the tiles.

| row | what |
|---|---|
| stock | `mlt-core` as is |
| stock + sort | stock with its own feature-order trials (Hilbert, feature id) |
| pipeline, per-layer default | every vertex stream by the codec with `forLayer(layer)` |
| pipeline, per-layer alone-best | per layer the composition whose layer bytes gzip smallest alone |
| pipeline, tile oracle | `TileSelector.oracle` (coordinate descent on the gzip size of the whole tile, from the smaller of the default and the alone-best tile, at most 10 passes), 96 candidates per layer |
| stock + gzip stream selection | stock with every stream-encoding competition decided by gzip size, see [the other streams by gzip size](#the-other-streams-by-gzip-size) |
| pipeline, tile oracle + gzip streams | the tile oracle with every candidate on gzip stream selection; what `--vertex-pipeline oracle` writes now |

A composition's reorder permutes **whole layers**: id and every property column follow the features, so a candidate is a
complete layer encoding and the tile the oracle minimises is the complete MLT v2 tile (the Java oracle sees the geometry buffers
only; its `tile-size` probe measured the property side separately). The reorder's vertex pass (ring rotation, MultiPoint sort)
is inside the codec, so a decoded layer holds the vertices the codec emitted.

## Result

```
mlt-oracle compare --mbtiles ../data/MVT/munich-plantiler.mbtiles --by-zoom --by-layer --csv munich-plantiler.csv
```

| row | gzip bytes | vs stock | raw bytes | vs stock |
|---|---|---|---|---|
| stock v2 | 16.786.524 | ref | 18.487.134 | ref |
| stock v2 + own sort trials | 16.643.440 | -0,85% | 18.317.963 | -0,92% |
| pipeline, plain delta + split varint | 16.202.284 | -3,48% | 19.383.707 | +4,85% |
| pipeline, per-layer default (`forLayer`) | 15.785.075 | **-5,97%** | 19.186.915 | +3,79% |
| pipeline, per-layer alone-best | 15.255.104 | -9,12% | - | - |
| pipeline, tile oracle, no feature reorder (32 compositions) | 15.298.529 | -8,86% | 18.767.109 | +1,51% |
| pipeline, tile oracle (96) | 15.234.332 | **-9,25%** | 18.702.304 | +1,16% |
| stock v2 + gzip stream selection | 16.083.379 | -4,19% | 20.721.218 | +12,08% |
| pipeline, tile oracle (96) + gzip streams | 15.084.159 | **-10,14%** | 20.312.304 | +9,87% |

The two last rows are the [other streams by gzip size](#the-other-streams-by-gzip-size); every other section up to there
is the tile oracle with the stock raw-length stream selection.

### Where the 9,25% comes from

Each step is the same tile, one decision added:

| step | gzip bytes | step vs previous |
|---|---|---|
| stock v2 | 16.786.524 | |
| every layer: plain delta + zigzag + split varint (the Java `DELTA_ZIGZAG_VARINT` reference, written by the codec) | 16.202.284 | **-3,48%** |
| per-layer best of the 32 compositions without a feature reorder (tile oracle) | 15.298.529 | **-5,58%** |
| the 64 compositions with a feature reorder added | 15.234.332 | **-0,42%** |

- **About 38% of the gain (3,48 of 9,25 points) is the baseline.** Under gzip the stock v2 vertex encoding is 3,6% larger than
  plain delta + varint residuals written by the codec (16.786.524 vs 16.202.284). The stock encoder picks its integer coding by
  size before gzip, and [general-purpose-compression.md](general-purpose-compression.md) found in Java that the smaller raw
  encoding is not the smaller one under gzip, which is why the Java baseline is `DELTA_ZIGZAG_VARINT`. (This port did not inspect which
  stock codec wins per layer; the cause is inferred.) Against that gzip-aware plain row the pipeline's own gain is -6,0%
  (15.234.332 vs 16.202.284).
- The predictors, stream splits and escape planes give -5,6%; they are the codec.
- The whole-layer feature reorder is worth only -0,4% once the property columns are in the size. The tile oracle uses the 64
  reorder compositions on 28% of the layers.

- The tile oracle saves 1,55 MB of 16,79 MB gzipped. Against the best stock variant (own sort trials) it is -8,47%.
- The codec trades raw bytes for compressibility: the oracle tile is 1,2% larger **uncompressed** and 9,3% smaller under gzip.
  Its residual streams are built for a general-purpose compressor, not as a final format ([general-purpose-compression.md](general-purpose-compression.md)).
- The descent beats the alone-best tile by 0,14% only (the alone-best choice is already close to a local optimum of the tile), and
  the per-layer default by 3,49%: the fixed `forLayer` row is not tuned for whole layers (see `poi` below).
- Every one of the 358 oracle tiles decodes and keeps its geometry (see Verification below).

### Verification

Four checks, all run on the full corpus:

1. **Decode.** All 358 oracle tiles decode with the patched decoder; the vertices equal the codec's emitted buffer, ids and
   properties equal the stock decode of the same feature order.
2. **Geometry.** Every feature of every oracle tile is compared with the stock decode of the same layer (parts, rings, vertices).
   0 of 482.944 features hold another shape. 428.850 are identical (same vertices, same order); 31.971 are *reordered*
   (a ring starting at another vertex, a MultiPoint sorted: `building` 7.094, `housenumber` 17.274, `landcover` 4.199, `landuse` 3.050,
   `water` 329); **22.123 are *reversed***: the same line, running the other way (`transportation` 18.521, `transportation_name` 2.477,
   `waterway` 1.082, `boundary` 43). The `CHAIN` reorder reverses lines; a client that uses line direction (one-way arrows, waterway flow)
   sees it flipped. The thesis' lossless claim is *geometry up to ring start and line direction*, the same freedom the Java
   `CHAIN` row has. The reference is the stock decode of the `TileLayer` that `mvt_to_tile_layers` reads from the MVT (stock v2 is lossless
   for it); the MVT itself is not re-read.
3. **Independent size.** `mlt-oracle compare --write-tiles <dir>` writes every stock and oracle tile as `<z>_<x>_<y>.stock.mlt` / `.oracle.mlt`;
   `research-rust/check_tiles.py` re-computes gzip level 6 with Python's zlib: 16.786.524 and 15.234.332 bytes, the same as the Rust
   figures, per tile (0 mismatches over 358 tiles).
4. **Determinism.** A second full run wrote 716 files, byte-identical to the first (same hashes), and the same table.

### By zoom (gzip bytes)

| zoom | tiles | stock | default | oracle | oracle vs stock |
|---|---|---|---|---|---|
| z0 | 1 | 6.481 | 6.074 | 5.994 | -7,51% |
| z4 | 1 | 7.905 | 7.796 | 7.752 | -1,94% |
| z7 | 1 | 82.009 | 78.703 | 78.497 | -4,28% |
| z9 | 1 | 162.740 | 151.948 | 151.034 | -7,19% |
| z10 | 4 | 320.231 | 300.589 | 298.066 | -6,92% |
| z11 | 9 | 412.381 | 382.804 | 379.269 | -8,03% |
| z12 | 20 | 700.404 | 658.437 | 638.248 | -8,87% |
| z13 | 63 | 2.121.098 | 1.947.447 | 1.911.720 | -9,87% |
| z14 | 252 | 12.811.126 | 12.097.173 | 11.610.021 | **-9,38%** |

The gain grows with zoom: z14 holds 76% of the bytes and the vertex mass. Every zoom is smaller than stock.

### By layer (each layer compressed on its own, so the total differs from the tile)

| layer | layers | stock | default | oracle | default vs stock | oracle vs stock |
|---|---|---|---|---|---|---|
| building | 310 | 4.287.027 | 3.483.515 | 3.483.636 | -18,74% | **-18,74%** |
| landcover | 351 | 1.748.145 | 1.544.086 | 1.512.044 | -11,67% | -13,51% |
| transportation | 354 | 3.608.695 | 3.300.258 | 3.199.356 | -8,55% | -11,34% |
| landuse | 348 | 876.416 | 802.374 | 794.569 | -8,45% | -9,34% |
| waterway | 243 | 158.040 | 149.952 | 147.642 | -5,12% | -6,58% |
| transportation_name | 351 | 892.802 | 847.715 | 838.878 | -5,05% | -6,04% |
| water | 341 | 279.966 | 271.640 | 263.434 | -2,97% | -5,91% |
| boundary | 333 | 132.501 | 126.001 | 125.391 | -4,91% | -5,37% |
| park | 335 | 298.468 | 288.622 | 288.644 | -3,30% | -3,29% |
| aeroway | 58 | 13.274 | 13.394 | 13.113 | +0,90% | -1,21% |
| water_name | 260 | 65.882 | 66.087 | 65.569 | +0,31% | -0,48% |
| mountain_peak | 123 | 30.098 | 30.111 | 30.064 | +0,04% | -0,11% |
| place | 356 | 391.111 | 394.913 | 391.091 | +0,97% | -0,01% |
| poi | 329 | 3.035.607 | 3.411.173 | 3.058.335 | **+12,37%** | +0,75% |
| aerodrome_label | 31 | 7.166 | 7.220 | 7.221 | +0,75% | +0,77% |
| housenumber | 245 | 1.239.693 | 1.262.901 | 1.262.033 | +1,87% | +1,80% |
| **all** | 4.368 | 17.064.891 | 15.999.962 | 15.481.020 | -6,24% | -9,28% |

- `building` is most of the gain (-18,7%, 0,8 MB): the ring-frame predictors on a polygon-only layer.
- `poi`: the fixed default (`HILBERT_CENTROID_ROT_MP` + `DELTA`) is **+12,4% worse than stock** once the property columns are in the
  size. The likely cause is that the Hilbert feature order breaks up the runs in the property columns that the original (draw) order
  keeps, which a geometry-only oracle cannot see (not isolated here). The tile oracle moves most `poi` layers off the reorder and ends
  +0,75% above stock. `housenumber` and `aerodrome_label` end +0,8 to +1,8% above stock too, whichever of the 96 compositions
  is picked; not investigated. The tile as a whole is still 9,25% smaller.

### What the oracle picks (4.368 buffers)

| stage | pick |
|---|---|
| reorder | none 71,9%, `HILBERT_CENTROID_ROT_MP` 22,2%, `CHAIN` 5,8% |
| predictor | `DELTA` 34,2%, `QUAD_CORNER_SHARED_FRAME` 16,1%, `LINE_CONTINUATION_SHARED_FRAME_HEADING` 16,1%, `QUAD_CORNER` 9,8%, `QUAD_CORNER_FRAME` 8,5%, `LINE_CONTINUATION_FRAME_HEADING` 6,5%, `LINE_CONTINUATION` 4,9%, `QUAD_CORNER_SHARED` 3,8% |
| streams | `FLAT` 55,8%, `CLASS` 44,2% |
| terminal | `VARINT_SPLIT` 53,0%, `ESCAPE_PLANES` 47,0% |

Whole-layer reorders are picked on 28,0% of the buffers; in the Java geometry-only search a reorder costs the properties nothing, so the
shares are not comparable.

### Whole tileset through `mlt convert`

The encoder is also in the `mlt` CLI (`research-rust/mlt-cli-pipeline`, [README](../../../research-rust/README.md)):
`mlt convert <in>.mbtiles <out>.mbtiles --mlt-version 2 --vertex-pipeline <off|default|oracle|NAME> --verify` writes a whole tileset, and
`mlt ui <out>.mbtiles` opens it in the terminal visualizer. Same corpus (`munich-plantiler.mbtiles`, 358 tiles), sizes summed over the
tiles of the written archive (raw, and gzip level 6 re-measured with Python's zlib by `research-rust/check_convert.py --sum`):

| `--vertex-pipeline` | raw bytes | gzip bytes | vs stock | layers by the codec / stock layout | `convert` time |
|---|---|---|---|---|---|
| (stock v2, from the table above) | 18.487.134 | 16.786.524 | ref | - | - |
| `default` | 19.186.915 | 15.785.075 | -5,97% | 4.368 / 0 | 9 s |
| `PIPE_QUAD_CORNER_SHARED_FRAME_CLASS_ZIGZAG_ESCAPE_PLANES` for every layer | 19.338.509 | 15.534.092 | -7,46% | 4.368 / 0 | 9 s |
| `oracle` | 18.702.304 | 15.234.332 | **-9,25%** | 4.368 / 0 | 697 s |

- `default` and `oracle` give the figures of `compare` to the byte (15.785.075 and 15.234.332 gzip, 19.186.915 and 18.702.304 raw).
- The `oracle` archive equals the 358 `<z>_<x>_<y>.oracle.mlt` files that `compare --write-tiles` wrote, byte for byte (`check_convert.py`: 0 differ),
  and so does a 40-tile subset converted on its own. `compare` over its first 40 tiles before and after moving the oracle into the shared
  `mlt-tile-oracle` crate wrote the same CSV and the same 80 tile files.
- `--verify` ran on all three conversions: every tile decodes with the patched decoder, the vertices equal the codec's emitted buffer, and every
  feature keeps its shape against the stock decode of the same layer (up to ring start and line direction, as in the geometry check above).
  The stock round-trip check against the input does not apply, since the codec may rotate rings and reverse lines.
- One composition for every layer (the `QUAD_CORNER_SHARED_FRAME` row, the most picked ring predictor) already gives -7,46% with no search,
  and beats `forLayer` by 1,6 points; the oracle adds 1,9 points on top. This row was not searched for: it is the one composition the check used, and the
  best single composition of the 96 was not looked for.
- The `mlt ui` map reads the `format` metadata of an archive and decodes MLT tiles with the patched decoder. The loader was driven without a
  terminal (a temporary probe that asked it for tile 9/272/177 of the oracle and default archives): both decode to the same 2.333 features,
  11 layers, as the MVT source. The interactive screens themselves were not driven.

## The other streams by gzip size

The tile oracle searched the vertex stream only. Every other stream of a layer (ids, geometry lengths and types,
integer, float and string properties, shared dictionaries; and the stock vertex layouts of a layer that falls back)
is picked by stock `mlt-core` in a competition of candidate encodings (`Encoder::try_alternatives`: varint vs FastPFOR,
RLE, delta, FSST vs dictionary vs plain, ...), and stock keeps the candidate with the fewest **raw** bytes.

`EncoderConfig::with_gzip_selection(true)` (patch in `mlt-core-pipeline`, `writer.rs`) decides those competitions by
gzip instead: a candidate's score is its deflate size (zlib level 6) after the layer written so far. zlib is primed with
the last 32 KiB (the deflate window) of the layer before the competition, with the running best of every enclosing
competition left out, and compresses only the candidate. The first candidate is scored only once a second one
arrives. The context is the layer, not the tile: the layers before it are not decided while its candidates are written.
The tile oracle (`mlt_tile_oracle::gzip_config`) writes every one of its 96 candidates per layer this way, and the
descent then runs as before. With the flag off every competition is decided as before: the raw-length rows above are
reproduced to the byte (15.234.332 / 18.702.304).

```
mlt-oracle compare --mbtiles ../data/MVT/munich-plantiler.mbtiles --by-zoom --by-layer --csv gzip-streams.csv --write-tiles tiles-gzip-streams
```

| step | gzip bytes | step vs previous | raw bytes |
|---|---|---|---|
| stock v2 | 16.786.524 | | 18.487.134 |
| stock v2 + gzip stream selection | 16.083.379 | **-4,19%** | 20.721.218 |
| tile oracle (96), raw-length streams | 15.234.332 | -9,25% vs stock | 18.702.304 |
| tile oracle (96) + gzip streams | 15.084.159 | **-0,99%** (-10,14% vs stock) | 20.312.304 |

- Under the tile oracle the other streams give one point more (-150 KB). On stock the same switch gives -4,19%, which
  also decides the stock vertex layouts by gzip; how much of the -4,19% is vertex streams was not isolated.
- The tiles grow raw (+9,87% vs stock, against +1,16% for the raw-length oracle): gzip prefers encodings that are
  larger before compression. Which encodings change was not inspected.
- By zoom the gain is in every zoom from z5 up; z14 goes from -9,38% to **-10,39%** vs stock, z0 to z4 are unchanged.
- By layer (each layer gzipped on its own, oracle vs stock): `poi` +0,75% → **-1,56%** (the one large layer that was
  above stock), `place` -0,01% → -2,56%, `transportation_name` -6,04% → -8,03%, `landcover` -13,51% → -13,76%,
  `building` -18,74% → -19,50%, `transportation` -11,34% → -11,71%; `housenumber` +1,80% → +2,31% (worse on its own; the oracle
  scores the tile, not the layer alone), `aerodrome_label` +0,77% → +0,63%. All layers -9,28% → -10,15%.
- Picks: reorder none 69,6%, `HILBERT_CENTROID_ROT_MP` 23,9%, `CHAIN` 6,5% (before 71,9 / 22,2 / 5,8%).

**Verification.** All 358 tiles of both oracles decode, the vertices equal the codec's emitted buffer, ids and properties
equal the stock decode of the same feature order, 0 of 482.944 features hold another shape (22.160 reversed, 33.696 reordered).
`check_tiles.py` on the written tiles: 16.786.524 and 15.084.159 bytes, 0 mismatches against the CSV over 358 tiles. Determinism
(a second run) was not checked.

**The 32 KiB window.** Scoring with gzip of the whole layer so far plus the candidate (the exact criterion) was measured on the first
40 tiles: oracle + gzip streams 1.631.993 bytes (-0,95% vs the raw-length oracle) against 1.633.109 (-0,88%) with the window, so the
window gives away 0,07 points. It is the deflate window, so every match the exact criterion could find is in reach; the difference is
deflate's block splits (each candidate is compressed as its own stream).

**Cost.** First 40 tiles (the low zooms and the first z12-z14 tiles), `compare --no-stock-sorted`, 12 cores: 2 min 11 s with both
trial sets and no scoring, 8 min 31 s with the exact criterion, 6 min 22 s with the window, 3 min 19 s with the window, lazy first-candidate
scoring and one reused zlib state (same bytes as the window alone). The full corpus took about 70 minutes (from the file times; it was not timed),
against 14 minutes before: `compare` now writes every layer twice (raw-length and gzip stream selection), scores every competition and
verifies both oracles.

**Not re-run.** `mlt convert --vertex-pipeline oracle` now writes the gzip-stream oracle; the `convert` table above (15.234.332, 697 s) is
the raw-length oracle and was not re-run, nor `check_convert.py` against the new tiles.

## Caveats

- The decoder side was not timed: `decode-time` is a Java probe, and this port's decoder is a straightforward loop.
- The oracle is the descent's local optimum, as in Java, and the encode of 96 full layers per layer is far from a production cost
  (the 358 tiles took 14 minutes on this machine, all cores, with the verification and the two extra oracles).
- The "no feature reorder" oracle row runs its own descent on the 32 compositions with reorder `None`; it is not a subset of the
  full oracle's path, only of its candidate set, so the reorder step of -0,42% is a difference of two local optima.
- The stream code reuse and the stock-incompatible files are listed in the [README](../../../research-rust/README.md).
- Layers with z coordinates, tessellated layers, vertex dictionaries and empty vertex buffers keep stock vertex layouts (none occurs in this corpus: 0 layers fell back).
- Not measured: Brotli and zstd (the oracle searches gzip only), `Tessellation::Auto`.
