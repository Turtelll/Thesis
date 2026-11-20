# Shared line vertices

**Code names:** `LINE_CONTINUATION_SHARED_RAW` (code 28, Rust: the index a slot among the earlier parts' raw
vertices); before it `LINE_CONTINUATION_SHARED` (code 12) and `LINE_CONTINUATION_SHARED_DICT`, which index the
distinct positions

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
buffer, so the index reaches every vertex of every earlier line part; it grows with log2 of their number. Flipping a
part whose end is the junction is free in geometry terms (a line part read backwards covers the same points)
but drops the line's direction.

## How the encoding works
`LineContinuation` plus references (`research-rust/mlt-pipeline/src/refs.rs`, `encoder/reference/LineReferences.java`).
Per line part, in buffer order:
1. **Part reversal (not transmitted):** if the end is a candidate and the start is not, reverse the part
   (changes line direction).
2. **Entry flag** at the part start, only if an earlier line part exists: 1 bit; on a hit a
   `ceil(log2 slots)`-bit index replaces the start residual.
3. **Exit flag** at the part's last vertex: same, flag + index.
4. **Slots:** every vertex of every earlier line part in buffer order, duplicates included, append-only; a
   position several parts hold (a junction) is addressed by its first slot. Codes 12 and `_DICT` indexed the
   distinct positions instead; see "Index" below.
5. Rings and points in the column keep plain residuals and are never candidates. A buffer the references do
   not shrink is written under the plain predictor.

Payload: header, shared-reference block (flag bits, index bits, per-stream value counts), residual streams
(segmented order starts-last).

```
part P1: a b c         slots after P1: 0 a, 1 b, 2 c
part P2: d e c   →  reversed to c e d:  entry=1 slot 2 (c) | residual e | exit=0, residual d
```

## How the decoder works
1. Read flags, indices, value counts and the residual streams.
2. Keep the vertex index of every slot, appended once per completed line part; no position table.
3. At each part start after the first line part read the entry flag: on 1 emit the vertex of slot `index`, else
   decode the residual. Same at the part's last vertex (exit flag); the last vertex is known from the part
   lengths.
4. All other vertices: line continuation prediction + residual. Reversed parts are emitted reversed.

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`, `transportation`, pipeline,
`LINE_CONTINUATION_SHARED_FRAME_HEADING` vs the same predictor without references): **`CHAIN` -9,19% →
-12,06%**, Hilbert -6,89% → -11,59%, no reorder -4,97% → -10,75%.

**Decisive case for inclusion:** 3 to 6 points on `transportation` under every order, the largest single gain
on the line layers. On the joint tile (geometry + properties, ids regenerated) `CHAIN` + references is -5,14%
gzip against the previous default. Today `for_layer("transportation")` = `CHAIN` + `LINE_CONTINUATION_SHARED_RAW`
(the line frame is gone from the grid). The cheaper [marked-table variant](../out-of-scope/line-shared-marked.md)
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

## Index: raw slots instead of distinct positions
Code 12 indexed the earlier parts' distinct positions. The decoder could only resolve that index by keeping a
position table of every line vertex (a lookup and an insert per vertex), although lines have no follow flag that
would need one. `LINE_CONTINUATION_SHARED_RAW` (code 28, Rust) indexes the raw slots, as the ring references do
since codes 26 and 27; the table is gone from the decoder. Everything else is unchanged.

- Size: Munich, per-layer default +0,046% gzip (15.836.613 to 15.843.951 B), tile oracle +0,043% (15.173.753 to
  15.180.223 B).
- Decode: the references' overhead over `LINE_CONTINUATION` on `transportation` falls from 17,4 / 16,3 to
  10,0 / 9,4 ns per vertex (-42%, codec alone, two runs); whole tile 4% (per-layer default) and 3% (tile oracle) less.
- The headline figures above were measured with the distinct index.

Source: `research-java/docs/results/shared-decode-time.md`.
