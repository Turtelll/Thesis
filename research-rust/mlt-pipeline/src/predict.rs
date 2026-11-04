//! S2: prediction (`encoder/prediction/Prediction.java` and `mechanism/{ClassifiedPredictor,
//! SegmentQuadCorner, LineContinuation}`): the eight live predictors of the 96-composition grid.
//!
//! A [`Predictor`] classifies every vertex from the topology streams alone, says which class has a
//! geometric prediction, and predicts from the vertices decoded so far. The framed predictors add a
//! [`FramePass`] that rotates plain-delta edges into a frame predicted from decoded runs.

use crate::buffer::{Topology, V, line_runs, vertex_runs};
use crate::frame::FramePrior;

/// The class a vertex falls in; class 0 is the interior class (plain delta).
pub const INTERIOR: usize = 0;
/// The first vertex of a run.
pub const START: usize = 1;
/// A quad corner (`QUAD_CORNER`) or a continued line vertex (`LINE_CONTINUATION`).
pub const PREDICTED: usize = 2;

/// A line run needs this many vertices before the continuation applies.
const MIN_LINE_RUN_LENGTH: usize = 6;

/// The predictor field of the payload header: one of the eight live predictors.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Prediction {
    Delta,
    QuadCorner,
    LineContinuation,
    QuadCornerFrame,
    QuadCornerShared,
    QuadCornerSharedFrame,
    LineContinuationFrameHeading,
    LineContinuationSharedFrameHeading,
}

impl Prediction {
    /// In `Prediction.values()` declaration order, which the grid iterates in.
    pub const ALL: [Self; 8] = [
        Self::Delta,
        Self::QuadCorner,
        Self::LineContinuation,
        Self::QuadCornerFrame,
        Self::QuadCornerShared,
        Self::QuadCornerSharedFrame,
        Self::LineContinuationFrameHeading,
        Self::LineContinuationSharedFrameHeading,
    ];

    /// The stable header code (not the ordinal).
    #[must_use]
    pub fn code(self) -> u8 {
        match self {
            Self::Delta => 0,
            Self::QuadCorner => 1,
            Self::LineContinuation => 2,
            Self::QuadCornerFrame => 5,
            Self::QuadCornerShared => 7,
            Self::QuadCornerSharedFrame => 8,
            Self::LineContinuationFrameHeading => 11,
            Self::LineContinuationSharedFrameHeading => 13,
        }
    }

    #[must_use]
    pub fn of_code(code: u8) -> Option<Self> {
        Self::ALL.into_iter().find(|p| p.code() == code)
    }

