# Lectrice for Android: build plan M2 → M5 (2026-10-25)

Written by Opus after the spike (GO, `../lectrice/research/2026-10-25-android-m1-results.md`). The behaviour of every
feature is in `../lectrice/docs/android-logic/` (files 01–21); the look is
`../lectrice/design/2026-10-23-android-quiet-v2.html` + its brief. This file only says **in which order** the features are
built, what each milestone needs from the core, how it is checked, and what can go wrong.

Branch: `feature/app-m2` (local, never pushed). Each milestone leaves `docs/progress/M<n>.md`. Nothing is committed until
the user asks.

## 0. Decisions newer than the android-logic files (they win)

| Topic | android-logic says | Now | Source |
|---|---|---|---|
| Core version | `v0.2.0` | **`v0.3.0`** pin (`rust/lectrice-ffi/Cargo.toml`); local dev through the `.cargo/config.toml` path override | user 2026-10-25 |
| Models | fp32 "Full quality" + official int8 "Smaller" | **Fast** (ours, q8, default, ~111 MB installed, release `model-kokoro-lectrice-fast-v1`), **Full quality** (official fp32, ~326 MB installed), **Small** (ours, w8, release `model-kokoro-lectrice-small-v1`). Official int8 dropped. **Each model has its own engine id.** | user 2026-10-25 |
| Background generation | WorkManager workers | Synthesis that must keep going (playback with the screen off, export, prepare, download) runs while **a foreground service** is up: Samsung throttles background work 4–8× (spike S7). Playback: the Media3 service; export / prepare / download: one `JobService`-style foreground service of ours (`BackgroundWorkService`, types `mediaProcessing` / `dataSync`). No WorkManager dependency (see §5). | spike S7 + Opus |
| Threads | one value | **per phone class**: 4 threads when the SoC has little cores and ≤ 8 cores at < 2.9 GHz big (S10+ class), 8 on S26 class; all cores (no pinning). Settings can override (Advanced). | variants-cores |
| Prepare mode (file 14) | conditional | **built** (S10+ class is slower than real time) | spike verdict |
| Read-ahead and the player | two callers fight | **one generation queue**: only the sentence the player is on plans read-ahead (`plan_ahead = true`); ExoPlayer's own preloads pass `plan_ahead = false`, so they merge into the core's queue instead of re-planning it | spike results §"large book" |

## 1. Architecture in short (spec §4, adapted)

Gradle modules (package root `app.lectrice`; every module is an Android library except `app`; `core:model` has no Android
imports and its tests run on the JVM):

| Module | Holds |
|---|---|
| `core:model` | domain types, `ErrorName`, repository/controller interfaces, pure helpers (toc, time left, progress, page markers, status text, export space maths) |
| `core:data` | `RustCore` (owns the uniffi `LectriceCore`, every call `suspend` on IO), repositories, mappers, SAF import, settings store, model catalog + installer |
| `core:playback` | `PlaybackService` (Media3 `MediaSessionService`), `SentenceDataSource`, playlist window, `ReadAheadGovernor`, sleep timer, the `PlaybackController` adapter |
| `core:work` | `BackgroundWorkService` (export, prepare, model download as foreground work), `AacEncoderSink` (MediaCodec + MediaMuxer + `chpl`) |
| `core:designsystem` | `LectriceTheme` (tokens light / dark / black), type, icons, components (pill, sheets, rows, banners, segmented control, switch rows) |
| `feature:library`, `feature:reader`, `feature:settings`, `feature:onboarding` | screens + ViewModels; see only `core:model` + `core:designsystem` |
| `app` | `MainActivity`, `LectriceApplication`, `AppContainer` (hand-written DI), navigator, manifest |
| `spike` | unchanged (kept for benchmarks) |

Rust: `rust/lectrice-ffi` grows the real API (spec §5.2 + file 19) and an Android composition root; the uniffi bindings are
generated into `core:data`.

**Design split (CLAUDE.md):** Opus writes the architecture, data, playback, ViewModels and the UI state contracts, and a
build brief per screen; **Fable writes the Compose UI** (design system components and screens) from that brief, against the
state classes. Opus reviews and wires.

