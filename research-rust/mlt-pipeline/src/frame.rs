//! `encoder/prediction/mechanism/FramePrior.java`: the frame of a ring predicted from the rings
//! already decoded (`QUAD_CORNER_PREVFRAME_FIRST_ONLY`), and the exactly invertible integer rotation
//! of an edge into it.
//!
//! The tables are built from `libm` (the musl port of the fdlibm kernels behind Java's `StrictMath`)
//! and `round` is Java's `Math.round` (ties toward +infinity), so they match Java bit for bit.

use crate::buffer::V;
use std::sync::OnceLock;

/// Opt-in call counters (`--features frame-stats`): how often the steps of the frame prior run, so
/// the cost model of a decode can be checked against data. A no-op otherwise.
pub mod stats {
    /// What is counted.
    #[derive(Clone, Copy)]
    pub enum Counter {
        /// Edge votes (`vote`, one `sqrt` and two divisions each).
        Vote,
        /// `frame_index` calls (one per framed vertex).
        FrameIndex,
        /// Rotations that are not the identity (table loads and three shifts).
        Rotate,
        /// Rings remembered.
        Remember,
    }

    /// The counter names, in `Counter` order.
    pub const NAMES: [&str; 4] = ["vote", "frame_index", "rotate", "remember"];

    #[cfg(feature = "frame-stats")]
    mod on {
        use super::Counter;
        use std::sync::atomic::{AtomicU64, Ordering::Relaxed};

        static COUNTS: [AtomicU64; 4] = [const { AtomicU64::new(0) }; 4];

        #[inline]
        pub fn bump(counter: Counter, by: u64) {
            COUNTS[counter as usize].fetch_add(by, Relaxed);
        }

        pub fn reset() {
            for c in &COUNTS {
                c.store(0, Relaxed);
            }
        }

        pub fn snapshot() -> [u64; 4] {
            std::array::from_fn(|i| COUNTS[i].load(Relaxed))
        }
    }

    #[cfg(feature = "frame-stats")]
    pub use on::{reset, snapshot};

    /// Counts one event (or `by`) when the `frame-stats` feature is on.
    #[inline(always)]
    pub fn bump(counter: Counter, by: u64) {
        #[cfg(feature = "frame-stats")]
        on::bump(counter, by);
        #[cfg(not(feature = "frame-stats"))]
        let _ = (counter, by);
    }
}

use stats::{Counter, bump};

/// Builds the rotation tables now instead of on the first rotation (the cold-start cost a reader
/// pays once per process).
pub fn init_tables() {
    let _ = int_tables();
}

const PRIOR_WEIGHT: f64 = 50.0;
const MIN_RING: usize = 3;

const DEGREES_TO_RADIANS: f64 = 0.017_453_292_519_943_295;

fn to_radians(degrees: f64) -> f64 {
    degrees * DEGREES_TO_RADIANS
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

/// Bits of the integer frame index (4096 frames over 90 deg, 0.022 deg apart).
const INT_FRAMES: i32 = 4096;
const INT_ZERO_FRAME: i32 = INT_FRAMES / 2;
const Q16: u32 = 16;
const Q16_HALF: i64 = 1 << (Q16 - 1);

/// What the integer frame reads instead of calling trigonometry (`FramePrior.IntTables` in Java):
/// built once from `libm` (fdlibm, as Java's `StrictMath`), rounded to integers.
struct IntTables {
    /// `round(atan(k / 4096) * 8192 / pi)`, `k = 0..=4096`: a slope's angle in 1/16384 turns.
    atan: Vec<i32>,
    /// `(round(tan(a / 2) * 65536), round(sin(a) * 65536))` per frame, `a = -(q * 90 / 4096 - 45)` deg.
    shear: Vec<[i32; 2]>,
}

fn int_tables() -> &'static IntTables {
    static TABLES: OnceLock<IntTables> = OnceLock::new();
    TABLES.get_or_init(|| {
        let atan = (0..=4096)
            .map(|k| round(libm::atan(f64::from(k) / 4096.0) * (8192.0 / std::f64::consts::PI)) as i32)
            .collect();
        let shear = (0..INT_FRAMES)
            .map(|q| {
                let alpha = to_radians(-(f64::from(q) * 90.0 / f64::from(INT_FRAMES) - 45.0));
                [round(libm::tan(alpha / 2.0) * 65536.0) as i32, round(libm::sin(alpha) * 65536.0) as i32]
            })
            .collect();
        IntTables { atan, shear }
    })
}

