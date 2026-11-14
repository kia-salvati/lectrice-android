# UI brief · S10+ re-check polish (2026-11-13, Opus → Fable)

Three small UI fixes found on the Galaxy S10+ re-check (`docs/progress/M2-recheck.md`). Compose only; no logic,
no new strings unless listed. Keep the existing style (theme tokens `LectriceTheme.colors` / `.type`, no visible
scrollbars, 48 dp touch targets).

**Do not edit** these three files at all:
`app/src/main/kotlin/app/lectrice/LectriceApplication.kt`, `app/.../MainActivity.kt`,
`feature/settings/src/main/kotlin/app/lectrice/feature/settings/SettingsScreen.kt`.

## P1 · Content scrolls under the status bar icons (Settings, Voice model page, Storage, Licenses, S1–S4)
On these pages the `TopBar` (`core/designsystem/.../Components.kt`, `statusBarsPadding()` inside it) scrolls away with
the content, so text then runs under the clock and battery icons (screens: `small-13-settings-lower.png`,
`small-15-model-page-bottom.png` in `~/Desktop/lectrice-android-builds/2026-11-13-recheck/screens/`).

Build:
1. In `core/designsystem/.../Components.kt` add
   `@Composable fun StatusBarScrim(modifier: Modifier = Modifier)`: a `Box` with
   `Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(LectriceTheme.colors.bg)`.
   Solid `bg` (works for light, dark and black themes).
2. Draw it as the **last child** (on top) of the outer `Box` of each of these screens, aligned `TopCenter`:
   `ModelScreens.kt` (Welcome, ChooseModel, Download, Ready, ModelPage), `StorageScreen.kt`, `LicensesScreen.kt`.
   Skip Settings: `SettingsScreen.kt` is off-limits (Opus adds the scrim there).
3. The reader, the Listen view and the library are **not** changed (the reader already fades text under the status
   bar; the library's header is fine).

## P2 · Storage (T3) says "No voice installed" / "No books yet." while it loads
File 20 §"Loading": rows and the summary card show "…" until the numbers arrive. In `StorageScreen.kt`, while
`state.loading` is true: under "Voice models" show one `ListRow("…")` instead of `storage_no_voice`; under "Books"
show the "…" text line instead of `storage_no_books` (same style as `orLoading` uses). After loading, unchanged.

## P3 · Speed segments unreadable at 200 % font on a 360 dp screen
`SegmentedControl` (`Components.kt`) squeezes six labels ("0.75×" … "2×") into one 48 dp row, so at font scale 2.0 they
show as "0.…", "1.…" (screen `small-37-more-200.png`). Used in the player sheet, the Listen view's speed sheet, the
export page and Settings.

Build: inside `SegmentedControl`, when the labels don't fit on one row, lay the options out in **two rows**
(`ceil(n / 2)` per row, each row its own rounded pill with the same drawing: `bg2`, `accentContainer` for the selected
segment, `line` dividers and outline), 8 dp between rows. "Don't fit" = any label's measured width (with
`LectriceTheme.type.label`, via `rememberTextMeasurer`) + 8 dp padding is wider than `rowWidth / n` (use
`BoxWithConstraints`). Each segment stays ≥ 48 dp tall and keeps `selectable(role = RadioButton)`; the
`contentDescription` stays on the whole control. One row is unchanged at normal font sizes.

## Check
`flock ~/.cache/lectrice-heavy-build.lock ./gradlew -Dorg.gradle.jvmargs=-Xmx2g --max-workers=2
-Plectrice.mavenCentralMirror=https://maven.aliyun.com/repository/public :app:assembleDebug testDebugUnitTest -q`
must pass. Don't use a phone. Write what you did in `docs/progress/M2-recheck-ui.md` (short).
