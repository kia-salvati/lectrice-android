# M2 fixes · UI (Fable, 2026-10-26)

Brief: `docs/2026-10-26-m2-fixes-ui-brief.md`. Source: the S10+ report `~/Desktop/lectrice-android-builds/m2-2026-10-26/S10-checks.md`.
Only composables, icons and strings changed; nothing in the brief's "Don't touch" list was touched (in `Pill.kt` only
`rememberPillStatus`; in `SettingsScreen.kt` `VoiceSheet` plus its one call site on the old line 114).

## 1 · D8: previous-sentence and delete-sweep icons
- `core/designsystem/src/main/kotlin/app/lectrice/core/designsystem/Icons.kt`: `skip_previous` and `delete_sweep` path data
  replaced with the brief's strings (absolute `M` after every `z`); one-line comment above `icon()` explaining the rule.

## 2 · D5: voice sample spinner and equaliser
- `core/designsystem/.../Components.kt`: new `Equalizer(modifier, tint)` composable — a Canvas with 3 rounded bars, 3 dp wide,
  2 dp gap, heights animating 4 → 14 dp (`tween(300)` reversed = a 600 ms loop) with start offsets 0 / 200 / 400 ms; under
  `LocalReducedMotion` the bars are static 8 / 14 / 10 dp. Sizes and timings are named constants (`EQ_*`). Also added an
  `iconSize` parameter (default 24 dp) to `LectriceIconButton` for item 5.
- `feature/reader/.../PlayerSheet.kt`: `VoiceRows` / `VoiceRow` take the `VoiceSampleState` (`sample`), each row gets
  `sample.phaseOf(voice.id)`. New private `SampleButton`: a 48 dp round, always-tappable button showing the speaker icon
  (Idle), the design system's `Spinner` at 16 dp (Loading) or the `Equalizer` (Playing), all in the row's icon tint. Content
  descriptions: "Hear %1$s" / "Making a sample of %1$s" / "Playing a sample of %1$s". The player sheet passes
  `state.voiceSample`.
- `feature/reader/.../ExportPage.kt`: passes `state.voiceSample` to `VoiceRows`.
- `feature/settings/.../SettingsScreen.kt`: `VoiceSheet(…, sample: VoiceSampleState, …)` with the same private
  `SampleButton` (settings strings); the call site passes `state.voiceSample`. `LectriceIconButton` import dropped (unused).
- `feature/settings/.../ModelScreens.kt`: S4 "voice ready" `PlayButton` now uses
  `state.voiceSample.phaseOf(Voices.DEFAULT_VOICE_ID)`: `playing = phase == Playing`, `preparing = phase == Loading`.
- Strings: `feature/reader/src/main/res/values/strings.xml` (`sheet_voice_making`, `sheet_voice_playing`),
  `feature/settings/src/main/res/values/strings.xml` (`settings_voice_making`, `settings_voice_playing`).

## 3 · D2: library recent row at large font
- `feature/library/.../LibraryScreen.kt`: `RecentRowView` takes the existing `stacked` flag (`fontScale >= STACK_FONT_SCALE`,
  i.e. `>=` so exactly 2.0 counts; before, the flag only reached the continue card, never the rows). Stacked: the
  "Continue · 34 %" label + bar move into the row's `extra` slot, under the title/meta, start-aligned, so the text column has
  the full width next to the cover. Not stacked: the label + bar stay as `trailing` but are capped at
  `TRAILING_MAX_SHARE` (40 %) of the row's width via `BoxWithConstraints` + `widthIn(max)`, and the label wraps instead of
  staying on one line.

## 4 · D3: 200 % font at 360 dp
- `feature/reader/.../Pill.kt` (`rememberPillStatus` only): when `LocalDensity.current.fontScale >= TALL_FONT_SCALE` and the
  screen is narrower than `WIDE_STATUS`, the status shows only the last `StatusPart` of `player.location` ("p. 3"); the
  spoken label (`locationSpoken`) is unchanged. `TALL_FONT_SCALE` in `ReaderScreen.kt` is now `internal`.
- `feature/reader/.../ListenView.kt`:
  - `ListenTopBar`: `height(TOP_BAR_HEIGHT)` → `heightIn(min = TOP_BAR_HEIGHT)`, so both lines fit and the teleprompter
    (the next child of the same Column) starts below the bar.
  - Tools row: the four tools are now a list of `Tool(caption, contentDescription, onClick, top)`. At
    `fontScale >= TALL_FONT_SCALE` and width < `WIDE_STATUS` (`narrow`) they are laid out as a 2 × 2 grid (`ToolCell`:
    equal `weight(1f)` cells, `heightIn(min = TOOL_CELL_MIN_HEIGHT)` = 48 dp, icon or "1×" left of the caption, caption
    `maxLines = 1`). Otherwise the old row, but `ToolButton` cells use `width(TOOL_SIZE).heightIn(min = TOOL_SIZE)` instead
    of `size(TOOL_SIZE)`, so "Speed" under "1×" is no longer cut.
- `feature/reader/.../PlayerSheet.kt`: the chapter line's title already had `maxLines = 2` + ellipsis; the detail line under
  it ("92 % of this chapter · under a minute left in it · Page 3 · …") was the one wrapping to 4+ lines in
  `76-sheet-360dp-font200.png`, so it got `maxLines = 2`, `TextOverflow.Ellipsis` too.

## 5 · D7: R7 slow-phone tip
- `feature/reader/.../ReaderScreen.kt`:
  - The close button is a full 48 dp `LectriceIconButton` (default size) with a 20 dp icon (`TIP_CLOSE_ICON`, via the new
    `iconSize` parameter) and the content description `reader_tip_close` = "Close tip" (new string;
    `banner_dismiss` stays for the voice-failed banner).
  - `tipHeight` state: the tip's modifier reports its size with `onSizeChanged` (converted to `Dp`), the reader list's bottom
    content padding becomes `abovePill + 36.dp + tipHeight`, and a `LaunchedEffect(state.banner)` resets it to 0 dp when the
    banner is no longer the slow-phone tip.

## Deviations / notes
- Grid caption "without clipping": the cell is half the row wide so the captions fit; `TextOverflow.Ellipsis` is the
  fallback instead of hard clipping.
- Spinner colour in the sample button: the brief only fixes the size (16 dp); it uses the row's icon tint (like the
  equaliser) so it stays readable on the selected row's `accentContainer` background.

## Build
`flock ~/.cache/lectrice-heavy-build.lock ./gradlew -Dorg.gradle.jvmargs=-Xmx2g --max-workers=2 -Plectrice.mavenCentralMirror=https://maven.aliyun.com/repository/public :app:compileDebugKotlin :core:designsystem:testDebugUnitTest`
→ BUILD SUCCESSFUL (feature:library, feature:reader, feature:settings and app recompiled; core:designsystem tests ran;
no Kotlin warnings). Not committed; no phones used.
