//! Local provenance inspection tool. Signing awaits a configured key provider.
fn main() {
    if let Err(error) = inspect() {
        eprintln!("Inspection failed: {error}");
        std::process::exit(1);
    }
}
fn inspect() -> Result<(), Box<dyn std::error::Error>> {
    let path = std::env::args()
        .nth(1)
        .ok_or("Usage: c2pa-worker <asset-path>")?;
    let reader = c2pa::Reader::from_context(c2pa::Context::new()).with_file(path)?;
    println!("{}", reader.json());
    Ok(())
}
