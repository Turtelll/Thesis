# Shared line vertices via dictionary (`LINE_CONTINUATION_SHARED_DICT`)

## encoding target:
transportation, waterway, transportation_name (LineString / MultiLineString)

## Idea of the encoding
Roads meet at junctions: 38,7% of `transportation` vertices sit at a position that occurs more than once in
the buffer, and 74% of those are the start or end of a line part. When a part starts or ends where an
earlier part already had a vertex, point at that earlier position instead of coding a long jump across the
tile. If only the end of a part is a junction, flip the part so the junction comes first.

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

## results
**Current corpus `munich-plantiler`, FastPFOR era**, bytes vs same codec/layout `LINE_CONTINUATION`:

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
- **OpenZL pipeline** (`LINE_CONTINUATION_SHARED_FRAME_HEADING`, Munich `transportation`, per tile gzip vs
  `DELTA_ZIGZAG_VARINT`): no reorder -4,97% → -10,75%, Hilbert -6,89% → -11,59%, `CHAIN` -9,19% → -12,06%.
  Joint tile (geometry + properties), ids regenerated: `CHAIN` + references -5,14% gzip vs previous default.
  Now `forLayer("transportation")` = `CHAIN` + `LINE_CONTINUATION_SHARED_FRAME_HEADING`.
- **General-purpose compression, `transportation` per tile gzip:** `_SHARED_DICT` -9,49%.
