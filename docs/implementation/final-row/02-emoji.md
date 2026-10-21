# 02 · Emoji (`FinalInsertEmoji`)

## Build (look)
- Use the README chrome. The top row holds the "Search emoji" pill and the segmented control. The bottom bar holds ABC, the **10 category icons** and Backspace. Categories: Recent, Smileys, People, Animals, Food, Travel, Activities, Objects, Symbols, Flags.
  - Each category button is 48×44 on tablet and 44×44 on phone.
  - The active category gets a 34 dp `fillHi` circle and the `text` colour; the others use `sub`.
  - On phone the category row scrolls.
- **Grid.** No tile backgrounds.
  - Tablet: 14 columns, 52 dp rows, 32 sp glyphs.
  - Phone: 8 columns, 46 dp rows, 28 sp glyphs.
  - Section headers ("Frequently used", "Smileys & people", …) scroll with the grid and are 26 dp tall.
- **Category tracking.** As the grid scrolls, the bottom-bar category follows the section in view. The existing `onScroll` handler already does this; keep it.
- **Skin-tone popover (hold).**
  - A rounded card (16 dp) above the held emoji, with six 52 dp cells.
  - The default tone has a `fillHi` background and a 2 dp accent outline.
  - Below the cells, a line reads "<Tone> is your default · change it in Settings", with a check icon.
  - The rest of the panel dims (18% black on light themes, 40% on dark).
  - Slide and lift to insert, as today.

## Fix
- **Cells below 44 dp on narrow phones.**
  - Android: `InsertPanel.kt:216` stretches the columns to the width, so a 360 dp phone gets about 43 dp cells. Compute `cols = min(8, floor(width / 44dp))` on phone and `min(14, floor(width / 52dp))` on tablet. Use `STRETCH_SPACING_UNIFORM` so cells keep their size and the leftover space centres the columns.
  - iOS: the grid hard-codes 384/784 pt (`InsertPanel.swift:96,198`), which overflows a 375 pt iPhone. Derive the columns from `bounds.width` the same way.
- **First-open cost.** This is already moved off the UI thread (see the README). If Emoji opens before the warm-up finishes, show Recents only and fill in the rest when ready. Never block on the lazy value from a touch handler.

## Acceptance
- Cells are at least 44 dp wide at 320, 360, 390 and 412 dp widths, and at least 52 dp on tablet.
- Opening Emoji first thing after the IME starts does not drop frames on the tablet (`adb shell dumpsys gfxinfo`).
- TalkBack reads each emoji's name, and the popover cells read "<name>, <tone>[, default]".

## Tests
- A JVM test for a pure `emojiColumns(widthDp, tablet)` function.
