# M2 UI, Part A (Fable, 2026-10-26): design system, Library, Settings, M4 screens, launcher icon

Built from `docs/2026-10-25-m2-ui-brief.md` §2 Part A against the state contracts (no field changed, none added).
No git commands, no new artifacts; one build-file line (see "Needs Opus").

## Build and tests

- `:core:designsystem:compileDebugKotlin :feature:library:compileDebugKotlin :feature:settings:compileDebugKotlin`: OK, no warnings.
- `:core:designsystem:testDebugUnitTest` (3 contrast tests, every §9.2 pair + the 6 tile pairs, light/dark/black ≥ 4.5:1): pass.
- `:feature:settings:testDebugUnitTest` (`LicensesTest`): pass.
- `grep 'Color(0x'` outside `core/designsystem`: nothing. No `println`.
- `:app` was not built (Rust library; Opus).
- The btrfs metadata filled twice during the work ("No space left on device" on small writes and on Gradle's report
  files); waited and retried as the brief says. The Gradle metadata chunk has since grown to 2.5 GiB.

## Files

`core/designsystem/src/main/kotlin/app/lectrice/core/designsystem/`
- `LectriceTheme.kt`: `TokenSet` (ARGB `Long`s: light, dark, black + `scrim` + the 6 tile hue pairs), `Tokens.textPairs`
  (the §9.2 pairs for the test), `LectriceColors`, `LectriceTheme(theme: ThemeSetting) {}` (System → phone's light/dark,
  dark set not black; sets status/navigation bar icon colours through `WindowInsetsController`, API 29 fallback), provides
  `LectriceTheme.colors`, `LectriceTheme.type`, `LocalReducedMotion`, material3's `LocalContentColor` and `ripple()` as the
  indication.
- `Type.kt`: `LectriceType` (title-l/m, body, body-s, label, label 16, caption, Quiet row 16/20 500 + meta 14/18, library
  title 20/24, section header 14/20 600, reading serif 19/32 + h1/h2/h3, sample italic 17/26, welcome title 32/40, code mono
  14/20, cue 13/18, `serif(size, line)` helper).
- `Motion.kt`: `rememberReducedMotion()` (`ANIMATOR_DURATION_SCALE == 0`), `motionSpring` (0.85 / MediumLow, snap under
  reduced motion), `motionTween`.
- `Icons.kt`: `LectriceIcons`, every path of the mockup's `ICONS` table as a lazy `ImageVector` (24 dp viewport).
- `Components.kt`: `Text` (BasicText wrapper), `LectriceIcon`, `PrimaryButton`, `SecondaryButton`, `LectriceTextButton`,
  `LectriceIconButton`, `PlayButton` (56/48 disc, preparing ring, long-press slot), `ListRow` (Quiet density, leading /
  extra / trailing slots, long-press, content description, TalkBack custom actions), `StatusLine` (StatusParts, only the
  shrinking parts ellipsize), `ChevronRight`, `Divider`, `SectionHeader`, `TopBar`, `LectriceCard`, `InfoCard`, `CoverTile`,
  `PlaceholderBlock`, `LectriceSwitch`, `RadioMark`, `CheckboxRow`, `SegmentedControl` (`.segc`, 40 dp pill in a 48 dp
  strip), `ChoiceChip`, `StepSlider` (5-stop text size), `LinearProgress` (4/8 dp, determinate/indeterminate),
  `ProgressBarSmall` (56 × 4), `Spinner`, `Banner` + `BannerAction` (warn/err), `LectriceSnackbar` (inverse colours,
  underlined action), `LectriceDialog`.
- `BottomSheet.kt`: `BottomSheet` (modal, scrim, handle, AnchoredDraggable with Hidden/Half/Full anchors, follows the
  finger, nested scroll from an inner list, 30 % / 1000 dp/s close, spring or snap, `onDismiss` once hidden; `fullHeight`
  = screen − 48 dp, else wraps content), `SheetHeader`, `SheetBottomSpacer`.
- `src/test/.../ContrastTest.kt`: WCAG contrast over `Tokens.textPairs` for the three sets.

