# 12 · Typing test: switch and compare (`FinalTestCompare`)

Files: Android `settings/TypingTestPage.kt` and `LayoutLibrary.kt`; iOS `ios/App/TypingTestScreen.swift` and `AppSettingsModel.swift`.

## Bugs
1. **Android "✓ Test complete" is unreadable on light themes.** It uses hard-coded `#8ED5BE` on `#EBE4D6`, which is 1.34:1 (kt:151). Use `#1E6B52` on light and `#8ED1B8` on dark, matching iOS `LayoutStyle.success`.
2. **iOS "Which keyboard?" menu is too small to tap.** The target is under 44 pt (swift:117). Add `.frame(minHeight: 44)` and `.contentShape(Rectangle())`.

## Deltas
- **Results line.** "N above/below your <Keyboard> average (X)" (kt:158, swift:299).
- **Comparison table.**
  - Pass `thisTest: TypingResult?` into `KeyboardComparison`/`comparison` and tag that row **This test**.
  - When TailorKeys and one other keyboard both have data, add a summary: "TailorKeys is N wpm faster/slower on average, M points more/less accurate".
  - Subtitle: "All Test sentence results".
- **Placeholder copy.** "Drawn by the system. TailorKeys shows no keys, preview or key hints here." (kt:265, swift:56). iOS hint: "Tap and hold the globe key to switch keyboards." Hide it while comparing.
- **Next-key outline (TailorKeys only).** In the test preview, outline the key for `sentence[typed.length]` with a 2 dp accent ring. Never do this when typing with another keyboard.

## Tests
- Comparison summary maths, with and without a second keyboard.
- Next-key lookup for letters, space and punctuation.
