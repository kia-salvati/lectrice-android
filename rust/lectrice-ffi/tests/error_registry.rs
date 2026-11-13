//! Kotlin mirrors the Rust error registry as `enum class ErrorName(val code: Int)` (README §4.8). This keeps them equal.

use std::collections::BTreeMap;
use std::path::Path;

const KOTLIN_FILE: &str = "../../core/model/src/main/kotlin/app/lectrice/core/model/ErrorName.kt";

#[test]
fn kotlin_error_names_and_codes_equal_the_rust_registry() {
    let source = std::fs::read_to_string(Path::new(env!("CARGO_MANIFEST_DIR")).join(KOTLIN_FILE)).unwrap();
    let kotlin: BTreeMap<String, u32> = source
        .lines()
        .filter_map(|line| {
            let line = line.trim().trim_end_matches(',');
            let (name, rest) = line.split_once('(')?;
            let code = rest.strip_suffix(')')?.parse().ok()?;
            name.chars().all(|c| c.is_ascii_alphanumeric()).then(|| (name.to_string(), code))
        })
        .collect();
    let rust: BTreeMap<String, u32> = lectrice_ffi::error_registry().into_iter().map(|entry| (entry.name, entry.code)).collect();

    assert_eq!(kotlin, rust);
}
