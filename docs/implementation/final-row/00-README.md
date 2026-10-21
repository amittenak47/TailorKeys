# Final row: Insert panel (sleek), custom themes and typing test compare

These are implementation docs for the **"Final"** row of the design canvas *TailorKeys Settings Redesign*. Each artboard has one doc, and each doc stands on its own. Read this README first, because it holds the rules every doc relies on.

| # | Artboard | Doc | Scope |
|---|---|---|---|
| 1 | `FinalInsertEntry` | [01-insert-key.md](01-insert-key.md) | Small fix |
| 2 | `FinalInsertEmoji` | [02-emoji.md](02-emoji.md) | Restyle and sizing fix |
| 3 | `FinalInsertSearch` | [03-emoji-search.md](03-emoji-search.md) | Restyle |
| 4 | `FinalInsertKaomoji` | [04-kaomoji.md](04-kaomoji.md) | Restyle and a new hold menu |
| 5 | `FinalInsertClipboard` | [05-clipboard-notes.md](05-clipboard-notes.md) | Restyle and accessibility actions |
| 6 | `FinalInsertNotes` | [06-notes-editor.md](06-notes-editor.md) | Restyle and a counter |
| 7 | `FinalInsertVoiceAndroid` | [07-voice-android.md](07-voice-android.md) | Restyle, composing text, rings, fallback |
| 8 | `FinalInsertVoiceIos` | [08-dictate-ios.md](08-dictate-ios.md) | Restyle |
| 9 | `FinalSettingsPanels` | [09-settings-pages.md](09-settings-pages.md) | Deltas |
| 10 | `FinalThemeEditor` | [10-theme-editor.md](10-theme-editor.md) | Deltas and 2 bugs |
| 11 | `FinalLookCustomThemes` | [11-custom-themes.md](11-custom-themes.md) | Small delta |
| 12 | `FinalTestCompare` | [12-typing-test-compare.md](12-typing-test-compare.md) | Deltas and 2 bugs |

**Design source.** Exact values are in each artboard's HTML on the canvas (`project/<Artboard>.dc.html`). Where this README and an artboard disagree, the artboard wins on looks and this README wins on behaviour. Captions describe behaviour; they are not UI copy, unless a doc quotes them as copy.

## Already done (uncommitted in the working tree at hand-off)

- **iOS clipboard capture.** `KeyboardViewController.attachInsertPanel` now treats a field as a password field only when `isSecureTextEntry` is true. Before, any empty field counted as a password field, so "Read clipboard" did nothing.
- **One note length limit.** Notes are limited to 2,000 characters everywhere: `ClipboardStore.NOTE_MAX` and `TITLE_MAX` on Android, `ClipboardStore.noteMax` and `titleMax` on iOS. This covers the panel editor, the settings dialogs and the store. Clips keep 20,000. Tests: `InsertDataTest.notesShareOneLengthLimitButClipsKeepTheirs`, plus a check in `ios/Tests/main.swift`.
- **Emoji warm-up off the UI thread.** `InsertPanel` parses the emoji catalog and runs the `hasGlyph` checks on a low-priority thread when the panel is built. Glyph checks are serialized on the `Paint`.

## Sleek system (applies to docs 01–08)

These tokens are derived from the keyboard's own `Palette`. Do not add new theme colours.

| Token | Value |
|---|---|
| `fill` | `mix(background, text, 0.07)`. Quiet field, search pill and segmented-control track. |
| `fillHi` | `mix(background, text, 0.12)`. Selected category capsule. |
| `hair` | `mix(key, text, 0.12)`. 1 px separators inside grouped lists, inset 14 dp from the leading edge. |
| `thumb` | `key`. The raised segment, with a soft shadow (elevation about 1 dp). No accent underline. |
| `sub` | `hint` / muted text. Darken it toward `text` until it reaches **4.5:1 on `fill`**. On Warm, `#6B6254` fails on `fill` (4.1:1) and must be darkened. |
| Accent | Used only for the Stop button, Save, the focused-field outline, the held-item outline and the primary button in a state. Never for selection state. |
| Radius | Fields, chips, groups and segments: 12 dp. Thumb: 9 dp. Menus and popovers: 14–16 dp. |
| Type | System font: Roboto Flex on Android, SF on iOS. Section headers are 12 sp, semibold, uppercase, tracked +0.4, in `sub`. Rows are 15 sp. |
| Targets | Every control's hit area is **at least 44 dp/pt** high. Visual pills may be smaller inside that area. |

### Panel chrome (replaces the current top bar and category row)

The panel stays the same height as the keys. It has three bands:

1. **Top row, 44 dp.** The search pill fills the width, followed by the **icon-only** segmented control: Emoji (smiley), Kaomoji (`^_^`), Clipboard and Voice. The control is the same on phone and tablet. Segments are 56 dp wide on tablet and 44 dp on phone. Each segment's accessibility label is its name; on iOS, Voice is called "Dictate". A long-press shows the name as a tooltip.
2. **Content.** It sits directly on the panel background, with no inner surface or card behind the whole content.
3. **Bottom bar, 44 dp.**
   - ABC on the left: text only, no key fill. It is 64 dp wide on tablet and 52 dp on phone.
   - Context in the middle: emoji category icons, kaomoji category text, the Clips/Notes switch, or the privacy line.
   - Backspace on the right: icon only, no fill.
   - On phone, the middle scrolls, with a 24 dp fade on the trailing edge.

Panel padding is 10/12/4 dp on tablet and 6/4/2 dp on phone. The gap between bands is 6 dp on tablet and 4 dp on phone.

Selection uses one language everywhere: the segment thumb, or the `fillHi` capsule for categories. Remove every `inset 0 -3dp` accent underline and every accent stroke used to mark selection.

## Build and test rules

- JAVA_HOME = `C:\Program Files\Java\jdk-23`. Run `.\gradlew.bat --no-daemon testDebugUnitTest assembleDebug --quiet`. Run one Gradle build at a time.
- Swift can't be compiled on this machine, so re-read changed Swift files carefully. Add pure-logic checks to `ios/Tests/main.swift`.
- Keep Insert UI changes inside `insert/` and `ios/Keyboard/InsertPanel.swift`. Stay out of the `Predictor` and commit paths.
- Commit in logical commits, one per doc where practical. Do not push. Do not install anything to devices.

## Done means

Every doc's acceptance list passes. All tests pass. The debug APK builds. iOS mirrors Android unless a doc says otherwise.
