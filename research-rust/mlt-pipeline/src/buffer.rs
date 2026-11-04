//! The input of the codec: a vertex buffer and the four topology streams that partition it, as
//! `research-java`'s `BufferContext` holds them (`buffer/Encoding.java`, `buffer/VertexRuns.java`).
//!
//! The topology lists are the geometry length streams of the MLT geometry section:
//! `geometry_types` one entry per feature, `num_geometries` one per multi-part feature,
//! `num_parts` the rings per polygon (or, in a column without polygons, the vertices per line),
//! `num_rings` the vertices per ring (or, in a column with polygons, per line).

/// Geometry type ordinals as the MLT geometry-type stream stores them.
pub mod geometry_type {
    pub const POINT: i32 = 0;
    pub const LINESTRING: i32 = 1;
    pub const POLYGON: i32 = 2;
    pub const MULTIPOINT: i32 = 3;
    pub const MULTILINESTRING: i32 = 4;
    pub const MULTIPOLYGON: i32 = 5;
}

use geometry_type::{MULTILINESTRING, MULTIPOINT, MULTIPOLYGON, POINT, POLYGON};

/// One vertex, `(x, y)` in tile coordinates.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Default)]
pub struct V {
    pub x: i32,
    pub y: i32,
}

impl V {
    #[must_use]
    pub const fn new(x: i32, y: i32) -> Self {
        Self { x, y }
    }

    /// The 64-bit key of a position, as the Java `PositionTable` forms it.
    #[must_use]
    pub fn key(self) -> u64 {
        (u64::from(self.x as u32) << 32) | u64::from(self.y as u32)
    }
}

/// The four topology streams, no vertices.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Topology {
    pub geometry_types: Vec<i32>,
    pub num_geometries: Vec<i32>,
    pub num_parts: Vec<i32>,
    pub num_rings: Vec<i32>,
}

/// A vertex buffer and its topology.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Buffer {
    pub vertices: Vec<V>,
    pub topology: Topology,
}

impl Buffer {
    #[must_use]
    pub fn new(vertices: Vec<V>, topology: Topology) -> Self {
        Self { vertices, topology }
    }

    /// The same topology without vertices: what a decoder has read before the vertex stream.
    #[must_use]
    pub fn topology_only(&self) -> Self {
        Self { vertices: Vec::new(), topology: self.topology.clone() }
    }
}

impl Topology {
    /// Whether the column contains a (Multi)Polygon.
    #[must_use]
    pub fn contains_polygon(&self) -> bool {
        self.geometry_types.iter().any(|&t| t == POLYGON || t == MULTIPOLYGON)
    }
}

/// Start offsets of each vertex run (ring, line part), ascending, always beginning with 0.
/// A topology that yields no usable runs gives `[0]`: one run covering everything.
#[must_use]
pub fn vertex_runs(topology: &Topology, vertex_count: usize) -> Vec<usize> {
    if vertex_count == 0 {
        return vec![0];
    }
    let lengths = if topology.contains_polygon() { &topology.num_rings } else { &topology.num_parts };
    if lengths.is_empty() {
        return vec![0];
    }
    let mut starts = Vec::with_capacity(lengths.len());
    let mut offset = 0usize;
    for &length in lengths {
        if length <= 0 || offset > vertex_count {
            return vec![0];
        }
        starts.push(offset);
        offset += length as usize;
    }
    if offset == vertex_count { starts } else { vec![0] }
}

fn multi_count(topology: &Topology, cursor: usize) -> Option<i32> {
    topology.num_geometries.get(cursor).copied()
}

/// Per run (in [`vertex_runs`] order), whether it is a LineString part rather than a polygon ring,
/// or `None` when the topology does not line up with the runs or the column holds points.
#[must_use]
pub fn line_runs(topology: &Topology, run_count: usize) -> Option<Vec<bool>> {
    let polygon_column = topology.contains_polygon();
    let mut is_line = vec![false; run_count];
    let mut run = 0usize;
    let mut geometry_cursor = 0usize;
    let mut part_cursor = 0usize;
    for &t in &topology.geometry_types {
        if t == POINT || t == MULTIPOINT {
            return None;
        }
        let runs: i64;
        let mut line = false;
        if polygon_column && (t == POLYGON || t == MULTIPOLYGON) {
            let polygons = if t == MULTIPOLYGON {
                let c = multi_count(topology, geometry_cursor)?;
                geometry_cursor += 1;
                c
            } else {
                1
            };
            let mut total = 0i64;
            for _ in 0..polygons {
                let p = *topology.num_parts.get(part_cursor)?;
                part_cursor += 1;
                total += i64::from(p);
            }
            runs = total;
        } else {
            runs = if t == MULTILINESTRING {
                let c = multi_count(topology, geometry_cursor)?;
                geometry_cursor += 1;
                i64::from(c)
            } else {
                1
            };
            if runs < 0 {
                return None;
            }
            line = true;
        }
        if runs <= 0 || run as i64 + runs > run_count as i64 {
            return None;
        }
        for i in 0..runs as usize {
            is_line[run + i] = line;
        }
        run += runs as usize;
    }
    (run == run_count).then_some(is_line)
}

/// A buffer's runs and whether each is a ring or a line, as `RingReferences.Runs` derives them.
#[derive(Clone, Debug)]
pub struct Runs {
    pub starts: Vec<usize>,
    pub is_line: Vec<bool>,
    pub vertex_count: usize,
}

impl Runs {
    /// `None` when the runs are not derivable from the topology (a single run, or no line flags).
    #[must_use]
    pub fn of(topology: &Topology, vertex_count: usize) -> Option<Self> {
        let starts = vertex_runs(topology, vertex_count);
        if starts.len() < 2 {
            return None;
        }
        let is_line = line_runs(topology, starts.len())?;
        Some(Self { starts, is_line, vertex_count })
    }

    #[must_use]
    pub fn count(&self) -> usize {
        self.starts.len()
    }

    #[must_use]
    pub fn end(&self, r: usize) -> usize {
        if r + 1 < self.starts.len() { self.starts[r + 1] } else { self.vertex_count }
    }
}