    /// The Java enum constant's name.
    #[must_use]
    pub fn name(self) -> &'static str {
        match self {
            Self::Delta => "DELTA",
            Self::QuadCorner => "QUAD_CORNER",
            Self::LineContinuation => "LINE_CONTINUATION",
            Self::QuadCornerFrame => "QUAD_CORNER_FRAME",
            Self::QuadCornerShared => "QUAD_CORNER_SHARED",
            Self::QuadCornerSharedFrame => "QUAD_CORNER_SHARED_FRAME",
            Self::LineContinuationFrameHeading => "LINE_CONTINUATION_FRAME_HEADING",
            Self::LineContinuationSharedFrameHeading => "LINE_CONTINUATION_SHARED_FRAME_HEADING",
        }
    }

    /// Whether this predictor codes shared-vertex references.
    #[must_use]
    pub fn shared(self) -> bool {
        self.plain() != self
    }

    /// The predictor a buffer without references falls back to.
    #[must_use]
    pub fn plain(self) -> Self {
        match self {
            Self::QuadCornerShared => Self::QuadCorner,
            Self::QuadCornerSharedFrame => Self::QuadCornerFrame,
            Self::LineContinuationSharedFrameHeading => Self::LineContinuationFrameHeading,
            other => other,
        }
    }

    /// The residual predictor behind this choice (a shared row's is its plain one).
    #[must_use]
    pub fn predictor(self) -> Predictor {
        match self.plain() {
            Self::Delta => Predictor::new(Base::Delta, None),
            Self::QuadCorner => Predictor::new(Base::QuadCorner, None),
            Self::LineContinuation => Predictor::new(Base::LineContinuation, None),
            Self::QuadCornerFrame => {
                Predictor::new(Base::QuadCorner, Some(Framing { rings: true, lines: false, heading: false }))
            }
            Self::LineContinuationFrameHeading => {
                Predictor::new(Base::LineContinuation, Some(Framing { rings: false, lines: true, heading: true }))
            }
            _ => unreachable!("plain() returns a plain predictor"),
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Base {
    Delta,
    QuadCorner,
    LineContinuation,
}

/// Which kinds of run the frame is applied to, and whether continued vertices are rotated by the
/// heading of the edge before them.
#[derive(Clone, Copy, Debug)]
struct Framing {
    rings: bool,
    lines: bool,
    heading: bool,
}

/// A classified predictor. One instance serves one buffer: the framed ones remember the runs of the
/// layout they last classified.
pub struct Predictor {
    base: Base,
    framing: Option<Framing>,
    starts: Vec<usize>,
    ring: Vec<bool>,
    line: Vec<bool>,
    framed_run: Vec<bool>,
    classes: Vec<usize>,
    vertex_count: usize,
}

impl Predictor {
    fn new(base: Base, framing: Option<Framing>) -> Self {
        Self {
            base,
            framing,
            starts: Vec::new(),
            ring: Vec::new(),
            line: Vec::new(),
            framed_run: Vec::new(),
            classes: Vec::new(),
            vertex_count: 0,
        }
    }

    /// Per-vertex class, derived from the topology streams alone.
    pub fn classify(&mut self, topology: &Topology, vertex_count: usize) -> Vec<usize> {
        let classes = match self.base {
            Base::Delta => vec![INTERIOR; vertex_count],
            Base::QuadCorner => classify_quad_corner(&vertex_runs(topology, vertex_count), vertex_count),
            Base::LineContinuation => classify_line_continuation(topology, vertex_count),
        };
        if let Some(framing) = self.framing {
            let runs = vertex_runs(topology, vertex_count);
            let is_line = if runs.len() < 2 { None } else { line_runs(topology, runs.len()) };
            self.ring = vec![false; runs.len()];
            self.line = vec![false; runs.len()];
            self.framed_run = vec![false; runs.len()];
            if let Some(is_line) = is_line {
                for r in 0..runs.len() {
                    self.ring[r] = !is_line[r];
                    self.line[r] = is_line[r];
                    self.framed_run[r] = if self.ring[r] { framing.rings } else { framing.lines };
                }
            }
            self.starts = runs;
            self.classes.clone_from(&classes);
            self.vertex_count = vertex_count;
        }
        classes
    }

    #[must_use]
    pub fn class_count(&self) -> usize {
        match self.base {
            Base::Delta => 1,
            Base::QuadCorner | Base::LineContinuation => 3,
        }
    }

    /// Region order when segmented by class: a permutation of `0..class_count()`.
    #[must_use]
    pub fn region_order(&self) -> &'static [usize] {
        match self.base {
            Base::Delta => &[0],
            Base::QuadCorner | Base::LineContinuation => &[START, INTERIOR, PREDICTED],
        }
    }

    /// Whether `class` has a geometric prediction (otherwise the previous vertex predicts it).
    #[must_use]
    pub fn has_prediction(&self, class: usize) -> bool {
        match self.base {
            Base::Delta => false,
            Base::QuadCorner | Base::LineContinuation => class == PREDICTED,
        }
    }

    #[must_use]
    pub fn predict_x(&self, i: usize, v: &[V]) -> i32 {
        match self.base {
            Base::QuadCorner => v[i - 3].x.wrapping_add(v[i - 1].x).wrapping_sub(v[i - 2].x),
            Base::LineContinuation => v[i - 1].x.wrapping_mul(2).wrapping_sub(v[i - 2].x),
            Base::Delta => unreachable!("delta has no prediction"),
        }
    }

    #[must_use]
    pub fn predict_y(&self, i: usize, v: &[V]) -> i32 {
        match self.base {
            Base::QuadCorner => v[i - 3].y.wrapping_add(v[i - 1].y).wrapping_sub(v[i - 2].y),
            Base::LineContinuation => v[i - 1].y.wrapping_mul(2).wrapping_sub(v[i - 2].y),
            Base::Delta => unreachable!("delta has no prediction"),
        }
    }

    /// A fresh frame pass over the buffer last classified, for the framed predictors.
    #[must_use]
    pub fn pass(&self) -> Option<FramePass<'_>> {
        self.framing.map(|_| FramePass {
            p: self,
            prior: FramePrior::new(),
            run: 0,
            edge_x: 0,
            edge_y: 0,
            by_heading: false,
        })
    }
}

