# Lectrice for Android

The Android app for Lectrice. The main project, its decisions and its rules live in the sibling
repo `../lectrice` (GitHub: kia-salvati/lectrice). **Read `../lectrice/CLAUDE.md` first**; its
rules, agent workflow and "how the user likes to work" apply here too.

## Key documents (in ../lectrice/docs/)
- `2026-10-23-android-spec.md`: Android v1 design (layers, the `lectrice-ffi` API, Media3
  playback, model download, export, theme, Telegram principles).
- `2026-10-23-android-m1-spike-plan.md`: the go/no-go spike on the user's phones.
- `../lectrice/research/2026-10-23-android-options.md`: why Kotlin + Compose.

## Decisions
- Kotlin + Jetpack Compose, applying Telegram's principles. **Never copy Telegram code** (GPL).
- **One shared Rust core, never forked:** depend on `lectrice-domain`, `lectrice-app`,
  `lectrice-infra` from kia-salvati/lectrice via Cargo git dependencies pinned to release tags;
  for side-by-side development use a gitignored `.cargo/config.toml` path override. Android-only
  adapters live here.
- Everything runs on the phone (no server). Test phones: Galaxy S10+ and Galaxy S26 Ultra.
- iOS, if ever, is a separate repo (`lectrice-ios`).
- Releases: signed APKs on GitHub Releases via GitHub Actions (after the first working version).
  Back up the signing key; never commit it.
- **Public repo:** no text from the user's copyrighted books in tracked files.
- License: MIT OR Apache-2.0.
- Git: author `kia <limsclaude@gmail.com>`, remote `origin` = `personal:kia-salvati/lectrice-android.git`.
