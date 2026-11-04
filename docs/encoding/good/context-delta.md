# Context-adaptive delta (`CONTEXT_DELTA_T2_M32`)

## encoding target:
transportation (LineString)

## Idea of the encoding
Continuing the last edge wins on straight stretches and loses at corners. If the encoder could tell from
already-decoded vertices whether the line is currently "smooth", it could use continuation only there
and plain delta elsewhere, and keep the two kinds of residual apart. A small state machine tracks how
long the line has been going straight and picks the predictor from that.

## How the encoding works
- Per vertex a context `c = min(run, 3)` from a run-length state machine over reconstructed vertices.
- A step is *smooth* if the turn between the last two edges is shallow by an integer test
  `|cross| <= |dot| >> turnShift`, `dot > 0` (no floating point).
- Predictor: `c = 0` plain delta; `c >= 1` `p[i-1]` + mean of the last `c` edges.
- A residual wider than `missThreshold` collapses the run to 0.
- The context also selects one of four FastPFOR regions. Region sizes depend on decoded data, so the four
  region *counts* are written into the payload (second `StreamMetadata` header).

## How the decoder works
1. Read the four region counts, split the payload into the four regions.
2. Walk the vertices, maintaining the same run-length state machine on the decoded vertices.
3. For each vertex compute `c`, take the next residual from region `c`, add it to the predictor for `c`,
   then update the state (smooth test, miss threshold) with the decoded vertex.

## results
- **Retired corpus `munich.mbtiles`, LINESTRING, FastPFOR:** `T2_M8` -0,4%, `T2_M32` -1,1% (vs
  `SEGMENT_PAIR` -6,3%). **NY `transportation`:** -0,8% / -1,8% (vs -7,0%). Weak win, loses to part-start
  grouping by a factor of six.
- Why (NY z14): only ~6% of vertices reach context 2 or 3; loosening the threshold widens their residuals
  in lockstep (8,86 → 11,30 bits). The context lags one vertex: every corner is coded at full context.
- Oracle picked it on 149 of 8.366 buffers (~2,4 KB).
- **Current corpus, split varints, per tile:** +1,54% vs `DELTA_ZIGZAG_VARINT` (best layer
  `transportation` -1,38% vs `LINE_CONTINUATION` -3,08%). **Retired.**
