# Versions (M1 spike, 2026-10-24)

Every version the Android build uses, where it came from and why. The Gradle-side pins live in
`gradle/libs.versions.toml`; the Rust pins in `rust/lectrice-ffi/Cargo.toml` and `Cargo.lock`; the native archives in
`native/SHA256SUMS`.

## Laptop tools (installed by the user's download block, plan §3a)

| Item | Version | Where | On disk |
|---|---|---|---|
| JDK (Eclipse Temurin) | 21.0.13+9 | `~/.local/opt/jdk-21` | 347 MB |
| Android cmdline-tools | 19.0 (build 13114758), in `cmdline-tools/latest` | `~/Android/Sdk` | 158 MB |
| platform-tools (adb) | 37.0.1 | `~/Android/Sdk` | 22 MB |
| Platforms | android-36 (rev 2), android-37.0 (rev 2) | `~/Android/Sdk` | 287 MB |
| Build-tools | 36.1.0, 37.0.0 | `~/Android/Sdk` | 301 MB |
| NDK | r29, 29.0.14206865 | `~/Android/Sdk/ndk` | 2.4 GB |
| Rust | 1.99.1 stable + target `aarch64-linux-android` | rustup | 145 MB for the target |
| cargo-ndk | 4.1.2 | `~/.cargo/bin` | 2.3 MB |
| ONNX Runtime venv (desk check, S6) | as installed | `~/.local/opt/ort-venv` | 184 MB |
| int8 Kokoro bundle | `kokoro-int8-multi-lang-v1_0` (model file `model.int8.onnx`, confirmed) | `../lectrice/models/` | 183 MB |

Not installed, on purpose: cmdline-tools 23 ("latest" on Google's site), because it needs the Android CLI, which this
network can't reach. Android Studio (not needed for the spike).

Environment (`~/.zshenv`, already there; this step changed nothing):
`JAVA_HOME=~/.local/opt/jdk-21`, `ANDROID_HOME=~/Android/Sdk`, `ANDROID_NDK_HOME=$ANDROID_HOME/ndk/29.0.14206865`,
plus `PATH` for the JDK, cmdline-tools and platform-tools.

## Build versions picked in S1

| Item | Version | Why |
|---|---|---|
| Gradle (wrapper) | **9.8.2** | Current release on services.gradle.org (built 2026-10-19). Distribution checked against `distributionSha256Sum` 21bd5da2…e389607 and wrapper jar against 3b8a2577…ce68e98, both from services.gradle.org. |
| Android Gradle plugin | **9.4.1** | Newest stable AGP in Google's Maven metadata (updated 2026-10-22). Builds with Gradle 9.8.2 and JDK 21. |
| Kotlin (Compose compiler plugin) | **2.4.21** | Newest stable on Maven Central (2.5.0 is beta). AGP 9 compiles Kotlin itself ("built-in Kotlin"), so there is no `kotlin-android` plugin. |
| Compose BOM | **2026.09.00** | Newest stable BOM (material3 1.4.0, ui 1.12.1). |
| activity-compose | **1.13.0** | Newest stable (1.14 is alpha). |
| core-ktx | **1.19.1** | Newest stable; brings `FileProvider` for sharing the result file. |
| Media3 (exoplayer, datasource) | **1.11.1** | Newest stable; the plan's pick. |
| JNA | **5.19.1** (`@aar`) | Newest; uniffi's Kotlin bindings call through JNA. The `aar` carries `libjnidispatch.so` for arm64. |
| kotlinx-coroutines-android | **1.11.0** | Newest stable. |
| compileSdk | **37** (`android-37.0`) | Compose foundation 1.12.1 (in BOM 2026.09.00) refuses to build against 36. |
| targetSdk | **36** | Android 16 behaviour; nothing in the spike needs 37's. |
| minSdk | **29** | Plan §5.2 (also the cargo-ndk `--platform`). |
| uniffi | **0.32.2** | Newest (2026-09-23); the spec's pick. |
| sherpa-onnx crate | **1.13.8** (`sherpa1`), **2.0.0** (`sherpa2`, published 2026-10-23) | The two sets the spike compares. |
| tracing / tracing-subscriber / serde_json / thiserror | 0.1.44 / 0.3.23 / 1.0.154 / 2.0.21 | The same exact versions as the core's workspace. |

## Native archives (`native/SHA256SUMS`)

| Archive | SHA-256 | Gives |
|---|---|---|
| `sherpa-onnx-v1.13.8-android.tar.bz2` | `2ff63469…6b4f44` | `libsherpa-onnx-c-api.so` 4.5 MB, `libonnxruntime.so` 22.2 MB |
| `sherpa-onnx-v2.0.0-android.tar.bz2` | `578650d0…e0f5d` | `libsherpa-onnx-c-api.so` 4.3 MB, `libonnxruntime.so` 22.2 MB |
| `pdfium-android-arm64.tgz` (`chromium/7881`) | `16d23bb8…a839` | `libpdfium.so` 6.3 MB |

All four `.so` files and our `liblectrice_ffi.so` have 16 KB (`0x4000`) aligned LOAD segments.

## Mirrors used (this network)

- **Google Maven** (`maven.google.com` / `dl.google.com`) answers 404 to every artifact here. The Google groups
  (`com.android.*`, `androidx.*`, `com.google.*`) come from **`https://maven.aliyun.com/repository/google`**, set in the
  gitignored `local.properties` as `lectrice.googleMavenMirror`. Without that key the build uses `google()`.
  Every downloaded file is listed in `gradle/verification-metadata.xml` with its SHA-256, and the PGP signature is
  checked where the artifact is signed (`verify-signatures` on; 46 keys in `gradle/verification-keyring.keys`). AndroidX,
  Media3 and AGP are signed with Google's key `EB4C1BFD4F042F6DDDCCEC917721F63BD38B4796` ("Google Inc. (Linux Packages
  Signing Authority)", looked up on keyserver.ubuntu.com). 63 files have no signature (mostly POM/module files and a few
  old AndroidX artifacts); for those the SHA-256 recorded on the first download is trust-on-first-use from the mirror.
- **Maven Central** through `https://repo1.maven.org/maven2/` (official host) instead of `repo.maven.apache.org`, which
  this network blocks. Not a mirror.
- No SDK mirror was needed in this step.
