# Upstream `ComponentwiseDelta2` (Rust, maplibre-tile-spec #1806) — `DELTA2_ZIGZAG`

> **Consolidation note.** The Gradle task(s) benchmark named below were removed from the build (the code is at git tag `pre-consolidation`); `research measure` and the other `research` commands replace the rest. The numbers below are unchanged history.

> **Revisited under split varints:** `DELTA2_ZIGZAG_VARINT_SPLIT` is +8,93% whole tileset
> (smallest of gzip/Brotli/zstd), worse than the baseline on every layer. See
> [revisit-varint-split.md](../revisit-varint-split.md).

**Status:** registered for comparison (`core()`), regression on both corpora
**Effect:** `transportation` bytes +9,8% (Munich) / +8,8% (Chicago) under FastPFOR, +10,5% /
+11,3% under Varint; mean residual bits +12,8% / +19,1%; delta2 is smaller on **0 of 673**
buffers, so a per-stream race against delta (what the Rust encoder does) never picks it
**Corpus:** `data/extractedBaseline` (Munich) and `data/extracted-chicago`,
`--layer transportation`, core interleaved, `--set: core -rans`

## What upstream added

Upstream commit `7f56208b` (2026-10-01, "opt-in second-order delta encodings for v2
integer and vertex streams"; included in the submodule since the bump to `bf9359d2`) adds
`Delta2` to the v2 `Int`/`Str` families and `ComponentwiseDelta2` to the `Vertex` family.
For vertices: componentwise `delta2[i] = (v[i] - v[i-1]) - (v[i-1] - v[i-2])`,
zigzag-coded, both predecessors starting at `0`, continuous over the whole vertex stream
(no reset at part, ring or feature boundaries, no geometry-type gate). It is opt-in
(`EncoderConfig::with_delta2`) and raced per stream against plain componentwise delta
(`write_plain_vertices` → `try_alternatives`), so it is only written where it is smaller.

`VertexTransform.ZigZagDelta2` is a line-for-line port of
`encode_componentwise_delta2_vec2s`, round-tripped by `VertexBufferCodecTest`.

## Measured

```
./gradlew benchmark --args="../data/extractedBaseline --layer transportation --by-zoom --csv ..."
./gradlew benchmark --args="../data/extracted-chicago --layer transportation --by-zoom --csv ..."
```

Bytes vs `DELTA_ZIGZAG` under the same codec:

```
zoom   Munich Varint  Munich FastPFOR  Chicago Varint  Chicago FastPFOR
z4          +1,8%          +6,7%           +2,6%           +9,3%
z5          +2,6%          +7,5%           +3,8%          +10,7%
z6          +4,5%         +10,7%           +5,1%          +10,6%
z7          +5,0%          +8,1%           +5,2%           +9,2%
z8          +4,5%          +7,3%           +6,3%           +9,1%
z9         +10,0%         +12,2%           +8,1%          +11,4%
z10         +9,2%         +11,4%           +8,9%          +10,5%
z11         +8,2%         +10,9%           +9,3%          +10,0%
z12        +12,5%         +10,0%          +13,3%           +9,3%
z13        +11,1%         +10,2%          +12,4%           +9,3%
z14        +10,4%          +9,6%          +10,8%           +8,4%
layer      +10,5%          +9,8%          +11,3%           +8,8%
```

Every zoom, both cities, both codecs is a regression; no buffer is smaller under delta2.
For comparison, the gated `LINE_CONTINUATION` (same second difference, applied only to the
3rd+ vertex of line parts with ≥4 vertices) is -0,1% / +0,3% under FastPFOR.

## Conclusion

### Same predictor, different scope

Both implementations compute the same thing for a vertex in the middle of a line: the
second difference `(v[i] - v[i-1]) - (v[i-1] - v[i-2])`, i.e. "the next edge repeats the
previous one". They differ only in *which* vertices get that prediction and in *who
decides*:

| | upstream `ComponentwiseDelta2` | this thesis: `LINE_CONTINUATION` |
|---|---|---|
| Vertices predicted | every vertex of the stream | only the 3rd+ vertex of a LineString part with ≥ 4 vertices |
| Part / feature boundaries | crossed: the jump to the next part is treated as an edge | respected: starts and 2nd vertices stay plain delta |
| Polygons, points | predicted like lines | plain delta |
| Signalling | one encoding id per stream | none — derived from the topology streams already in the tile |
| Decision | encoder races delta vs delta2 per stream, keeps the smaller | always applied (a per-buffer race shows up only in the oracle) |
| Streams covered | vertex streams **and** all integer streams (ids, offsets, lengths, integer columns) | vertex streams only |

So upstream is the general, data-agnostic form of the idea, protected from losses by the
race; ours is a gated form tailored to where the idea can work in vector-tile geometry.

### Where upstream's delta2 loses on our data

Splitting the residual bits by a vertex's position within its part (line-only
`transportation` buffers, `delta2_by_position.py`) shows that the whole regression comes
from the part boundaries, not from the predictor itself:

