# Context-adaptive delta

**Code names:** `CONTEXT_DELTA_T2_M32`

> **Verdict: Kept under FastPFOR, then retired.** Under the headline metric it loses: +2,44% gzip on the
> tileset.

## Encoding target
transportation (LineString).

## Idea
Continuing the last edge wins on straight stretches and loses at corners. If the encoder could tell from
already-decoded vertices whether the line is currently "smooth", it could use continuation only there and
plain delta elsewhere, and keep the two kinds of residual apart. A small state machine tracks how long the line
has been going straight and picks the predictor from that.

## Intention
[Line continuation](line-continuation.md) uses a fixed gate (line runs of a minimum length). A gate that
adapts to the decoded shape should switch continuation off exactly where it hurts, at corners, and could
average several edges on long straight stretches. Because the state is derived from decoded vertices,
nothing per vertex is signalled.

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

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`, segmented split varints): **tileset
+2,44%** (`CONTEXT_DELTA_T2_M32_VARINT_SPLIT`, computed from the gzip byte total). Per layer only the smallest
of gzip/Brotli/zstd was reported: `transportation` -1,38%, against -3,08% for `LINE_CONTINUATION`.

**Decisive case — why it was retired:** the context cannot see a corner coming. It is computed from vertices
already decoded, so it lags one vertex and every corner is still coded at full context, and only ~6% of
vertices ever reach context 2 or 3. Its one target layer gains less than half of what the plain fixed gate
gives, and it grows the tileset, so line continuation's fixed gate stayed the line predictor. It was kept only while
FastPFOR was the target (-1,1%).

Supporting figures:

- Smallest of gzip/Brotli/zstd, segmented split varints: tileset +1,54%.
- Interleaved plain Varint, same metric: +3,41% gzip / +3,24% Brotli.
- *Not under the headline metric* — retired corpus `munich.mbtiles`, LINESTRING, FastPFOR: `T2_M8` -0,4%,
  `T2_M32` -1,1% (vs `SEGMENT_PAIR` -6,3%). NY `transportation`: -0,8% / -1,8% (vs -7,0%). Loses to
  part-start grouping by a factor of six.
- Why (NY z14): only ~6% of vertices reach context 2 or 3; loosening the threshold widens their residuals in
  lockstep (8,86 → 11,30 bits). The context lags one vertex: every corner is coded at full context.
- Oracle picked it on 149 of 8.366 buffers (~2,4 KB).

Source: `research-java/docs/results/context-delta.md`, `revisit-varint-split.md`,
`general-purpose-compression.md`.
