---
date created: Thursday, October 15th 2026, 10:56:37 am
date modified: Thursday, October 15th 2026, 11:26:00 am
---

## VertexBuffer pipeline with a general-purpose backend: notes per stage
```
S1 reorder → S2 predict → S3 segment → S4 zigzag → S5 gcd (opt.) → S6 width → S7 transpose → S8 brotli/zstd/gzip
```

---

### S1: Feature reordering
**Purpose.** Reduce the jump from one feature to the next in the delta chain, and bring similar geometries close together so LZ finds repeats.

**How**
- Sort features by a space-filling curve key (Hilbert or Morton) on the start vertex or bbox centroid.
- Rotate each ring’s start vertex to the vertex closest to the previous feature’s end. Rings are cyclic, so this is free; keep the winding direction.
- Optional secondary key: feature class or shape, e.g. vertex count, so similar residual sequences end up adjacent and LZ can match them.

**Signaling.** None. MLT doesn’t preserve feature order.

**Decoder cost.** Zero.

**Pitfalls**
- The reorder also permutes every property column. Always measure total tile size.
- Never reverse line direction in transportation layers (one-way semantics).
- Ring rotation changes which vertex is “first”. Check that nothing downstream depends on it, such as the index buffer or Earcut output.

**Measure**
- Mean |inter-feature jump| before and after.
- Compressed size of geometry only vs. the whole tile.

---

### S2: Prediction
**Purpose.** Turn absolute coordinates into small residuals.

**Candidates**
- Delta (order 1) as the baseline.
- 2nd-order linear extrapolation `2a − b` for smooth or densified lines.
- Parallelogram prediction (SegmentQuadCorner) for building footprints.
- Context-based prediction for transportation.

**How**
- Keep the delta chain continuous across feature boundaries, otherwise S1 gains nothing.
- Choose the predictor per FeatureTable.

**Signaling.** A predictor enum per segment or table; varint, a few bytes.

**Decoder cost.** Sequential prefix dependency, the same as today.

**Pitfalls**
- Per-feature predictor switching creates mixed residual distributions (mixture entropy). Fix this in S3, not here.
- Tune for low _byte_ entropy after S4–S7, not for minimal bit width as with FastPFOR.

**Measure.** Order-0 entropy of the zigzagged residuals, per predictor and per layer.

---

### S3: Segmentation from existing metadata
**Purpose.** Homogeneous streams, the core OpenZL principle. deflate and zstd use one order-0 Huffman table per block and don't split blocks where the statistics change, so mixed residual distributions cost bytes.

**Inputs (already in each buffer).** `featureCount`, `partOffset`, `vertexCounts`.

**Derivability rule.** The decoder can infer a segmentation if it is a pure function of metadata decoded before the vertex buffer. Anything else needs an explicit flag stream (1 bit or a small enum per feature), or a decoder that classifies from already decoded vertices, which forces strictly sequential decoding.

|   |   |
|---|---|
|Derivable|From|
|Vertex range of each feature and part|Prefix sums over `vertexCounts` and `partOffset`|
|Role of each vertex: part start, first edge, interior|Its position within the part|
|Feature class, if the rule uses only metadata|e.g. `vertexCount == 4` and single part → quad candidate|
|Segment lengths|Sum of `vertexCounts` per class or role|

**How: role-based (per vertex)**

|   |   |   |
|---|---|---|
|Stream|Content|Typical width (S6)|
|`jump`|First vertex of each part: delta from the previous part's end|2 bytes|
|`edge`|Second vertex: plain delta|1–2 bytes|
|`body`|Remaining vertices: predictor residuals|Mostly 1 byte|

- The jumps have a much wider distribution. Separating them keeps the low-byte statistics of `body` clean and lets each stream get its own width in S6.
- This generalizes SegmentQuadCorner's three-region reordering: the regions become separate streams for the backend instead of FastPFOR block regions.

**How: class-based (per feature)**
- Quad candidates → parallelogram prediction → stream A.
- Everything else → delta or 2nd-order prediction → stream B.
- With a metadata-derivable rule there is no flag stream.
- Write each class as one contiguous segment; never interleave per feature.

**Variant: implicit segmentation through S1.** MLT doesn't preserve feature order, so sort by **(class, Hilbert key)** instead of Hilbert key alone.
- Each class becomes contiguous in feature order, so segments are contiguous by construction: no gather, no cursors.
- Order stays spatial within each class; cost ≈ one large jump per class boundary.
- `vertexCounts` turns into long runs (e.g. all 4s together), which LZ compresses better.

**Signaling.** Segment boundaries and lengths: none (derived from metadata). Still needed:
- Predictor enum per stream or FeatureTable.
- Width per stream (S6), 2 bits each.
- Transpose layout variant (S7), if chosen per tile.
- Class flags only if the class rule is not metadata-derivable.

