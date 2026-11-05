# Delta-of-delta and reset variants (`DOD_*`, `DELTA_RESET_*`)

> **Verdict: Rejected.** All six variants +9,4% to +14,1% (retired corpus, FastPFOR bytes); not re-run under
> the headline metric (no code left).

## Encoding target
all layers, expected to help transportation (LineString / MultiLineString).

## Idea
Two variations of plain delta: (1) second-order differencing, so straight stretches with constant edges give
zero residuals; (2) restart the delta at each part/ring boundary, so the large jumps between parts disappear
from the residuals.

## Intention
These are the two textbook improvements over delta coding for polylines: second order removes the constant
direction of a straight road, and per-part resets stop one feature's position from leaking into the next
feature's residuals. Both are simple and need no signalling.

## How the encoding works
Six variants over two axes:
- order: first (`p[i] - p[i-1]`) or second (`(p[i] - p[i-1]) - (p[i-1] - p[i-2])`);
- reset: continuous through the buffer, or restart at each run (the run's first vertex stored as a raw coordinate,
  the seed).
Zigzag, then FastPFOR or Varint.

## How the decoder works
Continuous: integrate once (first order) or twice (second order) across the whole buffer. Reset: at each run start
read the raw seed coordinate, then integrate within the run.

## Results
**Headline:** *not measured under the headline metric* (retired corpus `munich.mbtiles`, bytes vs
`DELTA_ZIGZAG_FASTPFOR`; code removed). Best variant `DELTA_RESET_VARINT` **+9,4%**, best second-order
`DOD_CONT_FASTPFOR` **+10,7%**. The same second-order rule, ported later from upstream MLT
([upstream delta2](upstream-delta2.md)), is +9,94% gzip on the current corpus.

**Decisive case against:** both ideas make the residuals larger. A reset replaces the jump by a raw seed
coordinate, which is a full-magnitude number, and there are many short runs. Second-order differencing is
smaller at 60,1% of positions, but it amplifies the grid rounding noise (mean zigzag width 6,18 → 7,14 bits,
≥ 12 bits 6,2% → 10,1%), worst on `transportation`. Applied only where it helps (line interiors), the
second-order idea became [line continuation](../good/line-continuation.md).

Supporting figures (*retired corpus, bytes vs `DELTA_ZIGZAG_FASTPFOR`*):

| row | change |
|---|---|
| `DELTA_RESET_VARINT` | +9,4% |
| `DOD_CONT_FASTPFOR` | +10,7% |
| `DOD_RESET_VARINT` | +11,1% |
| `DELTA_RESET_FASTPFOR` | +12,5% |
| `DOD_CONT_VARINT` | +13,1% |
| `DOD_RESET_FASTPFOR` | +14,1% |

- Per-buffer selection oracle: this family contributes 0,14% on top of choosing between baselines.

Source: `research-java/docs/results/failed/delta-of-delta.md`, `selection-oracle.md`.
