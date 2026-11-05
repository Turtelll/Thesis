# Sparse rANS frequency table

> **Verdict: Out of scope (rANS only).** -5,10% over dense tables (rANS bytes), but retired with
> [rANS](rans-entropy-coder.md); never measured under the headline metric.

## Encoding target
all layers under rANS; largest effect on small buffers (housenumber, poi, low zooms).

## Idea
The rANS table always wrote all 33 bucket frequencies (66 bytes), but a region uses on average only 3 to 11
buckets. Writing only the present buckets removes most of the fixed table cost, without any state shared
between buffers.

## Intention
The fixed table cost was what made rANS lose on small buffers. A sparse table is the cheapest fix that keeps
every buffer self-contained (unlike [shared tables](../bad/shared-rans-tables.md)).

## How the encoding works
`RansCodec.writeTable`: `[1 B present count][present × (1 B symbol, 2 B frequency)]` instead of the dense
`33 × 2 B`.

## How the decoder works
Read the count, then `count` (symbol, frequency) pairs; absent symbols have frequency 0; build the cumulative
table and decode as before.

## Results
**Headline:** *not measured under the headline metric* (rANS bytes, `munich-plantiler`):
`QUAD_CORNER_SEG_RANSSEG` 11.393.052 → 10.812.307 B (**-5,10%**).

**Decisive case — why it is out of scope:** it works as designed (the probe predicted 587.448 B, the build
saved 580.745 B, -52,6% on 0–50 vertex buffers), but it only improves rANS, and rANS is not a delivery format:
a browser decodes gzip, Brotli or zstd natively, not rANS. Retired together with rANS.

Supporting figures (*rANS bytes*):

- Probe (ideal bits): sparse control recovers 59,5% of the dense table bytes (587.448 of 987.168 B); 0–50
  vertex buffers 75,0%.
- By size: 0–50 vertices -52,6%, 5000+ -0,6%.
- Turns `RANSSEG` vs `RANS` from +9,62% loss into a wash (+0,00%).

Source: `research-java/docs/results/shared-tables.md`, `rans-entropy-coder.md`.
