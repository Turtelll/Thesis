# MLT vertex-buffer encoding

Documentation of the Bachelor thesis on new encodings that reduce MapLibre Tile (MLT) size for
`VertexBuffer`s.

- [Pipeline](pipeline/README.md): how a vertex buffer is encoded, step by step, with one page per step.
- Encoding: one page per idea tried, sorted into *good* (kept), *bad* (measured and rejected) and
  *out-of-scope*.

## How to read the encoding pages

Every encoding page has the same structure: **Verdict** (one line), **Encoding target** (layers and
geometry types), **Idea** (what it does), **Intention** (which property of the data it exploits and why
that should make tiles smaller), how the encoder and decoder work, and **Results**.

**Headline metric.** Every Results section opens with the same number: the change in **gzip bytes per
tile** against the baseline **`DELTA_ZIGZAG_VARINT`**, on the corpus **`munich-plantiler`**.

- *Baseline* `DELTA_ZIGZAG_VARINT`: each vertex stored as the difference to the previous vertex
  (delta), mapped to unsigned (zigzag), written as a Varint: the plain MLT vertex-buffer encoding. Of the
  two baselines (Varint and FastPFOR) it is the smaller one once gzip runs, so it is the one to beat.
- *Per tile*: all vertex buffers of one tile are concatenated and compressed on their own (one HTTP
  response), then summed over all tiles. gzip level 6. gzip is the reference because every browser
  decodes it natively.
- *Corpus* `munich-plantiler`: Munich, generated with Planetiler 0.10.2, 358 tiles, 4.368 vertex buffers,
  baseline 11.237.124 B gzip.
- *Negative is better*: -5,00% means the tiles are 5% smaller than the baseline. "Tileset" is the whole
  corpus; a layer name (e.g. `building`) means only that layer's buffers.

Brotli and zstd figures, residual bit widths and older corpora appear only as supporting figures,
always labelled. If an idea was never measured under the headline metric (for example because it was
dropped while FastPFOR bit-packing was still the target), the page says so and states the evidence
that decided it instead.

**Decisive case.** Each Results section states the one fact that settles the verdict: why a kept
encoding earns its place (*good*; some were kept for a while and later superseded, the page says by
what), why a rejected one does not pay (*bad*), or why an idea falls outside what this thesis can
ship (*out-of-scope*: lossy, tied to a codec the target dropped such as FastPFOR or rANS, or not
carried into the consolidated pipeline).

Terms used on the pages (run, residual, zigzag, class stream, byte planes) are explained step by step
in the [pipeline](pipeline/README.md).

The code and the full measurement logs are in the [repository](https://github.com/Turtelll/Thesis)
(`research-java/`, `research-java/docs/results/`).
