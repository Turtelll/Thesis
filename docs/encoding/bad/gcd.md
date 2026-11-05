# Per-stream GCD (pipeline S5)

> **Verdict: Rejected.** -5,80% gzip on the tileset with GCD against -5,86% without; removed from the encoder.

## Encoding target
all layers; stream stage.

## Idea
If every value of a stream shares a common divisor (e.g. coordinates snapped to a coarser grid), divide by it
and write the divisor once.

## Intention
If a tiler snapped some geometry to a coarser grid, every residual of such a stream would be a multiple of
the grid step, and dividing it out would shrink every value for the price of one
number per stream.

## How the encoding works
Per stream: `g = gcd(values)`, write `g` in front, then `v / g`.

## How the decoder works
Read `g`, multiply every value of the stream by it.

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`): **tileset -5,80%** with GCD against
**-5,86%** without.

**Decisive case against:** the premise does not hold on this corpus. Only 1.042 of 11.817 streams have a
divisor > 1, holding 0,021% of the values, mostly tiny point streams (`aerodrome_label`, `water_name`,
`mountain_peak`; `building` and `transportation` 0,000%), and the divisor is mostly 2 or 3. The divisor in
front of every stream costs more than those values save.

Supporting figures (same metric): no GCD -5,86% gzip / -8,12% Brotli 9 / -7,34% zstd; GCD -5,80% / -7,99% /
-7,31%.

Source: `research-java/docs/results/openzl-pipeline.md`.
