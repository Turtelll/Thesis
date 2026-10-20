# Docs index

- [architecture.md](architecture.md) — pipeline, package layout, the `Encoding` contract,
  the two baselines, and the project's file tree.
- [evaluation-protocol.md](evaluation-protocol.md) — the 4-stage methodology for testing a
  new predictor (probe the distribution → layout × codec matrix → add-ons isolated →
  check the oracle). Read this before adding an encoding.
- [mlt-fidelity.md](mlt-fidelity.md) — why the extractor is a verbatim copy of MLT's
  encoder, and the two encoding rules the spec text doesn't state.
- [results/](results/README.md) — every encoding tried: what won, what regressed, and
  why, with measured numbers. Check here before re-deriving a result.
