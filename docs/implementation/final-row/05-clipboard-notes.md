# 05 · Clipboard and notes (`FinalInsertClipboard`)

## Build (look)
- **Inset grouped lists.**
  - One `key`-coloured group per list, 12 dp radius, with `hair` separators inset 14 dp.
  - Rows are 48 dp on tablet and 44 dp on phone.
  - Text is one line, 15 sp, with an ellipsis. The age is trailing, 13 sp, in `sub` (darkened to 4.5:1 on `key`).
  - Pinned rows lead with a filled pin icon. Image clips, if ever stored, lead with a 30 dp thumbnail.
- **Tablet:** two columns with a 14 dp gap. Left: PINNED, then RECENT. Right: NOTES, with **+ New** in `accT` on the header row.
- **Phone:** the bottom-bar middle is a **Clips / Notes** segmented switch (34 dp pill inside the 44 dp bar). Only one list shows at a time.
- **Tablet bottom-bar middle:** the privacy line, with a lock icon: "On this device · clears after <keep>". It updates with the keep setting. For "until cleared" it reads "On this device · kept until you clear it".
- **Hold menu:**
  - The held row gets a 2 dp accent inset outline and the panel dims.
  - A 14 dp card menu offers **Pin** (Unpin when pinned), **Edit**, **Save as note** and **Delete**. Delete comes last, in `danger`.
  - Notes get Pin, Edit and Delete.
- **States.** Each is centred in the content area: an icon in a 44 dp `fill` circle, a 17 sp semibold title, 14 sp `sub` text, then 44 dp buttons, of which only the primary is accent-filled.
  - **History off:** "Clipboard history is off" / "Notes still work: they are your own text, not history." [Turn on] [Settings]
  - **First use:** "Your clipboard, kept on this device" / "Copied text shows here and clears after <keep>. Pins and notes stay until you remove them. Password fields are never kept." [Got it]. Show it only when history is **on**. Today it also shows when history is off (`InsertPanel.kt:380-385`); fix that.
  - **iOS without Full Access:** "Turn on Full Access" / "iOS only shares the clipboard with Full Access. Notes still work." [Open Settings]. Notes stay reachable through the Clips/Notes switch.

## Fix (gaps)
- **Android accessibility actions.** Add custom accessibility actions on every clip and note row (Pin/Unpin, Edit, Save as note, Delete), matching iOS (`InsertPanel.swift:410`). Today the actions only open on long-press, as a PopupMenu (`InsertPanel.kt:396,409`). Replace the PopupMenu with the panel's own menu so it can be styled as on the board.
- **Held outline.** Missing today; add it.
- **iOS capture.** Today there is only a manual "Read clipboard" button (`InsertPanel.swift:386`). Keep it, label it "Paste from clipboard", and give it a 44 pt row at the top of Clips. Never read the pasteboard without a tap, because iOS shows its own paste prompt.

## Acceptance
- With TalkBack on a clip, the actions menu lists the four actions.
- With history off, only the history-off state shows, never the first-use card.
- The privacy line changes when the keep setting changes.
