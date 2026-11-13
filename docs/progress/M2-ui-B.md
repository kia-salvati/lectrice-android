# M2 UI, Part B (Fable, 2026-10-26): reader, pill, player sheet, chapters, Listen view, export page

Built from `docs/2026-10-25-m2-ui-brief.md` §2 Part B against the state contracts (no field changed, none added).
No git commands, no new artifacts, no gradle changes.

## Build and tests

- `:core:designsystem:compileDebugKotlin :feature:reader:compileDebugKotlin :feature:library:compileDebugKotlin
  :feature:settings:compileDebugKotlin`: OK, no warnings.
- `:core:designsystem:testDebugUnitTest` (contrast): pass.
- `grep 'Color(0x'` outside `core/designsystem`: nothing. No `println`. No book text, no sentence logging.
- **`:app:assembleDebug`: the Rust library builds (`:core:data:buildRustFfi` compiled `lectrice-ffi`), then
  `:app:compileDebugKotlin` fails in Opus's `app/src/main/kotlin/app/lectrice/AndroidPlatforms.kt:78`:
  `override suspend fun copyTo(export, uri) = files.copyTo(export, uri)` infers a non-`Unit` return type and no longer
  matches `ExportPlatform.copyTo(): Unit`. Not my file (another agent is working in `app/`), so left alone; the fix is
  `override suspend fun copyTo(export: AudioExport, uri: String) { files.copyTo(export, uri) }`. Nothing in my modules
  is referenced by that error.

## Files

`core/designsystem/src/main/kotlin/app/lectrice/core/designsystem/`
- `BottomSheet.kt`: two optional parameters, both backward compatible: `halfHeight: Dp?` (the half detent's height,
  for the player sheet's `min(600 dp, 70 %)`) and `targetDetent: SheetDetent?` (a change animates the sheet there; the
  export page pushes the sheet to Full).
- `ReaderComponents.kt` (new): `BookSlider` (track, buffered part, chapter ticks, 20 dp thumb, drag bubble from a
  label lambda, release → `onSeek`, TalkBack 1 % steps), `SearchField` (48 dp, radius full, `bg`, 1 dp `line`, search
  icon, clear ✕), `HintTooltip` (`inverseBg`, underlined "Got it"), `Modifier.endFade(scrollState, color)` (the 24 dp
  right fade only while more is hidden), `ProgressLine` (2 dp book progress under the collapsed pill).

`feature/reader/src/main/kotlin/app/lectrice/feature/reader/`
- `ReaderScreen.kt`: `ReaderScreen(state, actions, onBack, onListenViewChanged, exportPage)`. Screen state
  (`rememberSaveable`): open sheet (Player / Chapters / Speed / Sleep), export page pushed, Listen view open, pill
  collapsed, follow-scroll, R9 hidden locally. Status scrim, R9 banner (Try again + dismiss), R7 tip (text from
  `speed`/`best`, "Set {best}", "Prepare this chapter while charging" when `canPrepare`, dismiss), "Back to current"
  chip (↓/↑; shows when follow is off and the sentence is outside the 20–60 % band, 15–50 % at 200 % font; scrolling it
  back into the band turns follow on), snackbars for `ReaderMessage` (4 s), the one-time pill hint (3 s of expanded
  playing, not under TalkBack, any of the three gestures or Got it → `pillHintDone`), collapse rules (scroll ≥ 24 dp
  via a nested-scroll connection, 4 s without a touch, never while paused or with a sheet open, pausing expands),
  follow-scroll (35 % target, band rule, spring, instant on open / reduced motion; `followRequest` turns it on; a user
  scroll turns it off), the back order (scrubber → export page → sheet → Listen view → reader), Keyguard checks for the
  Listen view (`KeyguardManager.isKeyguardLocked`; chevron / back / drag-down / More call `requestDismissKeyguard`),
  `onListenViewChanged`.
- `ReaderText.kt`: `LazyColumn`, one item per block, key = block id, contentType = kind; paragraphs / notes / list
  items (bullet, indent 24) / headings (26/34, 22/30, 19/28 × `textSize.scale`, margins 48/16, 32/12, 24/8) /
  captions (14/20 italic `fg2`) are one `BasicText` with an `AnnotatedString` built in `remember` per block; page chips
  inline (`InlineTextContent`, width measured with a `TextMeasurer`) or standalone before code/table boxes; the
  current sentence's `hl` background is drawn behind the text as rounded rects (radius 4, padding 0 2) per line and
  cross-fades 120 ms, its text gets `hlFg`; only the block holding the current sentence reads it (`derivedStateOf` per
  item); tap → the sentence under the finger (nearest before a gap; chips ignored) → `jumpToSentence`; code/table →
  `bg2` box, radius 8, padding 12, mono 14/20, sideways scroll with the fade, cue line "Read as “…”" (highlighted when
  the cue is current), tap → `tapBlock`; with read-code on, the current code sentence is highlighted inside the box by
  substring search; TalkBack: paragraphs offer "Play from here", standalone chips read "Page 265" / "Page 265, PDF
  page 279"; text column max 640 dp centred.
