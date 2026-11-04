# Shared line vertices via marked table (`LINE_CONTINUATION_SHARED_MARKED`)

## encoding target:
transportation, waterway, transportation_name (LineString)

## Idea of the encoding
The dictionary variant makes the decoder keep a hash table of every line position, which costs decode time.
The encoder sees the whole buffer and knows which part ends recur later. So it can tell the decoder "remember
this one": only positions that will be referenced again enter the table. The decoder then just appends to an
array, and the index is narrower because every entry is used.

## How the encoding works
Same predictor, reversal, entry/exit flags and framing as the dictionary variant;
only the table differs:
- After every part entry or exit that was **not** referenced, a **remember bit**: 1 if the position is a
  line part end again later in the buffer. On 1 the position is appended to the table at once (so a closed
  line's exit can reference its own entry).
- Interior vertices never enter the table.

## How the decoder works
1. Per part start/end with a non-empty table: read the flag; on 1 read the index and emit `table[index]`.
2. Otherwise decode the residual, then read the remember bit and on 1 append the position to the table.
3. No position table, no per-vertex dedup lookup.

## results
Current corpus `munich-plantiler`, bytes vs same-codec `LINE_CONTINUATION`:

| segment | `_DICT_FASTPFOR` | `_MARKED_FASTPFOR` | `_DICT_SEG` | `_MARKED_SEG` |
|---|---|---|---|---|
| corpus | -2,25% | -1,45% | -1,74% | -1,08% |
| transportation | -8,48% | -5,61% | -6,11% | -3,72% |
| waterway | -6,01% | -5,17% | -6,96% | -6,22% |
| transportation_name | -3,61% | -1,53% | -2,62% | -0,97% |
| boundary | -0,34% | +0,33% | -1,15% | -0,51% |

- Loses to `_DICT` on every line layer (+3,13% / +2,55% on `transportation`) but beats plain continuation.
- Decode vs `DELTA_ZIGZAG_FASTPFOR`: `_MARKED_SEG` 1,15x / 1,24x vs `_DICT_SEG` 1,85x / 1,59x. The choice if
  decode time is binding. Not in the OpenZL pipeline.
