# 6. S3 Segmentation

## What it does
Decides which stream each residual is written to. Residuals of different kinds (large jumps to a new run,
ordinary edges, near-zero corners) interleaved break up the runs of similar bytes the backend compresses;
one stream per kind gives it homogeneous data. The prediction is unaffected: segmentation only moves where
a residual is stored.

## Options
`encoder/segmentation/Segmentation.java`; header byte 0 bits 3-4.

| option | streams | status |
|---|---|---|
| `FLAT` (0) | one, buffer order | used |
| `CLASS` (1) | one per predictor class, in the predictor's `regionOrder` | used |
| `ROLE` (2) | jump, edge, body | lost |
| `ROLE_CLASS` (3) | jump, edge, body split by class | lost |

## How it works
1. `layout(topology, predictor)`: the predictor classifies every vertex (step 4); under `CLASS` the stream
   of a vertex is the position of its class in `regionOrder` (`QUAD_CORNER`: `START, INTERIOR, CORNER`).
2. Value count per stream: two (x, y) per vertex assigned to it. Known on both sides from the topology.
3. `split`: walk the vertices, compute each residual (and apply the frame rotation, step 4), append its x, y
   to the vertex's stream.
4. Empty streams are skipped in the payload.

```
FLAT:   S I I C S I I C ...
CLASS:  S S ... | I I I I ... | C C ...
```

With shared references (step 5) the referenced vertices leave their streams, so the counts are written.

## How the decoder works
Derive the classes and the stream lengths from the topology, decode each stream (steps 7 to 9), then
`join`: walk the vertices in order, each taking the next value of its own stream.

## Why
History (`QUAD_CORNER_LINE`, Hilbert base, gzip vs `DELTA_ZIGZAG_VARINT`): `FLAT` -4,65%, `CLASS` -5,86%,
`ROLE` -4,53%, `ROLE_CLASS` -5,46%. Class streams are worth about 1,2 points, mostly on `building`; for
`DELTA` (one class) they change nothing. The role split costs more than it separates; a reorder that makes
classes contiguous costs more in the property columns than it gains.

More: [../encoding/good/class-streams.md](../encoding/good/class-streams.md),
[../encoding/bad/role-streams.md](../encoding/bad/role-streams.md),
[../encoding/bad/class-sorted-order.md](../encoding/bad/class-sorted-order.md),
[openzl-pipeline.md#s3-segmentation](https://github.com/Turtelll/Thesis/blob/main/research-java/docs/results/openzl-pipeline.md#s3-segmentation).
