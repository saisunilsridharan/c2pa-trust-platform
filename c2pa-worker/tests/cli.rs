use std::process::Command;

#[test]
fn missing_asset_is_a_failure() {
    let output = Command::new(env!("CARGO_BIN_EXE_c2pa-worker"))
        .arg("/no-such-c2pa-asset.jpg")
        .output()
        .unwrap();
    assert!(!output.status.success());
    assert!(output.stdout.is_empty());
}

#[test]
fn malformed_asset_does_not_produce_a_success_report() {
    let path = std::env::temp_dir().join(format!("c2pa-malformed-{}.jpg", std::process::id()));
    std::fs::write(&path, [0xff, 0xd8, 0xff, 0x00]).unwrap();
    let output = Command::new(env!("CARGO_BIN_EXE_c2pa-worker"))
        .arg(&path)
        .output()
        .unwrap();
    std::fs::remove_file(path).unwrap();
    assert!(!output.status.success());
    assert!(output.stdout.is_empty());
}
