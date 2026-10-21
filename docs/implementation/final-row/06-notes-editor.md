# 06 · Notes editor (`FinalInsertNotes`)

## Today
Create, edit and delete work, with Undo for 6 s (`InsertData.kt:71-76`, `InsertPanel.kt:300-315`). The length limit is now unified at 2,000 characters (see the README).

## Build (look)
- **Above the keys:**
  - A 44 dp header: **Cancel** (text, `accT`), a centred "New note" / "Edit note" title, and **Save** (36 dp accent pill inside a 44 dp target, never disabled).
  - Then a 44 dp field row.
    - Tablet: Title (flex 1) and Text (flex 2).
    - Phone: Text only. The title is optional on phone and is set from the settings app.
  - Fields use `fill` and a 12 dp radius, with a small 12 sp label inside. The focused field gets a 2 dp accent inset outline and the caret.
- **Live counter** in the Text field: `n/2,000`, 12 sp, `sub`. It turns `danger` at the limit. The title shows `n/40` only while it is focused.
- The strip and the phone number row hide while editing (unchanged). The Insert key shows its open state.
- **Delete:** immediate, with a toast pinned above the bottom bar. It is a 14 dp radius card in the `snack` colours, reading "Note deleted" with **Undo**, and lasts 6 s.

## Acceptance
- Typing past 2,000 characters stops input, and the counter shows `2,000/2,000` in `danger`.
- Cancel keeps the draft for the next edit of the same note (existing `noteDrafts`).
