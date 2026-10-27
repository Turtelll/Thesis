# Shared references + turn scalar — `QUAD_CORNER_SHARED_TURN_SCALAR*`

**Status:** measured in the workshop, then **code removed** (`SharedTurnScalar`, its mechanism test and
its workshop rows are no longer in the tree); never registered in `Baselines.research()`, no corpus-wide
benchmark. As first specified it **regresses** against `QUAD_CORNER_SHARED`; the `_ALL` variant ties it on
gzip and is about 1% smaller on Brotli/zstd at `building` z14.
**Corpus:** `data/extractedBaseline`, layer `building` only (310 buffers; z13 62, z14 248)
**To rebuild:** one `Encoding` that copies `SharedVertexRef`'s shared stream (it needs `State` and `rotate`
opened up with a per-ring filter, which were reverted) and reads `TurnScalar.classify`/`ux`/`uy`/`scaled`
for the turn vertices; the layout and selector are described below.

## What it does

[turn-scalar-encoder.md](failed/turn-scalar-encoder.md) and [shared-vertices.md](shared-vertices.md)
each win on a different population, so this composes them:

- **four-vertex polygon rings** — rotated and referenced exactly as `SharedVertexRef` does (entry flag +
  index, follow flag), `QUAD_CORNER`'s parallelogram corner for what is not referenced;
- **even rings of more than four vertices** — `TurnScalar`'s rule on every vertex past the second:
  an `along` scalar plus a `corr` pair. Never rotated or referenced (`Rings.FOUR`, the default);
- everything else — plain delta.

`Rings.ALL` lets every ring reference, as `SharedVertexRef` does, and applies the turn rule only to the
vertices of larger rings that were *not* referenced. The earlier-ring window and position table hold every
polygon ring either way.

Segmented regions in the order CORNER, CORR, INTERIOR, ALONG, START over `VARINT_SPLIT`; the header holds the
CORNER, CORR and INTERIOR lengths. Per buffer the encoder keeps the smaller of this and plain `QUAD_CORNER`
behind a mode byte; the `Selector` decides what "smaller" means.

## The selector matters

`SIZE` (fewer payload bytes, `SharedVertexRef`'s rule) almost never takes the turn rule: a Varint value
under 64 costs one byte whatever its width, and the `along` scalars add values, so the turn rule
raises raw bytes while only lowering entropy. On `building` z14 it takes the shared stream on a small share
of buffers (workshop `side` 2,6% for `Rings.FOUR`, vs 41,9% with `DEFLATE`). The default is `DEFLATE`:
the smaller of the two payloads after raw Deflate, encoder-only. `FORCED` (no fallback) is the diagnostic.

## Measured

`building`, gzip per tile (`./gradlew workshop --args="--layer building --by-zoom"`), change vs
`DELTA_ZIGZAG_VARINT`:

| row | all tiles | z13 | z14 |
|---|---|---|---|
| `QUAD_CORNER_SHARED` | -10,55% | -1,68% | -12,71% |
| `..._TURN_SCALAR` (`FOUR`, `DEFLATE`) | -9,49% | -1,68% | -11,39% |
| `..._TURN_SCALAR_ALL` (`ALL`, `DEFLATE`) | -10,40% | -1,68% | -12,52% |

z14 against `QUAD_CORNER_SHARED`, per-tile gzip / Brotli 11 / zstd 19 (a throwaway probe, deleted;
workshop reports gzip only):

| variant | gzip | Brotli | zstd |
|---|---|---|---|
| `FOUR`, `SIZE` | +3,38% | +3,66% | +3,58% |
| `FOUR`, `DEFLATE` | +1,51% | +0,48% | +0,52% |
| `FOUR`, `FORCED` | +1,65% | +0,37% | +0,43% |
| `ALL`, `SIZE` | +3,21% | +3,44% | +3,38% |
| `ALL`, `DEFLATE` | +0,22% | **-1,18%** | **-1,01%** |
| `ALL`, `FORCED` | +0,25% | -1,16% | -0,99% |

z13 is identical to `QUAD_CORNER_SHARED` for every `DEFLATE` row (the fallback is plain `QUAD_CORNER`
there). `FORCED` at z13 is +6,7% (`FOUR`) / +6,8% (`ALL`) gzip vs `DELTA`: generalised rings are not
rectilinear, so `corr` is not near zero. The fallback is what keeps them out.

## Why

- **References on larger rings are worth more than the turn rule.** At z14 only the four-vertex rings
  reference under `FOUR`; every reference on a larger ring is given up. That costs 1,5 to 3,4 points of gzip
  against what the turn rule recovers. `ALL` gives them back and ends level on gzip.
- **The turn rule's gain is small to begin with and gzip hides it.** From the controlled-layout probe on
  `TURN_SCALAR` alone (segmented, `VARINT_SPLIT`): `TURN_SCALAR` ties `QUAD_CORNER` under gzip at z14, and
  is 0,8 points ahead under Brotli/zstd. It touches only turn vertices (394.736 of 1.719.281 at z14), whose
  ideal order-0 cost falls from 12,32 to 10,90 bits. A zero-heavy extra value stream cannot go below about a bit
  per symbol under Huffman, which is why Brotli and zstd show the gain gzip does not (the compressor
  ranking agrees; the Huffman floor itself was not measured directly).
- **After references the two do not stack under gzip**, but do under Brotli/zstd: `ALL` is -1,2% / -1,0%
  there at z14.

## Not measured / open

- `Rings.ALL` + `DEFLATE` on the full corpus and the other polygon layers (`landcover`, `landuse`, `water`),
  where `TURN_SCALAR` lost 15–17% bytes on its own; the per-buffer fallback should contain it, unmeasured.
- Whole-corpus Brotli/zstd numbers: the three-compressor table above is `building` z14 only.
- Encoder cost: the selector Deflates two payloads per buffer, and the shared stream is built once more per
  `emitted` call.
- A single-run buffer never takes the shared stream (`SharedVertexRef.Runs.of` needs two runs), so a
  one-ring buffer cannot use the turn rule.
- Decode time not measured; the shared stream's position lookups (see [shared-vertices.md](shared-vertices.md#decode-time))
  plus a `Math.hypot` per turn vertex.
