# Follow flags for shared line vertices (`LINE_CONTINUATION_SHARED_DICT_FOLLOW`)

## encoding target:
transportation (LineString)

## Idea of the encoding
For buildings, a referenced vertex is often followed by the neighbour's next wall vertex. Two roads sharing
a stretch might behave the same: after a shared vertex, the next vertex may be the source road's neighbour,
in either direction.

## How the encoding works
Line dictionary references plus: after a vertex whose predecessor sits at a position an earlier part holds,
1 bit "this vertex is that source's neighbour" plus 1 direction bit (previous/next). On a hit no residual.

## How the decoder works
Keep a position table with part bounds per vertex; after such a vertex read the follow bit and on 1 the
direction bit, emit the source neighbour; else decode the residual.

## results
Current corpus `munich-plantiler` and others, dictionary rows, `transportation`, bytes vs
`LINE_CONTINUATION`:

| corpus | `_FASTPFOR` with follow | without | `_SEG` with follow | without |
|---|---|---|---|---|
| Munich | -7,69% | -8,48% | -5,27% | -6,11% |
| Chicago | -5,87% | -6,54% | -4,63% | -5,33% |
| Tokyo | -3,94% | -4,70% | -2,08% | -2,84% |

Loses everywhere: every referenced start makes the 2nd vertex follow-eligible and it almost never continues
along the other road (0,56% of vertices repeat within a part). Switched off; dropping the follow state also
cut decode time (`_SEG` 1,90x → 1,44x).
