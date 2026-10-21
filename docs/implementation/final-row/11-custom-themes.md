# 11 · Look, custom themes (`FinalLookCustomThemes`)

## Today
Implemented on both platforms: built-in themes, "Your themes", selection, Edit and Delete with confirmation, the fallback to Warm, and the dark-app preview.

## Delta
- **Android Create tile.** Replace the "+\nCreate theme" TextButton (`ThemeEditor.kt:70`) with a tile the same size as `ThemeTile`. It is a 48 dp dashed rounded square with "+", labelled "Create", and it aligns with the swatches in the FlowRow (as iOS does, `ThemeEditor.swift:15-33`).
- The board's Warm strip accent was corrected to `#9C4A28`, which matches the code's `smallAccent`. No code change is needed for this.

## Acceptance
- The Create tile lines up with the custom swatches at every width. TalkBack reads it as "Create theme, button".
