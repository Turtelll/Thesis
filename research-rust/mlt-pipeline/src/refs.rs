//! Shared-vertex references: `encoder/reference/RingReferences.java` (rings that repeat an earlier
//! ring's wall) and `LineReferences.java` (line parts that end on a position an earlier part holds),
//! with the bit stream reader and writer both use.

use crate::buffer::{Runs, Topology, V};
use rustc_hash::{FxHashMap, FxHashSet};
use std::collections::VecDeque;

const WINDOW: usize = 4;

/// Bits to index `size` candidates.
fn width(size: usize) -> u32 {
    if size <= 1 { 0 } else { usize::BITS - (size - 1).leading_zeros() }
}

/// Most significant bit first within a byte, the last byte zero-padded.
#[derive(Default)]
pub struct BitWriter {
    out: Vec<u8>,
    current: u32,
    filled: u32,
}

impl BitWriter {
    fn write(&mut self, value: u32, width: u32) {
        for b in (0..width).rev() {
            self.current = (self.current << 1) | ((value >> b) & 1);
            self.filled += 1;
            if self.filled == 8 {
                self.out.push(self.current as u8);
                self.current = 0;
                self.filled = 0;
            }
        }
    }

    fn into_bytes(mut self) -> Vec<u8> {
        if self.filled > 0 {
            self.out.push((self.current << (8 - self.filled)) as u8);
        }
        self.out
    }
}

pub struct BitReader<'a> {
    bytes: &'a [u8],
    position: usize,
}

impl<'a> BitReader<'a> {
    #[must_use]
    pub fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, position: 0 }
    }

    fn read(&mut self, width: u32) -> u32 {
        let mut value = 0u32;
        for _ in 0..width {
            let bit = (self.bytes[self.position >> 3] >> (7 - (self.position & 7))) & 1;
            value = (value << 1) | u32::from(bit);
            self.position += 1;
        }
        value
    }
}

/// What the encoder knows about the references of one buffer: its runs rotated (rings) or reversed
/// (line parts), which vertices are references, and the flag and index bit streams.
pub struct Scan {
    pub vertices: Vec<V>,
    pub referenced: Vec<bool>,
    pub flags: Vec<u8>,
    pub indices: Vec<u8>,
}

/// What a decoder needs from a reference scheme: per vertex in order, first `decode` (the vertex it
/// references, or `None` when it carries a residual), then `accept` once the vertex is known.
pub trait ReferenceWalk {
    fn decode(&mut self, i: usize, vertices: &[V]) -> Option<V>;
    fn accept(&mut self, i: usize, vertices: &[V]);
}

// --- rings ----------------------------------------------------------------------------------------

/// Rotates each polygon ring to start at the window position with the longest following run of
/// positions an earlier ring holds. Membership is rotation-invariant, so the unrotated rings decide.
fn rotate(vertices: &[V], runs: &Runs) -> Vec<V> {
    let mut out = vertices.to_vec();
    let mut earlier: FxHashSet<V> = FxHashSet::default();
    let mut window: VecDeque<(usize, usize)> = VecDeque::new();
    for r in 0..runs.count() {
        if runs.is_line[r] {
            continue;
        }
        let start = runs.starts[r];
        let n = runs.end(r) - start;
        let mut window_set: FxHashSet<V> = FxHashSet::default();
        for &(s, len) in &window {
            window_set.extend(vertices[s..s + len].iter().copied());
        }
        let mut best = 0;
        let mut best_run = 0;
        for i in 0..n {
            if !window_set.contains(&vertices[start + i]) || earlier.contains(&vertices[start + (i + n - 1) % n]) {
                continue;
            }
            let mut run = 0;
            while run < n && earlier.contains(&vertices[start + (i + run) % n]) {
                run += 1;
            }
            if run > best_run {
                best_run = run;
                best = i;
            }
        }
        for i in 0..n {
            out[start + i] = vertices[start + (best + i) % n];
        }
        earlier.extend(vertices[start..start + n].iter().copied());
        window.push_back((start, n));
        if window.len() > WINDOW {
            window.pop_front();
        }
    }
    out
}

