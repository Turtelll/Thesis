# Searching the per-(tile, layer) oracle on raw or entropy-estimated bytes

**Status:** failed — the search flags (`--trials raw,order0,order1` of `--step best`) were removed; the
oracle searches under the compressor it is judged under (`--search gzip|brotli|zstd`)
**Effect:** the choice made on raw bytes is 2,75 points worse under gzip than the one made under gzip;
order-1 entropy 0,8 points, order-0 1,5 points
**Corpus:** current baseline (`munich-plantiler.mbtiles`, `data/extractedBaseline`, 4.368 buffers, 358
tiles), geometry buffers, per tile, against `DELTA_ZIGZAG_VARINT` gzip (11.237.124 B)

Question: is the compression in the search loop needed, or does a size estimate that compresses nothing
pick (nearly) the same compositions? An estimate would make the 252-candidate search cheap.

`measure --step best` over the grid of [../tile-oracle.md](../tile-oracle.md), the search run once per
criterion, every choice then sized under gzip (34 minutes, one run):

| searched under | `ORACLE` (per buffer alone) | `ORACLE TILE` (descent on the tile) |
|---|---|---|
| gzip | 9.925.196 (-11,67%) | **9.895.841 (-11,94%)** |
| order-1 entropy | 10.027.895 (-10,76%) | 9.984.270 (-11,15%) |
| order-0 entropy | 10.075.441 (-10,34%) | 10.063.712 (-10,44%) |
| raw bytes | 10.204.544 (-9,19%) | 10.204.544 (-9,19%) |

The raw-byte choice is also worse than the fixed per-layer default (9.960.430, -11,36%). Raw size is
additive over a tile's buffers, so the descent finds nothing beyond the per-buffer choice; it favours the
split varint, which is smallest raw but not after LZ77 plus Huffman (its best single composition is flat
streams with the split varint, gzip's is class streams with escape width and byte planes). The entropy
bounds are closer but still lose, since they see no matches.

Conclusion: the search has to run on the target compressor. The estimate backends were removed.
