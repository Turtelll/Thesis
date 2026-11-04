//! `golden-check`: the Rust codec against the Java golden master (`research dump`). For every
//! (tile, layer) buffer of an extraction and every composition of the 96-grid the dump holds a row for,
//! the payload's SHA-256 and length and the SHA-256 of the emitted topology must equal the Java ones,
//! and the payload must decode back to the emitted vertices.

use crate::extraction::{self, ManifestEntry};
use flate2::read::GzDecoder;
use mlt_pipeline::registry::best_grid;
use mlt_pipeline::{Buffer, Composition, Reorder, decode, encode_reordered};
use rayon::prelude::*;
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::io::{BufRead, BufReader};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::{Path, PathBuf};

const USAGE: &str = "golden-check [--in <extractionDir>] [--golden <dumpDir>] [--layer <name>] [--limit-buffers <n>]\n\
  Encodes every buffer of the extraction with every composition of the 96-grid that the Java golden\n\
  dump holds a row for, and compares payload SHA-256, payload length and emitted-topology SHA-256.";

#[derive(Clone)]
struct Golden {
    sha: String,
    bytes: usize,
    emitted: String,
}

pub fn hex(bytes: &[u8]) -> String {
    use std::fmt::Write;
    bytes.iter().fold(String::with_capacity(bytes.len() * 2), |mut s, b| {
        let _ = write!(s, "{b:02x}");
        s
    })
}

pub fn sha256(bytes: &[u8]) -> String {
    hex(&Sha256::digest(bytes))
}

/// `GoldenDump.topologySha`: the four lists as Java prints them, then the vertex count.
pub fn topology_sha(buffer: &Buffer) -> String {
    let t = &buffer.topology;
    let mut text = String::new();
    for list in [&t.geometry_types, &t.num_geometries, &t.num_parts, &t.num_rings] {
        let items: Vec<String> = list.iter().map(ToString::to_string).collect();
        text.push_str(&format!("{}:[{}];", list.len(), items.join(", ")));
    }
    text.push_str(&format!("v{}", buffer.vertices.len()));
    sha256(text.as_bytes())
}

#[derive(Default, Clone)]
struct Tally {
    buffers: usize,
    payload: usize,
    topology: usize,
    decode: usize,
    first: Option<String>,
}

pub fn run(args: &[String]) -> Result<(), String> {
    let mut input = PathBuf::from("../data/extractedBaseline");
    let mut golden = PathBuf::from("../data/golden/v-final-full");
    let mut layer: Option<String> = None;
    let mut limit = usize::MAX;
    let mut it = args.iter();
    while let Some(arg) = it.next() {
        let mut value = |name: &str| it.next().cloned().ok_or_else(|| format!("{name} needs a value\n{USAGE}"));
        match arg.as_str() {
            "--in" => input = PathBuf::from(value("--in")?),
            "--golden" => golden = PathBuf::from(value("--golden")?),
            "--layer" => layer = Some(value("--layer")?),
            "--limit-buffers" => limit = value("--limit-buffers")?.parse().map_err(|e| format!("--limit-buffers: {e}"))?,
            "--help" | "-h" => {
                println!("{USAGE}");
                return Ok(());
            }
            other => return Err(format!("unknown argument {other}\n{USAGE}")),
        }
    }

    let grid = best_grid();
    let index: HashMap<String, usize> = grid.iter().enumerate().map(|(i, c)| (c.name(), i)).collect();
    let (goldens, covered) = read_golden(&golden.join("buffers.tsv.gz"), &index, grid.len())?;
    println!(
        "golden dump {}: {} of {} grid compositions have a row",
        golden.display(),
        covered.len(),
        grid.len()
    );
    let missing: Vec<String> = (0..grid.len()).filter(|i| !covered.contains(i)).map(|i| grid[i].name()).collect();

    let entries: Vec<ManifestEntry> = extraction::read_manifest(&input)?
        .into_iter()
        .filter(|e| layer.as_deref().is_none_or(|l| l == e.layer_name))
        .filter(|e| goldens.contains_key(&e.key()))
        .take(limit)
        .collect();
    println!("{} buffers to check", entries.len());

    let tallies: Vec<Vec<Tally>> = entries
        .par_iter()
        .map(|entry| check_buffer(&input, entry, &grid, &goldens[&entry.key()]))
        .collect::<Result<_, String>>()?;
    let mut total = vec![Tally::default(); grid.len()];
    for per_buffer in &tallies {
        for (i, t) in per_buffer.iter().enumerate() {
            total[i].buffers += t.buffers;
            total[i].payload += t.payload;
            total[i].topology += t.topology;
            total[i].decode += t.decode;
            if total[i].first.is_none() {
                total[i].first.clone_from(&t.first);
            }
        }
    }

    println!("\n{:<88} {:>8} {:>9} {:>9} {:>7}", "row", "buffers", "payload!=", "topology!=", "decode!");
    let mut bad = 0usize;
    let mut checked = 0usize;
    for (i, t) in total.iter().enumerate() {
        if t.buffers == 0 {
            continue;
        }
        checked += t.buffers;
        bad += t.payload + t.topology + t.decode;
        let flag = if t.payload + t.topology + t.decode > 0 { "  <-- " } else { "" };
        println!(
            "{:<88} {:>8} {:>9} {:>9} {:>7}{}{}",
            grid[i].name(),
            t.buffers,
            t.payload,
            t.topology,
            t.decode,
            flag,
            t.first.as_deref().unwrap_or("")
        );
    }
    println!("\n{checked} (row, buffer) pairs compared against the golden master, {bad} differ");
    if !missing.is_empty() {
        println!(
            "{} grid compositions have no row in the dump (run `research dump --grid best` to add them):",
            missing.len()
        );
        for name in &missing {
            println!("  {name}");
        }
    }
    if bad > 0 { Err(format!("{bad} mismatches")) } else { Ok(()) }
}

