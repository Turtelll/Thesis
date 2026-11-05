# Shared line vertices via dictionary

**Code names:** `LINE_CONTINUATION_SHARED_DICT`

> **Verdict: Kept.** In the pipeline the references take `transportation` from -9,19% to -12,06% gzip
> (`CHAIN` order); part of the `transportation` default.

## Encoding target
transportation, waterway, transportation_name (LineString / MultiLineString).

## Idea
Roads meet at junctions: 38,7% of `transportation` vertices sit at a position that occurs more than once in
the buffer, and 74% of those are the start or end of a line part. When a part starts or ends where an earlier
part already had a vertex, point at that earlier position instead of coding a long jump across the tile. If
only the end of a part is a junction, flip the part so the junction comes first.

## Intention
A part start is the most expensive residual of a line buffer (a jump), and at a junction it repeats a position
the decoder already holds. A flag per part end plus an index into all earlier line positions should cost far
less than the jump. Unlike ring references, which only look back 4 rings, junctions can be anywhere in the
buffer, so the dictionary keeps every earlier line position; the index grows with log2 of its size. Flipping a
part whose end is the junction is free in geometry terms (a line part read backwards covers the same points)
but drops the line's direction.

## How the encoding works
`LineContinuation` plus references (`encoder/reference/LineReferences.java`). Per line part, in buffer
order:
1. **Part reversal (not transmitted):** if the end is a candidate and the start is not, reverse the part
   (changes line direction).
2. **Entry flag** at the part start, only if the dictionary is non-empty: 1 bit; on a hit a
   `ceil(log2 dictionarySize)`-bit index replaces the start residual.
3. **Exit flag** at the part's last vertex: same, flag + index.
4. **Dictionary:** every distinct position of every earlier line part, first-seen order, flat index,
   append-only.
5. Rings and points in the column keep plain residuals and are never candidates. A buffer the references do
   not shrink is written under the plain predictor.

Payload: header, shared-reference block (flag bits, index bits, per-stream value counts), residual streams
(segmented order starts-last).

```
part P1: a b c         dictionary after P1: {a, b, c}
part P2: d e c   →  reversed to c e d:  entry=1 idx(c) | residual e | exit=0, residual d
```

## How the decoder works
1. Read flags, indices, value counts and the residual streams.
2. Keep a position table of every distinct decoded line-part position (the dictionary).
3. At each part start with a non-empty dictionary read the entry flag: on 1 emit `dictionary[index]`, else
   decode the residual. Same at the part's last vertex (exit flag); the last vertex is known from the part
   lengths.
4. All other vertices: line continuation prediction + residual.
5. Insert every decoded line vertex into the dictionary. Reversed parts are emitted reversed.

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`, `transportation`, pipeline,
`LINE_CONTINUATION_SHARED_FRAME_HEADING` vs the same predictor without references): **`CHAIN` -9,19% →
-12,06%**, Hilbert -6,89% → -11,59%, no reorder -4,97% → -10,75%.

**Decisive case for inclusion:** 3 to 6 points on `transportation` under every order, the largest single gain
on the line layers. On the joint tile (geometry + properties, ids regenerated) `CHAIN` + references is -5,14%
gzip against the previous default, so `forLayer("transportation")` = `CHAIN` +
`LINE_CONTINUATION_SHARED_FRAME_HEADING`. The cheaper [marked-table variant](../out-of-scope/line-shared-marked.md)
loses on every line layer.

Supporting figures:

- Older encoding set, same metric: `LINE_CONTINUATION_SHARED_DICT_VARINT` -1,27%,
  `_VARINT_SPLIT` -2,27% tileset; `transportation` with the old reorder `_SHARED_DICT` -9,49%.
- *Not under the headline metric* — current corpus, FastPFOR era, bytes vs same codec/layout
  `LINE_CONTINUATION`:

| segment | `_DICT_FASTPFOR` | `_DICT_VARINT` | `_DICT_SEG` | `_DICT_SEG` vs `DELTA_ZIGZAG_FASTPFOR` |
|---|---|---|---|---|
| corpus | -2,25% | -1,84% | -1,74% | -6,26% |
| transportation | -8,48% | -7,32% | -6,11% | -12,58% |
| waterway | -6,01% | -5,29% | -6,96% | -9,42% |
| transportation_name | -3,61% | -3,10% | -2,62% | -6,25% |

- Other corpora (`transportation`, `_DICT_FASTPFOR` / `_SEG`): Chicago -6,54% / -5,33%, Tokyo -4,70% / -2,84%.
- By zoom largest at z12: -12,95% / -9,67%.
- All-in mean bits only -3,0% (6,19 vs 6,39): bytes are the right judge.
- Decode `_SEG` 1,44x–1,47x baseline (after dropping follow state); 1,85x/1,59x in a later run.

Source: `research-java/docs/results/line-shared-vertices.md`, `general-purpose-compression.md`.
