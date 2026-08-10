# Thesis 
> This repository contains research code for a Bachelor Thesis
## Topic
The topic of the Research is finding new encoding strategies to reduce Maplibre Tile size (Vector Tiles) with various approaches.
### Data
Data will be provided in MVT and converted using the Java encoder at the start of the thesis with a view different options
1. no pre-tessellation
2. with pre tessellation
### Encodings 
- First focus point will be using predictors for the different geom types. With these Predictors only residuals get saved and reconstructed in the symmetrical decoder.
- stuff like rANS
- investigate draco 
