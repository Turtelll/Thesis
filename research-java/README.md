# research-java

A lightweight harness for testing VertexBuffer encoding ideas against the baseline.
It extracts geometries from `data/MVT/munich-de.mbtiles` into MLT-compatible
`VertexBuffer`s (exactly as `maplibre-tile-spec`'s `GeometryEncoder` would, one buffer
per MVT layer per tile), then measures every registered encoding against the baseline
encodings from the repo root `README.md` — overall, and **per layer**.

The per-layer breakdown is the point. An encoding that is much better on
`transportation` and much worse on `building` can be net-negative on the corpus and
still worth shipping on `transportation` alone; a single corpus number cannot express
that.

## Prerequisites

- JDK 21+ (the Gradle toolchain downloads one automatically if not on `PATH`)
- The `maplibre-tile-spec` submodule must be populated:
  ```
  git submodule update --init --recursive
  ```

Use this project's own wrapper from `research-java/` (`./gradlew`, or `.\gradlew.bat`
on Windows). It depends on `mlt-core` through a Gradle composite build
(`includeBuild` in `settings.gradle`), so `mlt-core` builds from source on the fly —
no `publishToMavenLocal`, and the submodule is never modified.

## Build & test

```
./gradlew test
```

## Pipeline

### 1. Extract (once)

```
./gradlew run --args="--mbtiles ../data/MVT/munich-de.mbtiles --out ../data/extracted"
```

`munich-de.mbtiles` is the Munich area cut out of the full-Germany tileset
`data/MVT/germany.mbtiles` (275.735 tiles, far too large to extract whole). `ExtractCli`
has no bbox filter, so the cut happens first, with `analysis-python/cut_aoi.py`:

```
python cut_aoi.py --src ../data/MVT/germany.mbtiles --dst ../data/MVT/munich-de.mbtiles --bbox 11.359,48.061,11.723,48.248
```

That yields 358 tiles (~20 MB) -> 4.272 buffers, 5.244.123 vertices.

#### Overture corpus (second, independent dataset)

`munich-de.mbtiles` and `NY-maptiler.mbtiles` are both OpenMapTiles-schema tilesets
built by planetiler/OpenMapTiles. An encoding tuned on them may be exploiting quirks
of that one producer. `munich-overture.mbtiles` covers the **same Munich AOI** from
Overture Maps instead — different source data, different schema, different tiler
(tippecanoe) — as a check that a win generalizes.

It is **not** a size comparator against `munich-de.mbtiles`: the layer sets, attributes
and generalization differ, so a byte difference between the two measures dataset and
tiler, not encoding. Compare encodings *within* each corpus.

Two steps, because tippecanoe is not available natively on Windows:

```
python ../data-analysis/overture_fetch.py            # -> data/overture/*.geojsonseq
wsl -e bash ../data-analysis/overture_tile.sh        # -> data/MVT/munich-overture.mbtiles
```

`overture_fetch.py` pulls the seven themes overlapping the AOI straight from Overture's
S3 parquet with DuckDB (`pip install duckdb`); `--release` pins the Overture release
(default `2026-08-19.0`). Tiling needs `sudo apt install tippecanoe` inside WSL once.
Then extract as usual:

```
./gradlew run --args="--mbtiles ../data/MVT/munich-overture.mbtiles --out ../data/extracted-overture"
```

| Flag | Required | Description |
|---|---|---|
| `--mbtiles <path>` | yes | Source `.mbtiles` |
| `--out <dir>` | yes | Output directory (created if missing) |
| `--zoom <minZ-maxZ>` | no | Zoom filter, inclusive. `--zoom 14` means minZ=maxZ=14 |
| `--layer <name[,...]>` | no | Only these MVT layers |
| `--limit <N>` | no | Stop after N (tile, layer) extractions |

Per (tile, layer) with at least one geometry, writes `<z>_<x>_<y>_<layer>.bin` (the raw
VertexBuffer as interleaved little-endian `int32`, `[x0,y0,x1,y1,...]` — undelta'd and
unencoded, the neutral starting point for any experiment) plus a `.json` sidecar with
the topology streams (`tileExtent`, `geometryTypes`, `numGeometries`, `numParts`,
`numRings`, `vertexCount`). A `manifest.json` indexes them all.

### 2. Benchmark

```
./gradlew benchmark
```

Defaults to `../data/extractedBaseline`, the project's current baseline corpus. Pass a
directory explicitly to run on a different extraction: `./gradlew benchmark
--args="../data/extracted"`.

