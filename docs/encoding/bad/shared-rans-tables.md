# rANS tables shared across buffers (probe)

## encoding target:
all layers under rANS (per tile, per layer across tiles, or corpus-static)

## Idea of the encoding
Buffers of the same tile, or of the same layer across tiles, have similar residual distributions. One frequency
table shared by many buffers would be paid once instead of per buffer.

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

## results
Current corpus `munich-plantiler`, vs today's dense tables (987.168 B):

| model | table bytes | penalty bytes | net saving |
|---|---|---|---|
| sparse control (no sharing) | 399.720 | 0 | 59,5% |
| per-tile shared | 85.276 | 422.696 | 48,5% |
| per-layer across tiles | 7.984 | 118.442 | 87,2% |
| corpus-static (held out) | 0 | 369.761 on the test half alone | — |

- Per-tile sharing is worse than the sparse table alone; large buffers lose under it (5000+: -118,4%).
- Per-layer-across-tiles gains 27,7 points over the sparse table but needs cross-buffer addressing (the wall that
  stopped junction back-references and shape reuse). Corpus-static backfires. Not pursued.
