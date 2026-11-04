//! S1: feature reordering (`encoder/reorder/{Reorder, GeometryReorder}.java`), the two reorders the
//! 96-composition grid uses: `HILBERT_CENTROID_ROT_MP` and `CHAIN`.
//!
//! Nothing is transmitted: a decoder emits the order it reads. In a tile a reorder is two things,
//! so the codec exposes both:
//!
//! * [`Reorder::feature_order`] and [`Reorder::permute`]: the permutation of whole features, which
//!   the tile applies to every column (properties and ids follow);
//! * [`Reorder::vertex_pass`]: what changes inside the vertex buffer once the features sit in their
//!   new order (polygon rings rotated to start nearest the previous vertex, the points of every
//!   MultiPoint sorted along the curve).
//!
//! [`Reorder::apply`] is both in a row, `Reorder.apply` of the Java encoder.

use crate::buffer::{Buffer, Topology, V, geometry_type as gt, line_runs, vertex_runs};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Reorder {
    /// The identity.
    None,
    /// Hilbert order of the bounding-box centre, rings rotated, MultiPoint points sorted.
    HilbertCentroidRotMp,
    /// Greedy nearest-endpoint chain of whole features.
    Chain,
}

/// Resolution of the curve grid, per axis.
const MAX_ORDER: u32 = 16;

impl Reorder {
    pub const ALL: [Self; 3] = [Self::None, Self::HilbertCentroidRotMp, Self::Chain];

    #[must_use]
    pub fn suffix(self) -> &'static str {
        match self {
            Self::None => "",
            Self::HilbertCentroidRotMp => "_HILBERT_CENTROID_ROT_MP",
            Self::Chain => "_CHAIN",
        }
    }

    #[must_use]
    pub fn reorders(self) -> bool {
        self != Self::None
    }

    fn rotates(self) -> bool {
        self == Self::HilbertCentroidRotMp
    }

    fn sorts_points(self) -> bool {
        self == Self::HilbertCentroidRotMp
    }

    fn features(self, buf: &Buffer) -> Option<Features> {
        match self {
            Self::None => None,
            Self::HilbertCentroidRotMp => Features::of(buf, true),
            Self::Chain => Features::of(buf, false),
        }
    }

    /// The order the reorder emits the features of `buf` in, as indices into its feature list;
    /// `None` when the reorder leaves the buffer alone (and the identity otherwise needs no
    /// permutation of the tile's other columns).
    #[must_use]
    pub fn feature_order(self, buf: &Buffer) -> Option<Vec<usize>> {
        let features = self.features(buf)?;
        match self {
            Self::None => None,
            Self::Chain => {
                if features.count() == 0 {
                    return None;
                }
                Some(greedy_chain(&features, &buf.vertices))
            }
            Self::HilbertCentroidRotMp => {
                if features.count() < 2 && !self.sorts_points() {
                    return None;
                }
                let sort_members = self.sorts_points() && features.point_column();
                let (anchor_x, anchor_y) = features.anchors(&buf.vertices);
                let order = if sort_members {
                    let all_x: Vec<i32> = buf.vertices.iter().map(|v| v.x).collect();
                    let all_y: Vec<i32> = buf.vertices.iter().map(|v| v.y).collect();
                    order_by_key(&keys(&anchor_x, &anchor_y, &all_x, &all_y))
                } else {
                    order_by_key(&keys(&anchor_x, &anchor_y, &anchor_x, &anchor_y))
                };
                Some(order)
            }
        }
    }

    /// `buf` with its features in `order`: vertices and topology streams move together.
    #[must_use]
    pub fn permute(self, buf: &Buffer, order: &[usize]) -> Buffer {
        match self.features(buf) {
            Some(features) => features.rebuild(&buf.vertices, &buf.topology, order, false),
            None => buf.clone(),
        }
    }

    /// The vertex-level part of the reorder, on a buffer whose features are already in order.
    #[must_use]
    pub fn vertex_pass(self, buf: &Buffer) -> Buffer {
        if self != Self::HilbertCentroidRotMp {
            return buf.clone();
        }
        let Some(features) = self.features(buf) else {
            return buf.clone();
        };
        let mut vertices = buf.vertices.clone();
        if self.sorts_points() && features.point_column() {
            let all_x: Vec<i32> = vertices.iter().map(|v| v.x).collect();
            let all_y: Vec<i32> = vertices.iter().map(|v| v.y).collect();
            features.sort_multi_points(&mut vertices, &all_x, &all_y);
        }
        let identity: Vec<usize> = (0..features.count()).collect();
        features.rebuild(&vertices, &buf.topology, &identity, self.rotates())
    }

    /// `Reorder.apply`: the reordered buffer, or `buf` itself when it cannot be reordered safely.
    #[must_use]
    pub fn apply(self, buf: &Buffer) -> Buffer {
        match self.feature_order(buf) {
            Some(order) => self.vertex_pass(&self.permute(buf, &order)),
            None => self.vertex_pass(buf),
        }
    }
}

