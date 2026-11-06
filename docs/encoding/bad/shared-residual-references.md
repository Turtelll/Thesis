# Shared residual references

**Code names:** `QUAD_CORNER_FRAME_RESIDUAL_WINDOW`, `_ALIGNED`, `_RUN`; the same for `QUAD_CORNER_SHARED_FRAME_`,
`LINE_CONTINUATION_FRAME_HEADING_` and `LINE_CONTINUATION_SHARED_FRAME_HEADING_` (12 predictors, codes 14–25)

> **Verdict: Rejected.** In the tile oracle on `building` the references save 0,39% gzip against the oracle without
> them, nothing under Brotli, and the probe shows nothing on the other layers. Code removed.

## Encoding target
Every layer; probed on building and transportation, oracle on building only.

## Idea
Share the residuals instead of the vertices. After prediction (`QUAD_CORNER`, `LINE_CONTINUATION`) and the
frame, every vertex that carries a pair writes `(x, y)`. When that pair already occurred, write a reference to
it. This also catches vertices at different positions that have the same shape, which position references miss.

## Intention
Houses of the same type repeat their corner residuals exactly, and so do road stubs; a reference costs a flag and
a few index bits against two varints. At z14 `building` was expected to be the case where it pays.

## How the encoding works
`ResidualReferences` (code removed), over every residual stream (all classes, all geometry types):

1. **`WINDOW`:** one flag per vertex; a hit is a pair among the last 16 distinct pairs of the same stream
   (move-to-front), written as a 4-bit index.
2. **`ALIGNED`:** per run (ring or part) one flag that the run repeats one of the last 4 runs of the same kind
   and length; then one "copy the counterpart" flag per vertex.
3. **`RUN`:** per run one flag that the whole run repeats an earlier one (start vertex excluded, at least 3 hits).
4. **Per-stream enable mask**, chosen greedily by trial encodes on raw payload size: a stream whose pairs do not
   repeat writes no flags.
5. **Fallback chain per buffer:** the residual candidate, its position-shared base, the plain base; the smallest
   raw payload wins and the header names it (no mode byte).

The `replace` variants take the framed base; the `stack` variants take the position-shared base and share what
the position references left.

## How the decoder works
Reads the position block (stacked variants), then the mask, the flag and index bit streams and one value count
per stream. For every vertex it skips position-referenced ones, otherwise either takes the referenced pair (and
adds it to the move-to-front list or run history) or reads the next pair from its stream; then it adds the
prediction and inverts the frame as before.

## Results
**Probe** (`building`, 310 buffers, `QUAD_CORNER_SHARED_FRAME` base, all zooms, every stream on, raw payload
bytes before any compressor):

| mode | pairs | hit share | payload vs base |
|---|---|---|---|
| `WINDOW` | 2.040.481 | 17,53% | -7,91% |
| `ALIGNED` | 2.040.481 | 1,33% | -0,39% |
| `RUN` | 2.040.481 | 0,24% | -0,02% |

`WINDOW` by zoom: z13 6,60% hits, -0,09% payload; z14 20,64% hits, -9,98%. On `transportation` it hits 1,27%
(polygon base) and 1,42% (line base) of the pairs for -0,02% / -0,11% payload; `ALIGNED` and `RUN` stay below 0,1%.

**Tile oracle** (`building`, gzip per tile, `munich-plantiler`, grid of 96 compositions against the same grid
plus the four `WINDOW` predictors = 144):

| | gzip | vs `DELTA_ZIGZAG_VARINT` |
|---|---|---|
| per-layer default (Hilbert `QUAD_CORNER_SHARED_FRAME`, class) | 3.214.369 | -14,99% |
| oracle, 96 compositions | 3.213.707 | -15,01% |
| oracle, 144 compositions | 3.201.171 | -15,34% |

**Decisive case against:**
- The best case, a per-buffer oracle that is charged nothing for its choice, gains 0,39% over the oracle without
  the references (0,41% over the per-layer default), on one layer. The oracle picks a `WINDOW` predictor for 44% +
  7% of the `building` buffers; dropping `QUAD_CORNER_SHARED_FRAME_RESIDUAL_WINDOW` from the grid costs 0,41%.
- Under Brotli the 144-grid oracle is 2.955.513 B against 2.952.062 B for the per-layer default (+0,12%); under
  zstd it is 0,10% smaller. The raw payload shrinks by 7,9% in the probe, so gzip, Brotli and zstd already recover
  most of what the references remove.
- Not a gain anywhere else in the probe, and one more flag, mask and fallback chain in the decoder.

**Measurement caveat.** An earlier per-tile comparison in a `residualshared` step showed -4,5% to -4,8% gzip on
`building`. It listed the ring rows twice (`PipelineRegistry.DEFAULT` and the `building` default are the same
composition), so their bytes were counted twice per tile and the figure is not valid; the oracle numbers above
use no duplicated rows. The oracle run was stopped on `building`: the all-layers `bestresidual` run and the
`CHAIN`-reorder, `ALIGNED` and `RUN` oracle cases were not completed, and decode time was not measured.
