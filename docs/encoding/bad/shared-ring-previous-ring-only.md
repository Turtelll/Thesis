# Shared ring vertices, previous ring only

**Code names:** `QUAD_CORNER_SHARED_PREV`, `_PREVOFF`

> **Verdict: Rejected.** +0,17% (`_PREV`) and +0,86% (`_PREVOFF`) against the 4-ring window, same layout
> (uncompressed Varint bytes); not measured under the headline metric.

## Encoding target
building, landcover, landuse (Polygon).

## Idea
46,7% of shared building positions come from the immediately previous polygon. So drop the 4-ring window and
address only the previous ring: a narrower index (an offset into one ring), and optionally no entry flag at
all.

## Intention
Nearly half the hits come from one ring, so a smaller candidate set should make the index narrower for those,
and dropping the entry flag (`_PREVOFF`) would save one bit per ring.

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

## Results
**Headline:** *not measured under the headline metric* (current corpus, uncompressed bytes vs the 4-ring
window `QUAD_CORNER_SHARED`, same layout and codec): interleaved Varint **`_PREV` +0,17%, `_PREVOFF` +0,86%**.

**Decisive case against:** the other 53,3% of hits are lost or need a full residual, and that costs more than
the narrower index saves: the window earns its index bits. Without the entry flag every miss pays a full
offset. It is larger under every codec and layout tried; `_PREV` was smallest on 98 buffers, `_PREVOFF` on 1.
Code removed.

Supporting figures (*bytes vs the 4-ring window*):

| row | `_SHARED` | `_PREV` | `_PREVOFF` |
|---|---|---|---|
| interleaved FastPFOR | 12.120.751 | +0,19% | +0,89% |
| `_SEG` FastPFOR | 11.285.111 | +0,17% | +0,99% |
| interleaved Varint | 12.940.767 | +0,17% | +0,86% |

- All-in mean bits 5,51 / 5,52 / 5,61.

Source: `research-java/docs/results/shared-vertices.md`.
