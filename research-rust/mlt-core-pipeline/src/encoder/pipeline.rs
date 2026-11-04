//! PATCH (research-rust): what the tile oracle needs of a layer to search the research pipeline's
//! reorders: the layer's vertex buffer as the v2 writer sees it, and the layer with its features
//! (geometry, id and every property column together) in another order.

use crate::MltResult;
use crate::decoder::GeometryValues;
use crate::encoder::geometry::encode02::pipeline_buffer;
use crate::encoder::source::LayerSource as _;
use crate::tile::TileLayer;

impl TileLayer {
    /// The vertex buffer and the four topology streams of this layer, in feature order, exactly
    /// as the v2 writer hands them to the vertex codec.
    ///
    /// # Errors
    /// When the layer has z coordinates, or a feature that is not one geometry.
    pub fn vertex_buffer(&self) -> MltResult<mlt_pipeline::Buffer> {
        let mut geometry = GeometryValues::default();
        geometry.reserve(
            self.feature_count(),
            (0..self.feature_count()).map(|f| self.coord_count(f)).sum(),
        );
        for f in 0..self.feature_count() {
            self.push_geometry(f, &mut geometry);
        }
        let buffer = pipeline_buffer(geometry)?;
        if buffer.topology.geometry_types.len() != self.feature_count() {
            return Err(crate::MltError::NotImplemented(
                "the pipeline vertex codec for layers with geometry collections",
            ));
        }
        Ok(buffer)
    }

    /// This layer with feature `order[i]` at position `i`; `order` is a permutation of the features.
    ///
    /// # Panics
    /// When `order` is not a permutation of `0..feature_count()`.
    #[must_use]
    pub fn permuted(&self, order: &[usize]) -> Self {
        assert_eq!(order.len(), self.features.len(), "one position per feature");
        let mut taken = vec![false; order.len()];
        let features = order
            .iter()
            .map(|&f| {
                assert!(!std::mem::replace(&mut taken[f], true), "feature {f} twice");
                self.features[f].clone()
            })
            .collect();
        Self {
            features,
            ..self.clone()
        }
    }
}