**Decoder cost.** Gather variant (order stays purely spatial):
1. Compute a start cursor per stream from prefix sums over the metadata.
2. Walk features in buffer order; for each vertex, take its role and class and read the next value from that stream's cursor.
3. The delta chain still runs in original feature order, so S1's short inter-feature jumps are preserved.

One extra pass, no extra bytes; the classification must be cheap, which `vertexCount == 4` is. Sort variant: zero.

**Pitfalls**
- The (class, Hilbert) sort permutes every property column; an order that isn't purely spatial can hurt them.
- Re-evaluate the FastPFOR-specific transforms (SegmentPair, bit-window segmentation): they optimized 128-value block bit width, and under LZ+Huffman they may be neutral, or harmful if they break repeat patterns.
- Segments that are too small add overhead and give no block-level statistical gain.
- A class rule that depends on geometry breaks derivability and costs a flag stream or sequential decoding.

**Measure**
- Compressed size per backend: flat stream vs. role-split vs. role + class split vs. the (class, Hilbert) sort.
- Total tile size for the sort variant, to see the effect on property columns.
- Share of `jump` values above 255, which shows how much the separate width actually saves.

---

### S4: Zigzag
**Purpose.** Map signed residuals to unsigned so that small magnitudes become small values.

**How.** `(n << 1) ^ (n >> 31)` per component, after S2 and before S6.

**Why mandatory here.** Without it, negative residuals sign-extend into 0xFF high bytes. After S7 the high plane becomes a noisy mix of 0x00 and 0xFF instead of almost all 0x00.

**Alternative.** Bias by subtracting the min (range-pack style). The width is the same, but the low-byte distribution is worse for distributions peaked around zero.

**Decoder cost.** Negligible, one op per value and SIMD-friendly.

---

### S5: GCD / divide_by (optional, low priority)
**Purpose.** Remove a common factor from all values.

**Expectation.** On integer tile-coordinate residuals the GCD is ≈ 1 almost always. OpenZL’s power-of-2 caveat is about float mantissas and doesn’t apply here.

**How.** Compute the GCD per stream and apply it only if > 1.

**Signaling.** One varint, or a flag bit.

**Recommendation.** Implement it as a cheap check, report how often it fires per layer and zoom, and drop it if that’s near zero.

---

### S6: Byte-width selection (range pack)
**Purpose.** Use the narrowest fixed byte width: 1, 2 or 4.

**How**
- Width = bytes needed for the max zigzagged residual in the segment.
- Escape variant: if outliers are rare, use a 1-byte main stream with an escape code, plus a separate exception stream at full width. This is PFOR-like but byte-aligned.

**Signaling.** Width per segment (2 bits); exception count and positions for the escape variant.

**Note.** After S7, a too-wide choice costs little because the general compressor shrinks a near-zero high plane to almost nothing. The width decision is forgiving; the escape variant is the refinement.

**Measure.** Fraction of values > 255 per layer and zoom. This decides whether the escape variant pays off.

---

### S7: Byte-plane transpose (byte stream split)
**Purpose.** Separate the predictable high bytes from the noisy low bytes. This is the most important stage for a general compressor and the gap in the original plan.

**How.** For width 2, write all low bytes first, then all high bytes. Plane order variants:

- x_lo, y_lo, x_hi, y_hi
- x_lo, x_hi, y_lo, y_hi

**Decoder cost.** A cheap SIMD interleave.

**Pitfalls**
- For width 1 this is a no-op.
- For very short segments, the transpose and segmentation overhead can outweigh the gain.

**Measure.** Compressed size transposed vs. untransposed, per backend. Brotli’s context modeling may reduce the gap.

**Prior art.** Parquet BYTE_STREAM_SPLIT, Blosc shuffle, meshoptimizer’s vertex codec (byte grouping, designed to be followed by gzip/zstd).

---

### S8: Backend (brotli / zstd / gzip)
**Role.** Replaces OpenZL’s entropy stage, FSE gate and field_lz.

|Backend|Relevant properties|
|---|---|
|gzip / deflate|32 KB window, min match 3, Huffman per block|
|zstd|order-0 Huffman literals, FSE for sequences; homogeneous planes matter most|
|brotli|literal context from the previous 2 bytes; may tolerate interleaved layouts|

**Why there is no own entropy stage.** All three are native HTTP content encodings, so the browser decodes them "for free". This is the same argument that ruled out rANS.

**Pitfall.** The whole tile is compressed as one stream, so the vertex buffer shares the window with other columns. Isolated per-stream measurements are only approximate.

---

### Selection loop (across stages)
- Don’t port OpenZL’s thresholds. They were calibrated for OpenZL’s own backends.
- Encoding is offline and the goal is the best encoding per tile, so use trial compression, like OpenZL’s `tryGraph`:
	1. Enumerate a small candidate set per segment: {predictor} × {segmentation on/off} × {transpose layout}.
	2. Compress each candidate with the target backend.
	3. Keep the smallest and signal it with an enum.
- Run a final whole-tile check with the real backend and level.
- Report results per layer, zoom and backend, using the same keep/discard logic as the per-encoding chapters.

