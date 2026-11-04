# Shape reuse (WP5-E5.1 feasibility scan)

## encoding target:
building (Polygon)

## Idea of the encoding
32,4% of building rings share a translation-invariant shape with another ring. If a ring's shape equals one of the
last k rings, send a short back-reference to that shape (plus the translation) instead of all its edges.

## How the encoding works
Probe only (`BuildingShapeCli` k-window scan): for k ∈ {1, 4, 16}, does a ring's canonical shape signature match one
of the k most recently seen ring signatures in buffer order, exactly and allowing 90° rotations. Index cost
`ceil(log2(k+1))` bits not charged.

## How the decoder works
Not built. A decoder would read a hit flag and index, copy the referenced shape's edges (rotated if needed) from the
start vertex.

## results
Retired corpus `munich.mbtiles`, window hit rate:

| window | n=4 | all |
|---|---|---|
| k=1 (+rot90) | 0,66% (0,97%) | 0,52% (0,76%) |
| k=4 (+rot90) | 1,79% (2,63%) | 1,39% (2,03%) |
| k=16 (+rot90) | 3,36% (4,87%) | 2,61% (3,76%) |

Killed against the plan's 20% same-tile gate: 88–92% of shape repeats are cross-tile, not locally clustered. Stages
E5.2–E5.5 never implemented.
