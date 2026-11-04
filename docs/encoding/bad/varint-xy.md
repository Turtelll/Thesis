# Component-split varint (`VARINT_XY`)

## encoding target:
all layers; byte layout of the residual stream

## Idea of the encoding
x and y residuals may follow different distributions. Writing all x residuals first and all y residuals after
should give the compressor two homogeneous halves instead of an alternating stream.

## How the encoding works
Same zigzag varints as `VARINT`, reordered: `x0 x1 x2 ... | y0 y1 y2 ...` instead of `x0 y0 x1 y1 ...`. Same raw
size.

## How the decoder works
Read `n` varints as the x components, then `n` varints as the y components, pair them up by index.

## results
Current corpus `munich-plantiler`, `DELTA_ZIGZAG`, per tile vs `VARINT`: +0,58% gzip / +0,80% Brotli 9 / +0,71%
zstd. The alternating stream compresses better; regresses everywhere. Same result for the fixed-width transposes
that separate x from y (x/y-separating byte planes).
