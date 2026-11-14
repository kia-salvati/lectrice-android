# Options brief · "Desktop helps the phone" phone screens (Opus → Fable, 2026-11-13)

Spec: `../lectrice/docs/android-logic/24-desktop-helper.md` §2, §4, §10, §11 (UX notes). Output: **one local HTML file**
`../lectrice/design/2026-11-13-helper-phone-options.html` (never a published Artifact) + screenshots in
`../lectrice/design/screens/helper-phone-<option>-<screen>-{light,dark}.png` (412×915 frames, light and dark).

Look and feel: copy the frame, tokens and components of `../lectrice/design/2026-11-13-audiobook-source-options.html`
(Quiet v2: `--bg/--bg2/--fg/--fg2/--line`, `.screen` 412×915, `.row`, `.sw` switch, `.btn`, bottom sheets, the pill).
Read that file and `../lectrice/design/2026-10-23-android-quiet-v2.html` for the reader, pill and player sheet. No visible
scrollbars anywhere. 48 dp touch targets. Use the computer name "Kia's laptop" and phone "Galaxy S10+" in examples;
invented book text only ("Chapter 7 · p. 265" style status, no real book quotes).

Icon to add: Material Symbols Rounded **`computer`** (24 px; 16 px in the pill). Others exist: `wifi`, `check_circle`,
`warning`, `error`, `bolt` (add if missing), `more_vert` (add), `chevron_right`.

## Screens every option must show (same copy in all options; only layout differs)

| Id | Screen | States to draw |
|---|---|---|
| H1 | Settings → Computer helper | (a) nothing paired (switch disabled, "Pair a computer first"); (b) paired + connected; (c) paired + not reachable; (d) Needs attention (warn line "Your computer needs the Fast voice (111 MB) to help. Open Lectrice on the computer to download it." with action **Retry**) |
| H2 | Pair: computer list | (a) "Looking on this Wi-Fi…" spinner; (b) one computer found (name only: **no "Ready to pair" line**, discovery no longer says when a code is showing); (c) after 10 s nothing found: help text + link "Enter the address instead"; (d) not on Wi-Fi: "Connect to Wi-Fi to pair." |
| H3 | Pair: code entry | (a) empty, 6 boxes, numeric keyboard, subtitle "Type the code shown on Kia's laptop."; (b) Checking… (spinner, boxes locked); (c) wrong code: "That code didn't work. Check the code on your computer." (boxes cleared); (d) 3rd wrong code or no code showing: "No code is showing on Kia's laptop. On the computer, click **Pair a phone** to get a new code." (no automatic new code: security S4) |
| H4 | Pair: enter the address | one field "192.168.1.20:47878", IP keyboard, **Continue** → H3 |
| H5 | Paired | check mark, "Paired with Kia's laptop", line "Lectrice on your computer now makes the audio when you're both on this Wi-Fi.", the switch already on, button **Done** |
| H6 | Nearby-devices rationale (Android 17+ only) | sheet: "To find your computer, Lectrice needs permission to see devices on your Wi-Fi. It never looks beyond your home network." **Continue** / **Not now**. Plus the denied row: "Allow Nearby devices to use your computer" → **Open settings** |
| H7 | Pill + player sheet status | pill (expanded) with a 16 dp `computer` mark before "Ch. 7 · p. 265" only while connected/busy; player sheet line in: "Making audio on Kia's laptop · 12 min ready", "Kia's laptop is busy · 22 min ready", "Looking for Kia's laptop…", "Computer not reachable · 24 min ready", Needs attention (warn colour + **Fix**); the one-time snackbar "Your computer isn't reachable. The phone carries on by itself." |
| H8 | Prepare through the computer | player sheet row "Prepare on Kia's laptop" (bolt icon) → chips "This chapter · Next 3 chapters · As much as fits"; while running "Preparing on Kia's laptop · 40 %"; done "Ready up to Ch. 9" |
| H9 | Needs-attention texts | model missing (above); "Update Lectrice on your computer"; "Update Lectrice on this phone"; "Kia's laptop forgot this phone. Pair again." (+ **Pair again**) |

Long text rules: rows wrap, 200 % font must not clip. "min ready" = whole minutes, "under a minute ready" below 60 s.

## The options (draw all three, then a short comparison table)

**Option A · "Own page + pages" (spec baseline, Opus recommends).**
- Settings: a new section header **"Computer helper"** under "Voice model" with one `ListRow` "Computer helper", meta
  "Off" / "Kia's laptop · connected" / "Kia's laptop · not reachable", chevron → **H1 as its own page** (TopBar + back):
  switch row "Let a computer make the audio" + explainer paragraph (spec §11.1 text), section "Computers" (one row per
  computer: name + "Connected" / "Last seen 2 h ago", trailing `more_vert` → menu Rename / Forget), text button
  **"Pair with a computer"**, help line at the bottom.
- Pairing: full-screen pages pushed on top (H2 → H3 / H4 → H5), each with TopBar + back.
- Player sheet: a normal `ListRow` placed under the Voice row (and under the "Audio" row when the book has an audiobook):
  leading `computer`, title "Computer helper", subtitle = the status line, chevron → H1. Needs attention: subtitle in
  `warnFg` and a trailing text button **Fix** instead of the chevron.
- H8: the existing Prepare row (battery icon) becomes "Prepare on Kia's laptop" with the `bolt` icon while connected.

**Option B · "All in Settings + one pairing sheet".**
- Settings: no extra page. Section "Computer helper" directly in Settings: switch row (subtitle = status), one row per
  computer (⋯ menu), row "Pair with a computer". Help line under the section.
- Pairing: one tall bottom sheet whose content steps H2 → H3 → H5 (H4 as a step too), with a small step header
  ("Find · Code · Done") and a back arrow inside the sheet.
- Player sheet: the status folds into the **"Audio" row** (shown whenever a computer is paired or an audiobook is
  linked): subtitle "Made on Kia's laptop · 12 min ready" / "Made on this phone · computer not reachable · 24 min
  ready"; tap → the same "Listen with" sheet as file 23 with a third, informational card for the computer.

**Option C · "Own page, inline pairing".**
- Settings + H1 as in option A.
- Pairing is one page: the list of found computers on top; tapping a computer expands it in place to show the 6 boxes
  under its name (no separate code page); success replaces the list with the H5 block.
- Player sheet: no row; a one-line status text (fg2, 14 sp, `computer` icon 16 dp) directly under the speed slider,
  tappable → H1.

Comparison table at the bottom of the HTML: taps to pair, discoverability of the status, how it fits the existing
sheet (Voice/Audio/Sleep/Prepare rows), risk of confusion with the audiobook "Audio" row, 200 % font behaviour.

## Done when

The HTML opens locally (`google-chrome file:///…`), has a light/dark/black theme switch like the audiobook options file,
shows every H-screen for every option (screens shared between options may be drawn once and labelled "same in A/C"),
and the PNGs are written. Note what you did in two lines at the top of the HTML (a comment). Don't touch any other file.
