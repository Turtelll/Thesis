//! The compositions the oracle chooses among (`registry/CompositionGrids.bestGrid`) and the per-layer
//! default (`registry/LayerCompositions.forLayer`). The only place with MVT layer names.

use crate::format::Composition;
use crate::predict::Prediction;
use crate::reorder::Reorder;
use crate::segmentation::Segmentation;
use crate::stream::Terminal;

/// The 96 compositions: three reorders x eight predictors x flat or class streams x the two byte
/// terminals, in the Java grid's order (the first of equal sizes wins, so the order is part of the
/// result). It holds [`Composition::DEFAULT`] and every [`for_layer`] composition.
#[must_use]
pub fn best_grid() -> Vec<Composition> {
    let mut grid = Vec::with_capacity(96);
    for reorder in [Reorder::None, Reorder::HilbertCentroidRotMp, Reorder::Chain] {
        for predictor in Prediction::ALL {
            for segmentation in [Segmentation::Flat, Segmentation::Class] {
                for terminal in [Terminal::EscapePlanes, Terminal::VarintSplit] {
                    grid.push(Composition { reorder, predictor, segmentation, terminal });
                }
            }
        }
    }
    grid
}

fn hilbert(predictor: Prediction, segmentation: Segmentation, terminal: Terminal) -> Composition {
    Composition { reorder: Reorder::HilbertCentroidRotMp, predictor, segmentation, terminal }
}

/// The fixed composition of each fitted layer; a layer the fit has not seen takes
/// [`Composition::DEFAULT`].
#[must_use]
pub fn for_layer(layer: &str) -> Composition {
    use Prediction as P;
    use Segmentation::{Class, Flat};
    use Terminal::{EscapePlanes as Esc, VarintSplit as Var};
    let chain = |c: Composition| c.with_reorder(Reorder::Chain);
    match layer {
        "place" => hilbert(P::QuadCornerFrame, Flat, Var),
        "transportation" => chain(hilbert(P::LineContinuationSharedFrameHeading, Class, Esc)),
        "landcover" | "landuse" | "building" => hilbert(P::QuadCornerSharedFrame, Class, Esc),
        "transportation_name" => chain(hilbert(P::LineContinuationSharedFrameHeading, Flat, Var)),
        "water" => chain(hilbert(P::QuadCornerSharedFrame, Class, Esc)),
        "park" => hilbert(P::QuadCornerShared, Flat, Var),
        "boundary" => hilbert(P::LineContinuationSharedFrameHeading, Flat, Var),
        "poi" => hilbert(P::Delta, Class, Esc),
        "water_name" => hilbert(P::LineContinuation, Flat, Var),
        "housenumber" => hilbert(P::LineContinuation, Flat, Esc),
        "waterway" => chain(hilbert(P::LineContinuationSharedFrameHeading, Flat, Var)),
        "mountain_peak" => hilbert(P::LineContinuationFrameHeading, Flat, Var),
        "aeroway" => chain(hilbert(P::QuadCornerSharedFrame, Flat, Var)),
        "aerodrome_label" => hilbert(P::Delta, Flat, Var),
        _ => Composition::DEFAULT,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;

    #[test]
    fn the_grid_has_96_distinct_compositions_with_distinct_names() {
        let grid = best_grid();
        assert_eq!(grid.len(), 96);
        let names: HashSet<String> = grid.iter().map(Composition::name).collect();
        assert_eq!(names.len(), 96);
    }

    #[test]
    fn the_grid_holds_the_default_and_every_layer_composition() {
        let grid = best_grid();
        assert!(grid.contains(&Composition::DEFAULT));
        for layer in [
            "place", "transportation", "landcover", "landuse", "building", "transportation_name", "water", "park",
            "boundary", "poi", "water_name", "housenumber", "waterway", "mountain_peak", "aeroway", "aerodrome_label",
        ] {
            assert!(grid.contains(&for_layer(layer)), "{layer}");
        }
    }

    #[test]
    fn names_follow_the_java_grid() {
        assert_eq!(
            Composition::DEFAULT.name(),
            "PIPE_QUAD_CORNER_SHARED_FRAME_CLASS_ZIGZAG_ESCAPE_PLANES"
        );
        assert_eq!(
            for_layer("transportation").name(),
            "PIPE_CHAIN_LINE_CONTINUATION_SHARED_FRAME_HEADING_CLASS_ZIGZAG_ESCAPE_PLANES"
        );
    }
}