fn classify_quad_corner(starts: &[usize], vertex_count: usize) -> Vec<usize> {
    let mut classes = vec![INTERIOR; vertex_count];
    for (i, &start) in starts.iter().enumerate() {
        let end = if i + 1 < starts.len() { starts[i + 1] } else { vertex_count };
        if start < vertex_count {
            classes[start] = START;
        }
        if end - start == 4 {
            classes[start + 3] = PREDICTED;
        }
    }
    classes
}

fn classify_line_continuation(topology: &Topology, vertex_count: usize) -> Vec<usize> {
    let starts = vertex_runs(topology, vertex_count);
    let mut classes = vec![INTERIOR; vertex_count];
    let Some(is_line) = line_runs(topology, starts.len()) else {
        for &start in &starts {
            if start < vertex_count {
                classes[start] = START;
            }
        }
        return classes;
    };
    for (r, &start) in starts.iter().enumerate() {
        let end = if r + 1 < starts.len() { starts[r + 1] } else { vertex_count };
        if start < vertex_count {
            classes[start] = START;
        }
        if is_line[r] && end - start >= MIN_LINE_RUN_LENGTH {
            for c in &mut classes[start + 2..end] {
                *c = PREDICTED;
            }
        }
    }
    classes
}

/// The frame state of one pass; vertices are visited in order, `accept` after each.
pub struct FramePass<'a> {
    p: &'a Predictor,
    prior: FramePrior,
    run: usize,
    edge_x: i32,
    edge_y: i32,
    by_heading: bool,
}

impl FramePass<'_> {
    fn advance(&mut self, i: usize) {
        let starts = &self.p.starts;
        while self.run + 1 < starts.len() && starts[self.run + 1] <= i {
            self.run += 1;
        }
    }

    /// Whether the residual of vertex `i` is rotated into the frame.
    pub fn framed(&mut self, i: usize) -> bool {
        self.advance(i);
        let framing = self.p.framing.expect("a pass belongs to a framed predictor");
        let predicted = self.p.has_prediction(self.p.classes[i]);
        // a continued vertex has two vertices of its own run before it, so a heading
        self.by_heading = framing.heading && self.p.line[self.run] && predicted && i >= self.p.starts[self.run] + 2;
        self.by_heading || (self.p.framed_run[self.run] && i > self.p.starts[self.run] && !predicted)
    }

    /// Rotates a residual into the current frame; result in `x()`, `y()`.
    pub fn forward(&mut self, dx: i32, dy: i32) {
        if self.by_heading {
            self.prior.forward_heading(self.edge_x, self.edge_y, dx, dy);
        } else {
            self.prior.forward(dx, dy);
        }
    }

    /// The exact inverse of `forward`.
    pub fn inverse(&mut self, rx: i32, ry: i32) {
        if self.by_heading {
            self.prior.inverse_heading(self.edge_x, self.edge_y, rx, ry);
        } else {
            self.prior.inverse(rx, ry);
        }
    }

    #[must_use]
    pub fn x(&self) -> i32 {
        self.prior.x()
    }

    #[must_use]
    pub fn y(&self) -> i32 {
        self.prior.y()
    }

    /// Vertex `i` is known (`vertices` holds at least `0..=i`).
    pub fn accept(&mut self, i: usize, vertices: &[V]) {
        self.advance(i);
        if i > 0 {
            self.edge_x = vertices[i].x.wrapping_sub(vertices[i - 1].x);
            self.edge_y = vertices[i].y.wrapping_sub(vertices[i - 1].y);
        }
        if !self.p.framed_run[self.run] {
            return;
        }
        let start = self.p.starts[self.run];
        if i == start {
            self.prior.begin_ring(vertices[i]);
        } else {
            let previous = vertices[i - 1];
            self.prior.edge(vertices[i].x.wrapping_sub(previous.x), vertices[i].y.wrapping_sub(previous.y));
        }
        let end = if self.run + 1 < self.p.starts.len() { self.p.starts[self.run + 1] } else { self.p.vertex_count };
        if i == end - 1 {
            if self.p.ring[self.run] {
                self.prior.end_ring(vertices, start, end);
            } else {
                self.prior.end_line(vertices, start, end);
            }
        }
    }
}
