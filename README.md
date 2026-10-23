# Lectrice for Android

The Android app for [Lectrice](https://github.com/kia-salvati/lectrice), a free, fully local reader
that cleans PDFs into a reading view and reads them aloud with the Kokoro voice.

- **App:** Kotlin + Jetpack Compose.
- **Core:** the shared Rust core from the main Lectrice repo (`lectrice-domain`, `lectrice-app`,
  `lectrice-infra`), used as Cargo git dependencies pinned to release tags, through the
  `lectrice-ffi` bridge crate in this repo (uniffi → Kotlin).

Status: planning. See the Android spec and the first spike plan in the main repo's `docs/`.

## License

Licensed under either of [Apache License, Version 2.0](LICENSE-APACHE) or [MIT license](LICENSE-MIT),
at your option. Builds that include sherpa-onnx 1.13.x also contain espeak-ng (GPL-3.0).
