//! A Rust port of the research pipeline encoding of `research-java` for `VertexBuffer`s: reorder,
//! predict, segment, zigzag, byte width, transpose, with a self-describing two-byte header.
//! The payload is bit-identical to the Java encoder's, which `mlt-oracle golden-check` verifies.
//!
//! The codec works on a [`buffer::Buffer`]: the vertices of one geometry column and the four topology
//! streams that partition it. Those are the geometry length streams of an MLT geometry section, so the
//! codec can sit in the vertex slot of an MLT encoder and read the lengths it needs from the streams
//! the decoder has read before the vertices.

pub mod buffer;
pub mod format;
pub mod frame;
pub mod predict;
pub mod refs;
pub mod registry;
pub mod reorder;
pub mod segmentation;
pub mod stream;

pub use buffer::{Buffer, Topology, V};
pub use format::{Composition, Encoded, decode, encode, encode_reordered};
pub use predict::Prediction;
pub use reorder::Reorder;
pub use segmentation::Segmentation;
pub use stream::Terminal;
