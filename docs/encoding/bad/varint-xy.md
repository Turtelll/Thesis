# Component-split varint (`VARINT_XY`)

> **Verdict: Rejected.** +0,58% gzip on the tileset; separating x from y regresses under every compressor.

## Encoding target
all layers; byte layout of the residual stream.

## Idea
x and y residuals may follow different distributions. Writing all x residuals first and all y residuals after
should give the compressor two homogeneous halves instead of an alternating stream.

## Intention
The same reasoning that made [class streams](../good/class-streams.md) pay: separate populations compress
better than a mixture. x and y are the most obvious split, and it is free (same bytes, different order).

## How the encoding works
Same zigzag varints as `VARINT`, reordered: `x0 x1 x2 ... | y0 y1 y2 ...` instead of `x0 y0 x1 y1 ...`. Same raw
size.

## How the decoder works
Read `n` varints as the x components, then `n` varints as the y components, pair them up by index.

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`, on `DELTA_ZIGZAG`): **tileset
+0,58%**.

**Decisive case against:** the premise does not hold: the alternating stream compresses better than the two
halves. Splitting them loses under gzip, Brotli and zstd, and the same happened for
the fixed-width transposes that separate x from y ([x/y-separating byte planes](xy-separating-transposes.md)).

Supporting figures (same metric): +0,58% gzip / +0,80% Brotli 9 / +0,71% zstd.

Source: `research-java/docs/results/general-purpose-compression.md`.
