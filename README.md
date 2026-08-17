# Thesis 
> This repository contains research code for a Bachelor Thesis
> The research will be done in a seperate java project. MLT repo is included for converter and research purposes.
## Topic
The topic of the Research is finding new encoding strategies to reduce Maplibre Tile size (Vector Tiles) with various approaches. For VertexBuffer and IndexBuffer
### Data
Data will be provided in MVT
#### Baseline:
- for research purposes the geometries will be extracted from MVT to `VertexBuffers` that MLT uses.
  - the extracted data will be encoded with encodings from mlt (coppy paste):
        -> 1. Delta, zigZag, FastPfor -> bit algined (there is a library)
        -> 2. Delta, zigZag, Varint -> Byte aligned pattern
        -> 3. General Purpose Compression Gzip, Zstd -> Huffman Encoding + LZ77 -> Byte basis
    These 3 data sets will be the baseline to compare against/benchamrk
  - the baseline extraction + encoding pipeline (1 and 2 above; 3 is still pending) lives in
    [`research-java/`](research-java/README.md) - see its README for how to run extraction,
    encoding, and the round-trip tests.
#### new Encoding Results
- Different Results of diffrent prediction experiments
### Testing
- Starting with tests for Baseline that encodes the Basline data and decodes it and compares it to the MVT extractions
- every newly added encoding also has to follow these testing strategies to make sure data is not lossy or wrong 
### Encodings 
- First focus point will be using predictors for the different geom types. With these Predictors only residuals get saved and reconstructed in the symmetrical decoder.
- Second focus point are the IndexBuffers which are used to construct the pre-tesselated Vetices (indexing the VertexBuffer to use directly in GPU)
  - look at LineBreaker Algo for that
- compatibility with General Purpose Encodings 
maybe later
- stuff like rANS
- investigate draco more
