# research-java

A lightweight harness for testing VertexBuffer encoding ideas against the baseline. Everything runs
through one entry point, `./gradlew research --args="<command> ..."` (`./gradlew research` lists the
commands); the encoder is the modular `pipeline/`, the only encoder stack.
It extracts geometries from `data/MVT/munich-de.mbtiles` into MLT-compatible
`VertexBuffer`s (exactly as `maplibre-tile-spec`'s `GeometryEncoder` would, one buffer
per MVT layer per tile), then measures the pipeline's rows against the baseline
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
./gradlew research --args="extract --mbtiles ../data/MVT/munich-de.mbtiles --out ../data/extracted"
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
./gradlew research --args="extract --mbtiles ../data/MVT/munich-overture.mbtiles --out ../data/extracted-overture"
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

### 2. Measure

```
./gradlew research --args="measure"
```

`measure` is the one measurement harness. It encodes the two baselines and the pipeline's rows
over an extraction, **round-trips every row on every buffer** (encode, decode from the topology
streams alone, compare against the extraction; the run fails naming the row, tile and layer if one
does not reconstruct the buffer — no flag turns this off, except `--no-verify` for the grids that
`./gradlew test` already covers), and sizes them the way a client is served: every buffer of one
tile concatenated (one tile is one HTTP response), compressed on its own under gzip, Brotli 11 and
zstd 19, summed over tiles. Background:
[docs/results/general-purpose-compression.md](docs/results/general-purpose-compression.md).

Defaults to `../data/extractedBaseline`, the project's current baseline corpus. Pass a directory
explicitly to run on a different extraction (`research measure ../data/extracted`).

`ref` is the smaller baseline under gzip (`DELTA_ZIGZAG_VARINT`), and **every percentage in every
column is against it**. Rows that reorder geometries are additionally checked for geometry
preservation (`GeometryEquivalence`): reordering may change the order and nothing else.

The pipeline is composed of stages, one step per run, each building on the previous step's winner
(S1 reorder, S2 predict, S3 segment, S4 zigzag, S6 byte width, S7 transpose, S8 the general-purpose
backend; S5 was measured and removed). Results: [docs/results/openzl-pipeline.md](docs/results/openzl-pipeline.md);
the bytes a row writes: [docs/bitstream.md](docs/bitstream.md).

| Flag | Required | Description |
|---|---|---|
| `[extractionDir]` | no | Must contain `manifest.json`; defaults to `../data/extractedBaseline` |
| `--step <name>` | no | `s0` (default), `s1` to `s4`, `s6` to `s8`, `selector`, `ladder` (the pipeline built up decision by decision), `oracle` (the grid the oracle chooses among), `layer` (the per-layer default `PipelineRegistry.forLayer` beside the single `DEFAULT` row), `tile` (one composition per tile and layer), or `all` |
| `--rows a,b` | no | Run only these row names (from any step) |
| `--oracle` | no | Replace the per-row tables by the per-buffer best of the step's rows: what was chosen and how often, then the result against `ref`. Implied by `--step oracle` (48 compositions); the oracle chooses per backend, charges nothing for the choice and so is an upper bound |
| `--layer <name>` | no | Restrict the run to one layer |
| `--by-zoom` | no | Add a breakdown by tile zoom (whole tiles, gzip) |
| `--by-geometry` | no | Add a breakdown by the dominant geometry type of each (tile, layer) buffer |
| `--stats` | no | The step's own probes (for example predictor shares, width histograms) |
| `--isolated` | no | Also size every buffer compressed on its own, beside the per-tile figure |
| `--csv <file>` | no | Dump per-buffer gzip sizes (one column per row) |
| `--brotli-quality <0-11>` | no | Brotli quality (default 11; 9 is much faster for development runs) |
| `--limit-tiles <n>` | no | Only the first `n` tiles, for quick runs |
| `--no-verify` | no | Skip the round-trip check; always skipped for `ladder`, `layer` and the oracle (`PipelineEncodingTest` round-trips `PipelineRegistry.all()`, including the ladder stages and the oracle grid) |
| `--trials`, `--features <file>` | no | The opt-in selector report: compression-free selectors (entropy estimates) and how well cheap buffer features predict each stage's choice |

The old `benchmark` rows (the per-idea encodings of `Baselines.research()`, FastPFOR/rANS rows and
the `--with-old` references) no longer exist; their results are history in `docs/results/`, their
code is at git tag `pre-consolidation`.

The residual-bit tables (mean zigzag bits per transform, `zero pairs`, per-vertex change vs
`DELTA_ZIGZAG`) are their own probe:

```
./gradlew research --args="residual-bits --layer building"      # also: --by-zoom
```

Whole-tile effect of the reorder on the property columns and ids (the other side of S1):

