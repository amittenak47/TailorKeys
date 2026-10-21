# 10 · Theme editor (`FinalThemeEditor`)

Files: Android `settings/ThemeEditor.kt` and `Theme.kt:64`; iOS `ios/App/ThemeEditor.swift` and `Keyboard/CustomThemes.swift:18`.

## Bugs
1. **Edited themes jump to the end of the list.** The save does filterNot, then append (`KeyboardSettings.kt:278`, `KeyboardSettings.swift:351`). Replace the theme in place.
2. **Android "Start from" starts on the wrong theme.** It starts at the currently selected theme even when another custom theme is being edited (kt:120). Start at `existing?.selection`, or null for a new theme.

## Deltas
- **Checks.** Replace `checks()` with five (label, ratio, target) entries:

  | Check | Colours | Target |
  |---|---|---|
  | Text on keys | text / key | 4.5 |
  | Text on function keys | text / functionKey | 4.5 |
  | Enter key label | onAccent / accent | 4.5 |
  | Keys against background | key / background | 1.2 |
  | Pressed against keys | pressed / key | 1.2 |

  Warning copy: "Hard to read. Aim for 4.5 : 1 or more." and "Pressed keys look the same. Aim for 1.2 : 1 or more." Saving is never blocked.
- **Closest readable colour.** Add `closestReadable(fg, bg, target)`, which keeps the hue and steps the lightness. Show "Closest readable colour #XXXXXX reads at N : 1 [Use]" in the picker, and "Use #XXXXXX" on each failing check.
- **"Low contrast" badge** on any colour row that takes part in a failing check.
- **Chevron** points down on the open row (kt:156, swift:118).
- **Reset** in the picker restores the channel's colour from when the picker opened (kt:172, swift:135).
- **iOS:** the "Theme name" label with an "N / 24" counter. The selected "Start from" button gets a selected style and `.isSelected`.

## Tests
- Update the contrast-check unit tests on both platforms.
- Add `closestReadable` cases: it reaches the target and keeps the hue within ±1°.
- Saving an edited theme keeps its index.
