# Line continuation (`LINE_CONTINUATION`)

> **Verdict: Kept** as the base of every line predictor. `transportation` -4,88% gzip against -3,82% for
> plain delta; as layer default superseded by its frame, heading and dictionary variants.

## Encoding target
transportation, transportation_name, waterway (LineString / MultiLineString); polygons and points pass
through as plain delta.

## Idea
A road usually keeps going in roughly the direction it was heading. So the best guess for the next vertex is
"continue the last edge straight on". Store only how far the real vertex deviates from that straight-on point.
Building outlines (right angles) would get worse with this guess, so it is applied only to line geometries,
and only to lines long enough for the guess to help.

## Intention
Plain delta already makes consecutive road vertices small, but on a long, gently curving road each edge is
roughly the same as the previous one, so the delta keeps repeating a sizeable vector. Subtracting the previous
edge should leave only the bend and the change in step length. The gate (line runs only, at least 4 vertices)
exists because on corners and short parts the guess is worse than plain delta; geometry type and run length
come from the topology, so the gate costs nothing.

## How the encoding works
Mechanism: `encoder/prediction/mechanism/LineContinuation.java`. The geometry type of each run is found
by walking `geometryTypes`/`numGeometries`/`numParts`/`numRings`. Three classes:

1. **part start** — first vertex of each run: `p[i] - p[i-1]`.
2. **line interior** — 3rd and later vertex of a LineString/MultiLineString run with at least 4 vertices
   (`MIN_LINE_RUN_LENGTH = 4`): `pred = p[i-1] + (p[i-1] - p[i-2])`, residual `p[i] - pred`.
3. **interior** — 2nd vertex of any run, every vertex of a line run shorter than 4, every ring and point
   vertex: plain delta.

Residuals are zigzag-mapped, one stream per class under `CLASS` segmentation. Nothing is signalled, no
resets.

```
line run p0 p1 p2 p3 p4:
residual  p0-q   p1-p0   p2-(2p1-p0)   p3-(2p2-p1)   p4-(2p3-p2)
class     start  interior  line         line          line
```

## How the decoder works
1. Rebuild the run list and each run's geometry type from the topology streams, classify every vertex
   exactly as the encoder did.
2. Read each class stream with its own cursor.
3. Walk the vertices: start/interior → `p[i-1] + r`; line interior → `2·p[i-1] - p[i-2] + r`, both
   predecessors already decoded.

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`, pipeline step S2, every other stage
fixed): **`transportation` -4,88%** (plain `DELTA` -3,82%); tileset -3,83% (plain `DELTA` -3,97%: the tileset
is dominated by polygons, where the encoding is plain delta).

**Decisive case for inclusion:** it is the only line predictor that pays without any signalling, and it is
additive with everything built on top of it (frame, heading, dictionary references, chaining); those variants
are the `transportation` default today. On its own it is the predictor of `water_name` and `housenumber` in
the [per-layer default](per-layer-composition.md).

Supporting figures:

- Pipeline S8 stack: `transportation` -5,23%.
- *Not under the headline metric* — current corpus, FastPFOR era: mean residual bits -0,7% corpus, -2,1%
  `transportation`; bytes interleaved -0,1% on `transportation`; segmented (`LINE_CONTINUATION_SEG_FASTPFOR`)
  -6,8% `transportation` (beats `PART_START_SEG` -6,5%); with `--reorder feature-rev` -5,2%.
- Gates (mean bits): ungated continuation on POLYGON +11,09%, LINESTRING -3,58%; gated -1,08% globally. By
  part length: 3 vertices +1,13%, 4–5 -1,52%, 11–20 -6,77%, 21–50 -8,27%. Wins on 9 of 10 road classes
  (`service` +0,78% the exception).
- Per zoom: -1,4% to -4,9% bits on every zoom; FastPFOR bytes -0,3% to -2,3% z4–z11, ~-0,1% z12–z14.
  Chicago: +0,2% bits / +0,3% bytes (grid streets).
- Superseded on `transportation` by [shared line vertices via dictionary](line-shared-dictionary.md),
  [line frame prior](line-frame.md) and [heading frame](line-heading.md).

Source: `research-java/docs/results/line-continuation.md`, `line-continuation-by-zoom.md`,
`openzl-pipeline.md`.
