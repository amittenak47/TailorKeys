# 03 · Emoji search (`FinalInsertSearch`)

## Today
Implemented: the user's real keys do the typing, results replace the strip, and there is a no-results state (`InsertPanel.kt:289-336`). On phone the number row is hidden (`TailorKeysService.kt:485`).

## Build (look)
- **Above the keys,** two 44 dp rows with a 6 dp gap:
  1. **Cancel**, in text `accT`, which goes back to Emoji. Then the field: `fill`, 12 dp radius, a search icon, the query, an accent caret, and a 44 dp **Clear** icon button inside the field.
  2. **Results:** one row of 48×44 emoji cells that scrolls horizontally. Tapping one inserts it and keeps the query.
- **No results:** a centred line in `sub` reading `No emoji for “<query>”`.
- The Insert key shows its open state (doc 01).

## Fix
- **Scroll position on Cancel.** Returning to Emoji restores the grid's first visible position and offset. `renderPanel` rebuilds the page today, so save and restore `grid.firstVisiblePosition` and the top offset. Do the same on iOS.

## Acceptance
- After Search and then Cancel, the grid is back where it was.
- Inserting three results in a row keeps the field and the query.
