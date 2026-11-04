# Road chaining (`LINE_CONTINUATION_ROAD_CHAIN`)

## encoding target:
transportation (LineString)

## Idea of the encoding
One road that runs in one direction is stored as many short features. Each feature pays a start jump and
is too short for continuation to apply. Put the features that meet end to start next to each other, then
treat the whole chain as one road: the junction vertex is a repeat (drop it), and the continuation
predictor carries straight through the old feature boundary. No feature is reversed, so direction
survives.

## How the encoding works
1. **`GeometryReorder.ROAD_CHAIN`** (pre-pass, not transmitted, forward only): from the current exit take an
   unused feature that starts exactly there (the straightest by continuation residual of its 2nd vertex if
   several); else restart at the unused feature nearest the exit, preferring one no remaining feature
   arrives at. MultiLineStrings keep their part order; polygons follow the lines; columns with points are
   left alone.
2. **`RoadChainEncoding`:** one bit per line part that has a line part before it: 1 = starts at the previous
   part's last vertex. A joined part's first vertex is not transmitted.
3. A *road* = maximal run of joined parts. `LineContinuation` classifies over roads (start, 2nd vertex plain,
   3rd+ continuation if the road has ≥ 6 vertices).
4. Mode byte; plain fallback if the joined payload is not smaller.

```
features A: a0 a1 a2    B: a2 b1 b2     (B starts at A's end)
written:   a0 a1 a2 | join=1 | b1 b2     b1 predicted as continuation 2·a2 - a1
```

## How the decoder works
Read the mode byte and the join bitmap. Walk the parts (lengths from topology): for a joined part insert the
previous part's last vertex as its first vertex and continue decoding the road with continuation prediction
across the join; for an unjoined part start a new road.

## results
Current corpus `munich-plantiler` and Chicago, `transportation`, per-tile gzip vs `DELTA_ZIGZAG_VARINT`:

| row | Munich | Chicago |
|---|---|---|
| `LINE_CONTINUATION` | -3,47% | -0,94% |
| `_REORDER` (nearest-endpoint, no joins) | -7,83% | -6,40% |
| `_ROAD_ORDER` (road order, no joins) | -8,07% | -6,75% |
| **`_ROAD_CHAIN`** | **-8,38%** | **-7,04%** |
| `_ROAD_CHAIN_GATE4` | -8,31% | -6,54% |
| `_SHARED_DICT` | -9,49% | -5,69% |

- Order 4,4 / 5,5 points, road order 0,24 / 0,35, joins 0,31 / 0,29.
- By zoom: Munich z12 -18,6%, z13 -11,6% (smallest row there), z14 -6,9% (`_SHARED_DICT` -9,3%).
- Probe: 10,61% / 11,78% of line vertices in roads of 2+ features; 1,15 features per road (Munich).
- Smallest row on Chicago; oracle on Munich picks it on 32 of 354 buffers. Not combined with `_SHARED_DICT`.
- **Retired** in the consolidation (code at git tag `pre-consolidation`); the pipeline has no road chaining.
