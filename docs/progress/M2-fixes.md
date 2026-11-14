# M2 fixes · bugs from the S10+ device checks (2026-10-26, Opus + Fable)

Report: `~/Desktop/lectrice-android-builds/m2-2026-10-26/S10-checks.md`. Branch `feature/app-m2` (uncommitted); core
worktree `../lectrice-core-android` (`feature/core-android`, uncommitted). UI brief for Fable:
`docs/2026-10-26-m2-fixes-ui-brief.md`; Fable's notes: `docs/progress/M2-fixes-ui.md`. No phone was used.

## Bugs

### B1 · Crash in `Pill.kt` (`boundsInRoot()` on a detached node)
- **Cause:** the pill kept the ⏯ button's `LayoutCoordinates` in state. When the pill collapses, the expanded row (and the
  button) leaves the composition, but the stale coordinates stayed; the pill's next `onGloballyPositioned` (and the
  gesture handlers) called `boundsInRoot()` / `localBoundingBoxOf()` on them → `IllegalStateException`.
- **Fix:** `LayoutGuards.kt` (`attachedOrNull`, `boundsInRootOrNull`, `isInside`, `pillBounds`): every use of kept
  coordinates checks `isAttached` first; the expanded row clears `playCoords` in a `DisposableEffect` when it leaves.
  `longPressScrubber` uses the same guards.
- **Test:** `feature/reader` `LayoutGuardsTest` (JVM, a fake `LayoutCoordinates` that throws like the real one when
  detached): detached ⏯ → no bounds and no throw; collapsed pill reports itself; attached nodes give their rects.

