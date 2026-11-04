# rANS entropy coder (`*_RANS`)

## encoding target:
all layers; integer codec (largest gains on transportation (LineString) and building (Polygon))

## Idea of the encoding
FastPFOR prices a block by its widest value; an entropy coder prices each value by how often it occurs, i.e. it
pays for the median, not the tail. An order-0 entropy estimate showed FastPFOR spends 15,15% more than the
partitioned entropy of the residuals. A static rANS coder per buffer should capture part of that, and stack with
every predictor.

## How the encoding works
`RansCodec` (an `IntCodec`, static rANS, 12-bit precision, 32-bit state, 8-bit renormalisation):
- Alphabet = the residual's **bit-length bucket** (33 symbols); the `bucket - 1` bits below the leading one are
  written raw into a separate forward region.
- Per-buffer frequency table (dense: 33 × 2 bytes; later sparse, see
  sparse rANS table).
- The context is derived from the value itself, so the codec stays context-free.

```
residual 13 = 0b1101  → bucket 4 (rANS-coded), raw bits 101 (3 bits, raw region)
```

## How the decoder works
Read the frequency table, initialise the rANS state from the end of the coded region, decode bucket symbols one by
one (state → symbol via cumulative frequencies, renormalise by reading bytes), and for each bucket `k > 1` read
`k - 1` raw bits from the raw region and prepend the leading one.

## results
Retired corpus `munich.mbtiles`:
- Cost-model estimate (`building`, `SEGMENT_QUAD_CORNER` regions): partitioned order-0 2.403.477 B (+ ~56 KB
  tables) vs FastPFOR 2.832.684 B → gap 15,15%. Region partitioning alone is worth 8,93% in entropy terms.
- `building`: `SEGMENT_QUAD_CORNER_FASTPFOR` 2.834.672 → `_RANS` 2.668.361 (-5,87%), 38,6% of the estimated gap;
  the bucket alphabet overshoots order-0 entropy by ~21% on a geometric source.
- Additive with transforms: rANS on plain delta -12,96%, on `SEGMENT_QUAD_CORNER` -17,34%, with
  `--reorder feature-rev` **-27,16%** corpus (best FastPFOR row -21,48%); `transportation` -31,0%, `building` -27,2%.
- Conditioning y on x: worth 8.380 B (0,3%); order-1 over buckets 46.548 B, mostly the `starts` region.
- **Retired:** not spec-compatible, substantial encoder complexity, does not combine with gzip (browser-native).
  Not measured or printed any more; code at git tag `pre-consolidation`.
