# M2 re-check on the Galaxy S10+ (2026-11-13, Opus + Fable)

Phone: Galaxy S10+ (wireless adb), debug build `app.lectrice.debug`; the S26 Ultra was not touched. Invented test books
only (the three from `~/Desktop/lectrice-android-builds/m2-2026-10-26/`). Screens:
`~/Desktop/lectrice-android-builds/2026-11-13-recheck/screens/` (`small-*.png` are 480 px copies).

## Re-checks from `M2-fixes.md`

| # | Check | Result |
|---|---|---|
| B1 | Collapse the pill, tap it (×3), R7 tip, sheet | **Pass**: no crash (logcat clean) |
| B2 | Book sheet visible; Remove → confirm → snackbar → **Undo** restores the row; scrim taps during the opening ×3, library still reacts; Read ahead sheet visible; Default voice sheet reaches the bottom edge | **Pass** |
| B2 | Wrong model archive → error sheet (release APK, first run) | **Pass**; its text said "Pick the .zip" → now ".tar.bz2 file" |
| B4 | Orchard: "1 Planting" at the top of p. 1 kept (cleaner v4 re-clean on open); pill label right chapter | **Pass** |
| B5 | Chips read "p. 1", "p. 8", "p. 9" | **Pass** |
| B6 | Play, pause, force-stop, media PLAY key → the last book resumes at its place (`Start proc … for broadcast MediaButtonReceiver`); `mediaButtonReceiver` = ours | **Pass** (also after `am force-stop`) |
| B7 | Charging, tip → "Prepare this chapter while charging": `prepare started` 100 ms later, no `SpeechEngineUnavailable` | **Pass** |
| B8 | Debug app: Android's app storage page shows **Manage storage** → opens T3 with ✕ | **Pass** |
| B9 | Player sheet slider drag 67 % → 11 %: seeks, highlight moves | **Pass** |
| D2 | Library rows at 200 % font, 360 dp | **Pass** |
| D3 | Pill "p. 2" only; sheet chapter line 2 lines; Listen view tools 2 × 2 | **Pass**; new: speed segments unreadable ("0.…") → **P3** |
| D5 | "Hear Bella": spinner while made, equaliser while playing | **Pass** |
| D7 | R7 tip 48 dp close, text not covered | **Pass** |
| D8 | Previous-sentence icon `|◀` on pill and sheet | **Pass** |
| B3 | Setting off, Listen view open and playing, lock/wake twice → the system keyguard has focus (`isKeyguardShowing=true`, focus NotificationShade) | **Pass** |

## Bugs found and fixed

- **R1 · Re-opening a book while another plays didn't switch the player** (play toggled the other book). Cause: every
  screen's ViewModel lived in the Activity's store, so a re-opened reader reused its old ViewModel and never called
  `playback.open`. Fix: `app/ScreenStores.kt`, one `ViewModelStore` per back-stack screen, cleared when the screen is off
  the stack and no longer composed (the model setup screens share one). Re-tested on the phone: switching books works.
- **P1 · Text ran under the status-bar icons** on Settings, Voice model, Storage, Licenses, S1–S4 (Fable:
  `StatusBarScrim`; Settings by Opus).
- **P2 · Storage said "No voice installed" / "No books yet." while loading** → "…" (Fable).
- **P3 · Speed segments at 200 % on 360 dp** → two rows when the labels don't fit (Fable, `SegmentedControl`).
- Listen view sentence clipped at 6 lines at 200 % font: as specified (file 06 §"200 % font"), left.

## User decisions of 2026-11-13 applied

- **Model cards show the download size**: already the case (S2 and T2 headline = download, line "Download 140 MB ·
  169 MB on the phone"); checked on the phone.
- **Export uses 4 threads**: `EngineHost.exportThreads()` = min(phone threads, 4); exports load the engine with it
  (`ensureLoadedForExport`); the work service switches the engine to it once nobody has listened for 60 s (so a short
  pause never makes the player and the export take turns reloading); playback reloads with its own count when it
  starts. S10+: 4 either way; S26: 8 for playback, 4 for exports.
- **Release signing key** made and kept on this laptop, outside the repo (PKCS12, RSA 4096,
  alias `lectrice`, valid 100 years, SHA-256 cert `AE:F9:C7:F0:…:67:8C`), with its password file and a local backup.
  `keystore.properties` (gitignored, 0600) points at them. **Back the key up off this laptop too** (losing it
  means no updates for installed release builds).

## Not done / notes

- Download / airplane-mode checks: not run (metered network).
- Release APK (signed with the new key, `apksigner` cert SHA-256 `aef9c7f0…678c`) installed on the S10+, first run S1/S2 checked, uninstalled.
- APKs: `~/Desktop/lectrice-android-builds/2026-11-13-m2-recheck-audiobook/` (+ `SHA256SUMS`).
- Phone left locked (PIN), Lectrice force-stopped, stay-awake off; "Listen view over the lock screen" now off.
- `set_read_ahead_minutes … SpeechEngineUnavailable` at reader open and StrictMode disk reads on the main thread
  (`ModelStore.isInstalled`) are still logged in debug builds; harmless.