### B2 · Short bottom sheets invisible but taking taps; library ignoring taps
- **Cause 1 (invisible):** the sheet's `Column` was aligned to the bottom **and** moved by the AnchoredDraggable offset,
  whose anchors are measured from the top of the container. The offset was counted twice: a short sheet (book sheet,
  import error, Read ahead) ended up below the screen; tall sheets were 48 dp too low (their bottom was cut off; the
  Default voice sheet's top was at 96 dp instead of 48 dp in `screens/93-…`).
- **Cause 2 (taps blocked until force-stop):** the opening `animateTo` ran in the same coroutine as the "dismiss when
  hidden" wait. A scrim tap or a drag during the opening cancels that animation (AnchoredDraggable's mutex), which
  cancelled the whole effect: `onDismiss` never ran, the caller kept the sheet, and its (now transparent) scrim ate every tap.
- **Fix:** `BottomSheet.kt`: the column is aligned to the top (`sheetAnchors()` gives absolute positions); the opening
  animation runs in its own child job; dismissal waits for "has opened" then `isClosed(target, settled, animating)`.
- **Test:** `core/designsystem` `BottomSheetTest` (anchors for short/tall/half sheets; closed only after the slide ends).
  A Compose UI test of the real sheet needs Robolectric or a device (not available here: no Robolectric in the verified
  dependency set, phones not used).
- B10 (library stops reacting after the font change) is most likely the same leftover scrim.

### B3 · Listen view over the lock screen with the setting off
- **Cause (most likely):** the Listen view decided "locked" from `isKeyguardLocked` read once at resume / when it opened.
  Resuming during the unlock animation (or after a wake within Samsung's lock delay) leaves `locked = true` while the phone
  is actually unlocked, so the N2L marks ("Swipe up to unlock", lock icon) are drawn and taps land on the Listen view —
  which looks like "over the lock screen". Also, the window flag was only set from a callback, so it could stay on if the
  reader went away with the Listen view open.
- **Fix:** `ReaderScreen` gets `listenOverLockScreen`; lock marks are drawn only when the setting allows the window over
  the keyguard; `locked` is refreshed on screen on/off and set false on `ACTION_USER_PRESENT` (`KeyguardWatch.kt`).
  `MainActivity`: `setShowWhenLocked(listenOpen && setting)` is derived state in a `DisposableEffect` and reset to false
  when the reader leaves.
- **Test:** none automatic (keyguard); device re-check listed below.

### B4 · Chapter headings at the top of a page dropped (core cleaner)
- **Cause:** `lectrice-domain/src/cleaning/page_labels.rs` (`printed_number_offset`). Without PDF page labels the cleaner
  infers printed numbers from numbers in header/footer bands. In Chrome-made PDFs each chapter is one page starting with
  its number ("1 …" on PDF page 1, "2 …" on page 2 …), so the offset matched on ≥ 3 pages, fake labels "1", "2", "3" were
  invented, and the running-header rule (`bands.rs`, `carries_label`) then removed each heading as "a header carrying
  its page number". Sub-sections (mid-page) survived. The pill/card label showed the previous section as a consequence.
- **Fix:** a band line backs a page-number offset only if its shape (digits as `#`) repeats on ≥ 2 pages
  (`MIN_SHAPE_PAGES`); shared `band_key_pages()` in `bands.rs`. The user's five books clean byte-identically before and
  after. **Cleaner version 3 → 4** (saved books are re-cleaned on open; cached sentence audio of the old version isn't
  reused). CHANGELOG `[Unreleased]` → Core → Fixed.
- **Test:** `lectrice-domain/tests/cleaning_invented.rs::chrome_chapter_heading_at_the_top_of_a_page_is_kept_and_no_label_is_inferred`
  (invented text, Chrome's measured layout; fails on the old code) and
  `page_labels::tests::one_off_numbered_chapter_titles_are_not_page_numbers`. Workspace: 432 passed, 0 failed.
- **Limit:** a chapter title whose shape repeats on ≥ 3 pages ("Chapter 1", "Chapter 2" each at a page top) is still
  removed by the repeated-header rule.

### B5 · Page chips "p." without a number
- **Cause:** not a missing label: the inline chip's placeholder was a hair narrower than the chip's text + paddings
  (text width converted to sp + a fixed 18 sp, paddings in dp), so "p. 3" wrapped at the space and `maxLines = 1` showed "p.".
- **Fix:** `ReaderText.kt`: no-break space in the prefix; placeholder = measured text + the chip's own dp margin and
  paddings + 1 dp, converted once.
- **Test:** none automatic (text layout needs a device); device re-check.

### B6 · No media resumption after the process is stopped
- **Cause:** no `androidx.media3.session.MediaButtonReceiver` in the manifest, so the session had no persistent media
  button receiver (`mediaButtonReceiver=null`).
- **Fix:** receiver declared in `core/playback`'s manifest (Media3 picks it up as the session's media button receiver);
  `onPlaybackResumption` now fails cleanly (exception, logged) when there's no last book instead of returning an empty
  list, and logs counts.
- **Note:** `am force-stop` puts the app in the "stopped" state, in which Android may not deliver even this broadcast;
  re-check with swipe-away + process death (`am kill` after backgrounding) as well as force-stop.

### B7 · Prepare while charging didn't start at once
- **Cause:** the work service's loop ran `prepare.tick()` (→ `start_prepare`) **before** loading the engine, so the first
  start always failed with `SpeechEngineUnavailable`; the next loop (3 s later) worked.
- **Fix:** `BackgroundWorkService.run()` loads/holds the engine first, then ticks. `WorkCenter.onAppForeground()` also
  starts the service when a prepare request can run (charger plugged in while the app was closed). `onWork` logs
  `prepare requested runnable=…`.

### B8 · Debug build: "Clear data" disabled instead of "Manage storage"
- **Fix:** `android:manageSpaceActivity="app.lectrice.ManageSpaceActivity"` (fully qualified; the short form resolved
  against `app.lectrice.debug`).

## Design items (Fable, from the Opus brief `docs/2026-10-26-m2-fixes-ui-brief.md`; details in `M2-fixes-ui.md`)
- **D8** previous-sentence icon: same root cause as a parser quirk: Compose keeps the last point after `z`, so a relative
  `m` after `z` starts in the wrong place. `skip_previous` and `delete_sweep` (the only two icons with that pattern; checked
  with a script) now use an absolute `M`.
- **D5** sample spinner/equaliser: logic by Opus (`VoiceSampleState` in `core:model`, `VoiceSamplePlayer.state`, set by
  `CoreVoiceSamplePlayer`: Loading while made, Playing while it plays, Idle at the end or on failure; plumbed into the
  reader, export, settings and S4 UI states). Fable: `SampleButton` (spinner / new `Equalizer` / speaker) in the player
  sheet, export page and Settings → Default voice; S4's play button shows preparing/playing.
- **D2** library recent rows at ≥ 200 % font: "Continue · N %" moves under the title (the `stacked` flag never reached
  the rows); below that the trailing part is capped at 40 % of the row.
- **D3** 200 % font at 360 dp: pill shows only "p. N"; Listen top bar grows to fit two lines; tools row becomes a 2 × 2
  grid (or keeps its row without clipping "Speed"); the player sheet's chapter detail line is capped at 2 lines.
- **D7** R7 tip: 48 dp close button ("Close tip"); the reader list's bottom padding grows by the tip's height.

## Tests run
- Android JVM tests (`testDebugUnitTest`, all modules): 36 tests, 0 failures, incl. the new `LayoutGuardsTest` (4) and
  `BottomSheetTest` (3).
- Core worktree: `cargo test --workspace` 432 passed, 0 failed (book-data tests run with the local data linked in, then
  unlinked); clippy `-D warnings` and fmt clean. `lectrice-ffi` host tests pass.
- Builds: `:app:assembleDebug` + `:app:assembleRelease`; the merged manifest has
  `manageSpaceActivity="app.lectrice.ManageSpaceActivity"` and the `MediaButtonReceiver`.

## APKs
- `~/Desktop/lectrice-android-builds/m2-fixes-2026-10-26/app-debug.apk` and `app-release.apk` (+ `SHA256SUMS`).

## Not fixed / left as is
- **B9** slider drag didn't seek: unconfirmed (synthetic `adb input swipe`); recheck by hand first.
- Media session state 0 (none) after a pause sometimes: not reproduced here; needs a device log.
- `set_read_ahead_minutes failed: SpeechEngineUnavailable` warning on every reader open before the engine loads: harmless
  noise, the controller retries on the next update; left.
- Samsung SystemUI logs the media title (the sentence) when "Sentence on the lock screen" is on: outside our process;
  worth a note in the setting's help text later.
- D4 (Listen view previous line cut mid-line) is as designed; D1 matches the newer three-model decision.
- B4 limit: a chapter title whose shape repeats on ≥ 3 pages ("Chapter 1", "Chapter 2" at page tops) is still removed by
  the repeated-header rule.

## Device re-checks needed (S10+ or S26)
1. B1: collapse the pill (4 s), tap it; tip "Prepare…" + Home; play to the end of a book with the screen off. No crash.
2. B2: long-press a book (sheet visible, Remove + Undo), wrong model archive (error sheet), Settings → Read ahead; tap the
   scrim *during* the opening animation, then check the library still reacts; tall sheets now reach the bottom edge.
3. B3: setting off: Listen view open, lock, wake → system lock screen; unlock → no lock marks. Setting on: as before.
4. B4: re-open the three test books (re-cleaned, cleaner v4): "1 Day 1", "2 Blossom"… shown; pill/card label = the right
   chapter; chips show the PDF page.
5. B5: chips read "p. 3"; at 200 % font too.
6. B6: play, pause, swipe away, kill the process (and separately `am force-stop`), press the headset/media play key:
   the last book resumes at its place; `dumpsys media_session` shows our `mediaButtonReceiver`.
7. B7: charging, tap "Prepare this chapter while charging": starts within a few s, no `SpeechEngineUnavailable`.
8. B8: debug build: Android's app storage page shows "Manage storage" and opens T3.
9. Design: D8 icon on pill/sheet/Listen view; D5 spinner then equaliser on "Hear Bella"; 200 % font at 360 dp (library
   rows, pill, Listen top bar and tools, sheet chapter line); R7 tip close button and no covered text.
