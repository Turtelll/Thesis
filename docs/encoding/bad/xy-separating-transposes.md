# x/y-separating byte planes (`PLANE_MAJOR`, `COMPONENT_MAJOR`)

> **Verdict: Rejected.** -5,72% / -5,70% gzip on the tileset against -6,51% for interleaved byte planes.

## Encoding target
all layers; fixed-width streams.

## Idea
Like the [interleaved byte planes](../good/byte-plane-transpose.md), but also separate x from y, so each plane
(or each component) is homogeneous on its own.

## Intention
If separating byte planes helps, separating the two coordinates as well should make each plane even more
uniform.

## How the encoding works
`Transpose` index of byte `b` of value `i` (`n` values, width `w`):
- `PLANE_MAJOR`: `b·n + (i & 1)·(n/2) + i/2` → `x_lo, y_lo, x_hi, y_hi`.
- `COMPONENT_MAJOR`: `(i & 1)·(n/2)·w + b·(n/2) + i/2` → `x_lo, x_hi, y_lo, y_hi`.

## How the decoder works
Same index function inverted; value count from topology.

## Results
**Headline** (gzip per tile vs `DELTA_ZIGZAG_VARINT`, `munich-plantiler`, pipeline step S7, `ESCAPE`):
**`PLANE_MAJOR` -5,72%**, **`COMPONENT_MAJOR` -5,70%** against **`INTERLEAVED_PLANES` -6,51%** (tileset).

**Decisive case against:** separating x from y loses 0,8 points, just as it did for varints
([component-split varint](varint-xy.md)); both are even slightly worse than no transpose at all (-5,90%). Only
the byte planes help; the coordinate split hurts.

Supporting figures (same metric, gzip / Brotli 9 / zstd): `PLANE_MAJOR` -5,72% / -8,17% / -7,21%,
`COMPONENT_MAJOR` -5,70% / -8,02% / -7,11%, `INTERLEAVED_PLANES` -6,51% / -8,79% / -7,93%.

Source: `research-java/docs/results/openzl-pipeline.md`.
