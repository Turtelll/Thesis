//! The compositions the oracle chooses among (`registry/CompositionGrids.bestGrid`) and the per-layer
//! default (`registry/LayerCompositions.forLayer`). The only place with MVT layer names.

use crate::format::Composition;
use crate::predict::Prediction;
use crate::reorder::Reorder;
use crate::segmentation::Segmentation;
use crate::stream::Terminal;

/// The grid of `predictors`: three reorders x the predictors x flat or class streams, in the Java
/// grid's order (the first of equal sizes wins, so the order is part of the result).
fn grid_of(predictors: &[Prediction]) -> Vec<Composition> {
    let mut grid = Vec::with_capacity(predictors.len() * 6);
    for reorder in [Reorder::None, Reorder::HilbertCentroidRotMp, Reorder::Chain] {
        for &predictor in predictors {
            for segmentation in [Segmentation::Flat, Segmentation::Class] {
                grid.push(Composition { reorder, predictor, segmentation, terminal: Terminal::EscapePlanes });
            }
        }
    }
    grid
}

/// The 42 compositions: three reorders x seven predictors x flat or class streams. The ring frames
/// are the one-vote frames (codes 23 and 27), the ring references have the raw entry index (26, 27), lines
/// have no frame ([`Prediction::ALL`]); it holds [`Composition::DEFAULT`] and every [`for_layer`] composition.
/// Also what `golden-check` looks a Java dump's row names up in.
#[must_use]
pub fn best_grid() -> Vec<Composition> {
    grid_of(&Prediction::ALL)
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
    use Terminal::EscapePlanes as Esc;
    let chain = |c: Composition| c.with_reorder(Reorder::Chain);
    match layer {
        "place" => hilbert(P::QuadCornerPrevFrameFirstOnly, Flat, Esc),
        "transportation" => chain(hilbert(P::LineContinuationShared, Class, Esc)),
        "landcover" | "landuse" | "building" => hilbert(P::QuadCornerSharedRawPrevFrameFirstOnly, Class, Esc),
        "transportation_name" => chain(hilbert(P::LineContinuationShared, Flat, Esc)),
        "water" => chain(hilbert(P::QuadCornerSharedRawPrevFrameFirstOnly, Class, Esc)),
        "park" => hilbert(P::QuadCornerSharedRaw, Flat, Esc),
        "boundary" => hilbert(P::LineContinuationShared, Flat, Esc),
        "poi" => hilbert(P::Delta, Class, Esc),
        "water_name" => hilbert(P::LineContinuation, Flat, Esc),
        "housenumber" => hilbert(P::LineContinuation, Flat, Esc),
        "waterway" => chain(hilbert(P::LineContinuationShared, Flat, Esc)),
        "mountain_peak" => hilbert(P::LineContinuation, Flat, Esc),
        "aeroway" => chain(hilbert(P::QuadCornerSharedRawPrevFrameFirstOnly, Flat, Esc)),
        "aerodrome_label" => hilbert(P::Delta, Flat, Esc),
        _ => Composition::DEFAULT,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;

    #[test]
    fn the_grid_has_42_distinct_compositions_with_distinct_names() {
        let grid = best_grid();
        assert_eq!(grid.len(), 42);
        let names: HashSet<String> = grid.iter().map(Composition::name).collect();
        assert_eq!(names.len(), 42);
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
        // the one-vote ring frames took the slots of the float and then the integer ring frame (Java: `Prediction.grid()`),
        // the raw entry index (26, 27) those of Java's distinct one (7, 25)
        assert_eq!(
            Composition::DEFAULT.name(),
            "PIPE_QUAD_CORNER_SHARED_RAW_PREVFRAME_FIRST_ONLY_CLASS_ZIGZAG_ESCAPE_PLANES"
        );
        assert_eq!(
            for_layer("transportation").name(),
            "PIPE_CHAIN_LINE_CONTINUATION_SHARED_CLASS_ZIGZAG_ESCAPE_PLANES"
        );
    }
}
