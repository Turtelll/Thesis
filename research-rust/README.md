# research-rust

The tile-oracle pipeline encoding of `research-java`, ported to Rust and used as the **VertexBuffer
encoder of MapLibre Tile v2** (`unstable-v2`), measured against the stock v2 encoder under gzip.
Result: [`research-java/docs/results/mlt-v2-rust-port.md`](../research-java/docs/results/mlt-v2-rust-port.md).

| crate | what |
|---|---|
| `mlt-pipeline` | the codec: reorder, predict, segment, zigzag, byte width, transpose, the 2-byte self-describing header, the shared-reference blocks. Bit-exact with Java (`golden-check`). No dependency on mlt-core. |
| `mlt-core-pipeline` | a copy of `maplibre-tile-spec/rust/mlt-core` at the pinned commit (`062d6c72`, 0.22.1) with a small patch, library name `mlt_core`. The submodule itself is untouched. |
| `mlt-tile-oracle` | the tile oracle as a library: `encode_tile(layers, base, VertexMode, verify)` writes the layers of a tile with the codec as vertex stream (per-layer default, one fixed composition, or the gzip descent over the 96 compositions, every candidate with its other streams picked by gzip size: `gzip_config`), plus the geometry check. Shared by `mlt-oracle` and the `mlt` CLI. |
| `mlt-oracle` | the measurement CLI: `golden-check` (codec against the Java golden master), `compare` (whole-tile gzip, stock vs the tile oracle) and `diff` (two tilesets hold the same features). |
| `mlt-cli-pipeline` | a copy of `maplibre-tile-spec/rust/mlt` (the `mlt` CLI, 0.6.1) at the same commit, linked against `mlt-core-pipeline`, with `mlt convert --vertex-pipeline` and an MLT-aware `.mbtiles` map in `mlt ui`. Binary name `mlt`. |

## Commands (from `research-rust/`)

```
cargo test -p mlt-pipeline
cargo build --release -p mlt-oracle
mlt-oracle golden-check                                   # 96-grid rows present in ../data/golden/v-final-full, matched by name
mlt-oracle golden-check --golden ../data/golden/grid-best # all 96 rows (research dump --grid best)
mlt-oracle compare --mbtiles ../data/MVT/munich-plantiler.mbtiles --by-zoom --by-layer --csv out.csv
mlt-oracle compare --mbtiles ../data/MVT/munich-plantiler.mbtiles --write-tiles target-results/tiles   # also write <z>_<x>_<y>.stock.mlt / .oracle.mlt
python check_tiles.py target-results/tiles out.csv        # independent gzip -6 sizes (Python zlib) of the written tiles, checked against the csv
mlt-oracle decode-time target-results/munich-stock.mbtiles target-results/munich-oracle.mbtiles --by-zoom   # whole-tile decode time (parse + decode_all), first archive (stock v2) as reference; --reps/--warmup/--csv
```

### Whole tilesets and the visualizer: the `mlt` CLI

```
cargo build --release -p mlt-cli-pipeline                 # builds target/release/mlt
mlt convert in.mbtiles out.mbtiles --mlt-version 2 --vertex-pipeline default   # per-layer default composition (forLayer)
mlt convert in.mbtiles out.mbtiles --mlt-version 2 --vertex-pipeline oracle    # tile oracle: best of 96 compositions per layer on the gzip size of the tile, the other streams picked by gzip size
mlt convert in.mbtiles out.mbtiles --mlt-version 2 --vertex-pipeline PIPE_QUAD_CORNER_SHARED_FRAME_CLASS_ZIGZAG_ESCAPE_PLANES   # one composition for every layer
mlt convert in.mbtiles out.mbtiles --mlt-version 2 --vertex-pipeline oracle --verify   # decode every tile, compare with the stock decode
mlt ui out.mbtiles                                        # terminal visualizer: the world map of an MLT .mbtiles
mlt ui tile.mlt | mlt ui <directory> | mlt ls | mlt decode | mlt dump | mlt hexdump   # read pipeline tiles as well
python check_convert.py --subset ../data/MVT/munich-plantiler.mbtiles target-results/munich40.mbtiles 40   # the first 40 tiles, as a small tileset
python check_convert.py out.mbtiles target-results/tiles   # converted tiles byte for byte against `compare --write-tiles`, and their gzip total
python check_convert.py --sum out.mbtiles                  # raw and gzip -6 totals of a converted tileset
mlt convert in.mbtiles out.mbtiles --mlt-version 2 --vertex-pipeline oracle --gzip     # each tile compressed before it is stored: --gzip (6), --brotli (9), --zstd (19)
python check_convert.py --same a.mbtiles b.mbtiles        # two archives hold the same tiles once decompressed
mlt-oracle diff ../data/MVT/munich-plantiler.mbtiles out.mbtiles   # same tiles, layers and features (MVT or MLT, stock or pipeline, any compression): features matched in any order on id, properties and geometry up to ring start, MultiPoint order and line direction; exits 1 on a difference
```

