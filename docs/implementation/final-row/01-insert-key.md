# 01 · Insert key (`FinalInsertEntry`)

## Today
Implemented: the key sits next to ?123 in the presets, it is removable, and the app shows a one-time "Add the Insert key" prompt. Files: `layout/KeyGrid.kt:218-228,331,349`, `settings/LayoutLibraryPage.kt:50-62`, iOS `KeyGrid.swift:212` and `LayoutScreen.swift:32,73`.

## Build
- **Open state.** When the panel's search or note editor brings the keys back, the Insert key shows a tinted fill (`palette.pressed`) **and** a 2 dp accent outline. Tapping it returns to the panel. Today only the ring is drawn (`KeyboardView.kt:1136`). Add the fill. Mirror this on iOS.
- **Prompt, unchanged behaviour.** It stays one-time and offers the first custom layout that lacks the key. This is intended, so do not change it. Do add a comment in `LayoutLibraryPage.kt` saying the seen flag is also set when no layout qualifies, so the behaviour isn't "fixed" later by mistake.

## Acceptance
- With the panel's search open, the Insert key shows both the fill and the outline in Warm, Dark and High contrast.
- With the panel closed, the key looks like any other function key.