// --- space-filling curve --------------------------------------------------------------------------

/// Curve keys of the points `(xs, ys)` on a grid spanning `(ref_x, ref_y)`, at most 16 bits per axis.
fn keys(xs: &[i32], ys: &[i32], ref_x: &[i32], ref_y: &[i32]) -> Vec<i64> {
    let (mut min_x, mut min_y, mut max_x, mut max_y) = (i32::MAX, i32::MAX, i32::MIN, i32::MIN);
    for i in 0..ref_x.len() {
        min_x = min_x.min(ref_x[i]);
        min_y = min_y.min(ref_y[i]);
        max_x = max_x.max(ref_x[i]);
        max_y = max_y.max(ref_y[i]);
    }
    let span = (i64::from(max_x) - i64::from(min_x)).max(i64::from(max_y) - i64::from(min_y));
    let bits = (64 - span.leading_zeros()).max(1);
    let shift = bits.saturating_sub(MAX_ORDER);
    let grid_order = bits.min(MAX_ORDER);
    xs.iter()
        .zip(ys)
        .map(|(&x, &y)| {
            let gx = (i64::from(x) - i64::from(min_x)) >> shift;
            let gy = (i64::from(y) - i64::from(min_y)) >> shift;
            hilbert_key(grid_order, gx, gy)
        })
        .collect()
}

/// Indices sorted by key, ties keeping the original order.
fn order_by_key(keys: &[i64]) -> Vec<usize> {
    let mut idx: Vec<usize> = (0..keys.len()).collect();
    idx.sort_by_key(|&i| (keys[i], i));
    idx
}

/// Distance along the Hilbert curve of the cell `(x, y)` on a `2^order` grid.
fn hilbert_key(order: u32, x: i64, y: i64) -> i64 {
    let n = 1i64 << order;
    let mut d = 0i64;
    let (mut px, mut py) = (x, y);
    let mut s = n >> 1;
    while s > 0 {
        let rx = i64::from((px & s) != 0);
        let ry = i64::from((py & s) != 0);
        d = d.wrapping_add(s.wrapping_mul(s).wrapping_mul((3 * rx) ^ ry));
        if ry == 0 {
            if rx == 1 {
                px = n - 1 - px;
                py = n - 1 - py;
            }
            std::mem::swap(&mut px, &mut py);
        }
        s >>= 1;
    }
    d
}

// --- features -------------------------------------------------------------------------------------

/// A buffer cut into features: each owns a contiguous vertex range, its runs (rings or line parts;
/// none for points), and the entries of `num_geometries` / `num_parts` that travel with it.
struct Features {
    vertex_start: Vec<usize>,
    vertex_count: Vec<usize>,
    first_run: Vec<usize>,
    run_count: Vec<usize>,
    /// index into `num_geometries`, -1 for none
    geometry_at: Vec<i32>,
    /// first `num_parts` entry owned (polygon columns)
    part_at: Vec<usize>,
    /// `num_parts` entries owned
    part_run: Vec<usize>,
    run_starts: Vec<usize>,
    run_lengths: Vec<usize>,
    is_line: Vec<bool>,
    polygon_column: bool,
}

