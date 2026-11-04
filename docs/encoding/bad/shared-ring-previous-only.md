# Shared ring vertices, previous ring only (`QUAD_CORNER_SHARED_PREV`, `_PREVOFF`)

## encoding target:
building, landcover, landuse (Polygon)

## Idea of the encoding
46,7% of shared building positions come from the immediately previous polygon. So drop the 4-ring window
and address only the previous ring: a narrower index (an offset into one ring), and optionally no entry
flag at all.

## How the encoding works
Same as shared ring vertices (rotation, follow flags unchanged) with a
different entry:
- `PREV_FLAG` (`_PREV`): window 1, entry flag + offset into the previous ring.
- `PREV_OFFSET` (`_PREVOFF`): window 1, no entry flag; the offset has one extra value (the ring length)
  meaning "no match", so hit and miss both cost `ceil(log2(n+1))` bits.

## How the decoder works
As for the shared ring encoding, but the candidate set is the previous ring only: `_PREV` reads a flag then
an offset; `_PREVOFF` reads the offset directly and treats the value `n` as "no reference, read a
residual".

## results
Current corpus `munich-plantiler`, bytes vs the 4-ring window, same layout and codec:

| row | `_SHARED` | `_PREV` | `_PREVOFF` |
|---|---|---|---|
| interleaved FastPFOR | 12.120.751 | +0,19% | +0,89% |
| `_SEG` FastPFOR | 11.285.111 | +0,17% | +0,99% |
| interleaved Varint | 12.940.767 | +0,17% | +0,86% |

All-in mean bits 5,51 / 5,52 / 5,61. The window earns its index bits; without the flag every miss pays a
full offset. `_PREV` smallest on 98 buffers, `_PREVOFF` on 1. Code removed.
