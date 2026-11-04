//! The payload of one buffer (`encoder/format/{Composition, PayloadHeader, PipelineEncoding}.java`,
//! `docs/bitstream.md`): a two-byte header carrying every choice but the reorder, an optional
//! shared-reference block, then every non-empty stream. No stream length, segment boundary or class
//! label is written; they follow from the topology streams the decoder has read before the vertices.

use crate::buffer::{Buffer, Topology, V};
use crate::predict::Prediction;
use crate::refs::{self, LineWalk, ReferenceWalk, RingWalk, Scan};
use crate::reorder::Reorder;
use crate::segmentation::{self, Layout, Segmentation};
use crate::stream::{self, Reader, Terminal, put_varint};

/// The six per-stage choices of one pipeline row. Everything but `reorder` is in the header.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct Composition {
    pub reorder: Reorder,
    pub predictor: Prediction,
    pub segmentation: Segmentation,
    pub terminal: Terminal,
}

impl Composition {
    /// `PipelineRegistry.DEFAULT`.
    pub const DEFAULT: Self = Self {
        reorder: Reorder::None,
        predictor: Prediction::QuadCornerSharedFrame,
        segmentation: Segmentation::Class,
        terminal: Terminal::EscapePlanes,
    };

    /// `Composition.name()`: `PIPE` + reorder suffix + `_` + predictor + `_` + segmentation +
    /// `_ZIGZAG_` + width + transpose suffix.
    #[must_use]
    pub fn name(&self) -> String {
        format!(
            "PIPE{}_{}_{}_ZIGZAG_{}",
            self.reorder.suffix(),
            self.predictor.name(),
            self.segmentation.name(),
            self.terminal.name()
        )
    }

    #[must_use]
    pub fn with_reorder(self, reorder: Reorder) -> Self {
        Self { reorder, ..self }
    }

    #[must_use]
    pub fn with_predictor(self, predictor: Prediction) -> Self {
        Self { predictor, ..self }
    }

    /// The two header bytes.
    #[must_use]
    pub fn header(&self) -> [u8; 2] {
        let predictor = self.predictor.code();
        let first = (predictor & 0b111) | self.segmentation.code() << 3 | self.terminal.transpose_code() << 6;
        let second = self.terminal.width_code() | (predictor >> 3) << 4;
        [first, second]
    }

    /// The inverse of [`Composition::header`]; the reorder is not in it, so the caller supplies it.
    #[must_use]
    pub fn parse_header(first: u8, second: u8, reorder: Reorder) -> Option<Self> {
        let predictor = Prediction::of_code((first & 0b111) | (second >> 4) << 3)?;
        let segmentation = Segmentation::from_code((first >> 3) & 0b11)?;
        if (first >> 5) & 1 != 0 {
            return None;
        }
        let terminal = Terminal::from_codes(second & 0b1111, (first >> 6) & 0b11)?;
        Some(Self { reorder, predictor, segmentation, terminal })
    }
}

/// What the encoder emits for one buffer: the payload and the buffer it decodes to.
pub struct Encoded {
    pub payload: Vec<u8>,
    pub emitted: Buffer,
}

/// Encodes `permuted`, a buffer whose features are already in the composition's order; the
/// reorder's vertex pass (ring rotation, MultiPoint sort) is applied here.
#[must_use]
pub fn encode(permuted: &Buffer, c: &Composition) -> Encoded {
    encode_reordered(&c.reorder.vertex_pass(permuted), c)
}

/// Encodes `reordered`, the output of `Reorder::apply`: the plain predictor's payload, or for a
/// shared predictor the smaller of that and the payload with references.
#[must_use]
pub fn encode_reordered(reordered: &Buffer, c: &Composition) -> Encoded {
    if !c.predictor.shared() {
        return Encoded { payload: encode_streams(reordered, c), emitted: reordered.clone() };
    }
    let plain = encode_streams(reordered, &c.with_predictor(c.predictor.plain()));
    let scan = match c.predictor {
        Prediction::QuadCornerShared | Prediction::QuadCornerSharedFrame => {
            refs::scan_rings(&reordered.vertices, &reordered.topology)
        }
        _ => refs::scan_lines(&reordered.vertices, &reordered.topology),
    };
    let Some(scan) = scan else {
        return Encoded { payload: plain, emitted: reordered.clone() };
    };
    let rotated = Buffer::new(scan.vertices.clone(), reordered.topology.clone());
    let shared = encode_shared(&rotated, &scan, c);
    if shared.len() < plain.len() {
        Encoded { payload: shared, emitted: rotated }
    } else {
        Encoded { payload: plain, emitted: reordered.clone() }
    }
}

