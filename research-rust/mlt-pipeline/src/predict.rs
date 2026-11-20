//! S2: prediction (`encoder/prediction/Prediction.java` and `mechanism/{ClassifiedPredictor,
//! SegmentQuadCorner, LineContinuation}`): the seven predictors of the 42-composition grid
//! ([`Prediction::ALL`]).
//!
//! A [`Predictor`] classifies every vertex from the topology streams alone, says which class has a
//! geometric prediction, and predicts from the vertices decoded so far. The framed predictors add a
//! [`FramePass`] that rotates plain-delta edges into a frame predicted from decoded rings.

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

/// The predictor field of the payload header: one of the live predictors.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Prediction {
    Delta,
    QuadCorner,
    LineContinuation,
    /// `LINE_CONTINUATION_SHARED` (code 12): `LINE_CONTINUATION` on the shared-vertex references of line parts.
    /// A buffer the references do not shrink is written as plain `LINE_CONTINUATION`.
    LineContinuationShared,
    /// `QUAD_CORNER_PREVFRAME_FIRST_ONLY` (code 23): the previous ring's frame, one vote per ring (the ring's first
    /// edge). The ring frame of [`Prediction::ALL`], the grids and the per-layer defaults.
    QuadCornerPrevFrameFirstOnly,
    /// `QUAD_CORNER_SHARED_RAW` (code 26): `QUAD_CORNER` on the shared-vertex references of polygon rings, the entry
    /// index a slot into the window's raw vertices (no hash of the window). The ring references of [`Prediction::ALL`]
    /// and the per-layer defaults; Java's distinct-index codes 7 and 25 are not in this codec
    /// (docs/results/shared-decode-time.md). A buffer the references do not shrink is written as plain `QUAD_CORNER`.
    QuadCornerSharedRaw,
    /// `QUAD_CORNER_SHARED_RAW_PREVFRAME_FIRST_ONLY` (code 27): code 23 on the references of code 26. The shared ring
    /// frame of [`Prediction::ALL`], the grids and the per-layer defaults; falls back to code 23.
    QuadCornerSharedRawPrevFrameFirstOnly,
}

impl Prediction {
    /// The seven predictors of the 42-composition grid, in the order the grid iterates in (the
    /// first of equal sizes wins, so the order is part of the result): the ring frame is the one-vote one
    /// (codes 23 and 27), the ring references have the raw entry index (26, 27, in the slots of Java's 7 and 25);
    /// lines have no frame. The other frames (5, 8, 11, 13, 16-19) are gone from this codec.
    pub const ALL: [Self; 7] = [
        Self::Delta,
        Self::QuadCorner,
        Self::LineContinuation,
        Self::QuadCornerPrevFrameFirstOnly,
        Self::QuadCornerSharedRaw,
        Self::QuadCornerSharedRawPrevFrameFirstOnly,
        Self::LineContinuationShared,
    ];

    /// Every predictor the codec writes and reads.
    pub const EVERY: [Self; 7] = [
        Self::Delta,
        Self::QuadCorner,
        Self::LineContinuation,
        Self::LineContinuationShared,
        Self::QuadCornerPrevFrameFirstOnly,
        Self::QuadCornerSharedRaw,
        Self::QuadCornerSharedRawPrevFrameFirstOnly,
    ];

    /// The stable header code (not the ordinal).
    #[must_use]
    pub fn code(self) -> u8 {
        match self {
            Self::Delta => 0,
            Self::QuadCorner => 1,
            Self::LineContinuation => 2,
            Self::LineContinuationShared => 12,
            Self::QuadCornerPrevFrameFirstOnly => 23,
            Self::QuadCornerSharedRaw => 26,
            Self::QuadCornerSharedRawPrevFrameFirstOnly => 27,
        }
    }

    #[must_use]
    pub fn of_code(code: u8) -> Option<Self> {
        Self::EVERY.into_iter().find(|p| p.code() == code)
    }

    /// The Java enum constant's name.
    #[must_use]
    pub fn name(self) -> &'static str {
        match self {
            Self::Delta => "DELTA",
            Self::QuadCorner => "QUAD_CORNER",
            Self::LineContinuation => "LINE_CONTINUATION",
            Self::LineContinuationShared => "LINE_CONTINUATION_SHARED",
            Self::QuadCornerPrevFrameFirstOnly => "QUAD_CORNER_PREVFRAME_FIRST_ONLY",
            Self::QuadCornerSharedRaw => "QUAD_CORNER_SHARED_RAW",
            Self::QuadCornerSharedRawPrevFrameFirstOnly => "QUAD_CORNER_SHARED_RAW_PREVFRAME_FIRST_ONLY",
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
            Self::QuadCornerSharedRaw => Self::QuadCorner,
            Self::LineContinuationShared => Self::LineContinuation,
            Self::QuadCornerSharedRawPrevFrameFirstOnly => Self::QuadCornerPrevFrameFirstOnly,
            other => other,
        }
    }

    /// The residual predictor behind this choice (a shared row's is its plain one).
    #[must_use]
    pub fn predictor(self) -> Predictor {
        match self.plain() {
            Self::Delta => Predictor::new(Base::Delta, false),
            Self::QuadCorner => Predictor::new(Base::QuadCorner, false),
            Self::LineContinuation => Predictor::new(Base::LineContinuation, false),
            Self::QuadCornerPrevFrameFirstOnly => Predictor::new(Base::QuadCorner, true),
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

/// A classified predictor. One instance serves one buffer: the framed ones remember the runs of the
/// layout they last classified.
pub struct Predictor {
    base: Base,
    /// Whether the rings are coded in the previous ring's frame (lines have none).
    framed: bool,
    starts: Vec<usize>,
    framed_run: Vec<bool>,
    classes: Vec<usize>,
    vertex_count: usize,
}

impl Predictor {
    fn new(base: Base, framed: bool) -> Self {
        Self {
            base,
            framed,
            starts: Vec::new(),
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
        if self.framed {
            let runs = vertex_runs(topology, vertex_count);
            let is_line = if runs.len() < 2 { None } else { line_runs(topology, runs.len()) };
            self.framed_run = match is_line {
                Some(is_line) => is_line.iter().map(|&line| !line).collect(),
                None => vec![false; runs.len()],
            };
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
        self.framed.then(|| FramePass { p: self, prior: FramePrior::new(), run: 0 })
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
        let predicted = self.p.has_prediction(self.p.classes[i]);
        self.p.framed_run[self.run] && i > self.p.starts[self.run] && !predicted
    }

    /// Rotates a residual into the current frame; result in `x()`, `y()`.
    pub fn forward(&mut self, dx: i32, dy: i32) {
        self.prior.forward(dx, dy);
    }

    /// The exact inverse of `forward`.
    pub fn inverse(&mut self, rx: i32, ry: i32) {
        self.prior.inverse(rx, ry);
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
        if !self.p.framed_run[self.run] {
            return;
        }
        let start = self.p.starts[self.run];
        if i == start {
            self.prior.begin_ring();
        } else {
            self.prior.edge(vertices[i - 1], vertices[i]);
        }
        let end = if self.run + 1 < self.p.starts.len() { self.p.starts[self.run + 1] } else { self.p.vertex_count };
        if i == end - 1 {
            self.prior.end_ring(end - start);
        }
    }
}
