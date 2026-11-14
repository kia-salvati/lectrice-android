# UI brief · Play from an audiobook file, option B (Opus → Fable, 2026-11-13)

Spec: `../lectrice/docs/android-logic/23-audiobook-source.md` (§2, §4, §3.7). Mockup (picked: **option B** + shared S1–S4):
`../lectrice/design/2026-11-13-audiobook-source-options.html`, screenshots `../lectrice/design/screens/audiobook-B-*.png`,
`audiobook-S-*.png`. Match the mockup's B and S screens; Quiet v2 tokens/components as everywhere (`LectriceTheme`,
`core/designsystem`). The logic is done (Opus); you only write Compose. Don't change ViewModels, models or MainActivity.

**Do not touch** `LectriceApplication.kt`, `MainActivity.kt`, `SettingsScreen.kt`, `AppContainer.kt`.

## State and actions you get (already in the code)

- `PlayerInfo.audiobook: AudiobookUi?` (`feature/reader/.../ReaderUiState.kt`): `active`, `fileName`, `detail`, `follows`,
  `missing`. Null = the book has no linked audiobook.
- `ReaderActions.setAudioSource(AudioSource)`, `linkAudiobook(uri: String)`, `removeAudiobookLink()`.
- `ReaderMessage.AudiobookFailed(name)` (snackbar texts in its KDoc).
- `ExportActions.listenFromFile()`; `ExportUiState.close` is handled by the route (don't handle it).
- `LibraryActions.pickedAudiobook(uri)`, `listenToLinked(documentId)`, `dismissBookMissing()`;
  `LibraryUiState.audiobookBookMissing: AudiobookBookMissing?`; snackbars `LibrarySnackbar.AudiobookLinked(documentId,
  title)` and `LibrarySnackbar.AudiobookForeign` (texts in their KDoc).

## Build

1. **Icons** (`core/designsystem/.../Icons.kt`): add `Headphones` and `PictureAsPdf` (Material Symbols Rounded paths; the
   mockup has them). Mind the D8 lesson: absolute `M` after `z`.
2. **B1 · Player sheet** (`PlayerSheet.kt`): when `player.audiobook != null`, a list row under the Voice row, same style:
   leading `Headphones`, title "Audio", subtitle = `"Audiobook file · " + follows` when `active`, else
   `"Made on this phone"`; trailing chevron; opens B2. When `active`: the Voice row and the "Read code & tables aloud"
   row are disabled (60 % opacity, not clickable) with the caption "From the audiobook".
3. **B2 · "Listen with" sheet** (new composable in `feature/reader`, a `BottomSheet` like the others): two radio cards
   (style of the model cards S2: `ModelCardView` look): **Made on this phone** ("Your voice and speed · works for every
   page") and **Audiobook file** (lines: `fileName`, `detail`, `follows`). Selecting a card calls
   `setAudioSource(Generated|Audiobook)` and closes the sheet. Under the cards: text buttons **"Use another file…"**
   (calls the new `onPickAudiobook` lambda, see 7) and **"Remove link"** (error colour → `removeAudiobookLink()`, closes).
   **B4:** when `missing`, the Audiobook card uses the warn style with "File missing" and a button "Find it again…"
   (→ `onPickAudiobook`).
4. **B3 · Pill** (`Pill.kt`): when `player.audiobook?.active == true`, a 16 dp `Headphones` mark before the status text in
   the expanded pill (not in the collapsed button). When `missing`, the status text is "Audiobook file missing".
   Content description of the mark: "Playing from the audiobook file".
5. **S1 · Export done (E4)** (`ExportPage.kt`): a tonal button **"Listen from this file"** (with `Headphones`) under the
   title block, above "Save to Audiobooks", caption under it "The text follows each sentence." → `listenFromFile()`.
6. **Reader snackbar** (`ReaderScreen.kt`): show `ReaderMessage.AudiobookFailed` with the texts in its KDoc.
7. **Pickers:** add a parameter `onPickAudiobook: () -> Unit = {}` to `ReaderScreen` (passed down to B2) and to
   `LibraryScreen`. Opus wires them in `MainActivity` (an `OpenDocument` picker for `audio/*`).
8. **S2 · Library import menu** (`LibraryScreen.kt`): the round Open PDF button opens a small menu (mockup S2): "PDF book"
   (`PictureAsPdf`) → `onPickPdf()`; "Audiobook file (M4B)" (`Headphones`) → `onPickAudiobook()`.
9. **S3 · snackbars** (`LibraryScreen.kt`, the existing `when` at ~line 334): `AudiobookLinked` → "Linked to *title*."
   with action **Listen** → `listenToLinked(documentId)`; `AudiobookForeign` → its KDoc text, no action.
10. **S4 · sheet** (`LibraryScreen.kt`): when `audiobookBookMissing != null`: title "This audiobook belongs to another
    book", body "Open that book's PDF to read along.", buttons **Open PDF…** (filled → `dismissBookMissing()` then
    `onPickPdf()`) and **Not now** (text → `dismissBookMissing()`). (The mockup names the book; we don't know its title
    here, so no name.)

Strings go in each module's `strings.xml`. 48 dp targets, no visible scrollbars, 200 % font must not clip (rows wrap).

## Check

`flock ~/.cache/lectrice-heavy-build.lock ./gradlew -Dorg.gradle.jvmargs=-Xmx2g --max-workers=2
-Plectrice.mavenCentralMirror=https://maven.aliyun.com/repository/public :app:assembleDebug testDebugUnitTest -q`
must pass. No phone. Note what you did in `docs/progress/F23-ui.md` (short).
