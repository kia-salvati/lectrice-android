# M2 re-check UI fixes (2026-11-13)

Brief: `docs/2026-11-13-recheck-ui-brief.md` (P1–P3). Compose only, no new strings.

## P1 · Status bar scrim
- `Components.kt`: new `StatusBarScrim` (solid `bg`, status-bar height).
- Added, aligned `TopCenter`, to Welcome, ChooseModel, Download/Ready (one scrim in `DownloadScreen`'s
  outer `Box`), ModelPage, Storage, and both Licenses pages (list and license text). The Licenses pages
  had no outer `Box`, so their `Column` is now wrapped in one.
- **Placement note:** the scrim sits above the scrolling content and bottom buttons but *below* the
  in-tree dialogs, sheets and the snackbar, not as the very last child. Dialogs and sheets draw their own
  full-height dim; a scrim above them would leave an undimmed strip at the top. Move it to the end of
  the `Box` if the literal "last child" was meant.
- Settings is not changed here (`SettingsScreen.kt` is off-limits).

## P2 · Storage while loading
- Voice models: the empty row shows "…" while `state.loading`.
- Books: while loading with no books, the "…" header line (bodyS, `fg2`) shows instead of "No books yet."

## P3 · SegmentedControl at large fonts
- `SegmentedControl` measures the labels (`rememberTextMeasurer`, `type.label`) inside
  `BoxWithConstraints`. If any label + 8 dp is wider than `rowWidth / n`, the options go in two rows of
  `ceil(n / 2)`, 8 dp apart; each row is its own pill (new private `SegmentRow`, same drawing).
- With an odd count the second row has one segment fewer and its segments fill the row.
- One row is unchanged at normal font sizes.

## Check
`:app:assembleDebug testDebugUnitTest` passed (through the flock command). Not checked on a phone or in
screenshots.
