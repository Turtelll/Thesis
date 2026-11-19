//! Stages S4, S6 and S7 for one stream: zigzag, byte width and byte planes, written as the stream's
//! bytes (`encoder/stream/StreamCodec.java`, `ByteWidth`, `Transpose`, `PayloadBytes`). Only the terminal of the tile oracle's grid is ported: escape with byte planes.

/// How a stream's unsigned values become bytes: the byte terminal of the tile oracle's grid.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Terminal {
    /// `ByteWidth.ESCAPE` with `Transpose.INTERLEAVED_PLANES`: a 1-byte main stream where `0xFF`
    /// marks an exception, the exceptions at their own width, all as byte planes.
    EscapePlanes,
}

impl Terminal {
    /// The `ByteWidth` ordinal of the header (byte 1, bits 0-3).
    #[must_use]
    pub fn width_code(self) -> u8 {
        match self {
            Self::EscapePlanes => 3,
        }
    }

    /// The `Transpose` ordinal of the header (byte 0, bits 6-7).
    #[must_use]
    pub fn transpose_code(self) -> u8 {
        match self {
            Self::EscapePlanes => 1,
        }
    }

    /// The terminal a header's width and transpose codes name, if it is the one.
    #[must_use]
    pub fn from_codes(width: u8, transpose: u8) -> Option<Self> {
        match (width, transpose) {
            (3, 1) => Some(Self::EscapePlanes),
            _ => None,
        }
    }

    /// The name suffix `ByteWidth` + `Transpose.suffix()` give.
    #[must_use]
    pub fn name(self) -> &'static str {
        match self {
            Self::EscapePlanes => "ESCAPE_PLANES",
        }
    }
}

const ESCAPE_MARKER: u32 = 0xFF;
const WIDTH_MASK: u8 = 0b11;
const ESCAPE_FLAG: u8 = 1 << 2;
const EXCEPTION_SHIFT: u8 = 3;

/// `(n << 1) ^ (n >> 31)`.
#[must_use]
pub fn zigzag(n: i32) -> u32 {
    ((n << 1) ^ (n >> 31)) as u32
}

/// The inverse of [`zigzag`].
#[must_use]
pub fn unzigzag(v: u32) -> i32 {
    ((v >> 1) as i32) ^ -((v & 1) as i32)
}

/// Writes `value` as an unsigned LEB128 varint.
pub fn put_varint(out: &mut Vec<u8>, value: u32) {
    let mut rest = value;
    while rest & !0x7F != 0 {
        out.push(((rest & 0x7F) | 0x80) as u8);
        rest >>= 7;
    }
    out.push(rest as u8);
}

/// A cursor over a byte slice.
pub struct Reader<'a> {
    data: &'a [u8],
    position: usize,
}

impl<'a> Reader<'a> {
    #[must_use]
    pub fn new(data: &'a [u8]) -> Self {
        Self { data, position: 0 }
    }

    pub fn read_byte(&mut self) -> u8 {
        let b = self.data[self.position];
        self.position += 1;
        b
    }

    pub fn read_varint(&mut self) -> u32 {
        let mut value = 0u32;
        let mut shift = 0u32;
        loop {
            let b = self.read_byte();
            value |= u32::from(b & 0x7F).wrapping_shl(shift);
            if b & 0x80 == 0 {
                return value;
            }
            shift += 7;
        }
    }

    pub fn read_bytes(&mut self, length: usize) -> &'a [u8] {
        let bytes = &self.data[self.position..self.position + length];
        self.position += length;
        bytes
    }

    #[must_use]
    pub fn remaining(&self) -> usize {
        self.data.len() - self.position
    }
}

/// The narrowest of 1, 2, 4 bytes that holds the unsigned `max`.
fn min_width(max: u64) -> usize {
    if max < 1 << 8 {
        1
    } else if max < 1 << 16 {
        2
    } else {
        4
    }
}

fn code_of(width: usize) -> u8 {
    match width {
        1 => 0,
        2 => 1,
        _ => 2,
    }
}

fn width_of(code: u8) -> usize {
    match code {
        0 => 1,
        1 => 2,
        2 => 4,
        _ => panic!("bad width code {code}"),
    }
}

/// `values`, each as `width` little-endian bytes, as byte planes: all low bytes, then all second bytes...
fn write_planes(out: &mut Vec<u8>, values: &[u32], width: usize) {
    let n = values.len();
    let base = out.len();
    out.resize(base + n * width, 0);
    for (i, &v) in values.iter().enumerate() {
        for b in 0..width {
            out[base + b * n + i] = (v >> (8 * b)) as u8;
        }
    }
}