impl Features {
    fn new(count: usize, run_starts: Vec<usize>, is_line: Vec<bool>, polygon_column: bool, vertex_total: usize) -> Self {
        let run_lengths = (0..run_starts.len())
            .map(|r| (if r + 1 < run_starts.len() { run_starts[r + 1] } else { vertex_total }) - run_starts[r])
            .collect();
        Self {
            vertex_start: vec![0; count],
            vertex_count: vec![0; count],
            first_run: vec![0; count],
            run_count: vec![0; count],
            geometry_at: vec![-1; count],
            part_at: vec![0; count],
            part_run: vec![0; count],
            run_starts,
            run_lengths,
            is_line,
            polygon_column,
        }
    }

    fn count(&self) -> usize {
        self.vertex_start.len()
    }

    fn point_column(&self) -> bool {
        self.run_starts.is_empty()
    }

    /// `None` when the features cannot be located from the topology streams. A column of points is
    /// only a column of features when `allow_points` (the chain leaves it alone).
    fn of(buf: &Buffer, allow_points: bool) -> Option<Self> {
        let types = &buf.topology.geometry_types;
        if types.is_empty() || buf.vertices.is_empty() {
            return None;
        }
        let all_points = types.iter().all(|&t| t == gt::POINT || t == gt::MULTIPOINT);
        if all_points {
            return if allow_points { Self::of_points(buf) } else { None };
        }
        Self::of_runs(buf)
    }

    fn of_points(buf: &Buffer) -> Option<Self> {
        let t = &buf.topology;
        let mut features = Self::new(t.geometry_types.len(), Vec::new(), Vec::new(), false, buf.vertices.len());
        let mut vertex = 0usize;
        let mut geometry = 0usize;
        for (f, &ty) in t.geometry_types.iter().enumerate() {
            features.vertex_start[f] = vertex;
            features.geometry_at[f] = -1;
            let mut count = 1i32;
            if ty == gt::MULTIPOINT {
                count = *t.num_geometries.get(geometry)?;
                features.geometry_at[f] = geometry as i32;
                geometry += 1;
            }
            if count <= 0 {
                return None;
            }
            features.vertex_count[f] = count as usize;
            vertex += count as usize;
        }
        (vertex == buf.vertices.len()).then_some(features)
    }

    fn of_runs(buf: &Buffer) -> Option<Self> {
        let t = &buf.topology;
        let starts = vertex_runs(t, buf.vertices.len());
        if starts.len() < 2 {
            return None;
        }
        let is_line = line_runs(t, starts.len())?;
        let polygon_column = t.contains_polygon();
        let mut features = Self::new(t.geometry_types.len(), starts.clone(), is_line, polygon_column, buf.vertices.len());

        let mut run = 0usize;
        let mut geometry_cursor = 0usize;
        let mut part_cursor = 0usize;
        for (f, &ty) in t.geometry_types.iter().enumerate() {
            let runs: i64;
            features.geometry_at[f] = -1;
            features.part_at[f] = part_cursor;
            if polygon_column && (ty == gt::POLYGON || ty == gt::MULTIPOLYGON) {
                let polygons = if ty == gt::MULTIPOLYGON {
                    let c = *t.num_geometries.get(geometry_cursor)?;
                    features.geometry_at[f] = geometry_cursor as i32;
                    geometry_cursor += 1;
                    c
                } else {
                    1
                };
                let mut total = 0i64;
                for _ in 0..polygons {
                    total += i64::from(*t.num_parts.get(part_cursor)?);
                    part_cursor += 1;
                }
                features.part_run[f] = polygons as usize;
                runs = total;
            } else if ty == gt::MULTILINESTRING {
                let c = *t.num_geometries.get(geometry_cursor)?;
                features.geometry_at[f] = geometry_cursor as i32;
                geometry_cursor += 1;
                runs = i64::from(c);
            } else if ty == gt::POINT || ty == gt::MULTIPOINT {
                return None;
            } else {
                runs = 1;
            }
            if runs <= 0 || run as i64 + runs > starts.len() as i64 {
                return None;
            }
            let runs = runs as usize;
            features.first_run[f] = run;
            features.run_count[f] = runs;
            features.vertex_start[f] = starts[run];
            let last = run + runs - 1;
            features.vertex_count[f] = starts[last] + features.run_lengths[last] - starts[run];
            run += runs;
        }
        (run == starts.len()).then_some(features)
    }

