# Per-stream GCD (pipeline S5)

## encoding target:
all layers; stream stage

## Idea of the encoding
If every value of a stream shares a common divisor (e.g. coordinates snapped to a coarser grid), divide by it and
write the divisor once.

## How the encoding works
Per stream: `g = gcd(values)`, write `g` in front, then `v / g`.

## How the decoder works
Read `g`, multiply every value of the stream by it.

## results
Current corpus `munich-plantiler`, per tile vs `DELTA_ZIGZAG_VARINT`: no GCD -5,86% gzip / -8,12% Brotli 9 /
-7,34% zstd; GCD -5,80% / -7,99% / -7,31%. Only 1.042 of 11.817 streams have a divisor > 1, holding 0,021% of the
values (tiny streams: `aerodrome_label`, `water_name`, `mountain_peak`; `building`, `transportation` 0,000%),
mostly 2 or 3. The divisor in front of every stream costs more. Removed from the encoder.