| Flag | Required | Description |
|---|---|---|
| `[extractionDir]` | no | Must contain `manifest.json`; defaults to `../data/extractedBaseline` |
| `--csv <file>` | no | Also dump per-buffer gzip sizes (one column per row) |
| `--layer <name>` | no | Restrict the run to one layer |
| `--verbose` | no | Full per-encoding breakdown tables instead of the compact winner-only table |
| `--by-zoom` | no | Add a breakdown by tile zoom (whole tiles, gzip) |
| `--brotli-quality <0-11>` | no | Brotli quality (default 11, what static assets are precompressed at; roughly ten times slower than 9) |

The benchmark measures **research encoding → general-purpose compressor (gzip, Brotli, zstd
19)**; an encoding owns its region layout, any geometry reordering and its byte serialization. Sizes are
what a client is served: every buffer of one tile concatenated (one tile is one HTTP response),
compressed on its own, summed over tiles. Background:
[docs/results/general-purpose-compression.md](docs/results/general-purpose-compression.md).

A run prints, in order:

1. **Per encoding, per tile.** One row per candidate: raw, `order-0` (byte-entropy bound per tile before any LZ77 match), gzip, Brotli,
   zstd, against `ref` (see 4).
2. **Oracle composition**: which rows the research oracle picks, on how many buffers.
3. **Breakdowns, gzip.** By layer and by dominant geometry type each (tile, layer) buffer is
   gzipped on its own — the tile restricted to that layer — so these rows do not sum to the
   per-tile tables. `--by-zoom` sums whole gzipped tiles per zoom.
4. **Baselines vs oracle, per tile.** `DELTA_ZIGZAG_FASTPFOR` and `DELTA_ZIGZAG_VARINT` under
   gzip; the *research oracle* (per buffer, the smallest of the Varint baseline and every
   `Baselines.research()` row) under gzip, Brotli and zstd. `ref` is the smaller
   baseline under gzip, and **every percentage in every column is against it**. The oracle selects
   on uncompressed bytes and charges nothing for signalling the choice.

Reordering is not a benchmark flag: an encoding that wants it calls `GeometryReorder` itself
and declares `reordersGeometries()`. It is not always available (some cases need the original
feature order), so say so when a row depends on it.

Retired from the benchmark, still registered in `Baselines.retired()` so `./gradlew test`
round-trips them: rANS, the serializers `RAW` / `VARINT_XY` / `VARINT_SPLIT` / `BYTESPLIT` /
`UINT16*` and everything under `encoding/research/failed/`.

The residual-bit tables (mean zigzag bits per transform, `zero pairs`, per-vertex change vs
`DELTA_ZIGZAG`) moved to their own probe:

```
./gradlew residualBits --args="--layer building"      # also: --by-zoom
```

An encoding declaring `reordersGeometries()` is additionally checked for geometry preservation
on every buffer: reordering may change the order and nothing else, so the multiset of parts must
be unchanged (parts compared up to reversal). The run fails naming the tile and layer otherwise.
This is `GeometryEquivalence`.

Every run **round-trips every encoding on every buffer** — encode, decode, compare
against the extraction — and fails naming the encoding, tile and layer if any does not
reconstruct the buffer exactly. This is not optional and has no flag: an encoding that
discards data reports a small size and would look like the best result in the table
*because* it is broken, so a size from an unverified encoding is not a result.

### 2b. Pipeline benchmark (`pipeline/`)

```
./gradlew pipeline --args="--step s8 --by-zoom --with-old"
```

The OpenZL-style pipeline ([results](docs/results/openzl-pipeline.md)) has its own benchmark with the same
sizing as the one above (tile buffers concatenated, compressed per tile, every change against the smaller
baseline under gzip). Steps S1 to S8 build on the previous step's winner; one step per run.

| Flag | Required | Description |
|---|---|---|
| `[extractionDir]` | no | Must contain `manifest.json`; defaults to `../data/extractedBaseline` |
| `--step <name>` | no | `s0` (default), `s1` to `s4`, `s6` to `s8` (`s5`, the GCD, was removed), `selector`, `ladder` (the pipeline built up decision by decision), `oracle` (the grid the oracle chooses among), `layer` (the per-layer default `PipelineRegistry.forLayer` beside the single `DEFAULT` row), or `all` |
| `--rows a,b` | no | Run only these row names (from any step) |
| `--oracle` | no | Replace the per-row tables by the per-buffer best of the step's rows: what was chosen and how often, then the result against `ref`. Implied by `--step oracle` (48 compositions); the oracle chooses per backend, charges nothing for the choice and so is an upper bound |
| `--layer <name>` | no | Restrict the run to one layer |
| `--by-zoom` | no | Add a breakdown by tile zoom (whole tiles, gzip) |
| `--stats` | no | The step's own probes (for example predictor shares, width histograms) |
| `--with-old` | no | Add the old pipeline's best rows (`QUAD_CORNER`, `QUAD_CORNER_SHARED_RING_CANONICAL_REORDER`, `LINE_CONTINUATION_SHARED_DICT`) as read-only references |
| `--isolated` | no | Also size every buffer compressed on its own, beside the per-tile figure |
| `--csv <file>` | no | Dump per-buffer gzip sizes (one column per row) |
| `--brotli-quality <0-11>` | no | Brotli quality (default 11; 9 is much faster for development runs) |
| `--limit-tiles <n>` | no | Only the first `n` tiles, for quick runs |
| `--no-verify` | no | Skip the round-trip check; always skipped for `ladder`, `layer` and the oracle (see below) |