    /// The centre of every feature's bounding box.
    fn anchors(&self, vertices: &[V]) -> (Vec<i32>, Vec<i32>) {
        let n = self.count();
        let mut xs = vec![0i32; n];
        let mut ys = vec![0i32; n];
        for f in 0..n {
            let first = vertices[self.vertex_start[f]];
            let (mut min_x, mut max_x, mut min_y, mut max_y) = (first.x, first.x, first.y, first.y);
            for v in &vertices[self.vertex_start[f]..self.vertex_start[f] + self.vertex_count[f]] {
                min_x = min_x.min(v.x);
                max_x = max_x.max(v.x);
                min_y = min_y.min(v.y);
                max_y = max_y.max(v.y);
            }
            xs[f] = ((i64::from(min_x) + i64::from(max_x)) / 2) as i32;
            ys[f] = ((i64::from(min_y) + i64::from(max_y)) / 2) as i32;
        }
        (xs, ys)
    }

    /// Sorts the points of every MultiPoint along the curve, on the grid spanning `(ref_x, ref_y)`.
    fn sort_multi_points(&self, vertices: &mut [V], ref_x: &[i32], ref_y: &[i32]) {
        for f in 0..self.count() {
            if self.geometry_at[f] < 0 || self.vertex_count[f] < 2 {
                continue;
            }
            let from = self.vertex_start[f];
            let n = self.vertex_count[f];
            let xs: Vec<i32> = vertices[from..from + n].iter().map(|v| v.x).collect();
            let ys: Vec<i32> = vertices[from..from + n].iter().map(|v| v.y).collect();
            let sorted = order_by_key(&keys(&xs, &ys, ref_x, ref_y));
            let original: Vec<V> = vertices[from..from + n].to_vec();
            for (i, &s) in sorted.iter().enumerate() {
                vertices[from + i] = original[s];
            }
        }
    }

    /// Emits vertices and topology streams in the new order, the streams permuted with their
    /// features so the runs derive the same lengths from the result.
    fn rebuild(&self, vertices: &[V], t: &Topology, order: &[usize], rotate: bool) -> Buffer {
        let mut out: Vec<V> = Vec::with_capacity(vertices.len());
        let mut types = Vec::with_capacity(t.geometry_types.len());
        let mut num_geometries = Vec::with_capacity(t.num_geometries.len());
        let mut num_parts = Vec::with_capacity(t.num_parts.len());
        let mut num_rings = Vec::with_capacity(t.num_rings.len());
        let mut previous = V::new(0, 0);

        for &f in order {
            types.push(t.geometry_types[f]);
            if self.geometry_at[f] >= 0 {
                num_geometries.push(t.num_geometries[self.geometry_at[f] as usize]);
            }
            for i in 0..self.part_run[f] {
                num_parts.push(t.num_parts[self.part_at[f] + i]);
            }
            if self.run_count[f] == 0 {
                // Points: no runs, only the vertex range.
                out.extend_from_slice(&vertices[self.vertex_start[f]..self.vertex_start[f] + self.vertex_count[f]]);
                previous = *out.last().expect("a point feature has vertices");
                continue;
            }
            for r in self.first_run[f]..self.first_run[f] + self.run_count[f] {
                let start = self.run_starts[r];
                let length = self.run_lengths[r];
                let shift = if rotate && !self.is_line[r] && length >= 3 { nearest(vertices, start, length, previous) } else { 0 };
                for v in 0..length {
                    out.push(vertices[start + (shift + v) % length]);
                }
                previous = *out.last().expect("a run has vertices");
                if self.polygon_column {
                    num_rings.push(length as i32);
                } else {
                    num_parts.push(length as i32);
                }
            }
        }
        Buffer::new(out, Topology { geometry_types: types, num_geometries, num_parts, num_rings })
    }
}

/// The offset in the ring whose vertex is nearest `previous` (Manhattan, first on a tie).
fn nearest(vertices: &[V], start: usize, length: usize, previous: V) -> usize {
    let mut best = 0;
    let mut best_distance = i64::MAX;
    for k in 0..length {
        let v = vertices[start + k];
        let d = (i64::from(v.x) - i64::from(previous.x)).abs() + (i64::from(v.y) - i64::from(previous.y)).abs();
        if d < best_distance {
            best_distance = d;
            best = k;
        }
    }
    best
}

