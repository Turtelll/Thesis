# Shared line vertices via a recent-parts window

**Code names:** `LINE_CONTINUATION_SHARED`, window addressing

> **Verdict: Rejected.** -0,12% on `transportation` (FastPFOR bytes) against -8,48% for the dictionary; in the
> pipeline the tile oracle dropped it at a cost of 59 B.

## Encoding target
transportation (LineString).

## Idea
Do for roads what worked for buildings: reference junction positions held by one of the last 4 line parts,
with a short index into that window.

## Intention
The ring window (last 4 rings) works because neighbouring buildings are stored next to each other. If road
parts that meet at a junction were also stored close together, the same small window would catch the repeats
with a short index and no dictionary.

## How the encoding works
As the dictionary variant (reversal, entry/exit flags + index), but the
candidates are the distinct positions of the last 4 line parts, newest first (`Addressing.WINDOW`).

## How the decoder works
Keep the positions of the last 4 decoded parts; at a part start/end read the flag, on 1 read the index into
that window.

## Results
**Headline:** *not measured under the headline metric* (current corpus, FastPFOR-era bytes vs
`LINE_CONTINUATION_FASTPFOR`): `transportation` **-0,12%** (dictionary: -8,48%), tileset -0,05%. In the
pipeline the tile oracle picked it on 0,6% of buffers; dropping it costs +0,001% (59 B) of the oracle's gzip
total.

**Decisive case against:** a road's junction partner is rarely among the last 4 parts, so the window almost
never hits, and the hits that exist the [dictionary](../good/shared-line-vertices.md) finds as well. The
window addressing was removed, and `Prediction.LINE_CONTINUATION_SHARED` (code 12) was retired by the tile
oracle.

Supporting figures (*FastPFOR-era bytes vs `LINE_CONTINUATION_FASTPFOR`*): corpus -0,05%, `transportation`
-0,12%, `waterway` -3,08%, `transportation_name` -0,60%. Source recency not measured.

Source: `research-java/docs/results/line-shared-vertices.md`, `tile-oracle.md`.