Unlike `benchmark`, the round trip of the pipeline's rows is checked in **`./gradlew test`**
(`PipelineEncodingTest` over `PipelineRegistry.all()`, which includes the ladder stages and the oracle
grid), so the `ladder` and oracle runs only size. Every run says on its first line whether it
round-tripped.

Whole-tile effect of the reorder on the property columns and ids (the other side of S1):

```
./gradlew pipelineTileSize --args="../data/MVT/munich-plantiler.mbtiles --by-layer"
```

| Flag | Description |
|---|---|
| `<tileset.mbtiles>` | Tileset to encode with mlt-core; defaults to `../data/MVT/munich-plantiler.mbtiles` |
| `--modes A,B` | Reorder modes (`Reorder` names) to compare against no reorder |
| `--by-layer` | Per layer, single-layer property-only tiles |
| `--runs` | Why property columns grow: neighbour-equal runs and id ordering, before and after (first mode only) |
| `--ids keep\|sequential\|drop` | What happens to the feature ids (default `keep`); applied to every mode so each run's `NONE` row is the matching baseline. Changes the data: only for tilesets whose ids carry no meaning |
| `--brotli-quality <0-11>` | Brotli quality (default 9) |
| `--limit-tiles <n>` | Only the first `n` tiles |

`./gradlew pipeline --args="--help"` shows the usage line of the first table (as the error message of the run).

### 3. Chart the results

```
./gradlew benchmark --args="--csv ../data/out/run.csv"
./gradlew chart --args="../data/out/run.csv"
```

`chart` reads the benchmark's `--csv` file and writes three SVG charts to
`<csv name>-charts/` beside it (no extra dependencies; open them in a browser):

| File | Shows |
|---|---|
| `totals.svg` | Summed gzip bytes per encoding, as % change against the smaller baseline |
| `by-layer.svg` | Encoding x layer heatmap (all + the 8 largest layers), each column against that layer's smaller baseline |
| `by-zoom.svg` | Change per tile zoom for the best encodings and the other baseline |

| Flag | Description |
|---|---|
| `--out <dir>` | Output directory (default `<csv name>-charts` beside the CSV) |
| `--layer <name>` | Chart only one layer (`by-layer.svg` is then skipped) |
| `--top <n>` | Non-baseline encodings drawn in `by-zoom.svg` (default 5) |

The CSV holds gzip bytes **per (tile, layer) buffer**, so the numbers match the benchmark's
"by layer" / "by zoom" breakdowns, not its whole-tile tables.

## Trying a predictor fast

`./gradlew workshop` measures prediction ideas only: every row interleaved (no segmentation,
reordering or shared references) over plain Varint, against `DELTA_ZIGZAG_VARINT` and
`DELTA_ZIGZAG_FASTPFOR`. Per row it prints the mean residual size of the x and y components
(zigzag bit width and absolute value), the share of `(0, 0)` pairs, Varint bytes, and gzip bytes
with every tile compressed on its own. Every row is round-tripped on every buffer.

1. **Write the rule.** In `workshop/Workshop.experiments()`, add one `Predictor`. A rule writes the
   predicted `(x, y)` of vertex `i` into `out`, from `xs`/`ys` below `i` and the per-vertex
   `Topology` (`runStart`, `runLength`, `indexInRun`, `isRunStart`, `isLine`, `isRing`):

   ```java
   Predictor.of("LINE_CONT_ALL", (t, i, xs, ys, out) -> {
       if (t.isLine(i) && t.indexInRun(i) >= 2) Predictor.continuation(i, xs, ys, out);
       else Predictor.prev(i, xs, ys, out);
   })
   ```

   Reading `xs[i]` or later does not round-trip, so a look-ahead fails loudly instead of winning.
2. **Run it**: `./gradlew workshop --args="--only LINE_CONT_ALL,DELTA,LINE_CONTINUATION"`.