```
./gradlew research --args="tile-size ../data/MVT/munich-plantiler.mbtiles --by-layer"
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

`./gradlew research` lists every command; `./gradlew research --args="measure --help"` shows the usage line.

### 3. Prove nothing moved (the golden dump)

```
./gradlew research --args="dump --out ../data/golden/<name>"
./gradlew research --args="dump --diff ../data/golden/<old> ../data/golden/<new>"
```

`dump` writes, for every row of the baselines and `PipelineRegistry.all()` and every buffer, the SHA-256 and
size of the encoded bytes, plus per-tile compressed sizes (`buffers.tsv.gz`, `tiles.tsv.gz`,
`manifest.tsv`). `--diff` compares two dumps and names every row and buffer that changed, so a
refactor can be shown byte-identical. Options: `--limit-tiles <n>`, `--layer <name>`, `--serial`.
`research-java/golden/pre-consolidation.manifest.tsv` is the committed manifest of the dump taken at
tag `pre-consolidation` (Munich only).

## Trying a predictor fast

`./gradlew research --args="workshop"` measures prediction ideas only: every row interleaved (no segmentation,
reordering or shared references) over plain Varint, against `DELTA_ZIGZAG_VARINT` and
`DELTA_ZIGZAG_FASTPFOR`. Per row it prints the mean and median absolute residual of the x and y
components (in tile units), the share of `(0, 0)` pairs, Varint bytes, and gzip bytes
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
2. **Run it**: `./gradlew research --args="workshop --only LINE_CONT_ALL,DELTA,LINE_CONTINUATION"`.

`Workshop.known()` holds the predictors measured so far: `DELTA` (the baseline as a rule; its Varint
bytes equal `DELTA_ZIGZAG_VARINT`'s) and the pipeline's own predictors (`QUAD_CORNER`,
`LINE_CONTINUATION`, `QUAD_CORNER_LINE`, `QUAD_CORNER_FRAME`, `QUAD_CORNER_LINE_FRAME`) through
its `FLAT` split (`PredictionTransform`), so a row here writes the same residual pairs as the
`FLAT` row of `measure`. The whole-encoding rows that used to sit here (`TURN_SCALAR`,
`QUAD_CORNER_SHARED`) and the retired predictors (`ROT90_FIXED`, `QUAD_CORNER_ROT90`, `DELTA2_ZIGZAG`,
`MIDPOINT_REFLECT`) are history.

| Flag | Description |
|---|---|
| `[extractionDir]` | Default `../data/extractedBaseline` |
| `--layer <name>` / `--zoom <z>` | Only that layer / tile zoom |
| `--only A,B` | Only these workshop rows (baselines always shown) |
| `--by-layer` | The same table for the 6 layers with the most vertices, each tile restricted to that layer |
| `--by-zoom` | The same table per tile zoom |
| `--max-tiles <n>` | Stop after `n` tiles, for a quick look |
| `--reorder [VARIANT]` | Reorder every buffer before the prediction rows predict (rows are named `<row>_<VARIANT>`); `VARIANT` is a pipeline `Reorder` name, default `HILBERT_CENTROID_ROT_MP`. Baselines stay unordered |

The pipeline's reorderings also run on their own, as `REORDER_<VARIANT>` rows (`Workshop.reorderRows()`):
the plain `DELTA` predictor over the reordered buffer, never combined with another predictor, so a row
isolates what the reordering alone buys. Set them against the prediction rows, or add `--reorder` to
measure the two together. `--only` names the predictors as listed (before `--reorder` renames them) and
the `REORDER_` rows by their own names.

The `_PART_CHAIN` rows (`Workshop.partChainRows()`) keep feature order but reorder the parts inside every
MultiLineString (`GeometryReorder.PART_CHAIN`; `_PART_CHAIN_REV` may also reverse a part): `DELTA`,
`LINE_CONTINUATION` and `CHAIN_CONTINUATION` (continuation carried across a part that starts where
the previous one ended) over the chained buffer, e.g. `CHAIN_CONTINUATION_PART_CHAIN_REV`. They always
run and are not combined with `--reorder`. Results: [docs/results/line-part-chain.md](docs/results/line-part-chain.md).

A workshop win is a lead, not a result: promote the idea to a pipeline predictor (below) and measure it
with `./gradlew research --args="measure"`, where segmentation, the byte width and the shared references apply.

## Adding an idea to the pipeline

Everything `measure` sizes is one kind of thing: `org.maplibre.mlt.research.encoding.Encoding` —
`name()`, `encode(BufferContext)`, `decode(EncodedBuffer, BufferContext, vertexCount)`; the pipeline's
row is `PipelineEncoding(PipelineConfig)`. `decode` is mandatory and must not read `ctx.vertices()` —
that is the buffer it is reconstructing, and reading it would make even a codec that discards the
buffer round-trip successfully.

1. **Write the mechanism.** A predictor is a `ClassifiedPredictor` (per-vertex class derived from the
   topology streams, and what each class predicts), put in `pipeline/mechanism/` beside `SegmentQuadCorner`
   and `LineContinuation`. A reordering is a `Reorder` entry. `BufferContext` carries the topology
   streams (`numParts`, `numRings`, `geometryTypes`) alongside the vertices, so both sides derive
   the same runs.
2. **Register it** — one `Prediction` constant, appended at the end (the ordinals are the header's
   predictor id, see [docs/bitstream.md](docs/bitstream.md); the header has room for 16 predictors and
   3 free bits). `PipelineRegistry` lists which compositions are rows.
3. **Round-trip it.** `./gradlew test` round-trips every row of `PipelineRegistry.all()` and every
   `Prediction` from the topology alone; a mechanism test pins how the idea works on a small fixture.
4. **Measure it.** `./gradlew research --args="measure --rows <row> --by-zoom"`, then
   `research dump --diff` against a dump taken before, to show no other row moved.

A predictor that earns nothing is demoted rather than left registered: remove its `PipelineRegistry`
rows, write `docs/results/failed/<name>.md` with `Status:` / `Effect:` / `Corpus:` (following the
existing files there), and keep its mechanism test, which pins how the idea works independent of
whether it won.
## Docs

- [`docs/architecture.md`](docs/architecture.md) — pipeline, package layout, the
  `Encoding` contract, the two baselines, and the project's file tree.
- [`docs/evaluation-protocol.md`](docs/evaluation-protocol.md) — the methodology for
  testing a new predictor. Read this before adding an encoding.
- [`docs/mlt-fidelity.md`](docs/mlt-fidelity.md) — why the extractor is a verbatim copy
  of MLT's encoder.
- [`docs/results/`](docs/results/README.md) — every encoding tried, what won, what
  regressed, and why, with measured numbers.