`--gzip`, `--brotli` and `--zstd` (shorthands for `--tile-compression gzip|brotli|zstd`, mutually exclusive) compress every tile after it is
encoded and before it is merged into the output, for `.mbtiles` and `.pmtiles` output. The levels are the ones the thesis measures: gzip 6, Brotli
quality 9, zstd 19. An `.mbtiles` written this way carries a `compression` metadata row (`gzip`, `br`, `zstd`; Brotli has no magic bytes), which
`mlt ui` and `check_convert.py` read; without a flag the row is removed (the source's row described its MVT tiles). The `.mbtiles` file size is then
the compressed size, no longer the raw one. `check_convert.py` needs `pip install brotli zstandard` for those two.

`--vertex-pipeline` takes `off` (the stock encoder, the default), `default`, `oracle` (`--oracle-max-passes <n>`, default 10) or the
name of one of the 96 compositions of `bestGrid()` (a wrong name prints them all). It is a version 2 option, so it needs
`--mlt-version 2`, and it sits in the encode path every input of `convert` shares (`.mbtiles`, `.pmtiles`, a directory of tiles, an `.mlt`
input to re-encode, GeoJSON tiling); only `.mbtiles` input has been run and checked so far. The codec stores no triangles and orders the features by the composition's reorder, so it rejects `--tessellate` other
than `off`, `--sort`, `--rans-vertices` and `--fields` (rotated rings and reversed lines would misalign per-vertex m-values).
The layers of one tile are encoded together, which the oracle needs to search the gzip size of the whole tile; the summary
counts the layers the codec wrote and the layers that kept the stock vertex layout (z coordinates, geometry collections).
With `--verify` the pipeline's tiles are decoded and compared with the stock decode of the same layers (the shapes up to ring start and
line direction, see the result log); the stock check against the input does not apply to them.

`compare` prints the decomposition (stock, plain delta + varint, no-reorder oracle, full oracle) and, with verification on (default),
a geometry table: every feature of every oracle tile against the stock decode of the same layer, classified identical / reordered
(ring start, MultiPoint sort) / reversed (line direction) / different (a failure). A full corpus run takes about 14 minutes.

`cargo` must find the MSVC linker (VS 2022 Build Tools, C++ workload). The build output is
`research-rust/target/` (gitignored).

## The patch in `mlt-core-pipeline`

Rust `mlt-core` has no hook for a custom vertex encoder, so the vertex stream is written in a copy.
Everything else is the upstream source byte for byte. The changed places, all marked `PATCH (research-rust)`:

| file | change |
|---|---|
| `Cargo.toml` | new; package `mlt-core-pipeline`, `[lib] name = "mlt_core"`, depends on `mlt-pipeline` and `flate2` (zlib), no dev-dependencies/benches/tests |
| `src/lib.rs` | the crate doc no longer includes the README |
| `src/encoder/model.rs` | `EncoderConfig.vertex_pipeline: Option<mlt_pipeline::Composition>` with `with_vertex_pipeline`/`vertex_pipeline`; `allow_rans_vertices()` is always `false`; `EncoderConfig.gzip_selection` with `with_gzip_selection`/`gzip_selection` |
| `src/encoder/writer.rs` | with `gzip_selection` every stream-encoding competition (`try_alternatives`) keeps the candidate with the smallest gzip size (level 6) of the layer written so far (header, meta, data, the running bests of open competitions left out) instead of the smallest raw length; `AltLevel.best_score` |
| `src/encoder/geometry/encode02.rs` | `write_vertices` hands plain-xy vertex streams to the pipeline codec when the config selects a composition; `pipeline_input`/`pipeline_topology`/`pipeline_buffer` build its topology lists from the section's length streams |
| `src/encoder/geometry/streams.rs` | `encode_pipeline_vertex_stream02` |
| `src/decoder/geometry/decode.rs` | `decode_vertex_stream` reads the payload over the section's length streams (`pipeline_topology`) |
| `src/encoder/pipeline.rs` | new; `TileLayer::vertex_buffer()` (what the oracle reorders) and `TileLayer::permuted(order)` (features, ids and every property column together) |

