//! `encoder/prediction/mechanism/FramePrior.java`: the frame of a ring or line part predicted from
//! the runs already decoded, and the exactly invertible integer rotation of an edge into it.
//!
//! Every floating-point step mirrors Java's `StrictMath` (fdlibm) bit for bit: `libm` is the musl
//! port of the same fdlibm kernels, `round` is Java's `Math.round` (ties toward +infinity), and the
//! degree conversions are the multiplications by Java's constants.

use crate::buffer::V;
use std::sync::OnceLock;

const PRIOR_WEIGHT: f64 = 50.0;
const CELL: i32 = 128;
const GRID: usize = 96;
const ORIGIN: i32 = 16;
const MIN_RING: usize = 3;
const MIN_LINE: usize = 2;
const ZERO_FRAME: i32 = 32768;

const DEGREES_TO_RADIANS: f64 = 0.017_453_292_519_943_295;
const RADIANS_TO_DEGREES: f64 = 57.295_779_513_082_32;

fn to_radians(degrees: f64) -> f64 {
    degrees * DEGREES_TO_RADIANS
}

fn to_degrees(radians: f64) -> f64 {
    radians * RADIANS_TO_DEGREES
}

/// Java's `Math.round(double)`: the nearest integer, ties toward positive infinity, saturating,
/// NaN as 0. `x - floor(x)` is exact, so no `x + 0.5` rounding error enters.
/// `f64::floor` (hardware-exact, IEEE-754 `floor` has no rounding-mode ambiguity unlike
/// transcendental functions) is used in place of `libm::floor` here for speed; verified
/// bit-identical to `libm::floor` over an exhaustive/dense sweep plus special values
/// (NaN, ties, overflow) -- see the frame-trig investigation for the verification method.
fn round(x: f64) -> i64 {
    if x.is_nan() {
        return 0;
    }
    let floor = x.floor();
    let rounded = if x - floor >= 0.5 { floor + 1.0 } else { floor };
    rounded as i64
}

struct Tables {
    tan_half: Vec<f64>,
    sin: Vec<f64>,
}

fn tables() -> &'static Tables {
    static TABLES: OnceLock<Tables> = OnceLock::new();
    TABLES.get_or_init(|| {
        let mut tan_half = Vec::with_capacity(65536);
        let mut sin = Vec::with_capacity(65536);
        for index in 0..65536 {
            let alpha = to_radians(-degrees(index));
            tan_half.push(libm::tan(alpha / 2.0));
            sin.push(libm::sin(alpha));
        }
        Tables { tan_half, sin }
    })
}

fn degrees(index: i32) -> f64 {
    f64::from(index) * 90.0 / 65536.0 - 45.0
}

/// Folds degrees into [-45, 45).
fn fold(degrees: f64) -> f64 {
    ((degrees + 45.0) % 90.0 + 90.0) % 90.0 - 45.0
}

/// Quantizes a frame in [-45, 45) to a 16-bit index.
fn index_of(theta: f64) -> i32 {
    (round((theta + 45.0) / 90.0 * 65536.0) & 0xFFFF) as i32
}

/// The direction of an edge as `(quarter turns 0..=3, frame index)`.
fn heading(ex: i32, ey: i32) -> (i32, i32) {
    if ex == 0 && ey == 0 {
        return (0, ZERO_FRAME);
    }
    let t = (to_degrees(libm::atan2(f64::from(ey), f64::from(ex))) + 45.0) / 90.0;
    let whole = t.floor();
    let mut quarters = whole as i32;
    let mut index = round((t - whole) * 65536.0);
    if index == 65536 {
        index = 0;
        quarters = quarters.wrapping_add(1);
    }
    (quarters.rem_euclid(4), index as i32)
}

/// The frame state of one pass over a buffer.
pub struct FramePrior {
    ring_count: usize,
    theta: Vec<f64>,
    centroid_x: Vec<f64>,
    centroid_y: Vec<f64>,
    next: Vec<i32>,
    head: Vec<i32>,
    sum_cos: f64,
    sum_sin: f64,
    vote_cos: f64,
    vote_sin: f64,
    x: i32,
    y: i32,
}

impl Default for FramePrior {
    fn default() -> Self {
        Self::new()
    }
}

impl FramePrior {
    #[must_use]
    pub fn new() -> Self {
        Self {
            ring_count: 0,
            theta: Vec::new(),
            centroid_x: Vec::new(),
            centroid_y: Vec::new(),
            next: Vec::new(),
            head: vec![-1; GRID * GRID],
            sum_cos: 0.0,
            sum_sin: 0.0,
            vote_cos: 0.0,
            vote_sin: 0.0,
            x: 0,
            y: 0,
        }
    }

    /// Result of the last `forward` / `inverse`.
    #[must_use]
    pub fn x(&self) -> i32 {
        self.x
    }

