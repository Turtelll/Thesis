# Sparse rANS frequency table

## encoding target:
all layers under rANS; largest effect on small buffers (housenumber, poi, low zooms)

## Idea of the encoding
The rANS table always wrote all 33 bucket frequencies (66 bytes), but a region uses on average only 3 to 11
buckets. Writing only the present buckets removes most of the fixed table cost, without any state shared between
buffers.

## How the encoding works
`RansCodec.writeTable`: `[1 B present count][present × (1 B symbol, 2 B frequency)]` instead of the dense
`33 × 2 B`.

## How the decoder works
Read the count, then `count` (symbol, frequency) pairs; absent symbols have frequency 0; build the cumulative
table and decode as before.

## results
Current corpus `munich-plantiler`:
- Probe (ideal bits): sparse control recovers 59,5% of the dense table bytes (587.448 of 987.168 B); 0–50 vertex
  buffers 75,0%.
- Built: `QUAD_CORNER_SEG_RANSSEG` 11.393.052 → 10.812.307 B (**-5,10%**; probe predicted 587.448 B, real 580.745 B).
  By size: 0–50 vertices -52,6%, 5000+ -0,6%.
- Turns `RANSSEG` vs `RANS` from +9,62% loss into a wash (+0,00%).
- Retired with rANS (rANS entropy coder).
