# Shape reuse

**Code names:** WP5-E5.1 feasibility scan

> **Verdict: Rejected (feasibility scan, no encoder).** At most 3,76% of rings find their shape in a 16-ring
> window, far below the 20% gate; never measured under the headline metric.

## Encoding target
building (Polygon).

## Idea
32,4% of building rings share a translation-invariant shape with another ring. If a ring's shape equals one of
the last k rings, send a short back-reference to that shape (plus the translation) instead of all its edges.

## Intention
Many buildings are built from the same plan (row houses, garages, prefab blocks). If identical shapes sit close
together in the buffer, one reference plus a translation would replace all of a ring's edges. The scan was a
gate: build the encoder only if at least 20% of rings hit within the same tile.

## How the encoding works
Probe only (`BuildingShapeCli` k-window scan): for k ∈ {1, 4, 16}, does a ring's canonical shape signature match one
of the k most recently seen ring signatures in buffer order, exactly and allowing 90° rotations. Index cost
`ceil(log2(k+1))` bits not charged.

## How the decoder works
Not built. A decoder would read a hit flag and index, copy the referenced shape's edges (rotated if needed) from the
start vertex.

## Results
**Headline:** *not measured under the headline metric* (feasibility scan, window hit rate, retired corpus
`munich.mbtiles`). k=16 with rotation: **3,76%** of rings hit; gate: 20%.

**Decisive case against:** the repeats exist but not where a decoder can reach them: 88–92% of shape repeats
are in another tile, not locally clustered. The plan's 20% same-tile gate failed by a factor of five, so stages
E5.2–E5.5 were never implemented.

Supporting figures (*retired corpus, hit rate*):

| window | n=4 | all |
|---|---|---|
| k=1 (+rot90) | 0,66% (0,97%) | 0,52% (0,76%) |
| k=4 (+rot90) | 1,79% (2,63%) | 1,39% (2,03%) |
| k=16 (+rot90) | 3,36% (4,87%) | 2,61% (3,76%) |

Source: `research-java/docs/results/failed/shape-reuse.md`.