## 2. Milestones

### M2 · Foundation, library, reader, chapters (playback faked)

Features: 02 library, 03 open PDF, 04 reader, 07 chapters, 13 position, 21 remove from library, 11 (theme, text size, the
settings shell), 12 (their states), the error registry. Playback is a `FakePlaybackController` (a timer stepping sentences)
so the pill, highlight and follow-scroll can be built and tested.

Core / FFI:
- Pin `v0.3.0` (path override for local builds).
- FFI (Android repo only): `list_recent`, `save_position`, `remove_document`, `restore_document`, `purge_removed`,
  `clear_document_audio`, `storage_usage`, `current_cleaner_version`, `error_registry`, `core_version`; records for them.
- No main-repo change.

Checks:
- JVM: `ErrorRegistryTest` (names/codes = Rust registry, through a generated list), toc tree / current chapter / short label /
  section jumps / outline search (ports of the web's `toc` tests, invented inputs), `percentRead`, `timeLeft`,
  `planPageMarkers`, pill status text, contrast ≥ 4.5 for every token pair in all three themes, the colour-literal lint.
- ViewModels with fakes (`kotlinx-coroutines-test`): library paging/sorting, import queue ("Waiting…"), remove + undo, reader
  open at the saved sentence, follow-scroll state.
- Device (S26 Ultra, S10+): import an invented PDF, reopen at the saved place, tap-to-jump, chapters jump, remove + undo,
  black/dark/light vs screenshots, 360 dp, 200 % font, no main-thread core calls (debug check).

Risks: Compose reader performance on a 400-page book (mitigation: `LazyColumn` per block, precomputed `AnnotatedString`s off
the main thread, keys; measure); new dependencies need verification-metadata entries through the mirror (lifecycle-viewmodel-
compose, junit, coroutines-test).

### M3 · Real playback

Features: 05 pill + sheet, 06 Listen view, 08 read-ahead + governor, 09 background playback + lock screen, 15 sleep timer,
16 voices + speed (per-book), 17 (notifications permission, media service), 12 (slow-phone tip, engine errors).

Core / FFI:
- **Engine ids for our models (no core change, same as the desktop):** Fast = `kokoro-v1.0-lectrice-fast-v1`, Small =
  `kokoro-v1.0-lectrice-small-v1`, Full = `kokoro-v1.0`. Fast and Small load through the int8 slot; `lectrice-ffi` wraps
  them in a `BundleSpeechEngine` that reports the bundle's id (`EngineConfig::Kokoro.engine_id`).
- **K1 (main repo, `feature/core-android`):** `KokoroSpeechEngine::voice_catalog()` (voices before the engine loads).
- FFI: `speak_sentence` with `plan_ahead`, `stop_read_ahead`, `stop_all_read_ahead`, `set_read_ahead_minutes`,
  `speak_voice_sample`, `speech_stats`, `unload_engine`, `EngineConfig::Kokoro { model: ModelKind, … }`.

Checks:
- JVM: governor mode table (pause 60 s, sleep timer, battery saver / ≤ 15 %, thermal SEVERE/CRITICAL, low memory),
  playlist window maths, sleep timer (pause moves the end; end of chapter), status text per state, thread-count picker per
  SoC description.
- Device: a whole chapter with the screen off (both phones; S10+ on Fast), earbuds next/prev = one sentence, phone call and
  unplug pause, lock-screen title = sentence, Now Bar on the S26, Listen view over the lock screen with the setting on,
  process kill → play from media controls resumes, logcat has no sentence text (grep over 10 min), no buffering for 30 min
  at 1× and 1.5× on the S26 Ultra.

Risks: ExoPlayer preloading vs read-ahead (handled by the `plan_ahead` rule; measure rebuffers on the large invented book);
heat on the S26 at 8 threads (the governor stops read-ahead once 10 min ahead; consider 4 threads if the soak says so);
Media3 session version must match exoplayer (1.11.1).

### M4 · Models, export, storage, prepare, about

Features: 01 first run + model download (three models), 10 export (M4B only, speed baked in, partial export, space checks),
11 (model page, storage row), 14 prepare (built), 17 (battery page, `manageSpaceActivity`, backup rules), 18 about +
licenses, 20 storage screen.

Core / FFI:
- **K2 (main repo): Prepare jobs (C21)** — `StartPrepare` / `CancelPrepare` / `PrepareStatus`, priority = export, writes
  to the audio cache, `retain_around` keeps a pinned range (14-day limit). Tests in `lectrice-app`.
- FFI: export API (`start_export`, `estimate_export`, `pause/resume/cancel/get/list/delete/mark_saved`), the
  `AudioEncoderSink` callback trait and an `AudioExporter` adapter that forwards PCM parts to Kotlin and assembles the M4B
  (MediaMuxer + a `chpl` box written by Rust), `unpack_model_archive` (tar + bzip2, the desktop's pins), prepare API.

Checks:
- JVM: export estimate/space formulas (§3.11 of file 10) and range suggestion, download resume maths, manifest parsing,
  license file list test, model catalog (each model its own engine id).
- Device: download Fast over Wi-Fi, airplane mode mid-download → resumes; import a model archive offline; export a
  generated book at 1.5× (duration ÷ 1.5 ± 2 %), chapter marks in two players, Save to Audiobooks moves the file; prepare a
  chapter while charging on the S10+ and play it at 1.5× with no "Catching up…"; "Clear storage" opens our Storage screen.

Risks: MediaMuxer AAC + `chpl` post-step (M4B chapters); Android 15's 6-hour limit for `mediaProcessing` (parts + resume);
GitHub release downloads on mobile networks (resumable Range requests); APK size with three ABI-free native libs (arm64 only).

### M5 · Release polish

Signed release build config (key outside the repo, documented), R8 rules for JNA/uniffi, baseline profile and macrobenchmark
module (if the mirror has the artifacts), the 400-page generated benchmark book, TalkBack pass, reduced motion, landscape,
tablets (640 dp column). GitHub Actions release workflow is **written but not run** (needs the user's secrets).

## 3. Test data rule

Every test document is invented (generated text or the mockup's "The Long Book"). Real-book checks read `~/Desktop/*.pdf`
pushed to the phone, are skipped when missing, and log ids/counts only.

## 4. Builds on this laptop

Every heavy build runs as `flock ~/.cache/lectrice-heavy-build.lock …`, Gradle with `-Xmx2g --max-workers=2`
(`-Dorg.gradle.jvmargs=-Xmx2g`), one at a time. Maven Central through `-Plectrice.mavenCentralMirror=
https://maven.aliyun.com/repository/central` when `repo1.maven.org` is blocked (signatures still checked).

## 5. Dependencies added (each with its reason)

| Dependency | Why |
|---|---|
| `androidx.lifecycle:lifecycle-viewmodel-compose`, `lifecycle-runtime-compose` | ViewModels per screen, `collectAsStateWithLifecycle` |
| `androidx.media3:media3-session` | MediaSession, lock screen, notification, Bluetooth (M3) |
| `junit:junit`, `kotlinx-coroutines-test` | JVM tests |
| Rust `tar`, `bzip2` (desktop's pins) | unpacking a downloaded/imported model archive (M4) |

Not added, on purpose: DataStore (a tiny `SharedPreferences`-backed `SettingsStore` behind the same interface is enough),
WorkManager (our own foreground service; generation must be in the foreground anyway), navigation libraries, OkHttp, image
libraries, DI frameworks.

## 6. Pluggable engines

- Kotlin: `ModelCatalog` (in `core:data`) lists the models (id, engine id, size, URL, SHA-256, folder, model kind); the
  `EngineFactory` turns the chosen model + thread count into an `EngineConfig` for the core. Nothing else knows model files.
- Rust: `lectrice-ffi` `composition::load_engine(EngineConfig) -> Arc<dyn SpeechEngine>` is the only factory. Another engine
  becomes one more `EngineConfig` variant + one branch there (or a Kotlin-side engine bridged through a callback trait),
  without touching playback, read-ahead or export.
