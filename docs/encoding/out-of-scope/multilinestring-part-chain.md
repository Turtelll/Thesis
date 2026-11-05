# MultiLineString part chaining (`_PART_CHAIN`, `_PART_CHAIN_REV`, `CHAIN_CONTINUATION`)

> **Verdict: Out of scope (workshop only, never promoted).** `transportation` -4,85% gzip in the workshop, but
> the joins it was built for never occur, and its remaining gain is the restart order that feature chaining
> already provides.

## Encoding target
transportation (MultiLineString).

## Idea
Part order inside a MultiLineString carries no meaning. If parts that meet at a shared vertex were stored end
to start, each joined start would be a `(0, 0)` delta and continuation could run straight through the join.
Even without joins, ordering the parts so each starts near the previous one's end shortens the jumps.

## Intention
Feature chaining reorders whole features; inside a MultiLineString the parts keep the tiler's order. Chaining
the parts too should remove the jump between parts and let line continuation run across them.

## How the encoding works
`GeometryReorder.PART_CHAIN` / `_REV`, feature order kept, only MultiLineStrings with ≥ 2 parts; only the
run-length stream is permuted within each feature:
1. **Join:** from the current exit take an unused part entering exactly there (`_REV`: also one ending there,
   reversed); among several the one whose 2nd vertex is nearest the continued previous edge.
2. **Restart:** otherwise the unused entry nearest the exit (Manhattan), preferring chain starts (odd degree
   with `_REV`).
3. `CHAIN_CONTINUATION`: a part whose start repeats the previous vertex is a continuation; its 2nd vertex is
   predicted `2·p[start] - p[start-2]`, later vertices by continuation regardless of part length. Read off the
   decoded prefix, nothing signalled.

## How the decoder works
No change for the order (the permuted part lengths are in the topology). `CHAIN_CONTINUATION` detects a join
as "part start equals previous vertex" on the decoded prefix and applies the continuation prediction.

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`, `transportation`, workshop,
interleaved Varint): **`LINE_CONTINUATION_PART_CHAIN_REV` -4,85%** against **`LINE_CONTINUATION` -1,37%**;
without reversal -3,24%.

**Decisive case — why it is out of scope:** the joins never happen: 0 of 220.720 part boundaries start on the
previous part's end, before or after chaining (the tiler already merges them), so `CHAIN_CONTINUATION` adds
nothing. The whole gain is the restart order (mean part-start jump 650 → 514 → 438), and 1,6 points of it
come from `_REV`, which reverses line direction. The workshop rows were never promoted to the benchmark or the
pipeline.

Supporting figures (same metric, workshop, interleaved Varint):

| row | all | z14 |
|---|---|---|
| `LINE_CONTINUATION` | -1,37% | -1,45% |
| `DELTA_PART_CHAIN` / `_REV` | -1,89% / -3,49% | -1,88% / -3,52% |
| `LINE_CONTINUATION_PART_CHAIN` / `_REV` | -3,24% / **-4,85%** | -3,30% / **-4,96%** |
| `CHAIN_CONTINUATION_PART_CHAIN_REV` | -4,86% | -4,92% |
| `REORDER_CHAIN` (feature chaining) | -3,64% | -2,58% |

Source: `research-java/docs/results/line-part-chain.md`.