`feature/library/`
- `LibraryScreen.kt`: `LibraryScreen(state, actions, onPickPdf)`: Quiet header, banners (voice missing / downloading
  with bar / engine failed / list failed + Try again), Continue card (3 dp accent bar, `StatusLine` location,
  "49 % · 4 h 12 min left", play/pause button, body tap → `openBook(id, false)`, long-press → sheet, TalkBack "Remove from
  library"; stacks the button at font scale ≥ 2), Recent rows (tile, meta, "Continue · 34 %"/"Start" + 56 × 4 bar,
  divider inset 72, long-press + custom action), import rows (running: indeterminate 160 dp bar + "Reading… cleaning
  text"; queued: "Waiting…"), loading (nothing for 150 ms, then 3 placeholder rows), empty (L2), FAB `note_add` with the
  1 dp `bg` ring, paging (`loadMore()` 5 rows before the end), snackbars (L6 / Undo 8 s / errors, 4 s), L4 import-error
  sheet (per-error copy, curly quotes, `Sizes.format`), L7 book sheet, L7c dialog (extra lines + checkbox only when
  `exportCount > 0`).
- `res/values/strings.xml`.

`feature/settings/`
- `SettingsScreen.kt`: `SettingsScreen(state, actions, onBack)`: T1 sections, switches, text-size slider with S · M · L ·
  XL · XXL and the serif preview, theme segments, model / storage / battery / about rows, long-press (and TalkBack action)
  on the version row → `copyVersion()`; sheets: default voice (grouped, radio, 48 dp `volume_up` "Hear {name}" →
  `playVoiceSample`, tap = set + close), default speed (6 segments), read ahead (3 radio rows).
- `ModelScreens.kt`: `WelcomeScreen(onGetVoice, onNotNow)` (S1), `ChooseModelScreen(state, actions, onImportFile, onBack)`
  (S2/S2s, three radio cards with "Recommended", "Not sure? Pick Fast.", free-space + Wi-Fi hints, "Choose a model" →
  "Download {size}", "Import from a file…" disabled while a download runs / spinner while importing, S2b dialog, import
  error sheet), `DownloadScreen(state, actions, onOpenPdf, onLibrary)` (S3 with S3a–S3d, then S4 with the sample card and
  the slow-phone line), `ModelPageScreen(state, actions, onBack)` (T2: per-state card extras, T2c dialog).
- `StorageScreen.kt`: `StorageScreen(state, actions, onBack, closeInsteadOfBack)` (T3: summary card with the 5-segment
  bar in the `onTile` hues + legend, models, books largest-first with sizes, audio cache + Clear, exported audiobooks with
  the unfinished bar, phone rows, T3b book sheet, T3c export sheet, delete-export / delete-all dialogs, message
  snackbar, "Free up about …" banner).
- `LicensesScreen.kt`: `LicensesScreen(onBack)`: GPL notice, component rows → a text page reading `assets/licenses/<file>`.
- `res/values/strings.xml`.

`app/`
- `src/main/res/mipmap-anydpi-v26/ic_launcher.xml`, `drawable/ic_launcher_foreground.xml` (`menu_book`, white, 54 dp on the
  108 dp canvas), `values/ic_launcher_background.xml` (`#1D5FAE`), monochrome = the same glyph; `android:icon` added to the
  manifest.

## Needs Opus

- **`core/designsystem/build.gradle.kts` got one line: `implementation(project(":core:model"))`.** The brief's
  `LectriceTheme(theme: ThemeSetting)` (and `StatusLine(List<StatusPart>)`) need `core:model`, and the module had no
  dependency on it. Revert and change the signature if that dependency is unwanted.
- **System back inside a feature module**: no `BackHandler` is reachable from `feature:*` (activity-compose isn't a
  dependency of the feature modules and compose-ui 1.12 has no `ui-backhandler` in the cache). So the back button
  doesn't close an open sheet/dialog or the licenses text page; those have their own close paths (scrim, drag, Cancel,
  the text page's back arrow). If the app should intercept back for sheets, `app/` has to own that, or the features need
  `activity-compose`.
- `StorageScreen` "No voice installed" row has no "Download" button: `StorageActions` has no action for it
  (`openModel(modelId)` needs an id).
- `StorageScreen` draws no "There's room now" card: the state has only `requestedBytes`, not whether it is satisfied.
- `DownloadScreen` with `download == null && !ready` (cancelled download) shows S3 at 0 % with "…": Opus's navigator
  should pop it instead.
- `ReadyView` sample button never shows the spinner / equaliser (no state for it in `ModelSetupUiState`).

## Guesses (mockup-first where it had an answer; say if you want another)

1. Download-running Library banner (file 02 §3.6, no mockup frame): drawn as a warn banner with a `download` icon and a
   4 dp bar under the text, "Details" action.
2. Queued import row: tile + name + "Waiting…" only, no bar (the mockup only draws the running row).
3. Snackbar texts for `LibrarySnackbar.Error`: `StorageFull` → "Your phone is out of space.", else "Something went
   wrong."
4. Continue card stacks its play button at font scale ≥ 2.0 (R12b is drawn at 200 % only).
5. "Recommended" on the Fast card: `label` 14/20 500 in `accent` after the name, before the size.
6. Space-short copy (`SetupDialog.ImportFailed(StorageFull)`, S3d): "Not enough space. The voice needs {needed} free.
   Free up space and try again." (the state carries one number, not the 370 + 200 split).
7. Paused download label: "Paused · " + the state's progress line ("Paused · 142 MB of 370 MB · 38 %").
8. Installing state (S3 and the T2 card): indeterminate bar + "Checking and unpacking the voice…" (no copy given).
9. Failed download with another error name: "The download failed. Check your connection and try again."
10. Import-failed sheet: the L4 sheet style (error icon, title, body, OK); `FileUnreadable` → "This file couldn't be
    opened."
11. Settings' default-voice sheet opens at Full (the half detent exists); group headers use `SectionHeader` with
    `Voice.longSummary`.
12. Text-size preview = paragraph sp + 2 (the mockup draws 21 for L) with line height × 1.4.
13. Section headers use the mockup's padding 16 16 4 (file 11 says 24 16 8).
14. Storage book sheet → "Remove from library" asks with the L7c title/body (frees library + audio bytes), then calls
    `removeBook` (no checkbox: the Storage state has no export counts).
15. Export sheet for an unfinished export: header + "To delete it, cancel the export on its page." (no "Go to the
    export" row: no action for it).
16. "Clear audio cache" is disabled while `cacheFraction == 0`.
17. Remove dialog with `freesBytes == null` shows "…" for the amount.
18. Licenses text page shows every file of the component joined by blank lines, in `body-s`.
19. The version row is a `ListRow` with a no-op tap (so the long-press works) plus a TalkBack action "Copy the version".
20. `BottomSheet` closes without an exit animation when a choice inside it is made (the host drops it at once).
