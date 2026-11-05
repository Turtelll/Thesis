# rANS tables shared across buffers (probe)

> **Verdict: Rejected (probe).** Sharing tables per tile saves less than sparse per-buffer tables; never
> measured under the headline metric: rANS itself is [out of scope](../out-of-scope/rans-entropy-coder.md).

## Encoding target
all layers under rANS (per tile, per layer across tiles, or corpus-static).

## Idea
Buffers of the same tile, or of the same layer across tiles, have similar residual distributions. One frequency
table shared by many buffers would be paid once instead of per buffer.

## Intention
Under rANS the per-buffer frequency table is a fixed cost that dominates small buffers. Sharing it amortises
that cost; the probe measured how much accuracy each sharing scope gives up (penalty bytes) against the table
bytes it saves.

## How the encoding works
Probe only (`ShareTableCli`), ideal order-0 code length over the 33-bucket histograms of the three quad-corner
regions, five models:
- per buffer (today), sparse-table control,
- per-tile shared (all layers of a tile pool one table set),
- per-layer-across-tiles shared (one table set per MVT layer),
- corpus-static (trained on half the tiles, priced on the other half).
Selector cost 1 byte per buffer; mixture penalty = bits under the shared table minus bits under the own table.

## How the decoder works
Not built. A decoder would need the shared table (once per tile, per layer, or built into the codec) plus a
per-buffer selector saying which table applies.

## Results
**Headline:** *not measured under the headline metric* (probe, rANS table + penalty bytes vs today's dense
tables, 987.168 B, current corpus). Per-tile sharing: **48,5%** net saving against **59,5%** for sparse tables
without sharing.

**Decisive case against:** the only scope that tile delivery allows, the tile, is worse than not sharing: the
penalty for the mismatched distributions (422.696 B) exceeds the table bytes it saves, and large buffers lose
(5000+: -118,4%). Per-layer-across-tiles would gain 27,7 points over sparse tables but needs a table outside
the tile, the cross-buffer addressing that also stopped junction back-references and shape reuse.
Corpus-static tables backfire. Not pursued.

Supporting figures (*table/penalty bytes*):

| model | table bytes | penalty bytes | net saving |
|---|---|---|---|
| sparse control (no sharing) | 399.720 | 0 | 59,5% |
| per-tile shared | 85.276 | 422.696 | 48,5% |
| per-layer across tiles | 7.984 | 118.442 | 87,2% |
| corpus-static (held out) | 0 | 369.761 on the test half alone | — |

Source: `research-java/docs/results/shared-tables.md`.
