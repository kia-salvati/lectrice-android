# UI brief · "Desktop helps the phone", option A (Opus → Fable, 2026-11-13)

Spec: `../lectrice/docs/android-logic/24-desktop-helper.md` §4, §11. Mockup (**picked by Opus: option A**, "Own page +
pages"; the user can change it later): `../lectrice/design/2026-11-13-helper-phone-options.html`, frames `A-*` and the
shared `S-*` (screenshots `../lectrice/design/screens/helper-phone-A-*.png`, `helper-phone-S-*.png`). Match the A frames;
Quiet v2 tokens/components as everywhere (`LectriceTheme`, `core/designsystem`: `ListRow`, `SectionHeader`, `TopBar`,
`LectriceSwitch`, `LectriceTextButton`, `BottomSheet`, `ChoiceChip`, …). The logic is done (Opus); you only write
Compose, icons and strings. Your fill-ins from the options pass are accepted (H2c help bullets, H5 without title/back,
H6 denied card on H2, H3d greyed boxes, no button on the two "Update" texts).

**Do not touch** `LectriceApplication.kt`, `MainActivity.kt`, `AppContainer.kt`, any ViewModel,
`*UiState.kt`, `core/link`, `core/model`, `HelperLines.kt`. Don't run git.

## State and actions you get (already in the code)

- `feature/settings/.../ComputerHelperViewModel.kt`: `ComputerHelperUiState(helper: HelperState, found:
  List<FoundComputer>, pair: PairStep?)`, `PairStep` (`List(searchedLong)`, `Address(text, invalid)`, `Code(computerName,
  address, phase: CodePhase)`, `Done(computer)`), `CodePhase` (Typing, Checking, Wrong(attempt), NoCode, Unreachable,
  NotLocal, Incompatible), `ComputerHelperActions` (all callbacks; KDoc says which screen). `HelperState`, `HelperLink`,
  `HelperAttention`, `PairedComputer` (use `shownName`), `FoundComputer` are in `core/model/.../ComputerHelper.kt`.
- `SettingsUiState.helperSummary` + `SettingsActions.openComputerHelper()`.
- Reader: `ReaderUiState.helper: HelperLineUi?` (`text`, `needsAttention`, `pillMark`, `pillSpoken`),
  `PrepareInfo.onComputer: String?`, `ReaderMessage.ComputerLost`, `ReaderActions.openComputerHelper()`.

## Build

1. **Icons** (`Icons.kt`, Material Symbols Rounded, as in the mockup): `Computer`, `Bolt`, `MoreVert`. Mind the D8
   lesson: absolute `M` after `z`.
2. **Settings row** (`SettingsScreen.kt`): a new `SectionHeader("Computer helper")` + one `ListRow("Computer helper",
   subtitle = state.helperSummary, leadingIcon = Computer, chevron)` → `actions.openComputerHelper()`. Place it
   **directly after the Storage section** (before "Battery"), not after "Voice model".
   Keep the edit to those few lines.
3. **New file `feature/settings/.../ComputerHelperScreen.kt`** with exactly this entry point (MainActivity already calls
   it):
   ```kotlin
   @Composable
   fun ComputerHelperScreen(state: ComputerHelperUiState, actions: ComputerHelperActions, onBack: () -> Unit,
       onRequestPermission: () -> Unit, onOpenAppSettings: () -> Unit)
   ```
   - `state.pair == null` → **H1** (frames A-H1a–d, A-H1m, A-H9b–d): TopBar "Computer helper" + back (`onBack`); switch row
     "Let a computer make the audio" (`setEnabled`; disabled with subtitle "Pair a computer first" when
     `helper.computers` is empty); the explainer paragraph; section "Computers": one row per computer (`shownName`;
     subtitle "Connected" when it is `helper.active` and `helper.link.makesAudio`, else "Last seen …" from `lastSeenAtMs`
     ("Last seen 2 h ago", "Last seen just now", "Not seen yet"); trailing `MoreVert` → menu **Rename** (a small dialog
     with a text field, ≤ 40 chars, empty = back to the computer's name → `rename(peerId, label)`) / **Forget** (confirm
     dialog "Forget *name*? You'll need a new code to pair again." → `forget(peerId)`); text button **"Pair with a
     computer"** → `startPairing()`; the help line at the bottom. When `helper.link is NeedsAttention` show the H9 warn line
     under the switch with the texts of the mockup (model missing → **Retry** = `retry()`; not paired → **Pair again** =
     `startPairing()`; the two "Update" texts: no button; PermissionDenied → the H6 denied card with **Open settings** =
     `onOpenAppSettings()`).
   - **H6 rationale**: when `helper.needsPermission` is true and the person turns the switch on or taps "Pair with a
     computer", first show the rationale `BottomSheet` (frame S-H6a): **Continue** → `onRequestPermission()` (then do the
     action), **Not now** → close. (Today's target SDK 36 never sets `needsPermission`; it must still work.)
   - `state.pair` non-null → the pairing page for the step (frames A-H2a–d, A-H3a–d, A-H4, A-H5), TopBar back →
     `actions.pairBack()` (the route already maps the system back to it):
     - **H2** `List`: "Not on Wi-Fi" when `!helper.onWifi` (A-H2d); else `found` rows (name; tap → `pickComputer`; no
       "Ready to pair" line); while empty a spinner "Looking on this Wi-Fi…"; when `searchedLong` the help bullets + link
       **"Enter the address instead"** → `enterAddress()` (show this link also under a non-empty list, smaller).
     - **H4** `Address`: one field (IP keyboard: `KeyboardType.Uri` is fine), label "Address", placeholder
       "192.168.1.20:47878", error "That isn't an address like 192.168.1.20:47878." when `invalid`; **Continue** →
       `submitAddress()`; `addressChanged(text)` on every change.
     - **H3** `Code`: 6 digit boxes (one hidden `BasicTextField` driving 6 boxes is fine), numeric keyboard, paste of 6
       digits works, auto-submit on the 6th digit → `submitCode(code)`; subtitle "Type the code shown on
       *computerName*." (empty name → "your computer"). Phases: `Checking` (spinner, boxes locked), `Wrong(n)` "That code
       didn't work. Check the code on your computer." + "Try *n* of 3" (boxes cleared, typing again calls nothing
       special), `NoCode` (A-H3d text; boxes greyed; text button **Type a new code** → `typeAgain()`), `Unreachable`
       "Couldn't reach the computer. Check that both are on the same Wi-Fi and Lectrice is open there." + **Try again** →
       `typeAgain()`, `NotLocal` "That address isn't on your home network.", `Incompatible` "Lectrice on the computer and
       on this phone don't match. Update the older one."
     - **H5** `Done`: A-H5 (check mark, "Paired with *name*", the line, the switch shown on), **Done** →
       `finishPairing()`.
4. **Pill** (`Pill.kt`): when `state.helper?.pillMark == true`, a 16 dp `Computer` mark before the status text in the
   expanded pill only (frame S-H7p), like the audiobook `Headphones` mark (the audiobook mark wins if both, spec §3.12).
   Append `helper.pillSpoken` to the pill's content description.
5. **Player sheet** (`PlayerSheet.kt`, frames A-H7a–e): when `state.helper != null`, a `ListRow` right after the Voice
   row's divider (after the "Audio" row when that one shows): leading `Computer`, title "Computer helper", subtitle =
   `helper.text`; normal: chevron, click → `actions.openComputerHelper()`; `needsAttention`: subtitle in `warnFg` and a
   trailing text button **Fix** → `openComputerHelper()`. Divider after it.
6. **Prepare row** (H8, frames A-H8a–d): when `prepare.onComputer != null` the existing Prepare row's title is "Prepare
   on *name*" and its icon `Bolt` (else unchanged: battery icon, today's title). Chips and texts unchanged (the summary
   already says "Preparing on *name* · 40 %").
7. **Snackbar** (`ReaderScreen.kt`): `ReaderMessage.ComputerLost` → "Your computer isn't reachable. The phone carries on
   by itself." (no action), the same way the other reader messages are shown.

Strings go in each module's `strings.xml`. 48 dp targets, no visible scrollbars, 200 % font must not clip (rows wrap;
check frames `*-x2`).

## Check

`flock ~/.cache/lectrice-heavy-build.lock ./gradlew -Dorg.gradle.jvmargs=-Xmx2g --max-workers=2
-Plectrice.mavenCentralMirror=https://maven.aliyun.com/repository/public :app:assembleDebug testDebugUnitTest -q`
must pass (it also rebuilds the Rust library; that's expected and slow the first time). No phone. Write two short lines
of what you did in `docs/progress/H4-ui.md`.
