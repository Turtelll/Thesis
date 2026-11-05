# Follow flags for shared line vertices (`LINE_CONTINUATION_SHARED_DICT_FOLLOW`)

> **Verdict: Rejected.** Adding follow flags makes the dictionary rows larger on every corpus
> (Munich `transportation` -7,69% instead of -8,48%, FastPFOR bytes); not measured under the headline metric.

## Encoding target
transportation (LineString).

## Idea
For buildings, a referenced vertex is often followed by the neighbour's next wall vertex. Two roads sharing a
stretch might behave the same: after a shared vertex, the next vertex may be the source road's neighbour, in
either direction.

## Intention
On rings the follow flag is what turns a shared wall into one reference plus a few bits
([shared ring vertices](../good/shared-ring-vertices.md)). If roads also shared stretches, the same flag would
remove whole runs of residuals.

## How the encoding works
Line dictionary references plus: after a vertex whose predecessor sits at a position an earlier part holds,
1 bit "this vertex is that source's neighbour" plus 1 direction bit (previous/next). On a hit no residual.

## How the decoder works
Keep a position table with part bounds per vertex; after such a vertex read the follow bit and on 1 the
direction bit, emit the source neighbour; else decode the residual.

## Results
**Headline:** *not measured under the headline metric* (current corpus, FastPFOR-era bytes vs
`LINE_CONTINUATION`). Munich `transportation`: **-7,69% with follow flags vs -8,48% without**
(`_FASTPFOR`).

**Decisive case against:** roads meet at junctions, they do not run along each other. Every referenced start
makes its 2nd vertex follow-eligible, so the flag is paid almost every time, and the vertex almost never
continues along the other road (0,56% of vertices repeat within a part). It loses on all three corpora under
both layouts. Switching it off also cut decode time (`_SEG` 1,90x → 1,44x).

Supporting figures (*FastPFOR-era bytes vs `LINE_CONTINUATION`, `transportation`*):

| corpus | `_FASTPFOR` with follow | without | `_SEG` with follow | without |
|---|---|---|---|---|
| Munich | -7,69% | -8,48% | -5,27% | -6,11% |
| Chicago | -5,87% | -6,54% | -4,63% | -5,33% |
| Tokyo | -3,94% | -4,70% | -2,08% | -2,84% |

Source: `research-java/docs/results/line-shared-vertices.md`.
