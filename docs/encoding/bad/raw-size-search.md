# Composition search on raw or entropy-estimated bytes

## encoding target:
all layers; selection of the per-(tile, layer) composition

## Idea of the encoding
The tile oracle compresses every one of 252 candidates per buffer, which is slow. If raw size or a byte-entropy
estimate picked (nearly) the same compositions, the search would be cheap.

## How the encoding works
`measure --step best` with `--trials raw,order0,order1`: the oracle (per buffer and coordinate descent on the tile)
searches by raw payload bytes, order-0 or order-1 byte entropy instead of gzip; every choice then sized under gzip.

## How the decoder works
No change; the chosen composition is in the header.

## results
Current corpus `munich-plantiler`, per tile vs `DELTA_ZIGZAG_VARINT` gzip:

| searched under | per buffer | tile descent |
|---|---|---|
| gzip | -11,67% | **-11,94%** |
| order-1 entropy | -10,76% | -11,15% |
| order-0 entropy | -10,34% | -10,44% |
| raw bytes | -9,19% | -9,19% |

Raw search is worse than the fixed per-layer default (-11,36%); it favours split varint, smallest raw but not after
LZ77 + Huffman. The search must run on the target compressor; the flags were removed.
