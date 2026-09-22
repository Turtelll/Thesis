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
| `--csv <file>` | no | Also dump per-buffer sizes (one column per encoding) |
| `--layer <name>` | no | Restrict the run to one layer |
| `--verbose` | no | Full per-encoding tables instead of the compact winner-only table |
| `--segmented` | no | Also measure the segmentation add-on (`Baselines.segmented()`) |
| `--reorder <feature\|feature-rev>` | no | Also measure every selected row with geometry reordering applied |

rANS-based encodings are registered in `Baselines` (so `./gradlew test` still round-trips
them) but are filtered out of every benchmark run — rANS is retired as a research
direction (not spec-compatible, does not combine with gzip). The header names the
omission (`set: core -rans`).

Every run also prints a corpus-wide gzip table unconditionally: each selected encoding's
payload bytes, concatenated across the corpus and gzipped once, raw vs gzipped vs ratio.
Gzip is the browser-native compressor applied on top of whatever this harness ships, so
it is a standing column rather than an opt-in check.

### Add-ons are separate runs

A default run measures the baselines and `Baselines.core()` — the encoding ideas themselves,
interleaved and in the buffer's original order. **Segmentation and reordering are add-ons, and
they are opt-in for a reason:** both lift almost any encoding, so a number that has either baked
in does not say whether the encoding idea is good.

- **Segmentation** (`--segmented`) groups residuals into per-class regions. It only pays under a
  codec that prices a block's maximum (FastPFOR); under Varint it is byte-identical to the
  unsegmented row, and under rANS it is worth about one byte per buffer.
- **Reordering** (`--reorder`) is a pre-pass on the buffer, not an encoding. It is not always
  available: some cases need the original feature order preserved, and property encoding may want
  to sort the feature table by something else.

Each flag *adds* rows rather than replacing them, so the add-on's row and its plain original sit
in the same table and the difference between them is the add-on's contribution. The header line
names the set (`set: core +segmented +reorder feature-rev`) so a pasted table cannot be mistaken
for a default run. Never cite an add-on row as evidence that the core encoding is better — compare
core against core first, then report the add-on separately.

Prints corpus totals, then a breakdown by layer and by dominant geometry type. Each
encoding is shown as a percentage against the **best baseline for that group**, so a
new encoding's effect is readable per segment:

```
encoding                       bytes       vs raw      vs base
DELTA_ZIGZAG_FASTPFOR     13.399.028        30,7%         base
DELTA_ZIGZAG_VARINT       13.818.767        31,7%        +3,1%

--- by layer ---
layer                buffers  DELTA_ZIGZAG_FASTPFOR    DELTA_ZIGZAG_VARINT
water                   1478       916.048     base       951.783    +3,9%
water_name               762        17.065   +26,4%        13.506     base
transportation           634     4.232.495     base     4.288.158    +1,3%
housenumber              244       735.180     base       849.969   +15,6%
```

Negative means smaller, which is the goal.

After the layer and geometry breakdowns, a `--- residual bits ---` table reports mean
zigzag residual bit width per **transform** (codec-independent — `QUAD_CORNER_VARINT`,
`QUAD_CORNER_RANS` and `QUAD_CORNER_FASTPFOR` share one transform and collapse onto one
`QUAD_CORNER` row), against the plain `DELTA_ZIGZAG` transform. This is the current
research target: lower average residuals. Read it as a headroom signal, not a result — a
transform can narrow the mean while widening the tail, which a block-max codec
(FastPFOR) prices as no win at all until segmentation exposes it (see
`docs/evaluation-protocol.md`). Whether a residual win reaches the tile is answered by
the byte tables, not this one.

After that, an `--- encoding potential (FeatureTable wins)
---` table lists every registered encoding with how many buffers (FeatureTable instances)
it is the smallest encoding on, corpus-wide, and the bytes saved against each won buffer's
best baseline. Every encoding gets a row, including `0`-win ones — that is the signal to
demote it, see "Excluding an encoding" below. A high win count with negligible bytes saved
is still not a result: read both columns together (an encoding can win hundreds of buffers
by a byte each).

With `--reorder`, every buffer is additionally checked for geometry preservation before it is
encoded: reordering may change the order and nothing else, so the multiset of parts must be
unchanged (parts compared up to reversal). The run fails naming the tile and layer otherwise.
This is `GeometryEquivalence`, and it applies to any encoding declaring `reordersGeometries()`.

Every run **round-trips every encoding on every buffer** — encode, decode, compare
against the extraction — and fails naming the encoding, tile and layer if any does not
reconstruct the buffer exactly. This is not optional and has no flag: an encoding that
discards data reports a small size and would look like the best result in the table
*because* it is broken, so a size from an unverified encoding is not a result.

## Adding an encoding

Everything the harness measures is one kind of thing:
`org.maplibre.mlt.research.encoding.Encoding` — `name()`, `encode(BufferContext)`,
`decode(EncodedBuffer, BufferContext, vertexCount)`. `decode` is mandatory, for the
reason above. Both halves get the `BufferContext`, so a transform that resets on
part/ring boundaries derives the same runs when encoding and decoding. `decode` must
not read `ctx.vertices()` — that is the buffer it is reconstructing, and reading it
would make even a codec that discards the buffer round-trip successfully.

1. **Write it.** Most ideas decompose into a logical transform (`List<Vertex> <-> int[]`)
   plus a physical codec (`int[] <-> byte[]`): implement a `VertexTransform` and/or an
   `IntCodec` and pair them in a `VertexBufferCodec`, reusing
   `VertexTransform.ZigZagDelta` / `IntCodec.Varint` / `IntCodec.FastPfor` for whichever
   half you are not changing. Anything that does not fit that split implements
   `Encoding` directly.

   `BufferContext` carries the topology streams (`numParts`, `numRings`,
   `geometryTypes`) alongside the vertices, for encodings with structural reset points
   (the predictors in `ideen.md`) to condition on.

2. **Register it** — one line in `Baselines.core()`, interleaved and unreordered. If it is a
   `ClassifiedPredictor` whose win may depend on region packing, add a second line in
   `Baselines.segmented()` for its `_SEG` variant. Never register a reordered row: `--reorder`
   produces those from whatever is already selected.

3. **Run the benchmark.** Your encoding appears as a new column in every table, and is
   round-tripped over the whole corpus automatically. `VertexBufferCodecTest`
   parameterizes over `Baselines.all()`, so `./gradlew test` covers it too without a
   new test method.

With `core()` and `segmented()` empty the run reports the two baselines only — the null result
that shows the harness works before any new encoding is judged by it. See
[`docs/results/`](docs/results/README.md) for what the currently registered encodings do
and for the ones that were measured and rejected.

## Excluding an encoding

**Zero FeatureTable wins, or wins with negligible bytes saved, means demote now** — do not
leave a dead encoding registered. There is no tooling for this; it is four manual steps:

1. Delete its line(s) from `Baselines.core()` and `Baselines.segmented()`. This alone drops it
   from the benchmark and from `VertexBufferCodecTest`, which parameterizes over `Baselines.all()`.
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

