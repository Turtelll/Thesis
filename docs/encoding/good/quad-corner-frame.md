# Ring frame prior (`QUAD_CORNER_FRAME`, `QUAD_CORNER_SHARED_FRAME`)

## encoding target:
building, landcover, landuse, water, aeroway (Polygon, `QUAD_CORNER_SHARED_FRAME`); place (`QUAD_CORNER_FRAME`)

## Idea of the encoding
A building's walls sit at a few angles, and neighbouring buildings along a street share those angles. A
wall that is not parallel to the tile axes has a large delta in both x and y. Rotate the edge into the
wall's own frame first, and the wall becomes one large component and one near-zero one. The frame is
guessed from the nearest ring the decoder already has, then refined by the ring's own decoded edges, so
nothing is transmitted.

## How the encoding works
Mechanism: `encoder/prediction/mechanism/FramePrior.java` (old name `RingFramePrior`).

- **Frame of a ring:** length-weighted mean wall direction modulo 90°, angles quadrupled so right-angle walls
  vote together (trig-free vote `z^4 / |z|^3`), quantized to 16 bits, folded to [-45°, 45°).
- **Prior:** frame of the decoded ring whose centroid is nearest the new ring's start vertex (neighbour grid,
  cell size 128), counted as one wall of `PRIOR_WEIGHT = 50` units, refined edge by edge with the ring's own
  decoded edges (referenced vertices included).
- **Which vertices:** the plain-delta vertices of polygon rings except the ring start (what the corner
  predictor leaves to delta). Starts and predicted corners are unchanged.
- **Rotation:** residual `(dx, dy)` rotated by `-frame` with three lifting shears; each shear is an integer
  bijection, so the rotation is exactly invertible and the stream keeps two values per vertex. Shear
  coefficients from a static table of 65.536 entries; IEEE + `StrictMath` for determinism.
- Applied in `Segmentation.split/join` on the residual values, so it works under every segmentation.
- `QUAD_CORNER_SHARED_FRAME` = the same frame on top of shared ring vertices.

```
wall at 30°:   plain delta (87, 50)   →  framed (100, 0)
```

## How the decoder works
1. Decode the vertices in order. Keep a neighbour grid of decoded rings with their frames.
2. At a ring start, look up the decoded ring with centroid nearest the start vertex → prior frame.
3. For each framed vertex: read the residual, apply the inverse three-shear rotation with the current frame,
   add to `p[i-1]`; then update the frame with the decoded edge.
4. Unframed vertices (starts, corners, references) decode as in quad corner / shared ring vertices.
5. At ring end store the ring's frame and centroid in the grid.

## results
**Current corpus `munich-plantiler`, OpenZL pipeline**, `building`, 310 buffers, `HILBERT_CENTROID_ROT_MP`,
`CLASS`, `ESCAPE`, per tile vs `DELTA_ZIGZAG_VARINT`:

| row | gzip | Brotli 11 | zstd 19 |
|---|---:|---:|---:|
| `QUAD_CORNER` | -9,20% | -13,61% | -10,41% |
| `QUAD_CORNER_FRAME` | **-12,69%** | **-19,80%** | **-15,36%** |

- Helps under every segmentation (`FLAT` -5,43% → -9,89%, `CLASS` -9,20% → -12,69%) and without reorder
  (-8,11% → -11,41%); nearly additive with the Hilbert order.
- Other layers (plain → framed, gzip / Brotli / zstd): `landcover` -4,70% → -5,15% / -7,38% → -7,92% /
  -5,64% → -6,21%; `landuse` -4,70% → -5,72% / -8,41% → -9,63% / -6,16% → -7,29%; `aeroway` -2,14% → -2,21%.
  `water` -2,41% → -2,24% gzip (not switched for plain frame), `boundary`, `park` tie.
- **Old encoding set:** `QUAD_CORNER_SHARED` -5,58% → `_SHARED_FRAME` -6,47% gzip, -11,98% → -13,62% Brotli;
  `QUAD_CORNER_SHARED_FRAME_RING_CANONICAL_REORDER` -8,19% / -15,21% / -9,92%; `building` -13,35% gzip /
  -20,78% Brotli.
- **Best single pipeline row:** `PIPE_HILBERT_CENTROID_ROT_MP_QUAD_CORNER_SHARED_FRAME_CLASS_ZIGZAG_ESCAPE_PLANES`
  -9,95% gzip / -15,97% Brotli / -11,68% zstd (whole corpus). Part of the per-layer default (-11,36% /
  -17,18% / -13,03%).
- Decode: 5,86x `DELTA_ZIGZAG_FASTPFOR` on `building` (112 ns/value above 5000 vertices), mostly
  `StrictMath.atan2`; old `QUAD_CORNER_SHARED_FRAME` 5,1x. Stage 0 probe: decoded-neighbour frame beats a
  transmitted pre-pass of buffer frames (numbers lost).
