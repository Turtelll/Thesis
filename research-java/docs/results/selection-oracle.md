# Would a tile ever pick one of these? — per-buffer selection oracle

**Status:** cross-cutting analysis, not one experiment
**Corpus:** previous Munich corpus (`munich.mbtiles`) — see [reordering.md](reordering.md)
for the corpus-comparability banner

*(This section is mainly about the [delta-of-delta](failed/delta-of-delta.md) family. For
`SEGMENT_PAIR` the answer is different — see below.)*

No — which is why the delta-of-delta variants were removed. The tables in other results
files choose one encoding per corpus or per layer, but a real tile selects per stream —
`StreamMetadata` already carries the technique per stream, so an encoder may legitimately
encode each buffer with whichever candidate is smallest. The benchmark prints that as a
**per-buffer selection oracle**:

```
always DELTA_ZIGZAG_FASTPFOR        13.399.028
best baseline per buffer     13.325.818   -0,5%
best of all per buffer       13.307.179   -0,7%
```

A perfect selector over all eight encodings saves **0,7%** against simply always using
`DELTA_ZIGZAG_FASTPFOR`, and the six research encodings contribute only **0,14%**
(18.639 bytes) of that on top of selecting between the two baselines.

Win counts alone would have been misleading here: `DOD_RESET_VARINT` is the smallest
encoding on 911 buffers, which sounds meaningful until you see it saves ~17 bytes each
(94 at most) on buffers of median 110 vertices. It wins often, by nothing.

The oracle charges nothing for signalling and never guesses wrong, so 0,14% is a
ceiling, not an estimate. A real selector needs signalling per stream — at even one byte
per stream that is ~8,4 KB across 8.366 buffers, against 18,6 KB saved — plus a decoder
for every candidate it might pick. **None of the research encodings earn a place in a
tile**, per-buffer selection included.

The practical takeaway for later predictor work in `ideen.md`: **structural reset points
are not free.** A predictor that restarts per part or ring has to beat the baseline by
enough to pay for its own seeds first, which on this corpus is a ~10% handicap before
the predictor does anything.

`SEGMENT_PAIR` clears that bar without ever paying it: it has no reset seeds, because it
never restarts a prediction — it only changes which residuals share a FastPFOR block. Its
oracle line is also a different shape from the family above, since it does not need to be
selected *against* the baseline to be worth having:

```
always SEGMENT_PAIR_FASTPFOR        12.795.818
best baseline per buffer     13.325.818   +4,1%
best of all per buffer       12.757.039   -0,3%
```

Always using it beats a perfect free selector over both baselines by 4,1%, and a perfect
selector that also includes it improves on always-using-it by only 0,3%. That is the
opposite of the delta-of-delta result: the win does not depend on per-stream signalling,
so it needs no selector and no extra header — one technique, applied unconditionally.

**On `building`, the same conclusion holds even more strongly.** The current best
(`SEGMENT_QUAD_CORNER_FASTPFOR_REORDER_REV`) leaves a research-oracle gap of only
**0,08%** (2.534.515 always vs 2.532.507 oracle, 2.008 bytes across 311 buffers) — far
inside the "oracle within ~5% of Mode A" threshold that would make per-feature selection
worth its own signalling. **No Mode-B (per-feature enum) selector was built for this
layer.**
