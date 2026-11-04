//! `mlt-oracle`: the research pipeline encoding as the vertex encoder of MapLibre Tile v2.
//!
//! * `golden-check`: the Rust codec against the Java golden master;
//! * `compare`: whole-tile MLT v2 bytes under gzip, the stock encoder against the tile oracle.

mod compare;
mod extraction;
mod golden;

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let result = match args.first().map(String::as_str) {
        Some("golden-check") => golden::run(&args[1..]),
        Some("compare") => compare::run(&args[1..]),
        Some("--help" | "-h") | None => {
            println!("usage: mlt-oracle <golden-check|compare> [options]; each command prints its usage on --help");
            Ok(())
        }
        Some(other) => Err(format!("unknown command {other}")),
    };
    if let Err(message) = result {
        eprintln!("error: {message}");
        std::process::exit(1);
    }
}