/// What encoder and decoder both know after each completed polygon ring.
struct State {
    ring_start: Vec<usize>,
    ring_end: Vec<usize>,
    /// position -> latest vertex index holding it
    seen: FxHashMap<u64, i32>,
    window: VecDeque<(usize, usize)>,
    window_vertices: usize,
    /// position -> candidate index, rebuilt per ring
    distinct: FxHashMap<u64, i32>,
    candidates: Vec<usize>,
}

impl State {
    fn new(vertex_count: usize) -> Self {
        Self {
            ring_start: vec![0; vertex_count],
            ring_end: vec![0; vertex_count],
            seen: FxHashMap::default(),
            window: VecDeque::new(),
            window_vertices: 0,
            distinct: FxHashMap::default(),
            candidates: Vec::new(),
        }
    }

    fn source_of(&self, position: V) -> i32 {
        self.seen.get(&position.key()).copied().unwrap_or(-1)
    }

    /// The vertex before `source` on its ring: the reverse walk.
    fn previous_on_source(&self, source: usize, vertices: &[V]) -> V {
        let start = self.ring_start[source];
        let length = self.ring_end[source] - start;
        vertices[start + (source - start + length - 1) % length]
    }

    fn has_candidates(&self) -> bool {
        self.window_vertices > 0
    }

    /// The distinct positions of the window, newest ring first, first occurrence kept.
    fn collect_candidates(&mut self, vertices: &[V]) {
        self.distinct.clear();
        self.candidates.clear();
        for &(s, e) in self.window.iter().rev() {
            for i in s..e {
                let key = vertices[i].key();
                if !self.distinct.contains_key(&key) {
                    self.distinct.insert(key, self.candidates.len() as i32);
                    self.candidates.push(i);
                }
            }
        }
    }

    fn candidate_index(&self, position: V) -> i32 {
        self.distinct.get(&position.key()).copied().unwrap_or(-1)
    }

    fn add_ring(&mut self, start: usize, end: usize, vertices: &[V]) {
        for i in start..end {
            self.ring_start[i] = start;
            self.ring_end[i] = end;
            self.seen.insert(vertices[i].key(), i as i32);
        }
        self.window.push_back((start, end));
        self.window_vertices += end - start;
        if self.window.len() > WINDOW {
            let (s, e) = self.window.pop_front().expect("window is non-empty");
            self.window_vertices -= e - s;
        }
    }
}

/// The shared-vertex state of one pass over a buffer's vertices in order: encoding writes the flags,
/// decoding reads them.
pub struct RingWalk<'a> {
    runs: Runs,
    flags_out: BitWriter,
    indices_out: BitWriter,
    flags_in: Option<BitReader<'a>>,
    indices_in: Option<BitReader<'a>>,
    state: State,
    run: usize,
}

impl<'a> RingWalk<'a> {
    fn new(runs: Runs, flags_in: Option<BitReader<'a>>, indices_in: Option<BitReader<'a>>) -> Self {
        let state = State::new(runs.vertex_count);
        Self { runs, flags_out: BitWriter::default(), indices_out: BitWriter::default(), flags_in, indices_in, state, run: 0 }
    }

    /// A decoding walk over `vertex_count` vertices; `topology` needs no vertices.
    ///
    /// # Panics
    /// When the runs of the buffer do not follow from the topology.
    #[must_use]
    pub fn for_decoding(topology: &Topology, vertex_count: usize, flags: &'a [u8], indices: &'a [u8]) -> Self {
        let runs = Runs::of(topology, vertex_count)
            .expect("the runs of a shared-reference buffer must follow from the topology");
        Self::new(runs, Some(BitReader::new(flags)), Some(BitReader::new(indices)))
    }

    fn advance(&mut self, i: usize) {
        while self.run + 1 < self.runs.count() && self.runs.starts[self.run + 1] <= i {
            self.run += 1;
        }
    }

