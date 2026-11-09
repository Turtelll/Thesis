//! `mlt-oracle`: the research pipeline encoding as the vertex encoder of MapLibre Tile v2.
//!
//! * `golden-check`: the Rust codec against the Java golden master;
//! * `compare`: whole-tile MLT v2 bytes under gzip, the stock encoder against the tile oracle;
//! * `diff`: two tilesets (MVT or MLT, stock or pipeline) hold the same tiles, layers and features;
//! * `decode-time`: whole-tile decode time of MLT tilesets, stock v2 as reference.

mod compare;
mod decode_time;
mod diff;
mod extraction;
mod golden;

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let result = match args.first().map(String::as_str) {
        Some("golden-check") => golden::run(&args[1..]),
        Some("compare") => compare::run(&args[1..]),
        Some("diff") => diff::run(&args[1..]),
        Some("decode-time") => decode_time::run(&args[1..]),
        Some("--help" | "-h") | None => {
            println!("usage: mlt-oracle <golden-check|compare|diff|decode-time> [options]; each command prints its usage on --help");
            Ok(())
        }
        Some(other) => Err(format!("unknown command {other}")),
    };
    if let Err(message) = result {
        eprintln!("error: {message}");
        std::process::exit(1);
    }
}
