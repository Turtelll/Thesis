# Shared line vertices via recent-parts window (`LINE_CONTINUATION_SHARED`, window addressing)

## encoding target:
transportation (LineString)

## Idea of the encoding
Do for roads what worked for buildings: reference junction positions held by one of the last 4 line parts,
with a short index into that window.

## How the encoding works
As the dictionary variant (reversal, entry/exit flags + index), but the
candidates are the distinct positions of the last 4 line parts, newest first (`Addressing.WINDOW`).

## How the decoder works
Keep the positions of the last 4 decoded parts; at a part start/end read the flag, on 1 read the index into
that window.

## results
- Current corpus `munich-plantiler`, bytes vs `LINE_CONTINUATION_FASTPFOR`: corpus -0,05%, `transportation`
  -0,12%, `waterway` -3,08%, `transportation_name` -0,60% (dictionary: -8,48% `transportation`).
- A road's junction partner is rarely among the last 4 parts (source recency not measured). Window
  addressing removed.
- In the OpenZL pipeline `Prediction.LINE_CONTINUATION_SHARED` (code 12) was retired by the tile oracle:
  0,6% of tile oracle picks, drop cost +0,001% (59 B).
