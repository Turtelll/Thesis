//! Reads what `research-java`'s `extract` wrote (`ExtractionReader.java`): `manifest.json`, and per
//! entry the `.bin` (x, y as little-endian `i32` pairs) plus the `.json` topology sidecar.

use mlt_pipeline::{Buffer, Topology, V};
use serde::Deserialize;
use std::path::Path;

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ManifestEntry {
    pub z: u32,
    pub x: u32,
    pub y: u32,
    pub layer_name: String,
    pub vertex_count: usize,
    pub bin_file: String,
    pub json_file: String,
}

impl ManifestEntry {
    /// `z/x/y/layer`, the key a buffer has in the golden dump.
    #[must_use]
    pub fn key(&self) -> String {
        format!("{}\t{}\t{}\t{}", self.z, self.x, self.y, self.layer_name)
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Sidecar {
    geometry_types: Vec<i32>,
    num_geometries: Vec<i32>,
    num_parts: Vec<i32>,
    num_rings: Vec<i32>,
}

pub fn read_manifest(dir: &Path) -> Result<Vec<ManifestEntry>, String> {
    let text = std::fs::read_to_string(dir.join("manifest.json")).map_err(|e| format!("manifest.json: {e}"))?;
    serde_json::from_str(&text).map_err(|e| format!("manifest.json: {e}"))
}

pub fn read_buffer(dir: &Path, entry: &ManifestEntry) -> Result<Buffer, String> {
    let bytes = std::fs::read(dir.join(&entry.bin_file)).map_err(|e| format!("{}: {e}", entry.bin_file))?;
    if bytes.len() < entry.vertex_count * 8 {
        return Err(format!("{}: {} bytes for {} vertices", entry.bin_file, bytes.len(), entry.vertex_count));
    }
    let vertices = bytes
        .chunks_exact(8)
        .take(entry.vertex_count)
        .map(|c| V::new(i32::from_le_bytes(c[0..4].try_into().unwrap()), i32::from_le_bytes(c[4..8].try_into().unwrap())))
        .collect();
    let text = std::fs::read_to_string(dir.join(&entry.json_file)).map_err(|e| format!("{}: {e}", entry.json_file))?;
    let sidecar: Sidecar = serde_json::from_str(&text).map_err(|e| format!("{}: {e}", entry.json_file))?;
    Ok(Buffer::new(
        vertices,
        Topology {
            geometry_types: sidecar.geometry_types,
            num_geometries: sidecar.num_geometries,
            num_parts: sidecar.num_parts,
            num_rings: sidecar.num_rings,
        },
    ))
}