    #[must_use]
    pub fn y(&self) -> i32 {
        self.y
    }

    /// A run's start vertex is decoded: seed the frame with the nearest decoded run's.
    pub fn begin_ring(&mut self, start: V) {
        let nearest = self.nearest(f64::from(start.x), f64::from(start.y));
        let prior = if nearest < 0 { 0.0 } else { self.theta[nearest as usize] };
        self.sum_cos = PRIOR_WEIGHT * libm::cos(to_radians(4.0 * prior));
        self.sum_sin = PRIOR_WEIGHT * libm::sin(to_radians(4.0 * prior));
    }

    /// Rotates an edge into the current frame; the result is in `x()`, `y()`.
    pub fn forward(&mut self, dx: i32, dy: i32) {
        let q = self.frame_index();
        self.rotate(q, dx, dy);
    }

    /// The exact inverse of `forward` in the same frame.
    pub fn inverse(&mut self, rx: i32, ry: i32) {
        let q = self.frame_index();
        self.unrotate(q, rx, ry);
    }

    fn rotate(&mut self, q: i32, dx: i32, dy: i32) {
        if q == ZERO_FRAME {
            self.x = dx;
            self.y = dy;
            return;
        }
        let t = tables().tan_half[q as usize];
        let s = tables().sin[q as usize];
        let x1 = i64::from(dx).wrapping_sub(round(t * f64::from(dy)));
        let y1 = i64::from(dy).wrapping_add(round(s * x1 as f64));
        self.x = x1.wrapping_sub(round(t * y1 as f64)) as i32;
        self.y = y1 as i32;
    }

    fn unrotate(&mut self, q: i32, rx: i32, ry: i32) {
        if q == ZERO_FRAME {
            self.x = rx;
            self.y = ry;
            return;
        }
        let t = tables().tan_half[q as usize];
        let s = tables().sin[q as usize];
        let x1 = i64::from(rx).wrapping_add(round(t * f64::from(ry)));
        let dy = i64::from(ry).wrapping_sub(round(s * x1 as f64));
        self.x = x1.wrapping_add(round(t * dy as f64)) as i32;
        self.y = dy as i32;
    }

    /// Rotates `(dx, dy)` into the direction of the edge `(ex, ey)`.
    pub fn forward_heading(&mut self, ex: i32, ey: i32, dx: i32, dy: i32) {
        let (quarters, index) = heading(ex, ey);
        let mut qx = dx;
        let mut qy = dy;
        for _ in 0..quarters {
            let turned = qy;
            qy = qx.wrapping_neg();
            qx = turned;
        }
        self.rotate(index, qx, qy);
    }

    /// The exact inverse of `forward_heading` for the same edge.
    pub fn inverse_heading(&mut self, ex: i32, ey: i32, rx: i32, ry: i32) {
        let (quarters, index) = heading(ex, ey);
        self.unrotate(index, rx, ry);
        let mut qx = self.x;
        let mut qy = self.y;
        for _ in 0..quarters {
            let turned = qy.wrapping_neg();
            qy = qx;
            qx = turned;
        }
        self.x = qx;
        self.y = qy;
    }

    /// An edge of the current run is decoded: it votes for the run's frame.
    pub fn edge(&mut self, dx: i32, dy: i32) {
        let (c, s) = vote(dx, dy);
        self.vote_cos = c;
        self.vote_sin = s;
        self.sum_cos += c;
        self.sum_sin += s;
    }

    /// The ring `vertices[start, end)` is complete.
    pub fn end_ring(&mut self, vertices: &[V], start: usize, end: usize) {
        if end - start < MIN_RING {
            return;
        }
        self.remember(vertices, start, end, true);
    }

    /// The open run `vertices[start, end)` (a line part) is complete.
    pub fn end_line(&mut self, vertices: &[V], start: usize, end: usize) {
        if end - start < MIN_LINE {
            return;
        }
        self.remember(vertices, start, end, false);
    }

    fn remember(&mut self, vertices: &[V], start: usize, end: usize, closed: bool) {
        let mut cx = 0.0f64;
        let mut cy = 0.0f64;
        for v in &vertices[start..end] {
            cx += f64::from(v.x);
            cy += f64::from(v.y);
        }
        cx /= (end - start) as f64;
        cy /= (end - start) as f64;
        let cell = cell(cy) * GRID + cell(cx);
        let theta = own_frame(vertices, start, end, closed);
        self.theta.push(theta);
        self.centroid_x.push(cx);
        self.centroid_y.push(cy);
        self.next.push(self.head[cell]);
        self.head[cell] = self.ring_count as i32;
        self.ring_count += 1;
    }

    fn frame_index(&self) -> i32 {
        index_of(fold(to_degrees(libm::atan2(self.sum_sin, self.sum_cos)) / 4.0))
    }

