# Canonical ring start (`QUAD_CORNER_SHARED_RING_CANONICAL`, `RING_CANONICAL_THEN_FEATURE`)

> **Verdict: Out of scope (superseded in the consolidated pipeline).** -7,34% gzip on the tileset with feature
> chaining, 0,07 points better than chaining alone; the pipeline's ring rotation (`_ROT`) does the job better.

## Encoding target
building (Polygon).

## Idea
A ring's start vertex is arbitrary. Rotating every ring to a canonical start makes repeated building shapes
look identical in the stream, and if features are chained afterwards, the chain follows the start vertices
that are actually emitted.

## Intention
Many buildings repeat the same shape; with an arbitrary start vertex, two copies emit their edges in a
different cyclic order, so the byte strings differ. A canonical start (lowest x, then lowest y) makes the
interior residuals of a translated copy identical, which LZ77 can match, and gives the
[shared references](../good/shared-ring-vertices.md) and feature chaining a consistent start to work from.
Ring rotation does not change the geometry, so nothing is signalled.

## How the encoding works
- Every polygon ring is cyclically rotated (winding kept) to a canonical start vertex; not transmitted.
- `RING_CANONICAL_THEN_FEATURE`: rotate every ring, then nearest-endpoint feature chaining over the rotated
  starts.
- Encoded with `QUAD_CORNER_SHARED` over segmented split varints.

## How the decoder works
No change: rotated rings and the chained feature order are emitted as stored (ring rotation and feature order
are geometry-equivalent); the shared ring decoder runs unchanged.

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`, old encoding set, segmented split
varints): **`QUAD_CORNER_SHARED_RING_CANONICAL_REORDER` -7,34%** against **-7,27%** for feature chaining alone
(`QUAD_CORNER_SHARED_REORDER`); without chaining -5,67% vs -5,58%. (gzip figures computed from the byte totals.)

**Decisive case — why it is not in the pipeline:** the canonical start alone is worth 7.713 B (0,07%) over
feature chaining, all of it on `building`, and the pipeline already rotates rings for a different, stronger
reason: [Hilbert reorder](../good/hilbert-reorder.md)'s `_ROT` starts each ring at the vertex nearest the
previous run's end. On `building` that gives 3.214.369 B gzip against 3.276.195 B for the old canonical +
chain + frame row. So the canonical start was frozen in the consolidation (code at git tag
`pre-consolidation`); `CANON_CHAIN` stays only as a reference row.

Supporting figures (*smallest of gzip/Brotli/zstd*, current corpus):

| row | whole tileset | building |
|---|---|---|
| `QUAD_CORNER_SHARED` | -7,86% | |
| `QUAD_CORNER_SHARED_RING_CANONICAL` | -7,99% | -13,62% |
| `QUAD_CORNER_SHARED_REORDER` | -9,39% | -13,36% |
| **`QUAD_CORNER_SHARED_RING_CANONICAL_REORDER`** (rotate, then chain) | **-9,50%** | **-13,79%** |
| `..._REORDER_RING_CANONICAL` (chain, then rotate) | -9,44% | -13,79% |

- On plain interleaved Varint the canonical start was only -0,12% gzip; it needs the shared references.
- With the frame: `QUAD_CORNER_SHARED_FRAME_RING_CANONICAL_REORDER` -8,19% gzip / -15,21% Brotli / -9,92%
  zstd, the best row of the old encoding set; the pipeline row that replaced it is -9,95% gzip.

Source: `research-java/docs/results/revisit-varint-split.md`, `general-purpose-compression.md`,
`research-java/docs/consolidation-inventory.md`.