### Deviation: the stream code of rANS vertices carries the pipeline payload

The pipeline payload is written under `VertexLogical::Rans` (logical code 6, no physical encoding)
instead of a new `VertexLogical` variant, which would touch the stream header, the layout, the dump
walker and the model tables. A file written by this copy therefore does **not** decode with stock
`mlt-core`, and stock rANS vertices are not available in this copy (`allow_rans_vertices()` is off, the
stock rANS decoder is dead code). A real integration would add a variant. Layers with z coordinates,
tessellated layers, vertex dictionaries and empty vertex buffers keep the stock vertex layouts.

### The reorder split

A composition's reorder is two things. The feature permutation (`Reorder::feature_order`) is applied to
the `TileLayer`, so the id and every property column follow, as the tile oracle searches it. The vertex
pass (ring rotation to the vertex nearest the previous one, MultiPoint sort along the Hilbert curve) is
inside the codec, because a decoder reads the vertices it gets back. A decoded layer therefore holds
the vertices the codec *emitted*, which is what `compare` verifies.

## The patch in `mlt-cli-pipeline`

The `mlt` CLI is copied from `maplibre-tile-spec/rust/mlt` (`src/` without the snapshots; no tests or dev-dependencies, as in the core copy),
with the library and binary named `mlt` and `mlt-core` replaced by `mlt-core-pipeline` (library name `mlt_core`). Because it links the patched
decoder, `ls`, `dump`, `decode`, `hexdump` and `ui` read pipeline tiles unchanged. The changed places, marked `PATCH (research-rust)`:

| file | change |
|---|---|
| `Cargo.toml` | new; package `mlt-cli-pipeline`, depends on `mlt-core-pipeline`, `mlt-pipeline` and `mlt-tile-oracle`; dependency versions as in the submodule's `rust/Cargo.toml`, in this workspace's `[workspace.dependencies]` |
| `src/convert/mod.rs` | `--vertex-pipeline <off\|default\|oracle\|NAME>` and `--oracle-max-passes` (version 2 options); `Reencoder.vertex` and `encode_layers`, which hand the layers of a tile to `mlt_tile_oracle::encode_tile`; `convert_mvt_buffer` and `convert_mlt_buffer` (unknown layers keep their place) use it when the pipeline is on; the pipeline forces tessellation and sorts off and rejects `--sort`, `--rans-vertices`, `--fields`, `--tessellate` other than `off` and `--to mvt`; `--verify` checks the decode against the stock decode of the same layers; the summary counts layers written by the codec and layers that fell back |
| `src/ui/mbt.rs` | the loader thread reads the archive's `format` metadata (`mlt`), and MLT tiles go through `Decoder::decode_all` + `FeatureCollection::from_layers` (as `ui/tile.rs` does for `.mlt` files) instead of the MVT reader |

The `.mbtiles` that `mlt convert` writes carry `format = mlt`, which is how `mlt ui` tells them from MVT archives. `mlt-oracle` uses
`rusqlite 0.39` (`libsqlite3-sys 0.37`) because the CLI's `sqlx 0.9` links the same native library and a workspace may hold only one version.

## Bit-exactness with Java

`golden-check` compares payload SHA-256 and length, the emitted topology and the decode against the
golden dump, per buffer and per grid composition. It holds because the port uses wrapping integer
arithmetic where Java does, `libm` (fdlibm) for trig, Java's `Math.round`, `i64` Hilbert keys and the
Java tie-breaks; gzip sizes use real zlib level 6 (`flate2` with the `zlib` feature), as `java.util.zip` does.