    /// Encoder: whether vertex `i` is a reference; writes its flag. `vertices` holds at least `0..=i`.
    fn encode(&mut self, i: usize, vertices: &[V]) -> bool {
        self.advance(i);
        let ring = !self.runs.is_line[self.run];
        let start = self.runs.starts[self.run];
        let vertex = vertices[i];
        let source = if ring && i > start { self.state.source_of(vertices[i - 1]) } else { -1 };
        if source >= 0 {
            let referenced = vertex == self.state.previous_on_source(source as usize, vertices);
            self.flags_out.write(u32::from(referenced), 1);
            return referenced;
        }
        if ring && i == start && self.state.has_candidates() {
            self.state.collect_candidates(vertices);
            let index = self.state.candidate_index(vertex);
            self.flags_out.write(u32::from(index >= 0), 1);
            if index >= 0 {
                self.indices_out.write(index as u32, width(self.state.candidates.len()));
            }
            return index >= 0;
        }
        false
    }
}

impl ReferenceWalk for RingWalk<'_> {
    fn decode(&mut self, i: usize, vertices: &[V]) -> Option<V> {
        self.advance(i);
        let ring = !self.runs.is_line[self.run];
        let start = self.runs.starts[self.run];
        let source = if ring && i > start { self.state.source_of(vertices[i - 1]) } else { -1 };
        let flags = self.flags_in.as_mut().expect("a decoding walk");
        if source >= 0 {
            return if flags.read(1) == 1 { Some(self.state.previous_on_source(source as usize, vertices)) } else { None };
        }
        if ring && i == start && self.state.has_candidates() && flags.read(1) == 1 {
            self.state.collect_candidates(vertices);
            let w = width(self.state.candidates.len());
            let index = self.indices_in.as_mut().expect("a decoding walk").read(w) as usize;
            return Some(vertices[self.state.candidates[index]]);
        }
        None
    }

    fn accept(&mut self, i: usize, vertices: &[V]) {
        self.advance(i);
        if !self.runs.is_line[self.run] && i == self.runs.end(self.run) - 1 {
            let (s, e) = (self.runs.starts[self.run], self.runs.end(self.run));
            self.state.add_ring(s, e, vertices);
        }
    }
}

/// The references of `buffer` under `QUAD_CORNER_SHARED*`: rings rotated, which vertices are
/// references, the flag and index bits. `None` when the runs are not derivable.
#[must_use]
pub fn scan_rings(vertices: &[V], topology: &Topology) -> Option<Scan> {
    let runs = Runs::of(topology, vertices.len())?;
    let rotated = rotate(vertices, &runs);
    let mut walk = RingWalk::new(runs, None, None);
    let mut referenced = vec![false; rotated.len()];
    for i in 0..rotated.len() {
        referenced[i] = walk.encode(i, &rotated);
        walk.accept(i, &rotated);
    }
    Some(Scan { vertices: rotated, referenced, flags: walk.flags_out.into_bytes(), indices: walk.indices_out.into_bytes() })
}

// --- line parts -------------------------------------------------------------------------------------

/// The positions of every finished line part in first-seen order. Append-only.
struct Dictionary {
    index: FxHashMap<u64, i32>,
    entries: Vec<usize>,
}

impl Dictionary {
    fn new() -> Self {
        Self { index: FxHashMap::default(), entries: Vec::new() }
    }

    fn size(&self) -> usize {
        self.entries.len()
    }

    fn get(&self, dictionary_index: usize, vertices: &[V]) -> V {
        vertices[self.entries[dictionary_index]]
    }

    fn index_of(&self, position: V) -> i32 {
        self.index.get(&position.key()).copied().unwrap_or(-1)
    }

    fn add_part(&mut self, start: usize, end: usize, vertices: &[V]) {
        for i in start..end {
            let key = vertices[i].key();
            if !self.index.contains_key(&key) {
                self.index.insert(key, self.entries.len() as i32);
                self.entries.push(i);
            }
        }
    }
}

