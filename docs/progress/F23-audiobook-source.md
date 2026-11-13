# F23 · Play from an audiobook file (2026-11-13, Opus + Fable)

Spec: `../lectrice/docs/android-logic/23-audiobook-source.md`. Design: `../lectrice/design/2026-11-13-audiobook-source-options.html`
(brief `../lectrice/docs/2026-11-13-audiobook-source-brief.md`); **picked: option B** ("Audio" row in the player sheet →
"Listen with" sheet with two cards), by Opus because it has room for the file's details, chapter mode and errors, and a
later third producer (file 24's computer helper) shows as a status, not a source. The user can switch to A or C.
Compose brief: `docs/2026-11-13-audiobook-source-ui-brief.md`; Fable's notes: `F23-ui.md`.

## Core (worktree `../lectrice-core-android`, `feature/core-android`, CHANGELOG `[Unreleased]`)

- **C35** `ExportRunner` records each spoken sentence's start (same arithmetic as the chapter marks) per part;
  `ExportRecord.sentences` + `marked_parts` (serde defaults), so a resumed export keeps them; parts made before this
  version → no map.
- **C36** `lectrice_domain::audiobook`: `LectriceMap` (JSON, sentences as `[id, hash, startMs]`), `text_hash` (FNV-1a 32 of
  the lowercased, whitespace-collapsed spoken text). `AudioExporter::attach_map` (default: nothing) called after
  `assemble` for M4B; `FfmpegExporter` implements it. **The desktop's `SwappableExporter` must forward `attach_map`
  when it moves to this core** (one line), else desktop files carry no map.
- **C37** `lectrice_infra::export::mp4_map` (now always built; `FfmpegExporter` stays behind `ffmpeg-export`):
  `append_map` (top-level `uuid` box "lectrice-map-v01" at the end of the file), `read_audiobook` (map, `mvhd` length,
  `chpl` chapters, `©nam`, AAC check; boxes > 64 MB refused).
- **C38** `match_audiobook`: Exact (same doc + cleaner + hashes) → Remapped by hash (≥ 90 %, monotone walk, window 200)
  → Chapters (TOC title match) → Unaligned; another book → `AudiobookOtherBook`.
- **C39** registry `AudiobookNotLectrice` 3101, `AudiobookOtherBook` 3102, `AudiobookUnreadable` 3103 (Kotlin
  `ErrorName` follows; `error_registry.rs` passes).
- Tests: 7 domain (`audiobook::tests`), 4 infra (`mp4_map::tests`), app runner test (map of a whole export), infra
  `an_m4b_carries_the_lectrice_map` with the real ffmpeg (`--ignored`). Workspace green except `pdf_outline`, which needs
  libpdfium on the host (unrelated, as before).

## Android

- FFI: `inspect_audiobook(path)`, `match_audiobook(path, documentId)` (`audiobook_records.rs`); `AndroidExporter` and
  `ExporterSlot` implement/forward `attach_map`.
- `core:model/Audiobooks.kt`: `AudioSource` (Generated | Audiobook: the shared "audio source" of files 23/24),
  `AudiobookLink`, `AudiobookTimeline`, `AudiobookRepository`; `SettingsStore.audioSource/setAudioSource` (per book);
  `PlaybackState.source` / `audiobookMissing`; `PlaybackController.setAudioSource`.
- `core:data/AndroidAudiobookRepository`: links in `filesDir/lectrice/audiobooks.json`, timelines in
  `audiobook-maps/<doc>.json`; reads content URIs through `/proc/self/fd/N` (no copy), persisted read permission for
  imports; re-matches when size/date change; `fileMissing`.
- `core:playback`: `AudiobookSlicer` (MediaExtractor + MediaCodec, 100 ms pre-roll, sample-exact trim → WAV);
  `SentenceDataSource` serves file slices when `SpeechParams.audiobook` is set; `Media3PlaybackController` builds the
  order from the timeline, rate = speed ÷ file speed, no engine/read-ahead/stats in file mode, `setAudioSource` keeps the
  place and play state, file errors → "missing" and pause.
- ViewModels: reader (`PlayerInfo.audiobook`, `setAudioSource`, `linkAudiobook`, `removeAudiobookLink`), export
  (`listenFromFile`, Save to Audiobooks moves the link to the MediaStore URI), library (`pickedAudiobook`,
  `listenToLinked`, S4 sheet); "Open with"/"Share" for `audio/mp4`, `audio/x-m4b`; removing a book removes its link.

## Checked on the Galaxy S10+ (2026-11-13)

- Export of an invented 3-page book at 1.25× (Fast, **4 threads** in the log): 146 s, 1.2 MB M4B with 9 chapters and a
  map of 45 sentences (`durationMs` 145 690; ffmpeg decodes 145 664 ms). On the laptop every map boundary falls in the
  pause between sentences (lowest energy 0 to +100 ms after each mark).
- E4 "Listen from this file" → linked (Exact, 100 %) → the sheet shows the Audio row, Voice and "Read code & tables" off
  "From the audiobook"; play at 1.75× = player rate 1.4; highlight and follow-scroll track the file to the end
  (Finished); app CPU **3.3 %** while playing (no `speak_sentence`, no engine); media next = next sentence.
- File hidden behind the app's back → pill "Audiobook file missing", B4 card "File missing / Find it again…", no crash;
  "Made on this phone" switches back at the same sentence.
- "Open with" a copy of the M4B (as from the desktop) → linked, snackbar "Linked to …" + Listen; no persistable permission
  from "Open with", so the file is **copied** into app storage (added after the first test: otherwise the link breaks at
  the next start); after a force-stop, B2 → Audiobook file plays from the copy. An invented ffmpeg M4B without a map →
  the "not made by Lectrice" snackbar.

## Security pass 2 (2026-11-13)

Report: `../lectrice/docs/security/2026-11-13-security-review.md` §8; spec §3.9. The core checks box sizes and the
map's values (`LectriceMap::check`); "Open with" / "Share" asks first, then copies the file (≤ 2 GB) and matches the
copy (`AudiobookRepository.import(uri, fromAnotherApp)`); the slicer decodes AAC only, at most 10 min per sentence.

## Not built (yet)

- Chapter mode and unaligned playback for foreign M4Bs (spec §3.4): foreign files show a snackbar instead.
- The "pending" audiobook whose book isn't in the library (spec §2 B: linked when its PDF is imported later).
- Storage screen "+ audiobook" mark; the confirm before deleting a linked export.