fn read_planes(data: &[u8], n: usize, width: usize) -> Vec<u32> {
    (0..n)
        .map(|i| {
            let mut v = 0u32;
            for b in 0..width {
                v |= u32::from(data[b * n + i]) << (8 * b);
            }
            v
        })
        .collect()
}

/// Appends one non-empty stream of signed residuals (`x, y` interleaved).
pub fn encode(out: &mut Vec<u8>, signed: &[i32], terminal: Terminal) {
    let mapped: Vec<u32> = signed.iter().map(|&n| zigzag(n)).collect();
    match terminal {
        Terminal::EscapePlanes => write_escape_planes(out, &mapped),
    }
}

fn write_escape_planes(out: &mut Vec<u8>, mapped: &[u32]) {
    let n = mapped.len();
    let mut max = 0u64;
    let mut exceptions = 0usize;
    let mut max_exception = 0u64;
    for &v in mapped {
        let v = u64::from(v);
        max = max.max(v);
        if v >= u64::from(ESCAPE_MARKER) {
            exceptions += 1;
            max_exception = max_exception.max(v - u64::from(ESCAPE_MARKER));
        }
    }
    let width = min_width(max);
    let exception_width = min_width(max_exception);
    let pays = width > 1 && (n as u64) + (exceptions as u64) * (exception_width as u64) < (n as u64) * (width as u64);
    if pays {
        let mut main = Vec::with_capacity(n);
        let mut tail = Vec::with_capacity(exceptions);
        for &v in mapped {
            if v >= ESCAPE_MARKER {
                main.push(ESCAPE_MARKER);
                tail.push(v - ESCAPE_MARKER);
            } else {
                main.push(v);
            }
        }
        out.push(code_of(1) | ESCAPE_FLAG | code_of(exception_width) << EXCEPTION_SHIFT);
        write_planes(out, &main, 1);
        write_planes(out, &tail, exception_width);
    } else {
        out.push(code_of(width));
        write_planes(out, mapped, width);
    }
}

/// The inverse of [`encode`]: the signed residuals of a stream of `value_count` values.
pub fn decode(input: &mut Reader<'_>, value_count: usize, terminal: Terminal) -> Vec<i32> {
    let mapped = match terminal {
        Terminal::EscapePlanes => read_escape_planes(input, value_count),
    };
    mapped.into_iter().map(unzigzag).collect()
}

fn read_escape_planes(input: &mut Reader<'_>, n: usize) -> Vec<u32> {
    let descriptor = input.read_byte();
    let width = width_of(descriptor & WIDTH_MASK);
    let mut main = read_planes(input.read_bytes(n * width), n, width);
    if descriptor & ESCAPE_FLAG == 0 {
        return main;
    }
    let exception_width = width_of((descriptor >> EXCEPTION_SHIFT) & WIDTH_MASK);
    let exceptions = main.iter().filter(|&&v| v == ESCAPE_MARKER).count();
    let tail = read_planes(input.read_bytes(exceptions * exception_width), exceptions, exception_width);
    let mut next = 0;
    for v in &mut main {
        if *v == ESCAPE_MARKER {
            *v = ESCAPE_MARKER + tail[next];
            next += 1;
        }
    }
    main
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn zigzag_matches_java() {
        assert_eq!(zigzag(0), 0);
        assert_eq!(zigzag(-1), 1);
        assert_eq!(zigzag(1), 2);
        assert_eq!(zigzag(i32::MIN), u32::MAX);
        assert_eq!(zigzag(i32::MAX), u32::MAX - 1);
        for n in [0, 1, -1, 12345, -98765, i32::MIN, i32::MAX] {
            assert_eq!(unzigzag(zigzag(n)), n);
        }
    }

    #[test]
    fn terminals_round_trip() {
        let streams: Vec<Vec<i32>> = vec![
            vec![0, 0],
            vec![1, -1, 2, -2, 300, -300, 70000, -70000],
            vec![i32::MIN, i32::MAX, 0, 1],
            (0..500).map(|i| (i * 7919) % 2000 - 1000).collect(),
        ];
        let terminal = Terminal::EscapePlanes;
        for s in &streams {
            let mut out = Vec::new();
            encode(&mut out, s, terminal);
            let mut r = Reader::new(&out);
            assert_eq!(&decode(&mut r, s.len(), terminal), s);
            assert_eq!(r.remaining(), 0);
        }
    }
}