| position in part | share of vertices (Munich / Chicago) | delta bits/value | delta2 bits/value | change (Munich / Chicago) |
|---|---|---|---|---|
| 1st vertex (part start) | 24,9% / 29,7% | 8,23 / 7,69 | 8,59 / 8,19 | +4,3% / +6,4% |
| 2nd vertex | 24,9% / 29,7% | 5,22 / 4,98 | 8,53 / 8,15 | **+63,3% / +63,6%** |
| 3rd+ vertex (interior) | 50,1% / 40,6% | 5,43 / 5,05 | 5,09 / 5,04 | -6,2% / -0,3% |
| **whole layer** | | 6,08 / 5,82 | 6,82 / 6,90 | **+12,3% / +18,6%** |

(The totals match the Java benchmark's +12,8% / +19,1% on all buffers, which confirms the
port.) Read top to bottom, this is the whole argument:

1. **The 2nd vertex of every part pays for the jump twice.** Its "previous edge" is the
   jump from the end of the last part to this part's start — a long, unrelated move. Delta2
   subtracts that jump from a short real edge, so the residual is about as large as the
   jump itself: +63% on these vertices, which alone accounts for the entire regression
   (111% of the bit increase in Munich, 87% in Chicago). The part start itself also gets
   slightly worse, because its predecessor edge belongs to another feature.
2. **Our data is boundary-heavy.** Road layers in vector tiles consist of many short
   parts — OSM ways are short, and tiling clips them again at every tile edge — so 26%
   (Munich) to 39% (Chicago) of vertices sit in 2-vertex parts, and half (Munich) to 60%
   (Chicago) of all vertices are a 1st or 2nd vertex. A cost paid on those positions is
   paid on half the stream.
3. **The interior gain is small to begin with.** The tiler simplifies lines and keeps a
   vertex only where the line changes, so consecutive edges rarely repeat: delta2 narrows
   interior residuals by only 6,2% in Munich and 0,3% on Chicago's grid, where
   axis-aligned streets leave only the irregular block length to predict
   ([line-continuation-by-zoom.md](../line-continuation-by-zoom.md)).
4. **Net: the boundary loss is about 5× (Munich) to 100× (Chicago) the interior gain**, so
   delta2 never wins a single buffer, and the upstream race would simply always keep plain
   delta on this data. `LINE_CONTINUATION` keeps row 3's gain and skips rows 1–2, which is
   why the same predictor goes from +12,8% to -2,1% mean residual bits (Munich) once gated.

### Why upstream can still report 50% / 8,3%

Nothing above contradicts those numbers — they come from data with the opposite profile,
and the per-stream race guarantees they can only be gains. Upstream's own docs describe
the target as "a per-vertex elevation along a densely sampled line". Two kinds of input
fit that and are absent from (or invisible to) this corpus:

- **Densely, evenly sampled lines with few parts** — GPS tracks, survey lines,
  unsimplified exports. Few boundaries make rows 1–2 negligible, and near-constant edges
  make row 3's residual close to zero. The PR names "US roads or generally
  manhattan-street layouts"; Chicago is exactly such a grid and still loses, so the grid
  alone is not enough — what matters is long parts with evenly spaced vertices, which
  OSM-derived, simplified tiles do not have.
- **Integer streams with a constant step** — sequential ids, offsets of equal-length
  parts, regularly spaced timestamps or measures become all zeros under delta2. A 50%
  tile-level gain is far more plausible here than in vertices; this pipeline measures
  vertex buffers only and cannot see it.

The proprietary datasets cannot be checked here; asking which streams the encoder chose
delta2 for, and how long the line parts are, would tell which of the two it is.

### Takeaway for this thesis

The second-difference predictor is sound, but on OpenMapTiles-style vector tiles its
value is decided by **where it is applied**, not by the predictor: ungated, it loses
~10% bytes; gated to line interiors, it gains a little (Munich) or breaks even (Chicago).
For a general MLT encoder the upstream design — opt-in plus a per-stream race — is the
safe way to ship it, because it costs nothing where it does not help; for this corpus it
would simply never be selected.
