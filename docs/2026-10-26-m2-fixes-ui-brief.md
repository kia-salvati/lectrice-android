# M2 fixes · UI brief for Fable (2026-10-26, Opus)

Source: the S10+ device report `~/Desktop/lectrice-android-builds/m2-2026-10-26/S10-checks.md` (screens in `screens/`).
Design reference: `../lectrice/design/2026-10-23-android-quiet-v2.html`. Only the changes below; no new options, no
other restyling. Logic is done by Opus; you only change composables, icons and strings.

**Don't touch:** `MainActivity.kt`, `LectriceApplication.kt`, the manifest, any ViewModel or
`*UiState.kt`, `BottomSheet.kt`, `LayoutGuards.kt`, and in `Pill.kt` the gesture/coordinate code (`detectPillGestures`,
`longPressScrubber`, `handlers`, the `onGloballyPositioned` / `DisposableEffect` lines). In `SettingsScreen.kt` change only
`VoiceSheet`. No new dependencies. No visible scrollbars. Keep `const`/`val` names for every number (no magic values).

## 1 · D8: previous-sentence icon (and one more icon with the same bug)
Compose doesn't move the pen back to the subpath start after `z`, so a relative `m` after `z` starts from the wrong
point. In `core/designsystem/.../Icons.kt` replace the two path strings exactly:
- `skip_previous`: `M6 6h2v12H6zM9.5 12l8.5 6V6z`
- `delete_sweep`: `M15 16h4v2h-4zM15 8h7v2h-7zM15 12h6v2h-6zM3 18c0 1.1.9 2 2 2h6c1.1 0 2-.9 2-2V8H3v10zM14 5h-3l-1-1H6L5 5H2v2h12z`
Add a one-line comment above `icon()`: path data must use an absolute `M` after `z` (Compose keeps the last point).

## 2 · D5: voice sample spinner and equaliser (file 16)
New state (already wired): `VoiceSampleState` (`core.model`) with `phaseOf(voiceId): Phase` (`Idle`, `Loading`,
`Playing`). It is in `ReaderUiState.voiceSample`, `ExportUiState.voiceSample`, `SettingsUiState.voiceSample`,
`ModelSetupUiState.voiceSample`.
- `VoiceRows` / `VoiceRow` (`PlayerSheet.kt`, used by the player sheet and `ExportPage.kt`) and `VoiceSheet`
  (`SettingsScreen.kt`) take a `sample: VoiceSampleState` parameter. The 48 dp sample button shows, for that voice:
  `Idle` → the speaker icon (as now); `Loading` → the design system's `Spinner` at 16 dp, centred in the 48 dp button;
  `Playing` → an equaliser: 3 bars, 3 dp wide, 2 dp gap, rounded, tint = the icon's tint, heights animating between
  4 and 14 dp with offset phases (~600 ms loop); with `LocalReducedMotion` on, static bars 8/14/10 dp. Make it a
  reusable `Equalizer` composable in `core/designsystem` (`Components.kt`). The button stays tappable in every phase
  (tapping again restarts the sample).
- Content descriptions (new strings in each module that needs them): Idle "Hear %1$s" (existing), Loading
  "Making a sample of %1$s", Playing "Playing a sample of %1$s".
- S4 "voice ready" in `ModelScreens.kt` (`PlayButton(playing = false, …)`): `playing = phase == Playing`,
  `preparing = phase == Loading` (the `PlayButton` already supports `preparing`), using `Voices.DEFAULT_VOICE_ID`.

## 3 · D2: library recent row at large font (`LibraryScreen.kt`)
At 200 % font the recent row's text column is ~2 characters wide because the trailing "Continue · 100 %" (+ its bar)
keeps its full width (`screens/74-library-360dp-font200.png`). Rule: when `fontScale >= STACK_FONT_SCALE` (the existing
constant; note the test ran at exactly 2.0, so check the comparison actually takes effect for the **recent rows**, not
only the card), the trailing action moves **below** the title/meta, start-aligned with the text column, and the text
column gets the full width next to the cover. Below that scale, keep today's layout but give the text column priority:
the trailing column must never be wider than 40 % of the row (it wraps its label instead).

## 4 · D3: 200 % font at 360 dp
- **Pill status** (`rememberPillStatus` / `StatusButton` in `Pill.kt`; presentation only): when
  `LocalDensity.current.fontScale >= 2f` (reuse the reader's `TALL_FONT_SCALE`; make it `internal` if needed) and the
  screen is narrower than `WIDE_STATUS`, show only the page part of `player.location` (the last `StatusPart`, "p. 3")
  instead of "3 … · p. 3". The full label stays in the content description.
- **Listen view top bar** (`ListenTopBar`): `height(TOP_BAR_HEIGHT)` → `heightIn(min = TOP_BAR_HEIGHT)` so both lines
  (book title, section title) fit; the teleprompter must start below it, never over it (`screens/77-…`).
- **Listen view tools row** (`ToolButton`, 4 tools): at `fontScale >= 2f` and width < `WIDE_STATUS`, lay the 4 tools out
  as a 2 × 2 grid of equal-width cells, each a 48 dp-min-height row with the icon (or "1×") left of its caption, caption
  `maxLines = 1` without clipping. Otherwise keep the row, but cells use `heightIn(min = TOOL_SIZE)` instead of a fixed
  `size`, so "Speed" under "1×" is never cut.
- **Player sheet chapter line** ("3 The Winter Storm › 3.2 S…", wraps to 4+ lines at 200 %): `maxLines = 2`,
  `TextOverflow.Ellipsis`.

## 5 · D7: R7 slow-phone tip (`SlowPhoneTip`, `ReaderScreen.kt`)
- The close "×" must be a 48 dp touch target (`LectriceIconButton`), icon 20 dp, content description "Close tip"
  (string exists? if not, add `reader_tip_close`).
- The tip must not cover the text: while the tip is shown, the reader list's bottom content padding grows by the tip's
  measured height (`onSizeChanged` → a `Dp` state; reset to 0 when the tip goes away).

## Done means
`flock ~/.cache/lectrice-heavy-build.lock ./gradlew -Dorg.gradle.jvmargs=-Xmx2g --max-workers=2 -Plectrice.mavenCentralMirror=https://maven.aliyun.com/repository/public :app:compileDebugKotlin :core:designsystem:testDebugUnitTest`
passes (always through that `flock`; never two Gradle builds at once). Write `docs/progress/M2-fixes-ui.md` (what changed
per item, files). Don't commit. Don't use phones.