// --- the chain ------------------------------------------------------------------------------------

/// A uniform grid over the entry endpoints, answering "nearest unused endpoint to `(x, y)`" in
/// Manhattan distance, ties on the lower feature index (what a first-wins linear scan yields).
struct EndpointGrid {
    point_x: Vec<i32>,
    point_y: Vec<i32>,
    min_x: i64,
    min_y: i64,
    cols: i64,
    rows: i64,
    cell_size: i64,
    cell_start: Vec<usize>,
    cell_items: Vec<usize>,
}

impl EndpointGrid {
    fn build(point_x: Vec<i32>, point_y: Vec<i32>) -> Self {
        const TARGET_PER_CELL: usize = 2;
        let size = point_x.len();
        let min_x = i64::from(*point_x.iter().min().expect("a grid has points"));
        let min_y = i64::from(*point_y.iter().min().expect("a grid has points"));
        let max_x = i64::from(*point_x.iter().max().expect("a grid has points"));
        let max_y = i64::from(*point_y.iter().max().expect("a grid has points"));
        let span_x = max_x - min_x + 1;
        let span_y = max_y - min_y + 1;
        let target = (size / TARGET_PER_CELL).max(1);
        let side = ((span_x as f64) * (span_y as f64) / target as f64).sqrt().max(1.0);
        let cell_size = (side.ceil() as i64).clamp(1, i64::from(i32::MAX));
        let cols = ((span_x + cell_size - 1) / cell_size).max(1);
        let rows = ((span_y + cell_size - 1) / cell_size).max(1);
        let mut grid = Self { point_x, point_y, min_x, min_y, cols, rows, cell_size, cell_start: Vec::new(), cell_items: Vec::new() };

        let cells = (cols * rows) as usize;
        let mut counts = vec![0usize; cells + 1];
        for i in 0..size {
            counts[grid.cell_of(grid.point_x[i], grid.point_y[i]) + 1] += 1;
        }
        for c in 0..cells {
            counts[c + 1] += counts[c];
        }
        let mut cursor = counts.clone();
        let mut items = vec![0usize; size];
        for i in 0..size {
            let cell = grid.cell_of(grid.point_x[i], grid.point_y[i]);
            items[cursor[cell]] = i;
            cursor[cell] += 1;
        }
        grid.cell_start = counts;
        grid.cell_items = items;
        grid
    }

    fn cell_of(&self, x: i32, y: i32) -> usize {
        let cx = (self.cols - 1).min((i64::from(x) - self.min_x) / self.cell_size);
        let cy = (self.rows - 1).min((i64::from(y) - self.min_y) / self.cell_size);
        (cy * self.cols + cx) as usize
    }

    /// The nearest feature (the point index is the feature index) not in `used`.
    fn nearest(&self, x: i32, y: i32, used: &[bool]) -> usize {
        let origin_x = (self.cols - 1).min(0i64.max((i64::from(x) - self.min_x) / self.cell_size));
        let origin_y = (self.rows - 1).min(0i64.max((i64::from(y) - self.min_y) / self.cell_size));
        let mut best_distance = i64::MAX;
        let mut best: Option<usize> = None;
        let max_ring = self.cols.max(self.rows);
        for ring in 0..=max_ring {
            if best.is_some() && (ring - 1) * self.cell_size > best_distance {
                break;
            }
            for cy in (origin_y - ring)..=(origin_y + ring) {
                if cy < 0 || cy >= self.rows {
                    continue;
                }
                let edge_row = cy == origin_y - ring || cy == origin_y + ring;
                for cx in (origin_x - ring)..=(origin_x + ring) {
                    if cx < 0 || cx >= self.cols {
                        continue;
                    }
                    if !edge_row && cx != origin_x - ring && cx != origin_x + ring {
                        continue;
                    }
                    let cell = (cy * self.cols + cx) as usize;
                    for s in self.cell_start[cell]..self.cell_start[cell + 1] {
                        let i = self.cell_items[s];
                        if used[i] {
                            continue;
                        }
                        let d = (i64::from(self.point_x[i]) - i64::from(x)).abs()
                            + (i64::from(self.point_y[i]) - i64::from(y)).abs();
                        let better = d < best_distance || (d == best_distance && best.is_none_or(|b| i < b));
                        if better {
                            best_distance = d;
                            best = Some(i);
                        }
                    }
                }
            }
        }
        best.expect("an unused feature remains")
    }
}