`Workshop.known()` holds the predictors measured so far, in the same form: `DELTA` (the baseline
as a rule; its Varint bytes equal `DELTA_ZIGZAG_VARINT`'s), `QUAD_CORNER`, `LINE_CONTINUATION`,
`QUAD_CORNER_LINE`, `ROT90_FIXED`, `QUAD_CORNER_ROT90`, `DELTA2_ZIGZAG` and both
`MIDPOINT_REFLECT` pairings.

| Flag | Description |
|---|---|
| `[extractionDir]` | Default `../data/extractedBaseline` |
| `--layer <name>` / `--zoom <z>` | Only that layer / tile zoom |
| `--only A,B` | Only these workshop rows (baselines always shown) |
| `--by-layer` | The same table for the 6 layers with the most vertices, each tile restricted to that layer |
| `--by-zoom` | The same table per tile zoom |
| `--max-tiles <n>` | Stop after `n` tiles, for a quick look |

A workshop win is a lead, not a result: promote the idea to an `Encoding` (below) and measure it
with `./gradlew benchmark`, where segmentation and the split-varint layout apply.

## Adding an encoding

Everything the harness measures is one kind of thing:
`org.maplibre.mlt.research.encoding.Encoding` — `name()`, `encode(BufferContext)`,
`decode(EncodedBuffer, BufferContext, vertexCount)`. `decode` is mandatory, for the
reason above. Both halves get the `BufferContext`, so a transform that resets on
part/ring boundaries derives the same runs when encoding and decoding. `decode` must
not read `ctx.vertices()` — that is the buffer it is reconstructing, and reading it
would make even a codec that discards the buffer round-trip successfully.

1. **Write it.** One class per idea in `encoding/research/`, implementing `Encoding`
   directly and owning its whole byte layout — which streams it writes, in which order, with
   which serialization, and whether it reorders geometries first (`GeometryReorder`). No
   `Layout`/`IntCodec` constructor arguments: the layout is tuned inside the class. Building
   blocks: a `ClassifiedPredictor` plus `ClassRegions` (its residuals as one stream per vertex
   class), the `IntCodec` instances (`IntCodec.VARINT_SPLIT.encode(int[])`, ...), and
   `SharedVertexRef.BitWriter`/`BitReader`. `QuadCornerEncoding` is the smallest example.

   `BufferContext` carries the topology streams (`numParts`, `numRings`,
   `geometryTypes`) alongside the vertices, for encodings with structural reset points
   (the predictors in `ideen.md`) to condition on.

2. **Register it** — one line in `Baselines.research()`, next to the baseline list. That
   yields one benchmark row, named by the encoding's `name()`.

3. **Run the benchmark.** Your encoding appears as a new row in the per-tile tables, and is
   round-tripped over the whole corpus automatically. `VertexBufferCodecTest`
   parameterizes over `Baselines.all()`, so `./gradlew test` covers it too without a
   new test method.

With the research list empty the run reports the two baselines only — the null result that shows the
harness works before any new encoding is judged by it. See
[`docs/results/`](docs/results/README.md) for what the currently registered encodings do
and for the ones that were measured and rejected.

## Excluding an encoding

**No compressed win per tile and a negligible share of the oracle composition means demote
now** — do not leave a dead encoding registered. There is no tooling for this; it is four manual steps:

1. Delete its line from `Baselines.research()`. This alone drops it from the benchmark and from
   `VertexBufferCodecTest`, which parameterizes over `Baselines.all()`; add it to
   `Baselines.retired()` if it should stay round-tripped.
2. Move the class to `encoding/research/failed/` and fix its `package` line. `Baselines`
   imports `...encoding.research.*`, so the wildcard no longer reaches it there.
3. Move its `*MechanismTest.java` to the matching `test/.../research/failed/` package. The
   mechanism test survives the demotion — it pins how the idea works, independent of
   whether it won, so the mechanism need not be re-derived if the idea resurfaces.
4. Add a `docs/results/failed/<name>.md` with `Status:` / `Effect:` / `Corpus:`, following
   the existing files under `docs/results/failed/`.

## Docs

- [`docs/architecture.md`](docs/architecture.md) — pipeline, package layout, the
  `Encoding` contract, the two baselines, and the project's file tree.
- [`docs/evaluation-protocol.md`](docs/evaluation-protocol.md) — the methodology for
  testing a new predictor. Read this before adding an encoding.
- [`docs/mlt-fidelity.md`](docs/mlt-fidelity.md) — why the extractor is a verbatim copy
  of MLT's encoder.
- [`docs/results/`](docs/results/README.md) — every encoding tried, what won, what
  regressed, and why, with measured numbers.