    /// Index of the decoded run whose centroid is nearest `(px, py)`, or -1.
    fn nearest(&self, px: f64, py: f64) -> i32 {
        if self.ring_count == 0 {
            return -1;
        }
        let cx = cell(px) as i32;
        let cy = cell(py) as i32;
        let grid = GRID as i32;
        let mut best = -1i32;
        let mut best_distance = f64::MAX;
        for radius in 0..grid {
            for gy in 0.max(cy - radius)..=(grid - 1).min(cy + radius) {
                let full_row = (gy - cy).abs() == radius;
                let step = if full_row { 1 } else { 1.max(2 * radius) };
                let mut gx = cx - radius;
                while gx <= cx + radius {
                    if (0..grid).contains(&gx) {
                        let mut index = self.head[(gy * grid + gx) as usize];
                        while index >= 0 {
                            let i = index as usize;
                            let ddx = self.centroid_x[i] - px;
                            let ddy = self.centroid_y[i] - py;
                            let distance = ddx * ddx + ddy * ddy;
                            if distance < best_distance {
                                best_distance = distance;
                                best = index;
                            }
                            index = self.next[i];
                        }
                    }
                    gx += step;
                }
            }
            if best >= 0 && best_distance <= f64::from(radius) * f64::from(CELL) * f64::from(radius) * f64::from(CELL) {
                break;
            }
        }
        best
    }
}

/// The grid cell along one axis, clamped to the grid.
fn cell(coordinate: f64) -> usize {
    let c = ((coordinate / f64::from(CELL)).floor() as i32).wrapping_add(ORIGIN);
    c.clamp(0, GRID as i32 - 1) as usize
}

/// An edge's vote for its run's frame: its length times `e^(4i phi)`, IEEE arithmetic only.
fn vote(dx: i32, dy: i32) -> (f64, f64) {
    if dx == 0 && dy == 0 {
        return (0.0, 0.0);
    }
    let ex = f64::from(dx);
    let ey = f64::from(dy);
    let a = ex * ex - ey * ey;
    let b = 2.0 * ex * ey;
    let squared = ex * ex + ey * ey;
    let cube = squared * libm::sqrt(squared);
    ((a * a - b * b) / cube, 2.0 * a * b / cube)
}

/// Length-weighted mean wall direction modulo 90 deg of a complete run.
fn own_frame(v: &[V], start: usize, end: usize, closed: bool) -> f64 {
    let n = end - start;
    let mut c = 0.0f64;
    let mut s = 0.0f64;
    let edges = if closed { n } else { n - 1 };
    for k in 0..edges {
        let p = v[start + k];
        let q = v[start + (k + 1) % n];
        let (vc, vs) = vote(q.x.wrapping_sub(p.x), q.y.wrapping_sub(p.y));
        c += vc;
        s += vs;
    }
    if c == 0.0 && s == 0.0 { 0.0 } else { fold(to_degrees(libm::atan2(s, c)) / 4.0) }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rounding_is_javas() {
        assert_eq!(round(0.5), 1);
        assert_eq!(round(-0.5), 0);
        assert_eq!(round(1.5), 2);
        assert_eq!(round(-1.5), -1);
        assert_eq!(round(2.5), 3);
        assert_eq!(round(0.499_999_999_999_999_94), 0);
        assert_eq!(round(f64::NAN), 0);
        assert_eq!(round(1e30), i64::MAX);
        assert_eq!(round(-1e30), i64::MIN);
    }

    #[test]
    fn rotation_is_a_bijection() {
        let mut prior = FramePrior::new();
        prior.begin_ring(V::new(10, 10));
        prior.edge(30, 12);
        prior.edge(-12, 30);
        for &(dx, dy) in &[(30, 12), (-12, 30), (1, 0), (0, -7), (4000, -4000), (-1, -1), (0, 0)] {
            prior.forward(dx, dy);
            let (rx, ry) = (prior.x(), prior.y());
            prior.inverse(rx, ry);
            assert_eq!((prior.x(), prior.y()), (dx, dy));
        }
    }

    #[test]
    fn heading_rotation_is_a_bijection() {
        let mut prior = FramePrior::new();
        for &(ex, ey) in &[(5, 0), (0, 5), (-5, 0), (0, -5), (3, 4), (-7, 2), (0, 0), (100, -1)] {
            for &(dx, dy) in &[(1, 0), (0, 1), (-3, 9), (200, -150), (0, 0)] {
                prior.forward_heading(ex, ey, dx, dy);
                let (rx, ry) = (prior.x(), prior.y());
                prior.inverse_heading(ex, ey, rx, ry);
                assert_eq!((prior.x(), prior.y()), (dx, dy), "edge ({ex}, {ey})");
            }
        }
    }
}
