# F23 · Audiobook source UI (option B) — 2026-11-13

Brief: `docs/2026-11-13-audiobook-source-ui-brief.md`. Compose only; no ViewModel, model or `MainActivity` changes.

## What was built

| Step | File | Change |
|---|---|---|
| 1 | `core/designsystem/.../Icons.kt` | `PictureAsPdf` added (absolute `M` after every `z`). `Headphones` was already there. |
| 2 · B1 | `feature/reader/.../PlayerSheet.kt` | "Audio" row under Voice when `player.audiobook != null` (new `onAudioSource` parameter). In file mode the Voice row and "Read code & tables aloud" are at 60 % opacity, not clickable, with "From the audiobook". |
| 3 · B2/B4 | `feature/reader/.../AudioSourceSheet.kt` (new) | "Listen with" sheet: two radio cards, "Use another file…", "Remove link"; warn card with "File missing" + "Find it again…" when `missing`. |
| 4 · B3 | `feature/reader/.../Pill.kt` | 16 dp headphones mark before the status text (`PillStatus.audiobookMark`); "Audiobook file missing" status. |
| 5 · S1 | `feature/reader/.../ExportPage.kt` | Tonal "Listen from this file" + caption above "Save to Audiobooks". |
| 6, 7 | `feature/reader/.../ReaderScreen.kt` | `AudiobookFailed` snackbar texts; `ReaderSheet.AudioSource`; new parameter `onPickAudiobook: () -> Unit = {}`. |
| 7–10 · S2–S4 | `feature/library/.../LibraryScreen.kt` | New parameter `onPickAudiobook: () -> Unit = {}`; import menu on the round button; the two snackbar branches; the "belongs to another book" sheet. |
| — | both `strings.xml` | New strings. |

## Minimal readings (please confirm)

- Audio row subtitle is `"Audiobook file · " + follows` as the brief says, so it reads "Audiobook file · Text follows each
  sentence" (capital T; the mockup shows lower case).
- Opening B2 replaces the player sheet; picking a card, "Remove link", ✕ or Back close it back to the reader. "Use another
  file…" / "Find it again…" only call `onPickAudiobook` and leave the sheet open.
- B4 card shows the file name, "File missing" and "Find it again…" (no detail / follows lines, as in the mockup).
- S3 text is "Linked to “title”." with quotes (like "Removed “title”."), because `LectriceSnackbar` takes plain text; the
  mockup's italic title isn't drawn.
- S2 menu: closes on an outside tap, Back, or a second tap on the round button. The button keeps its "Open PDF" label.
- The pill mark isn't shown below 360 dp wide (the pill has no status text there) or in the collapsed button.

## Check

- `:feature:reader`, `:feature:library`, `:feature:settings`, `:core:designsystem` `testDebugUnitTest`: pass.
- The brief's full command (`:app:assembleDebug testDebugUnitTest`) fails in `:app:compileDebugKotlin` only:
  `MainActivity.kt:129:26` unsafe call on `Intent?` (`intent.type`). That file is outside this job, so it was left alone.
- Not run on a phone.
