# MultiLineString part chaining (`_PART_CHAIN`, `_PART_CHAIN_REV`, `CHAIN_CONTINUATION`)

## encoding target:
transportation (MultiLineString)

## Idea of the encoding
Part order inside a MultiLineString carries no meaning. If parts that meet at a shared vertex were stored end
to start, each joined start would be a `(0, 0)` delta and continuation could run straight through the join.
Even without joins, ordering the parts so each starts near the previous one's end shortens the jumps.

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

## results
Current corpus `munich-plantiler`, `transportation`, workshop (interleaved Varint), per-tile gzip vs
`DELTA_ZIGZAG_VARINT`:

| row | all | z14 |
|---|---|---|
| `LINE_CONTINUATION` | -1,37% | -1,45% |
| `DELTA_PART_CHAIN` / `_REV` | -1,89% / -3,49% | -1,88% / -3,52% |
| `LINE_CONTINUATION_PART_CHAIN` / `_REV` | -3,24% / **-4,85%** | -3,30% / **-4,96%** |
| `CHAIN_CONTINUATION_PART_CHAIN_REV` | -4,86% | -4,92% |
| `REORDER_CHAIN` (feature chaining) | -3,64% | -2,58% |

- **The joins never happen:** 0 of 220.720 part boundaries start on the previous part's end, before or after
  chaining (the tiler already merges them). `CHAIN_CONTINUATION` adds nothing. The whole gain is the restart
  order: mean part-start jump 650 → 514 → 438.
- Workshop only, never promoted to the benchmark or the pipeline; `_REV` reverses line direction (worth 1,6
  points).
