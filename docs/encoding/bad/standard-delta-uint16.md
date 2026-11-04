# Standardized-length deltas over fixed Uint16 (`STD_DELTA_*`)

## encoding target:
all layers with one fixed encoding (building, transportation, poi, ...)

## Idea of the encoding
Make the vertex data look the same in every layer: write every delta as tokens from one global vocabulary of
standardized lengths, each token a fixed 16-bit word. A tile (all layers concatenated, compressed as one response)
should then repeat the same 2-byte symbols across layers for LZ77 and Huffman to catch.

## How the encoding works
Componentwise continuous delta, then each delta `d` decomposed (tokens zigzagged, `S` a global step):

| name | `d` becomes | tokens per coordinate |
|---|---|---|
| `PLAIN` | `zigzag(d)` | 1 |
| `CHUNK_S` (16, 64, 256) | `±S` while `|rest| >= S`, then the remainder | 9,09 / 2,82 / 1,37 |
| `QUOTREM_S` | `floor(d/S)` stream, then `d mod S` stream | 2 |
| `POW2` | set bits of `|d|` as signed exponents, then 0 | 3,79 |
| `SNAP` | index of largest `T_i <= |d|` in `{0,1,2,3,4,6,8,12,16,...}`, then correction | 2 |

Serializers `UINT16` (interleaved LE words) or `UINT16_PLANES` (low bytes, then high bytes); escape `0xFFFF` + two
words for tokens that do not fit (never needed here).

## How the decoder works
Read 16-bit words (de-transposing planes), unzigzag tokens, reassemble each delta by the decomposition's rule
(sum chunks until the remainder, `q·S + r`, sum of powers, `T_i` + correction), then integrate.

## results
Current corpus `munich-plantiler`, per tile vs `DELTA_ZIGZAG_VARINT`:

| row | gzip | Brotli 9 | zstd 19 |
|---|---|---|---|
| `DELTA_ZIGZAG_VARINT_SPLIT` | -0,87% | -1,79% | -1,73% |
| `STD_DELTA_PLAIN_UINT16_PLANES` (best) | +2,05% | -0,21% | -1,08% |
| `STD_DELTA_QUOTREM16_UINT16_PLANES` | +13,39% | +6,44% | +2,29% |
| `STD_DELTA_SNAP_UINT16_PLANES` | +23,60% | +17,99% | +17,12% |
| `STD_DELTA_POW2_UINT16_PLANES` | +34,61% | +27,67% | +20,58% |
| `STD_DELTA_PLAIN_UINT16` | +17,69% | +12,81% | +10,75% |

LZ77 matches exactly the redundancy the tokens add; zigzag + varint is already a layer-independent code. Interleaved
`UINT16` +28% to +59% gzip. Regression.
