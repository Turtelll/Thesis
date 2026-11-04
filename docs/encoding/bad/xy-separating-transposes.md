# x/y-separating byte planes (`PLANE_MAJOR`, `COMPONENT_MAJOR`)

## encoding target:
all layers; fixed-width streams

## Idea of the encoding
Like the interleaved byte planes, but also separate x from y, so each plane (or each component) is homogeneous on
its own.

## How the encoding works
`Transpose` index of byte `b` of value `i` (`n` values, width `w`):
- `PLANE_MAJOR`: `b·n + (i & 1)·(n/2) + i/2` → `x_lo, y_lo, x_hi, y_hi`.
- `COMPONENT_MAJOR`: `(i & 1)·(n/2)·w + b·(n/2) + i/2` → `x_lo, x_hi, y_lo, y_hi`.

## How the decoder works
Same index function inverted; value count from topology.

## results
Current corpus `munich-plantiler`, S7 (`ESCAPE`), per tile vs `DELTA_ZIGZAG_VARINT`: `PLANE_MAJOR` -5,72% gzip /
-8,17% Brotli 9 / -7,21% zstd, `COMPONENT_MAJOR` -5,70% / -8,02% / -7,11%, vs `INTERLEAVED_PLANES` -6,51% /
-8,79% / -7,93%. Separating x from y loses 0,8 points, as `VARINT_XY` did. Discarded.
