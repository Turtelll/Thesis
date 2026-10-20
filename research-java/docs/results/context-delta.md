# Context-adaptive delta for LineStrings — `CONTEXT_DELTA`

> **Revisited under split varints:** `CONTEXT_DELTA_T2_M32_VARINT_SPLIT` is +1,54% whole
> tileset (smallest of gzip/Brotli/zstd) and behind the current rows on every layer; retired.
> See [revisit-varint-split.md](revisit-varint-split.md).

> **Revisited under split varints** ([revisit-varint-split.md](revisit-varint-split.md)):
> `CONTEXT_DELTA_T2_M32_VARINT_SPLIT` is +1,54% whole tileset against the baseline (best layer
> `transportation`, -1,38%, behind `LINE_CONTINUATION` at -3,08%); retired.

> **Revisited over split varints** ([revisit-varint-split.md](revisit-varint-split.md)):
> `CONTEXT_DELTA_T2_M32_VARINT_SPLIT` is +1,54% per tile (smallest of gzip/Brotli/zstd) against
> `DELTA_ZIGZAG_VARINT`, behind the baseline on `building`; retired.

**Status:** registered — weak win
**Effect:** -0,4% to -1,8% (loses to SEGMENT_PAIR by a factor of six)
**Corpus:** measured on both corpora — see [reordering.md](reordering.md) for the
corpus-comparability banner (Munich), plus NY `transportation`

Answers the question `ideen.md` leaves open as *Idee 2*, and the answer is **no**.

`ideen.md` measured that edge continuation (`pred = p[i-1] + (p[i-1]-p[i-2])`) beats the baseline
by **-10,1% in mean zigzag bits** on NY z14 with nothing signalled, but is **+1,9% as bytes** under
FastPFOR: it wins the median (5,91 -> 5,31 bits) and widens the tail (p99,9 rises 11 -> 12), and
FastPFOR prices the block maximum. The open question was whether the positions where continuation
loses are recognizable from a *derivable* feature — if so, a gate would suppress the tail and no
rANS would be needed.

**What it does.** A run-length state machine over already-reconstructed vertices, the `c = min(run,3)`
shape `ideen.md` specifies. A step is *smooth* when the turn between the last two edges is shallow
by an integer cross/dot test (`|cross| <= |dot| >> turnShift`, `dot > 0` — no floating point). The
context picks the predictor (`c=0` plain delta, `c>=1` the mean of the last `c` edges) **and** the
FastPFOR region the residual lands in, so a rough population never sets a smooth one's block width.
A mispredicted vertex (residual wider than `missThreshold`) collapses the run — the "Streikzähler"
of `ideen.md`'s decoder state. Nothing is signalled per vertex; only the four region *counts* are
written, since the decoder cannot derive region offsets from topology alone (see below).

**Measured on both corpora, LINESTRING:**

```
                            Munich LINESTRING        NY transportation
DELTA_ZIGZAG_FASTPFOR            5.992.732  ref         16.536.440  ref
CONTEXT_DELTA_T2_M8              5.968.948  -0,4%       16.410.704  -0,8%
CONTEXT_DELTA_T2_M32             5.925.249  -1,1%       16.242.958  -1,8%
SEGMENT_PAIR_FASTPFOR             5.614.175  -6,3%       15.386.675  -7,0%
REORDER_REV_SEGMENT_PAIR          4.668.030 -22,1%       13.625.028 -17,6%
```

**The gate works; the predictor is not worth gating.** It is *not* the +1,9% regression that
unconditional continuation produced — the gating and the per-context regions did suppress the tail,
so the mechanism does what it was designed to do. It simply buys very little, and it loses to
`SEGMENT_PAIR` by a factor of six.

**Why, measured directly** (NY z14, 300 buffers, share of vertices per context and mean residual
width in bits):

| `missThreshold` | c0 | c1 | c2 | c3 |
|---|---|---|---|---|
| 8 | 58,4% w=14,40 | 35,5% w=10,67 | **4,1%** w=9,05 | **1,9%** w=8,86 |
| 32 | 47,7% w=15,07 | 35,5% w=10,55 | 8,2% w=9,82 | 8,5% w=9,60 |
| unbounded | 34,3% w=16,09 | 33,5% w=10,67 | 10,9% w=11,22 | 21,4% w=11,30 |

**Only ~6% of vertices ever reach context 2 or 3.** The multi-edge smoothing that was supposed to
damp quantization noise almost never fires; 94% of the buffer sits in c0 (plain delta) or c1 (plain
continuation), so the encoding is essentially the baseline plus the continuation that was already
known to be worth nothing here. And the parameter cannot be tuned out of it: loosening
`missThreshold` grows c2/c3 but **their residuals widen in lockstep** (8,86 -> 11,30 bits), because
the vertices it admits are exactly the ones the predictor handles badly. There is no setting at
which a large *and* narrow population exists.

**The structural reason, and the limit of the whole approach.** The context of vertex `i` is read
*before* `i` is decoded, so it describes the run as of `p[i-1]` — the machine cannot know the line
turns at `i` until it has seen it turn. Every corner is therefore coded once at full context, with a
residual *wider* than a plain delta would have been, and only the following vertex is corrected.
That one-vertex lag is not an implementation detail to be fixed; it is what "derived from already
decoded data" means. A road at z14 turns often enough that the lag consumes the gain.

**One honest cost this pays that the other region-splitting encodings do not.** `SEGMENT_PAIR` and
`SEGMENT_QUAD_CORNER` classify each vertex from the topology streams, so a decoder computes every
region's offset before decoding anything. Here the context depends on the vertices being
reconstructed, so region sizes are not derivable — the four counts are written into the payload
(a handful of bytes, charged in `totalBytes()` via a second `StreamMetadata` header). This is why it
is an `Encoding` directly rather than a `VertexTransform`.

**Verdict.** Registered with its mechanism test so the negative need not be re-derived, same status
as [failed/right-angle-turn.md](failed/right-angle-turn.md). The per-buffer oracle picks a
`CONTEXT_DELTA` variant on 149 of 8.366 Munich buffers (~2,4 KB), so it is never worth shipping as a
whole-buffer encoding. **The trail now points back to `ideen.md`'s *Idee 1*:** an entropy coder
prices the median, a bit packer the maximum — continuation's gain is real in the distribution and
only invisible to FastPFOR, so rANS remains the one measured constellation where it could survive.
What this result adds is that the cheaper escape route is closed: the tail cannot be gated away by a
derivable rule, because the rule is one vertex too late. See
[rans-entropy-coder.md](rans-entropy-coder.md) for the rANS follow-up this pointed to.
