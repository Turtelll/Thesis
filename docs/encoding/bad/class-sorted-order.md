# Class-sorted feature order (`CLASS_HILBERT_CENTROID_ROT`)

## encoding target:
all layers; reorder pre-pass

## Idea of the encoding
On top of the Hilbert reorder, sort features so that vertices of the same predictor class are contiguous in
feature order, making the class streams even more homogeneous.

## How the encoding works
S1 Hilbert-centroid order with ring rotation, then features grouped so the predictor classes are contiguous;
topology and property columns permuted with them; nothing transmitted.

## How the decoder works
No change; decodes in stored order.

## results
Current corpus `munich-plantiler`, S3, per tile vs `DELTA_ZIGZAG_VARINT`: class-sorted `FLAT` -4,15% gzip,
`ROLE_CLASS` -5,04%, against `QUAD_CORNER_LINE` + `CLASS` -5,86% on the plain Hilbert order. Loses on the geometry
and costs more on the properties (`landcover` +33,5% vs +27,5%, `transportation` +24,0% vs +22,7%). Discarded.