/// The dump's rows of the grid, per buffer key, indexed by grid position, and the positions found.
fn read_golden(
    path: &Path,
    index: &HashMap<String, usize>,
    grid_len: usize,
) -> Result<(HashMap<String, Vec<Option<Golden>>>, std::collections::BTreeSet<usize>), String> {
    let file = std::fs::File::open(path).map_err(|e| format!("{}: {e}", path.display()))?;
    let reader = BufReader::with_capacity(1 << 20, GzDecoder::new(file));
    let mut goldens: HashMap<String, Vec<Option<Golden>>> = HashMap::new();
    let mut covered = std::collections::BTreeSet::new();
    for line in reader.lines() {
        let line = line.map_err(|e| format!("{}: {e}", path.display()))?;
        if line.starts_with('#') {
            continue;
        }
        let mut cols = line.split('\t');
        let (Some(row), Some(z), Some(x), Some(y), Some(layer), Some(sha), Some(bytes), Some(emitted)) = (
            cols.next(),
            cols.next(),
            cols.next(),
            cols.next(),
            cols.next(),
            cols.next(),
            cols.next(),
            cols.next(),
        ) else {
            continue;
        };
        let Some(&i) = index.get(row) else { continue };
        covered.insert(i);
        let key = format!("{z}\t{x}\t{y}\t{layer}");
        goldens.entry(key).or_insert_with(|| vec![None; grid_len])[i] = Some(Golden {
            sha: sha.to_string(),
            bytes: bytes.parse().map_err(|e| format!("payload_bytes: {e}"))?,
            emitted: emitted.to_string(),
        });
    }
    Ok((goldens, covered))
}

fn check_buffer(
    dir: &Path,
    entry: &ManifestEntry,
    grid: &[Composition],
    goldens: &[Option<Golden>],
) -> Result<Vec<Tally>, String> {
    let buffer = extraction::read_buffer(dir, entry)?;
    let mut tallies = vec![Tally::default(); grid.len()];
    if buffer.vertices.is_empty() {
        return Ok(tallies);
    }
    let mut reordered: HashMap<Reorder, Buffer> = HashMap::new();
    for (i, composition) in grid.iter().enumerate() {
        let Some(golden) = &goldens[i] else { continue };
        let input = reordered.entry(composition.reorder).or_insert_with(|| composition.reorder.apply(&buffer));
        let tally = &mut tallies[i];
        tally.buffers = 1;
        let outcome = catch_unwind(AssertUnwindSafe(|| {
            let encoded = encode_reordered(input, composition);
            let decoded = decode(&encoded.payload, &encoded.emitted.topology, encoded.emitted.vertices.len());
            (encoded, decoded)
        }));
        let where_ = format!("{}/{}/{} {}", entry.z, entry.x, entry.y, entry.layer_name);
        let Ok((encoded, decoded)) = outcome else {
            tally.decode = 1;
            tally.first = Some(format!("panic at {where_}"));
            continue;
        };
        if sha256(&encoded.payload) != golden.sha || encoded.payload.len() != golden.bytes {
            tally.payload = 1;
            tally.first = Some(format!("{where_}: {} bytes, java {}", encoded.payload.len(), golden.bytes));
        }
        if golden.emitted != "-" && topology_sha(&encoded.emitted) != golden.emitted {
            tally.topology = 1;
            tally.first.get_or_insert_with(|| format!("{where_}: emitted topology differs"));
        }
        if decoded != encoded.emitted.vertices {
            tally.decode = 1;
            tally.first.get_or_insert_with(|| format!("{where_}: decode differs"));
        }
    }
    Ok(tallies)
}