- `Pill.kt`: `Pill` (expanded ⏮ ⏯ ⏭ · status · ⋯, 56 dp / 72 at 200 % font; collapsed 56 dp round button at right 16
  with the 2 dp progress line; the morph animates width and x with the spring), `rememberPillStatus` (file 05 §3.2:
  location parts, speed part only ≥ 400 dp, moon icon + minutes ≥ 400 dp else icon only, preparing / catching up with
  spinner, finished, voice not installed in `accent` → `openModelSetup`, voice unavailable → re-shows R9; below 360 dp
  the status is just the `open_in_full` button), `detectPillGestures` (Initial-pass gesture map: tap stays with the
  buttons, horizontal ≥ 48 dp = ± sentence with `Confirm` haptic, up > slop = sheet / expand, ⏯ held 500 ms =
  scrubber with `LongPress` haptic; a committed gesture consumes so the buttons cancel), `ScrubberState` +
  `SpeedScrubberOverlay` (six 32 dp chips, 2× on top, 8 dp above the ⏯, follows the finger with `SegmentTick`
  haptics, release applies the last highlighted chip, back or a tap outside closes), `Modifier.longPressScrubber`
  (the sheet's and the Listen view's ⏯). TalkBack custom actions on the pill: Open the Listen view, Show chapters,
  Player and settings, Change speed (opens the sheet); collapsed: Show the player.
