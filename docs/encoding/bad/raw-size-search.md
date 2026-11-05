# Composition search on raw or entropy-estimated bytes

> **Verdict: Rejected.** Searching on raw bytes reaches -9,19% gzip, worse than the fixed per-layer default
> (-11,36%); the search must run on the real compressor (-11,94%).

## Encoding target
all layers; selection of the per-(tile, layer) composition.

## Idea
The tile oracle compresses every one of 252 candidates per buffer, which is slow. If raw size or a
byte-entropy estimate picked (nearly) the same compositions, the search would be cheap.

## Intention
An encoder that searches compositions has to be fast enough to run at tiling time. Raw size is free to
measure, and an order-0/order-1 entropy estimate is cheap; if either ranks the candidates like gzip does, the
expensive trial compression could be replaced.

## How the encoding works
`measure --step best` with `--trials raw,order0,order1`: the oracle (per buffer and coordinate descent on the tile)
searches by raw payload bytes, order-0 or order-1 byte entropy instead of gzip; every choice then sized under gzip.

## How the decoder works
No change; the chosen composition is in the header.

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`, tile descent): searched on **raw
bytes -9,19%**, on order-1 entropy -11,15%, on **gzip -11,94%**.

**Decisive case against:** raw size favours split varint, which is smallest before compression but not after
LZ77 + Huffman, so the raw search ends up worse than not searching at all (the fixed
[per-layer default](../good/per-layer-composition.md), -11,36%). Entropy estimates close part of the gap but
still lose 0,8 to 1,5 points to the real compressor. The search flags were removed.

Supporting figures (same metric):

| searched under | per buffer | tile descent |
|---|---|---|
| gzip | -11,67% | **-11,94%** |
| order-1 entropy | -10,76% | -11,15% |
| order-0 entropy | -10,34% | -10,44% |
| raw bytes | -9,19% | -9,19% |

Source: `research-java/docs/results/failed/raw-search.md`.
