# Burrows–Wheeler and move-to-front before the compressor

**Code names:** `*_INT_*`, `*_BYTE_*`, `BwtMtfCodec`

> **Verdict: Rejected.** All 48 rows lose to their plain predictor; the best stage row is -4,73% gzip where
> the same predictor without the stage is -7,27%.

## Encoding target
all layers; stage between the residual streams and gzip/Brotli/zstd.

## Idea
OpenZL puts a BWT and MTF in front of the general-purpose compressor: BWT sorts symbols by their following
context, MTF turns that locality into small ranks and zero runs, so the entropy coder models the stream more
cheaply.

## Intention
gzip only sees a 32 KB window and models bytes with a static Huffman code per block; BWT + MTF is how bzip2
gets context modelling out of a simple entropy coder. If the residual streams contain repeating contexts
gzip cannot exploit, a BWT stage in front should expose them as runs of small MTF ranks. It was tried both on
whole integers (`INT_*`) and on bytes (`BYTE_*`).

## How the encoding works
`BwtMtfCodec` wraps `VARINT_SPLIT` with a stage `MTF`, `BWT` or `BWT_MTF`, in one of two domains:
- `INT`: stage over the zigzag residuals, then `VARINT_SPLIT`.
- `BYTE`: `VARINT_SPLIT` first, then the stage over the bytes (bzip2-style MTF over 256 symbols).
BWT over a unique sentinel, primary index as one varint in front. 8 predictors × 6 codecs = 48 rows.

## How the decoder works
Read the primary index, invert MTF (rank → symbol with the same move-to-front list), invert BWT (LF mapping from the
primary index), then decode `VARINT_SPLIT` (order of the two steps by domain).

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`): best stage row
`QUAD_CORNER_SHARED_REORDER_INT_BWT` **-4,73%** against **-7,27%** for the same predictor without the stage;
on plain `DELTA_ZIGZAG` `INT_MTF` is **+8,74%** against -0,87%.

**Decisive case against:** every one of the 48 rows is larger than its plain predictor under every compressor.
MTF raises byte entropy (+5 to +9 points gzip), and BWT destroys the sequential context that the predictors
and class streams create: a residual's neighbours in the stream are exactly the vertices it was predicted from.
BWT also cannot replace segmentation (interleaved `QUAD_CORNER` + `INT_BWT` +0,38% gzip vs segmented plain
-3,36%).

Supporting figures (same metric):

| predictor | plain | INT_MTF | INT_BWT | INT_BWT_MTF | BYTE_MTF | BYTE_BWT | BYTE_BWT_MTF |
|---|---|---|---|---|---|---|---|
| `DELTA_ZIGZAG` | -0,87% | +8,74% | +1,68% | +8,41% | +6,00% | +2,01% | +6,30% |
| `QUAD_CORNER` | -3,36% | +4,82% | -0,84% | +5,19% | +2,01% | -0,80% | +3,13% |
| `QUAD_CORNER_SHARED` reordered | **-7,27%** | +0,15% | -4,73% | +1,05% | -1,89% | -4,59% | -0,71% |
| `LINE_CONTINUATION_SHARED_DICT` | -2,62% | +3,97% | -0,89% | +4,68% | +2,23% | -0,82% | +2,88% |

- Best stage row `QUAD_CORNER_SHARED_REORDER_INT_BWT` -4,73% gzip / -10,73% Brotli / -6,26% zstd vs plain
  -7,27% / -13,44% / -8,61%.

Source: `research-java/docs/results/failed/bwt-mtf.md`.
