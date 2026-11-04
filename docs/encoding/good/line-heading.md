# Heading frame (`LINE_CONTINUATION_HEADING`, `LINE_CONTINUATION_FRAME_HEADING`)

## encoding target:
transportation, transportation_name, waterway, boundary (LineString)

## Idea of the encoding
After line continuation, the residual of a continued vertex is "how much the road bent and how much the step
length changed", but still expressed in tile x/y. Rotate it into the direction the road was heading: then x
is the along-track change and y the sideways bend, two quantities that behave differently and are each
usually small. The direction is the incoming edge, which the decoder already has.

## How the encoding works
- **Continued vertices** (interior of a run ≥ 6, predicted `p[i-1] + (p[i-1] - p[i-2])`): the residual is
  rotated into the direction of `p[i-1] - p[i-2]`.
- **Rotation** (`forwardHeading/inverseHeading`): direction by `StrictMath.atan2`, split into whole quarter
  turns (exact `(x, y) → (y, -x)`) and a remainder in [-45°, 45°) quantized to 16 bits, rotated by the same
  three-shear integer rotation. A bijection for every edge; a zero edge leaves the residual unchanged.
- `LINE_CONTINUATION_HEADING`: heading only. `LINE_CONTINUATION_FRAME_HEADING`: also codes the plain edges in
  the neighbour frame (line frame); the two touch disjoint vertices.
- `LINE_CONTINUATION_SHARED_FRAME_HEADING`: plus dictionary references.

```
incoming edge heading 33°, continuation residual (tile axes) (4, 7)
→ rotated (along, side) ≈ (7, 4)  with along/side meaning fixed per vertex
```

## How the decoder works
For a continued vertex, compute the direction of the decoded edge `p[i-1] - p[i-2]`, read the residual,
apply the inverse quarter turn and inverse shear rotation, add to `2·p[i-1] - p[i-2]`. Plain edges decode
through the neighbour frame as in the line frame prior.

## results
Pipeline (`HILBERT_CENTROID_ROT_MP`, class streams, escape, planes), `transportation`, per tile vs
`DELTA_ZIGZAG_VARINT`:

| corpus | `LINE_CONTINUATION` | `_FRAME` | `_HEADING` | `_FRAME_HEADING` |
|---|---:|---:|---:|---:|
| Munich (current) gzip | -5,23% | -6,20% | -5,92% | **-6,89%** |
| Munich Brotli 11 | -9,57% | -10,68% | -10,32% | **-11,55%** |
| Munich zstd 19 | -6,53% | -7,56% | -7,20% | **-8,25%** |
| Chicago gzip | -1,33% | -1,50% | -1,60% | **-1,78%** |
| Overture gzip | -5,63% | -6,33% | -6,68% | **-7,47%** |

- Frame and heading add (Munich gzip 0,97 + 0,69 = 1,66 points). `transportation_name` Munich -4,49% → -6,76%.
- Whole-corpus per-layer default -8,40% → -8,64% gzip / -14,57% → -14,82% Brotli / -10,54% zstd. Switching
  only `transportation` made the corpus *worse* (-8,40% → -8,17%): `transportation` and `transportation_name`
  share geometry inside the tile's gzip window, so they are switched together.
- Decode 4,92x `DELTA_ZIGZAG_FASTPFOR` (one `atan2` per continued vertex, ~0,1 ms per buffer).
- `LINE_CONTINUATION_SHARED_FRAME_HEADING` + `CHAIN` is today's `forLayer("transportation")` (geometry alone
  -12,06% gzip / -15,87% Brotli / -13,09% zstd), also of `transportation_name`, `waterway`, `boundary`;
  `LINE_CONTINUATION_FRAME_HEADING` of `mountain_peak`. `LINE_CONTINUATION_HEADING` alone (code 10) retired by the
  tile oracle (2,5% of picks, drop cost 40 B).
