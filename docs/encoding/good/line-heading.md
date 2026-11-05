# Heading frame (`LINE_CONTINUATION_HEADING`, `LINE_CONTINUATION_FRAME_HEADING`)

> **Verdict: Kept.** `transportation` -6,89% gzip with frame + heading against -5,23% for plain continuation;
> with references and chaining the `transportation` default.

## Encoding target
transportation, transportation_name, waterway, boundary (LineString).

## Idea
After line continuation, the residual of a continued vertex is "how much the road bent and how much the step
length changed", but still expressed in tile x/y. Rotate it into the direction the road was heading: then x is
the along-track change and y the sideways bend, two quantities that behave differently and are each usually
small. The direction is the incoming edge, which the decoder already has.

## Intention
In tile axes, the meaning of a continuation residual depends on where the road points: on a road heading
north-east, a pure bend shows up in both x and y. In the road's own frame every vertex puts the same physical
quantity in the same component, so the compressor sees one stream of "step-length changes" and one of
"bends", each with a tighter distribution. It complements the [line frame prior](line-frame.md), which handles
the vertices continuation does not predict; the two touch disjoint vertices, so the gains should add.

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

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`, `transportation`, pipeline with
`HILBERT_CENTROID_ROT_MP`, class streams, escape, planes): **`transportation` -6,89%** with
`LINE_CONTINUATION_FRAME_HEADING` against **-5,23%** for `LINE_CONTINUATION` (heading alone -5,92%).
Tileset (per-layer default): -8,40% → -8,64%.

**Decisive case for inclusion:** frame and heading add (0,97 + 0,69 = 1,66 points), the gain holds on all
three corpora and under all three compressors, and nothing is signalled. With dictionary references and
`CHAIN` it is today's `forLayer("transportation")` (geometry -12,06% gzip), also of `transportation_name`,
`waterway` and `boundary`. The cost is decode time (4,92x, one `atan2` per continued vertex). The heading
alone (`LINE_CONTINUATION_HEADING`, header code 10) is retired: the tile oracle picked it on 2,5% of buffers, drop cost 40 B.

Supporting figures (same metric, `transportation`):

| corpus | `LINE_CONTINUATION` | `_FRAME` | `_HEADING` | `_FRAME_HEADING` |
|---|---:|---:|---:|---:|
| Munich (current) gzip | -5,23% | -6,20% | -5,92% | **-6,89%** |
| Munich Brotli 11 | -9,57% | -10,68% | -10,32% | **-11,55%** |
| Munich zstd 19 | -6,53% | -7,56% | -7,20% | **-8,25%** |
| Chicago gzip | -1,33% | -1,50% | -1,60% | **-1,78%** |
| Overture gzip | -5,63% | -6,33% | -6,68% | **-7,47%** |

- `transportation_name` Munich -4,49% → -6,76%.
- Tileset per-layer default -8,40% → -8,64% gzip / -14,57% → -14,82% Brotli / -10,54% zstd. Switching only
  `transportation` made the tileset *worse* (-8,40% → -8,17%): `transportation` and `transportation_name`
  share geometry inside the tile's gzip window, so they are switched together.
- Decode 4,92x `DELTA_ZIGZAG_FASTPFOR` (one `atan2` per continued vertex, ~0,1 ms per buffer).
- `LINE_CONTINUATION_SHARED_FRAME_HEADING` + `CHAIN` geometry alone: -12,06% gzip / -15,87% Brotli / -13,09%
  zstd. `LINE_CONTINUATION_FRAME_HEADING` is the default of `mountain_peak`.

Source: `research-java/docs/results/line-frame-prior.md`, `tile-oracle.md`.
