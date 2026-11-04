//! S3: segmentation (`encoder/segmentation/Segmentation.java`): which residual stream every vertex's
//! residual pair goes to, as a pure function of the topology streams and the predictor's classes,
//! plus the residual computation (`split`) and its inverse (`join`). Only `FLAT` and `CLASS`.

use crate::buffer::{Topology, V};
use crate::predict::Predictor;
use crate::refs::ReferenceWalk;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Segmentation {
    /// One stream, buffer order.
    Flat,
    /// One stream per predictor class, in the predictor's region order.
    Class,
}

impl Segmentation {
    /// The header code (`Segmentation.ordinal()`).
    #[must_use]
    pub fn code(self) -> u8 {
        match self {
            Self::Flat => 0,
            Self::Class => 1,
        }
    }

    #[must_use]
    pub fn from_code(code: u8) -> Option<Self> {
        match code {
            0 => Some(Self::Flat),
            1 => Some(Self::Class),
            _ => None,
        }
    }

    #[must_use]
    pub fn name(self) -> &'static str {
        match self {
            Self::Flat => "FLAT",
            Self::Class => "CLASS",
        }
    }
}

/// Which stream each vertex's residual pair goes to, the predictor class of each vertex, and the
/// number of values in each stream (two per vertex).
pub struct Layout {
    pub stream_of: Vec<usize>,
    pub classes: Vec<usize>,
    pub value_counts: Vec<usize>,
}

impl Layout {
    #[must_use]
    pub fn stream_count(&self) -> usize {
        self.value_counts.len()
    }

    /// This layout without the vertices that are references: no residual pair each.
    #[must_use]
    pub fn without_referenced(mut self, referenced: &[bool]) -> Self {
        for (i, &r) in referenced.iter().enumerate() {
            if r {
                self.value_counts[self.stream_of[i]] -= 2;
            }
        }
        self
    }

    /// This layout with the stream lengths a decoder read from the buffer.
    #[must_use]
    pub fn with_counts(mut self, counts: Vec<usize>) -> Self {
        self.value_counts = counts;
        self
    }
}

impl Segmentation {
    /// The layout of a buffer of `vertex_count` vertices; `topology` needs no vertices.
    #[must_use]
    pub fn layout(self, topology: &Topology, predictor: &mut Predictor, vertex_count: usize) -> Layout {
        let classes = predictor.classify(topology, vertex_count);
        let mut stream_of = vec![0usize; vertex_count];
        let stream_count = match self {
            Self::Flat => 1,
            Self::Class => {
                let mut position = vec![0usize; predictor.class_count()];
                for (i, &c) in predictor.region_order().iter().enumerate() {
                    position[c] = i;
                }
                for i in 0..vertex_count {
                    stream_of[i] = position[classes[i]];
                }
                predictor.class_count()
            }
        };
        let mut value_counts = vec![0usize; stream_count];
        for &s in &stream_of {
            value_counts[s] += 2;
        }
        Layout { stream_of, classes, value_counts }
    }
}

/// The signed residual of every vertex against its prediction, one interleaved `x, y` array per
/// stream, in buffer order within a stream. The vertices marked in `referenced` write no residual
/// (`layout` must be `without_referenced`); they still count as decoded for prediction and frame.
#[must_use]
pub fn split(layout: &Layout, predictor: &Predictor, vertices: &[V], referenced: Option<&[bool]>) -> Vec<Vec<i32>> {
    let mut streams: Vec<Vec<i32>> = layout.value_counts.iter().map(|&n| Vec::with_capacity(n)).collect();
    let mut pass = predictor.pass();
    let mut prev = V::new(0, 0);
    for i in 0..vertices.len() {
        let vertex = vertices[i];
        if referenced.is_some_and(|r| r[i]) {
            if let Some(p) = pass.as_mut() {
                p.accept(i, vertices);
            }
            prev = vertex;
            continue;
        }
        let class = layout.classes[i];
        let (px, py) = if predictor.has_prediction(class) {
            (predictor.predict_x(i, vertices), predictor.predict_y(i, vertices))
        } else {
            (prev.x, prev.y)
        };
        let mut rx = vertex.x.wrapping_sub(px);
        let mut ry = vertex.y.wrapping_sub(py);
        if let Some(p) = pass.as_mut() {
            if p.framed(i) {
                p.forward(rx, ry);
                rx = p.x();
                ry = p.y();
            }
            p.accept(i, vertices);
        }
        let stream = &mut streams[layout.stream_of[i]];
        stream.push(rx);
        stream.push(ry);
        prev = vertex;
    }
    streams
}

/// The inverse of [`split`]; reads only the streams, the layout and what it has rebuilt. `walk`
/// says which vertices are copied from an earlier run instead of read from the streams; `layout`
/// then carries the stream lengths written.
#[must_use]
pub fn join(
    layout: &Layout,
    predictor: &Predictor,
    streams: &[Vec<i32>],
    vertex_count: usize,
    mut walk: Option<&mut (dyn ReferenceWalk + '_)>,
) -> Vec<V> {
    let mut cursors = vec![0usize; streams.len()];
    let mut vertices: Vec<V> = Vec::with_capacity(vertex_count);
    let mut pass = predictor.pass();
    let mut prev = V::new(0, 0);
    for i in 0..vertex_count {
        if let Some(w) = walk.as_deref_mut() {
            if let Some(shared) = w.decode(i, &vertices) {
                vertices.push(shared);
                if let Some(p) = pass.as_mut() {
                    p.accept(i, &vertices);
                }
                w.accept(i, &vertices);
                prev = shared;
                continue;
            }
        }
        let class = layout.classes[i];
        let (px, py) = if predictor.has_prediction(class) {
            (predictor.predict_x(i, &vertices), predictor.predict_y(i, &vertices))
        } else {
            (prev.x, prev.y)
        };
        let s = layout.stream_of[i];
        let mut rx = streams[s][cursors[s]];
        let mut ry = streams[s][cursors[s] + 1];
        cursors[s] += 2;
        if let Some(p) = pass.as_mut() {
            if p.framed(i) {
                p.inverse(rx, ry);
                rx = p.x();
                ry = p.y();
            }
        }
        let vertex = V::new(px.wrapping_add(rx), py.wrapping_add(ry));
        vertices.push(vertex);
        if let Some(p) = pass.as_mut() {
            p.accept(i, &vertices);
        }
        if let Some(w) = walk.as_deref_mut() {
            w.accept(i, &vertices);
        }
        prev = vertex;
    }
    vertices
}