/// The direction of `(x, y)` in units of 1/16384 turn, `-8192..=8192` (`atan2` times `8192 / pi`),
/// from one division and one table read; `(0, 0)` is 0. IEEE arithmetic and integers only.
#[inline]
fn direction(y: f64, x: f64) -> i32 {
    let (ax, ay) = (x.abs(), y.abs());
    if !(ax > 0.0 || ay > 0.0) {
        return 0;
    }
    let swap = ay > ax;
    let slope = if swap { ax / ay } else { ay / ax };
    let mut t = int_tables().atan[(slope * 4096.0 + 0.5) as usize];
    if swap {
        t = 4096 - t;
    }
    if x < 0.0 {
        t = 8192 - t;
    }
    if y < 0.0 { -t } else { t }
}

/// The integer frame index of the summed votes: a quarter of their direction, rounded.
#[inline]
fn int_frame_index(sum_sin: f64, sum_cos: f64) -> i32 {
    (((direction(sum_sin, sum_cos) + 2) >> 2) + INT_ZERO_FRAME) & (INT_FRAMES - 1)
}

/// The frame state of one pass over a buffer: the previous ring's frame, one vote per ring.
///
/// A ring's first edge is coded in the frame of the ring decoded before it; that edge's own
/// direction (modulo 90 deg) is the frame of every edge after it, and is what the next ring starts
/// from. A ring is voted for once, by that one edge: no per-edge vote, no pass over the finished
/// ring. A ring whose first edge has no direction leaves the remembered frame as it was.
pub struct FramePrior {
    x: i32,
    y: i32,
    /// The unit vector of the last remembered ring's first-edge vote.
    last_cos: f64,
    last_sin: f64,
    /// The frame index of the ring being coded: the last ring's as one wall of `PRIOR_WEIGHT`,
    /// then the first edge's vote.
    fixed_frame: i32,
    /// Whether the ring being coded has seen its first edge.
    first_edge_seen: bool,
    /// The unit vector of that first edge's vote, if it had a direction.
    first: Option<(f64, f64)>,
}