- `PlayerSheet.kt`: R4 at the half detent (transport with section buttons, `BookSlider` + "Ch. 7 · 49 %" /
  "4 h 12 min left", chapter line button with `toc` + "Chapters", Speed segments, Voice row expanding inline
  (`VoiceRows`: current first, its group, "All N voices" grouped), Sleep row with `SleepChips` (End of chapter disabled
  without a TOC), Read code switch, the Prepare row when `state.prepare != null`, Export row (→ `exportPage` pushed over
  the sheet and its handle, sheet to Full; "Needs the voice" and disabled while `voiceMissing`), Library row.
- `ChaptersSheet.kt`: full height, header + close, `SearchField`, flat list with sticky level-1 rows (only when some
  entry has children), level styles 16/20 500 · 15/20 · 14/20 `fg2` with indents 16/32/48, 3 dp accent bar + 600 on the
  path, bold match ranges, centred on the deepest path row on open, keyboard hides on scroll, R8c empty text, "No
  chapter or section matches “…”."; rows read "{title}, page {label}[, current]".
- `ListenView.kt`: top bar (chevron "Back to reading", book title, section title; drag-down follows the finger, scale
  1 → 0.9 over 300 dp, closes past 30 % or a 1000 dp/s fling), teleprompter (previous 20/30 `fg2` clipped at the top,
  current 28/40 500 with the 3 dp bar, 24/34 past six lines, next 20/30 with ellipsis, 24 dp fades, tap = play/pause
  with the 600 ms 72 dp flash, swipe ≥ 48 dp = ± sentence with the 320 ms glide, R13c card for code/table with
  "Code example" / "Table" and the "Read as" caption, "Finished", heading at 600, live region only while paused),
  thumb zone (chapter progress with section ticks, "38 % of chapter 7" / "9 min left" or spinner + Preparing /
  Catching up, 64 / 88 / 64 transport with the preparing ring, Section buttons, Speed · Sleep · Chapters · More),
  R13h hint, predictive back (scale 0.9, translate 24, radius 32, border), container transform from the pill's rect
  (content fades in over the last 62 % of the spring), landscape 55 / 45 split, N2L marks (lock icon, "Swipe up to
  unlock", thumb zone up 24). `ListenSpeedSheet` and `ListenSleepSheet` (R13s and the sleep sheet).
- `ExportPage.kt`: `ExportPage(state, actions, onBack, onSaveAs)`: E1 (Voice row expanding into `VoiceRows`, Quality
  rows from `QualityRow` with disabled radio + "Download · …" text button + 4 dp bar while downloading, quality note,
  Speed segments + note, Read code switch, reassurance card, pinned footer summary / make time / Start), E6 footer
  (warn card with `sd_card_alert`, needed/free, the "not even one chapter" line, "Export part of the book" + "Free up
  space"), E7 (header with back → `backToChoose`, summary line, free line, From/To rows → a full-height list sheet
  centred on the picked chapter with the "move the other end" rule, footer with "Doesn't fit…" in `warnFg`, Start
  disabled and "Pick what fits"), progress pages per `ProgressKind` (running, waiting for another export, waiting for
  the engine, paused by the user, paused by Android with the battery text button, hot, battery, out of space during
  parts / assemble with the needed/free line and Continue only when it fits, failed with "Start again"), the cancel
  dialog, E4 with Save to Audiobooks / Saved (disabled, check) / caption / Save as… / Share / Make another version,
  message snackbar (4 s → `messageShown`), back order inside the page (list sheet → Part → `onBack`).
- `res/values/strings.xml`.

`feature/library/LibraryScreen.kt`, `feature/settings/{SettingsScreen,StorageScreen,ModelScreens,LicensesScreen}.kt`:
a `BackHandler` closes the open dialog or sheet (dialogs first), and the licenses text page.

## Guesses (mockup-first where it had an answer; say if you want another)

1. **Drag-up on the pill opens the sheet at once** (it springs to the half detent) instead of following the finger
   from the pill's top edge: Part A's `BottomSheet` owns its drag state and can't be driven from outside. The sheet's own
   handle drag follows the finger as before.
2. The scrubber sits 8 dp above the ⏯ it grew from (file 05 §3.5), left-aligned with it (the mockup's `left:68`); x
   is ignored for the hit test so "releasing away from every chip" keeps the last highlighted one.
3. The collapsed pill's 2 dp progress line sits just above the navigation bar (the mockup draws it inside the gesture
   area).
4. R4x (export row "Exporting · 42 %" + bar) is **not drawn**: `ReaderUiState`/`PlayerInfo` carry no export progress.
   Needs a field (e.g. `PlayerInfo.exportPercent: Int?`) from Opus.
5. "Voice unavailable · Tap for details" re-shows R9 only locally: dismissing R9 hides it on the screen without calling
   `dismissBanner()` (the ViewModel would null it until the next playback update). The Slow-phone tip's dismiss does
   call `dismissBanner()`.
6. The Prepare row (file 14) is drawn in the player sheet when `state.prepare != null`: title "Prepare while
   charging", subtitle = `summary`, `voiceWarning` under it in `warnFg`; tapping expands three chips (This chapter ·
   Next 3 chapters · As much as fits) or a "Cancel" text button while active. The R7 tip's "Prepare this chapter while
   charging" calls `prepare(ThisChapter)` then dismisses the tip.
7. Read-code on: the current code sentence is highlighted inside the box by finding its trimmed text in the box text
   (`boxText` keeps line breaks, sentences don't); when not found nothing is highlighted.
8. Headings' top margins are the CSS values minus the 16 dp paragraph gap before them (32/16/8 in code) so the visual
   gap is 48/32/24 as in the mockup; the first block has no top margin.
9. Page chips inside paragraphs are inline placeholders: TalkBack reads the paragraph text and skips them (no
   per-chip label); chips before code/table boxes are standalone and read "Page 265" / "Page 265, PDF page 279".
10. The inline chip placeholder width = measured "p. {label}" width + 18 sp (12 padding + 6 margin); height 20 sp.
11. `WaitingEngine` progress text: "This export waits while you listen and continues when you stop." (the page state
    has no model name; file 10's text names the model).
12. E3's "Samsung phones may stop it sooner: allow unrestricted battery" starts
    `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` directly (no `ExportActions` entry for it).
13. E7's "The whole book needs about {n}" uses the last Choose-page footer's `neededBytes` (the Part footer replaces
    it in the state); before any Choose footer it shows the part's own number.
14. The export page's Done header line shows the current choices (voice · model · speed) like E2–E5; the mockup's E4
    has the same line.
15. Listen view "Speed" tool a11y label is "Speed, 1.5×"; "More" is "Player and settings"; Sleep caption "Chapter" for
    End of chapter.
16. The Listen view's flying play circle (R13t) is not drawn; the stage grows from the pill's rect and its content
    fades in from 37.5 % of the transform (120 of 320 ms). Close plays the reverse.
17. Chapters from the Listen view jumps and closes the sheet; the Listen view stays (file 06 §3.5).
18. The player sheet's "Library" row closes the Listen view and the sheet, then `openLibrary()`.
19. Snackbars and the R7 tip sit 12 dp above the pill even while it is collapsed (the spec's "12 above the collapsed
    button" would put them over the text at the right; left as the mockup's `bottom:104`).
20. Reader "Failed" shows "Something went wrong." + "Try again" (→ `onBack`); no per-error text was given for the
    reader.
21. `LectriceDialog` and the E7 list sheet inside the export page are confined to the sheet's area (the mockup's
    `.dwrap` / `.inner` do the same).
22. The hardware-keyboard shortcuts (Space / ← / → / Esc, README §4.5) are not wired.
23. Focus return to ⋯ after a sheet closes (file 05 §3.6) is not implemented; the Listen view does focus its chevron on
    open.

## Open issues / needs Opus

- `:app:assembleDebug` fails in `app/.../AndroidPlatforms.kt:78` (above); nothing in Part B is involved.
- R4x needs an export-progress field (guess 4).
- `ReaderScreen` reads `KeyguardManager` and `AccessibilityManager` (touch exploration, to skip the pill hint) through
  `LocalContext`, and the export page starts the battery-settings intent: platform calls inside a feature module. Say
  if these should go through the ViewModel/platform interfaces instead.
- Not checked on a device (no phone attached in this session): the follow-scroll band maths, the gesture thresholds
  on the pill vs its buttons, the sheet detent heights and the Listen view transform need a real run on the S26 / S10+.
