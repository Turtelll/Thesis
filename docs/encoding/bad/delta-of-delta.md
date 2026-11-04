# Delta-of-delta and reset variants (`DOD_*`, `DELTA_RESET_*`)

## encoding target:
all layers, expected to help transportation (LineString / MultiLineString)

## Idea of the encoding
Two variations of plain delta: (1) second-order differencing, so straight stretches with constant edges give zero
residuals; (2) restart the delta at each part/ring boundary, so the large jumps between parts disappear from the
residuals.

## How the encoding works
Six variants over two axes:
- order: first (`p[i] - p[i-1]`) or second (`(p[i] - p[i-1]) - (p[i-1] - p[i-2])`);
- reset: continuous through the buffer, or restart at each run (the run's first vertex stored as a raw coordinate,
  the seed).
Zigzag, then FastPFOR or Varint.

## How the decoder works
Continuous: integrate once (first order) or twice (second order) across the whole buffer. Reset: at each run start
read the raw seed coordinate, then integrate within the run.

## results
Retired corpus `munich.mbtiles`, vs `DELTA_ZIGZAG_FASTPFOR`:

| row | change |
|---|---|
| `DELTA_RESET_VARINT` | +9,4% |
| `DOD_CONT_FASTPFOR` | +10,7% |
| `DOD_RESET_VARINT` | +11,1% |
| `DELTA_RESET_FASTPFOR` | +12,5% |
| `DOD_CONT_VARINT` | +13,1% |
| `DOD_RESET_FASTPFOR` | +14,1% |

- The resets are the dominant cost: each seed is a full-magnitude coordinate, and there are many short runs.
- Second-order residual smaller at 60,1% of positions, but mean zigzag width rises 6,18 → 7,14 bits (≥ 12 bits:
  6,2% → 10,1%): differencing amplifies grid rounding noise. Worst on `transportation`.
- Per-buffer selection oracle: this family contributes 0,14% on top of choosing between baselines. Removed. The gated
  form is line continuation.