impl FramePrior {
    #[must_use]
    pub fn new() -> Self {
        Self {
            x: 0,
            y: 0,
            last_cos: 1.0,
            last_sin: 0.0,
            fixed_frame: INT_ZERO_FRAME,
            first_edge_seen: false,
            first: None,
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

    /// A ring's start vertex is decoded: seed the frame with the last ring's.
    pub fn begin_ring(&mut self) {
        self.fixed_frame = int_frame_index(PRIOR_WEIGHT * self.last_sin, PRIOR_WEIGHT * self.last_cos);
        self.first_edge_seen = false;
        self.first = None;
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
        if q == INT_ZERO_FRAME {
            self.x = dx;
            self.y = dy;
            return;
        }
        bump(Counter::Rotate, 1);
        let [t, s] = int_tables().shear[q as usize];
        let (t, s) = (i64::from(t), i64::from(s));
        let x1 = i64::from(dx) - ((t * i64::from(dy) + Q16_HALF) >> Q16);
        let y1 = i64::from(dy) + ((s * x1 + Q16_HALF) >> Q16);
        self.x = (x1 - ((t * y1 + Q16_HALF) >> Q16)) as i32;
        self.y = y1 as i32;
    }

    fn unrotate(&mut self, q: i32, rx: i32, ry: i32) {
        if q == INT_ZERO_FRAME {
            self.x = rx;
            self.y = ry;
            return;
        }
        bump(Counter::Rotate, 1);
        let [t, s] = int_tables().shear[q as usize];
        let (t, s) = (i64::from(t), i64::from(s));
        let x1 = i64::from(rx) + ((t * i64::from(ry) + Q16_HALF) >> Q16);
        let dy = i64::from(ry) - ((s * x1 + Q16_HALF) >> Q16);
        self.x = (x1 + ((t * dy + Q16_HALF) >> Q16)) as i32;
        self.y = dy as i32;
    }

    /// The edge `from -> to` of the current ring is decoded: only the first votes for the ring's
    /// frame; a zero edge has no direction and leaves the previous ring's frame.
    pub fn edge(&mut self, from: V, to: V) {
        if !self.first_edge_seen {
            let (c, s) = vote(to.x.wrapping_sub(from.x), to.y.wrapping_sub(from.y));
            if c != 0.0 || s != 0.0 {
                self.fixed_frame = int_frame_index(s, c);
                let length = (c * c + s * s).sqrt();
                self.first = Some((c / length, s / length));
            }
        }
        self.first_edge_seen = true;
    }

    /// The ring of `len` vertices begun by the last `begin_ring` is complete: it is remembered by
    /// its first edge's frame (unchanged if that edge had none).
    pub fn end_ring(&mut self, len: usize) {
        if len < MIN_RING {
            return;
        }
        bump(Counter::Remember, 1);
        if let Some((cos, sin)) = self.first {
            self.last_cos = cos;
            self.last_sin = sin;
        }
    }

    fn frame_index(&self) -> i32 {
        bump(Counter::FrameIndex, 1);
        self.fixed_frame
    }
}

impl Default for FramePrior {
    fn default() -> Self {
        Self::new()
    }
}

/// An edge's vote for its ring's frame: its length times `e^(4i phi)`, IEEE arithmetic only.
fn vote(dx: i32, dy: i32) -> (f64, f64) {
    if dx == 0 && dy == 0 {
        return (0.0, 0.0);
    }
    bump(Counter::Vote, 1);
    let ex = f64::from(dx);
    let ey = f64::from(dy);
    let a = ex * ex - ey * ey;
    let b = 2.0 * ex * ey;
    let squared = ex * ex + ey * ey;
    let cube = squared * libm::sqrt(squared);
    ((a * a - b * b) / cube, 2.0 * a * b / cube)
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
    fn the_direction_table_stays_within_one_unit_of_atan2() {
        let mut angle = -180.0f64;
        while angle <= 180.0 {
            for length in [1.0f64, 3.0, 250.0, 123_456.0] {
                let (y, x) = (length * libm::sin(to_radians(angle)), length * libm::cos(to_radians(angle)));
                let exact = libm::atan2(y, x) * (8192.0 / std::f64::consts::PI);
                let tabulated = f64::from(direction(y, x));
                assert!((tabulated - exact).abs() < 1.01, "{angle} deg: {tabulated} vs {exact}");
            }
            angle += 0.37;
        }
        assert_eq!(direction(0.0, 0.0), 0);
        assert_eq!(direction(0.0, 5.0), 0);
        assert_eq!(direction(0.0, -5.0), 8192);
        assert_eq!(direction(5.0, 0.0), 4096);
        assert_eq!(direction(-5.0, 0.0), -4096);
        assert_eq!(direction(7.0, 7.0), 2048);
        assert_eq!(int_tables().atan[4096], 2048);
        assert_eq!(int_tables().shear[INT_ZERO_FRAME as usize], [0, 0]);
    }

    proptest::proptest! {
        #![proptest_config(proptest::prelude::ProptestConfig::with_cases(5_000))]

        #[test]
        fn the_integer_rotation_is_a_bijection_in_every_frame(
            wall_x in -8000i32..8000, wall_y in -8000i32..8000, dx in -9000i32..9000, dy in -9000i32..9000,
        ) {
            let mut prior = FramePrior::new();
            prior.begin_ring();
            prior.edge(V::new(0, 0), V::new(wall_x, wall_y));
            prior.forward(dx, dy);
            let (rx, ry) = (prior.x(), prior.y());
            prior.inverse(rx, ry);
            proptest::prop_assert_eq!((prior.x(), prior.y()), (dx, dy));
        }
    }

    /// A ring fed to the prior the way `FramePass` does: begin, one edge per vertex, end.
    fn feed_ring(prior: &mut FramePrior, ring: &[V]) {
        prior.begin_ring();
        for w in ring.windows(2) {
            prior.edge(w[0], w[1]);
        }
        prior.end_ring(ring.len());
    }

    /// A rectangle with sides along `angle` degrees, `a` and `b` long.
    fn tilted_rect(angle: f64, a: f64, b: f64, origin: (i32, i32)) -> Vec<V> {
        let (c, s) = (libm::cos(to_radians(angle)), libm::sin(to_radians(angle)));
        let p = |u: f64, v: f64| V::new(origin.0 + (u * c - v * s).round() as i32, origin.1 + (u * s + v * c).round() as i32);
        vec![p(0.0, 0.0), p(a, 0.0), p(a, b), p(0.0, b)]
    }

    #[test]
    fn the_first_edge_sets_the_frame_of_the_edges_after_it() {
        let mut prior = FramePrior::new();
        let ring = tilted_rect(33.0, 3000.0, 2000.0, (100, 100));
        prior.begin_ring();
        // the first edge is coded in the previous ring's frame (none yet: the tile's axes)
        prior.forward(ring[1].x - ring[0].x, ring[1].y - ring[0].y);
        assert_eq!((prior.x(), prior.y()), (ring[1].x - ring[0].x, ring[1].y - ring[0].y));
        prior.edge(ring[0], ring[1]);
        // the next wall (across the first) is nearly axis-parallel in the first edge's frame
        prior.forward(ring[2].x - ring[1].x, ring[2].y - ring[1].y);
        assert!(prior.x().abs() <= 8 && (prior.y() - 2000).abs() <= 8, "({}, {})", prior.x(), prior.y());
        prior.edge(ring[1], ring[2]);
        // later edges do not move it
        let before = prior.fixed_frame;
        prior.edge(ring[2], ring[3]);
        assert_eq!(prior.fixed_frame, before);
    }

    #[test]
    fn the_next_ring_starts_in_the_frame_of_the_last_rings_first_edge() {
        let mut prior = FramePrior::new();
        feed_ring(&mut prior, &tilted_rect(33.0, 3000.0, 2000.0, (0, 0)));
        let second = tilted_rect(33.0, 800.0, 500.0, (5000, 5000));
        prior.begin_ring();
        prior.forward(second[1].x - second[0].x, second[1].y - second[0].y);
        assert!(prior.x() > 790 && prior.y().abs() <= 8, "({}, {})", prior.x(), prior.y());
    }

    #[test]
    fn a_zero_first_edge_keeps_the_previous_frame() {
        let mut prior = FramePrior::new();
        feed_ring(&mut prior, &tilted_rect(33.0, 3000.0, 2000.0, (0, 0)));
        let degenerate = [V::new(7, 7), V::new(7, 7), V::new(50, 90), V::new(9, 400)];
        feed_ring(&mut prior, &degenerate);
        // the frame of the ring after it is still the 33 degree one
        prior.begin_ring();
        assert_eq!(prior.fixed_frame, int_frame_index(PRIOR_WEIGHT * prior.last_sin, PRIOR_WEIGHT * prior.last_cos));
        assert_ne!(prior.fixed_frame, INT_ZERO_FRAME);
    }

    proptest::proptest! {
        #![proptest_config(proptest::prelude::ProptestConfig::with_cases(3_000))]

        #[test]
        fn the_first_only_rotation_is_a_bijection(
            rings in proptest::collection::vec(proptest::collection::vec((-9000i32..9000, -9000i32..9000), 1..8), 1..6),
            dx in -9000i32..9000, dy in -9000i32..9000,
        ) {
            let mut prior = FramePrior::new();
            for ring in &rings {
                let ring: Vec<V> = ring.iter().map(|&(x, y)| V::new(x, y)).collect();
                prior.begin_ring();
                for w in ring.windows(2) {
                    prior.forward(dx, dy);
                    let (rx, ry) = (prior.x(), prior.y());
                    prior.inverse(rx, ry);
                    proptest::prop_assert_eq!((prior.x(), prior.y()), (dx, dy));
                    prior.edge(w[0], w[1]);
                }
                prior.end_ring(ring.len());
            }
        }
    }
}
