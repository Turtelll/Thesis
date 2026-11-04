# 7. S4 Residual map

## What it does
Maps each signed residual to an unsigned value, so that small magnitudes of either sign become small
numbers for the byte width step.

## How it works
`encoder/stream/ResidualMap.java`, applied per stream by `StreamCodec`. Zigzag is the only map (header byte 0
bit 5 = 0):

```
zigzag(n) = (n << 1) ^ (n >> 31)

 0 → 0   -1 → 1   1 → 2   -2 → 3   2 → 4   ...   100 → 200   -30 → 59
```

## How the decoder works
`(v >>> 1) ^ -(v & 1)` per value, after the byte width is undone.

## Why
The alternative `BIAS` (shift every value by the stream minimum, write the minimum in front) is larger raw
(20,4 against 12,9 million bytes) and worse on every layer: +9,51% gzip against -5,86% for zigzag. Removed.

More: [../encoding/bad/bias-map.md](../encoding/bad/bias-map.md),
[openzl-pipeline.md#s4-residual-map](https://github.com/Turtelll/Thesis/blob/main/research-java/docs/results/openzl-pipeline.md#s4-residual-map).