/// Greedily takes the unused feature whose first vertex is nearest the cursor, then moves the
/// cursor to that feature's last vertex.
fn greedy_chain(features: &Features, vertices: &[V]) -> Vec<usize> {
    let n = features.count();
    let entry: Vec<V> = (0..n).map(|f| vertices[features.vertex_start[f]]).collect();
    let exit: Vec<V> = (0..n).map(|f| vertices[features.vertex_start[f] + features.vertex_count[f] - 1]).collect();
    let grid = EndpointGrid::build(entry.iter().map(|v| v.x).collect(), entry.iter().map(|v| v.y).collect());
    let mut used = vec![false; n];
    let mut order = Vec::with_capacity(n);
    let (mut cx, mut cy) = (0i32, 0i32);
    for _ in 0..n {
        let best = grid.nearest(cx, cy, &used);
        used[best] = true;
        order.push(best);
        cx = exit[best].x;
        cy = exit[best].y;
    }
    order
}

#[cfg(test)]
mod tests {
    use super::*;

    fn square(x: i32, y: i32, n: i32) -> [V; 4] {
        [V::new(x, y), V::new(x + n, y), V::new(x + n, y + n), V::new(x, y + n)]
    }

    fn polygons(origins: &[(i32, i32)]) -> Buffer {
        let mut vertices = Vec::new();
        for &(x, y) in origins {
            vertices.extend(square(x, y, 10));
        }
        let n = origins.len();
        Buffer::new(
            vertices,
            Topology {
                geometry_types: vec![gt::POLYGON; n],
                num_geometries: vec![],
                num_parts: vec![1; n],
                num_rings: vec![4; n],
            },
        )
    }

    #[test]
    fn hilbert_visits_every_cell_once() {
        let mut seen: Vec<i64> = (0..16).flat_map(|x| (0..16).map(move |y| hilbert_key(4, x, y))).collect();
        seen.sort_unstable();
        assert_eq!(seen, (0..256).collect::<Vec<_>>());
    }

    #[test]
    fn chain_matches_a_linear_scan() {
        let buf = polygons(&[(100, 100), (0, 0), (500, 500), (12, 3), (90, 110), (3, 12)]);
        let features = Features::of(&buf, false).unwrap();
        let order = greedy_chain(&features, &buf.vertices);
        // linear first-wins reference
        let entry: Vec<V> = (0..6).map(|f| buf.vertices[features.vertex_start[f]]).collect();
        let exit: Vec<V> = (0..6).map(|f| buf.vertices[features.vertex_start[f] + 3]).collect();
        let mut used = [false; 6];
        let mut cursor = V::new(0, 0);
        let mut expected = vec![];
        for _ in 0..6 {
            let mut best = usize::MAX;
            let mut best_d = i64::MAX;
            for f in 0..6 {
                if used[f] {
                    continue;
                }
                let d = (i64::from(entry[f].x) - i64::from(cursor.x)).abs() + (i64::from(entry[f].y) - i64::from(cursor.y)).abs();
                if d < best_d {
                    best_d = d;
                    best = f;
                }
            }
            used[best] = true;
            expected.push(best);
            cursor = exit[best];
        }
        assert_eq!(order, expected);
    }

    #[test]
    fn apply_permutes_features_and_keeps_the_vertex_multiset() {
        let buf = polygons(&[(900, 900), (0, 0), (450, 450)]);
        let out = Reorder::HilbertCentroidRotMp.apply(&buf);
        assert_eq!(out.vertices.len(), buf.vertices.len());
        assert_eq!(out.topology, buf.topology);
        let mut a: Vec<_> = buf.vertices.iter().map(|v| v.key()).collect();
        let mut b: Vec<_> = out.vertices.iter().map(|v| v.key()).collect();
        a.sort_unstable();
        b.sort_unstable();
        assert_eq!(a, b);
        assert_eq!(out.vertices[0], V::new(0, 0));
    }
}