/// The header and every non-empty stream of `emitted` under `c`, no references.
fn encode_streams(emitted: &Buffer, c: &Composition) -> Vec<u8> {
    let mut predictor = c.predictor.predictor();
    let layout = c.segmentation.layout(&emitted.topology, &mut predictor, emitted.vertices.len());
    let streams = segmentation::split(&layout, &predictor, &emitted.vertices, None);
    let mut out = c.header().to_vec();
    write_streams(&mut out, c, &streams);
    out
}

/// Header, the flag bits and the entry indices (each behind its byte length), the length of every
/// stream (the references take vertices out of them), then the streams.
fn encode_shared(rotated: &Buffer, scan: &Scan, c: &Composition) -> Vec<u8> {
    let mut predictor = c.predictor.predictor();
    let layout = c
        .segmentation
        .layout(&rotated.topology, &mut predictor, rotated.vertices.len())
        .without_referenced(&scan.referenced);
    let streams = segmentation::split(&layout, &predictor, &rotated.vertices, Some(&scan.referenced));
    let mut out = c.header().to_vec();
    put_varint(&mut out, scan.flags.len() as u32);
    out.extend_from_slice(&scan.flags);
    put_varint(&mut out, scan.indices.len() as u32);
    out.extend_from_slice(&scan.indices);
    for &count in &layout.value_counts {
        put_varint(&mut out, count as u32);
    }
    write_streams(&mut out, c, &streams);
    out
}

fn write_streams(out: &mut Vec<u8>, c: &Composition, streams: &[Vec<i32>]) {
    let last = streams.iter().rposition(|s| !s.is_empty());
    for (s, values) in streams.iter().enumerate() {
        if !values.is_empty() {
            stream::encode(out, values, c.terminal, Some(s) == last);
        }
    }
}

/// The inverse of [`encode`]: the vertices of a buffer of `vertex_count` vertices whose topology
/// (as the tile carries it) is `topology`.
///
/// # Panics
/// On a payload this encoder did not write, or one that does not fit the topology.
#[must_use]
pub fn decode(payload: &[u8], topology: &Topology, vertex_count: usize) -> Vec<V> {
    let mut input = Reader::new(payload);
    let first = input.read_byte();
    let second = input.read_byte();
    let c = Composition::parse_header(first, second, Reorder::None).expect("a payload of this encoder");
    let mut predictor = c.predictor.predictor();
    let mut layout = c.segmentation.layout(topology, &mut predictor, vertex_count);

    let (flags, indices);
    let mut walk: Option<Box<dyn ReferenceWalk + '_>> = None;
    if c.predictor.shared() {
        let flag_len = input.read_varint() as usize;
        flags = input.read_bytes(flag_len);
        let index_len = input.read_varint() as usize;
        indices = input.read_bytes(index_len);
        let counts: Vec<usize> = (0..layout.stream_count()).map(|_| input.read_varint() as usize).collect();
        layout = Layout { value_counts: counts, ..layout };
        walk = Some(match c.predictor {
            Prediction::QuadCornerShared | Prediction::QuadCornerSharedFrame => {
                Box::new(RingWalk::for_decoding(topology, vertex_count, flags, indices))
            }
            _ => Box::new(LineWalk::for_decoding(topology, vertex_count, flags, indices)),
        });
    }

    let counts = &layout.value_counts;
    let last = counts.iter().rposition(|&n| n > 0);
    let mut streams: Vec<Vec<i32>> = Vec::with_capacity(counts.len());
    for (s, &n) in counts.iter().enumerate() {
        streams.push(if n == 0 { Vec::new() } else { stream::decode(&mut input, n, c.terminal, Some(s) == last) });
    }
    assert_eq!(input.remaining(), 0, "bytes left after the last stream");
    segmentation::join(&layout, &predictor, &streams, vertex_count, walk.as_deref_mut())
}