/// Reverse when the end is in the dictionary and the start is not, so the entry flag catches it.
fn should_reverse(dictionary: &Dictionary, part: &[V]) -> bool {
    part.len() >= 2 && dictionary.index_of(part[0]) < 0 && dictionary.index_of(part[part.len() - 1]) >= 0
}

fn reference(dictionary: &Dictionary, vertex: V, flags: &mut BitWriter, indices: &mut BitWriter) -> bool {
    if dictionary.size() == 0 {
        return false;
    }
    let index = dictionary.index_of(vertex);
    flags.write(u32::from(index >= 0), 1);
    if index >= 0 {
        indices.write(index as u32, width(dictionary.size()));
    }
    index >= 0
}

/// The references of one buffer under `LINE_CONTINUATION_SHARED_FRAME_HEADING`: its line parts
/// reversed where that lets the entry flag catch the part, which vertices are references, the bits.
#[must_use]
pub fn scan_lines(original: &[V], topology: &Topology) -> Option<Scan> {
    let runs = Runs::of(topology, original.len())?;
    let n = original.len();
    let mut out: Vec<V> = Vec::with_capacity(n);
    let mut referenced = vec![false; n];
    let mut dictionary = Dictionary::new();
    let mut flags = BitWriter::default();
    let mut indices = BitWriter::default();
    for r in 0..runs.count() {
        let start = runs.starts[r];
        let end = runs.end(r);
        let line = runs.is_line[r];
        let part = &original[start..end];
        if line && should_reverse(&dictionary, part) {
            out.extend(part.iter().rev().copied());
        } else {
            out.extend_from_slice(part);
        }
        for i in start..end {
            let vertex = out[i];
            let part_end = line && (i == start || i == end - 1);
            referenced[i] = part_end && reference(&dictionary, vertex, &mut flags, &mut indices);
        }
        if line {
            dictionary.add_part(start, end, &out);
        }
    }
    Some(Scan { vertices: out, referenced, flags: flags.into_bytes(), indices: indices.into_bytes() })
}

/// The decoder of [`scan_lines`] as a pass over the buffer.
pub struct LineWalk<'a> {
    runs: Runs,
    flags: BitReader<'a>,
    indices: BitReader<'a>,
    dictionary: Dictionary,
    run: usize,
}

impl<'a> LineWalk<'a> {
    /// # Panics
    /// When the runs of the buffer do not follow from the topology.
    #[must_use]
    pub fn for_decoding(topology: &Topology, vertex_count: usize, flags: &'a [u8], indices: &'a [u8]) -> Self {
        let runs = Runs::of(topology, vertex_count)
            .expect("the runs of a shared-reference buffer must follow from the topology");
        Self { runs, flags: BitReader::new(flags), indices: BitReader::new(indices), dictionary: Dictionary::new(), run: 0 }
    }

    fn advance(&mut self, i: usize) {
        while self.run + 1 < self.runs.count() && self.runs.starts[self.run + 1] <= i {
            self.run += 1;
        }
    }
}

impl ReferenceWalk for LineWalk<'_> {
    fn decode(&mut self, i: usize, vertices: &[V]) -> Option<V> {
        self.advance(i);
        let part_end = self.runs.is_line[self.run]
            && (i == self.runs.starts[self.run] || i == self.runs.end(self.run) - 1);
        if part_end && self.dictionary.size() > 0 && self.flags.read(1) == 1 {
            let w = width(self.dictionary.size());
            let index = self.indices.read(w) as usize;
            return Some(self.dictionary.get(index, vertices));
        }
        None
    }

    fn accept(&mut self, i: usize, vertices: &[V]) {
        self.advance(i);
        if self.runs.is_line[self.run] && i == self.runs.end(self.run) - 1 {
            let (s, e) = (self.runs.starts[self.run], self.runs.end(self.run));
            self.dictionary.add_part(s, e, vertices);
        }
    }
}
