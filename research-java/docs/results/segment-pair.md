# Grouping part starts — `SEGMENT_PAIR_FASTPFOR`

> **Revisited under split varints:** `PART_START_SEG_VARINT_SPLIT` is -3,65% whole tileset
> (smallest of gzip/Brotli/zstd), never ahead of `LINE_CONTINUATION`; retired. See
> [revisit-varint-split.md](revisit-varint-split.md).

> **Revisited under segmented split varints** ([revisit-varint-split.md](revisit-varint-split.md)):
> `PART_START_SEG_VARINT_SPLIT` is -3,65% per tile (smallest of gzip/Brotli/zstd), never ahead
> of `LINE_CONTINUATION`; retired.

> **Revisited over segmented split varints** ([revisit-varint-split.md](revisit-varint-split.md)):
> `PART_START_SEG_VARINT_SPLIT` is -3,65% per tile (smallest of gzip/Brotli/zstd), never ahead of
> `LINE_CONTINUATION`; retired.

**Status:** registered — the first encoding to beat the baseline
**Effect:** -4,5% corpus / -6,5% transportation
**Corpus:** previous Munich corpus (`munich.mbtiles`) — see [reordering.md](reordering.md)
for the corpus-comparability banner

It does so on every layer except the point-only ones, where it degrades to the baseline
exactly.

```
DELTA_ZIGZAG_FASTPFOR     13.399.028        ref
DELTA_ZIGZAG_VARINT       13.818.767      +3,1%
SEGMENT_PAIR_FASTPFOR     12.795.818      -4,5%  *
```

| segment | baseline | SEGMENT_PAIR | |
|---|---|---|---|
| `transportation` | 4.232.495 | 3.958.179 | **-6,5%** |
| `boundary` | 1.159.184 | 1.088.496 | **-6,1%** |
| `building` | 3.228.047 | 3.059.154 | -5,2% |
| `landcover` | 1.194.048 | 1.162.968 | -2,6% |
| `water` | 916.048 | 905.852 | -1,1% |
| `housenumber` (point-only) | 735.180 | 735.180 | byte-identical |
| LINESTRING | 5.992.732 | 5.614.175 | **-6,3%** |
| POLYGON | 5.713.018 | 5.489.845 | -3,9% |
| POINT | 1.512.324 | 1.512.324 | byte-identical |

**What it does.** The same componentwise delta + zigzag residuals as the baseline, in a
different order: every part-start residual first, then every within-part delta. No
reordering of vertices, no reset seeds, nothing signalled — the split point is derived
from the topology streams on both sides, so encoder and decoder agree without a flag.

**Why it wins.** The starting observation was that `transportation` is not what the
previous round assumed. It is not long polylines; it is a fragmented road graph:

| property of the `transportation` corpus | measured |
|---|---|
| parts that are exactly 2 vertices | **70,4%** |
| parts ≤ 5 vertices | 92,1% |
| part-boundary deltas, share of all deltas | 30,2% |
| …their share of total zigzag bits | **35,5%** |
| mean zigzag width, interior delta | 10,30 bits |
| mean zigzag width, boundary delta | 13,10 bits |

Boundaries are not rare events between long runs — they are a third of the buffer, and
they are 2,8 bits wider on average than the interior deltas they sit between. Interleaved,
the wide population sets the block width for the narrow one, because **FastPFOR packs a
block to the width of its widest value**. Grouping the two populations lets each be packed
at its own width. Simulating just that block-width effect over 60 buffers predicts -20,2%
on the packed bits, and the measured byte win follows from it.

This is the same mechanism the [delta-of-delta result](failed/delta-of-delta.md) identified
as the reason *it* lost — "both physical codecs price the tail, not the median". Here the
tail is separated from the median instead of being thickened.

It follows that the win is specific to a bit-aligned codec. `SEGMENT_PAIR` over Varint was
measured and is **byte-identical to `DELTA_ZIGZAG_VARINT`**: a byte-aligned codec prices
each value independently, so reordering values cannot change the total. It is not
registered, since a column that can never differ from a baseline is not a result worth a
column in every table.
