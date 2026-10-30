# Next fixes: autocorrect trust, layer keys, split strip

Planned 2026-10-26. Ordered by how much each one hurts day to day. Before starting, commit the current
uncommitted work (split grips, touch drift, gap keys, layer grid, depth shrink) so each fix below lands as its
own change.

---

## 1. Look-back must not overturn words you typed on purpose (meta -> meat)

**What happened.** `meta` is not in `words.txt` (60,000 words; `meat` is #1166, `graph` #4983). At its own space
autocorrect left it alone. Then `graph` finished, and look-back (`Predictor.lookBack`) re-scored it:

- the word on screen is unknown, so it pays `UNKNOWN_WORD_DOUBT` (6.5 nats);
- `meat` is a transposition, which `CorrectionRules.smallEdit` lets through no matter where the taps landed;
- `LOOK_BACK_MARGIN` is only 3 nats.

So clean, deliberate taps lost to a word the taps never pointed at. Sentence re-decoding has the same weakness.

**Fixes**

1. **A word autocorrect kept stays kept, unless the taps disagree.** If autocorrect declined to change a word at
   its space, look-back and re-decoding may only change it when the taps lean toward the replacement (its tap cost
   is below a small threshold, and at least one tap sat closer to the replacement's letter than to the typed one).
   Clean taps on m-e-t-a are evidence *for* meta, not neutral. Also raise the margin for such words (start at 5
   nats, tune with the eval).
2. **Capitalized mid-sentence = a name.** A word whose first letter you shifted yourself mid-sentence is never
   corrected at space, look-back or re-decode (`Figma`, `Vercel`, `Meta`). Today `CorrectionRules.eligible` only
   protects words with capitals after the first letter.
3. **Fill vocabulary gaps.** Audit `words.txt` against the language model's vocabulary and a recent web or chat
   word list; add common modern words that are missing (meta, repo, async, ...). Every word the model knows should
   be a dictionary word.
4. **Undo teaches.** Check that backspace after a look-back or re-decode records a rejection (like a normal
   correction does), so the same flip never happens twice.
5. **Show it.** When look-back changes an earlier word, flash a brief underline on it so the change is visible
   and easy to undo.

**Tests.** Add `meta graph has an edge` (clean taps) to `AutocorrectSlipsTest`. Track two numbers in
`AutocorrectEvalTest`: carefully typed words changed (must go down) and real slips fixed (must not drop much).

---

## 2. Keeping a word exactly as typed (brand names, odd spellings)

**Double space for a period: usage numbers.** There are none public. iOS and Gboard both ship it on by default,
and TailorKeys does too (`DOUBLE_SPACE_PERIOD = true`), so people coming from an iPhone expect it. Nobody has
published how many actually use it.

**Recommendation, best first.** A prefix button is the weakest option: you have to know a correction will misfire
before you type, and it costs a key.

1. Item 1.2 above (capitalized = name) covers most brand names with no action at all.
2. **Backspace right after a correction restores what you typed** and remembers it (already exists for ordinary
   corrections; extend it to look-back and re-decoding).
3. **The wheel's first slot shows your exact text in quotes** after any correction; tapping it restores and
   learns it at once (normally a word is learned after being typed twice).
4. **Optional: double space = keep as typed.** Turn the double-space setting into a choice: *Period* / *Keep word
   as typed* / *Nothing*. With *Keep*, a second quick space reverts the correction the first space made, and
   learns the word.

---

## 3. Use the context better (the wheel often beats autocorrect)

The model already sees the last two sentences plus the current one (`EnglishTokens.context`). The gap is that
autocorrect and the wheel rank differently: autocorrect has extra gates (`reachable`, short-word rules,
margins, `TYPO_WEIGHT`), so the wheel sometimes has the right word when autocorrect does not.

1. **Log the disagreements first.** Add a debug setting that records, at each space: typed, autocorrect result,
   wheel top 3, and whether you then picked a wheel word. Each time you pick from the wheel after a correction is a
   labeled mistake. Collect a few days of real typing before tuning.
2. **One ranking.** Autocorrect takes the wheel's top candidate, and only decides *whether* to apply it (the
   confidence gate), instead of ranking again on its own.
3. **Topic memory.** Give a boost to words already used earlier in the same text field (a cache model). If you
   wrote "graph" two sentences ago, "graph" and "meta" both stay likely. Cheap and effective for technical writing.

---

## 4. Punctuation

Today: a space before punctuation is swapped, brackets and quotes pair up, double space gives a period, and
TidyText fixes spacing and capitals.

1. **Contractions.** A table-driven pass with tests: dont -> don't, im -> I'm, youre -> you're, wont -> won't
   (careful: "wont" and "its" are real words, so those two need context).
2. **Question marks.** When a sentence that starts like a question (what, how, why, can you, do you, is it) ends
   in a period, offer "?" in the wheel, or swap it automatically with undo.
3. **Commas.** Suggest only (wheel), never insert automatically. The model already scores a comma token, so it
   can rank where one is likely.

**Needs from you:** 3 to 5 real examples where punctuation went wrong, so this targets what you actually hit.

---

## 5. Split keyboard: a touch bar instead of the center column

**Problem.** The center column is as wide as the split gap. Slide the halves together and its keys squash into a
line. The center-gap cursor is also too far from the thumbs.

**Plan: a strip in each half, between its letter rows and its space key** (decided 2026-10-30: above the space
row, not under it, close to the thumbs). Each strip is as wide as its half; nothing crosses the gap. About 28 dp,
used for:

- **Cursor touch bar.** Swipe left or right to move the cursor (uses the existing cursor sensitivity); press,
  hold, then drag for fine moves; double-tap then drag to select.
  It uses the same push-to-move feel as today's cursor zones.

**Center keys when the gap is narrow** (decided 2026-10-30). When the center gap is below about 0.8 key width,
the center column's keys move outside the halves: a column in the empty space beyond each half's outer edge. They
move back when the gap widens (250 ms, instant with reduced motion).

- Both outside columns always have the same number of slots and the same key size; an odd count leaves an empty
  slot on one side. Slots split the letter rows' height evenly (at most a key and a half tall each) and sit
  centered in the space beside each half.
- Each center key has a side: *Auto* (alternates to balance), *Left* or *Right*, set in the layout editor. When the
  choices put more keys on one side, both columns grow to that count so the sizes still match.
- Without room outside (halves near the screen edges as well), the grips stop the halves before either column would
  be narrower than about 0.8 key width. Nothing is squeezed.
- The grips go by the keys you see, not each half's slot (the slot leaves room for the full tilt range, so at tilt 0
  it is about a quarter wider than the keys). Raising the tilt later pushes the halves apart as far as it needs.
  The gear between the wheels always keeps 36 dp.

**Touch bar style** (built 2026-10-30): *Joystick* (push from wherever the finger lands) or *Left and right* (the
left bar moves the cursor left, the right one right; a tap moves one character, holding repeats from 8 up to 22
characters a second). Cursor sensitivity scales both. With *Left and right*, a double tap doesn't start selecting,
since quick taps each move a character.

Add a *Touch bar* option to `CursorControl` and make it the split default. Standard (unsplit) layouts get it as an
option: one strip across the keyboard between the letters and the space row.

**Enough room on iOS?** Yes. The extension sets its own height (`KeyboardViewController.swift:355`), and Apple
publishes no fixed limit; a strip of about 30 pt is well inside what keyboards normally use. On Face ID iPhones
the system's globe and mic bar sits *below* the extension, so the strip isn't at the screen edge. On iPad there is
no bar, so touches at the very bottom can be delayed by system edge gestures; keep a few points of padding there.
To keep the total height unchanged, take the strip's height out of the key rows.

---

## 6. Layer keys: choose how they switch, plus a real Shift key

1. **Setting "Layer keys switch by":** *Swipe sideways* (current) / *Swipe up and down* (the old behavior,
   still in git history) / *Tap*. In *Tap* mode a tap moves to the next face and a short hold moves back one.
   This applies to keys that switch modes (ABC, 123, #+=, Shift). Keys that stack characters still need a swipe,
   since a tap types the shown character.
2. **A dedicated Shift key** in the key palette (`Keys.SHIFT`): tap = one capital, double tap = caps lock, tap
   again = off. Today Shift lives on the ABC key (`tapLayerKey`), which stays as it is when no Shift key is
   placed.
3. **Shift stacked with layers, in Tap mode:** tap cycles one capital -> caps lock -> next layer; a short hold
   goes back one.
4. iOS gets the same.

---

## 7. Depth slider

The current (uncommitted) depth shrinks bowl keys by up to 8% (`MAX_SINK`), only in one direction, which is too
subtle to notice.

1. Range becomes -100 to 100. **Positive = bowl:** keys deeper in the bowl (home row, middle finger) draw smaller.
   **Negative = dome:** the center stays full size and the rim keys draw smaller.
2. Raise the strongest shrink to about 12 to 15% so it reads as depth, with the shading following the same
   direction.
3. Touch targets stay full size (drawing only, as now). Show a live preview in settings while dragging.

Built 2026-10-30: the range is -100 (dome) to 100 (bowl); strength stays at 8%. A *Compact* switch closes the shrunk
keys up so the gaps stay even (rows keep their centers, rows stack up from the bottom letter row), and touches
follow the keys as drawn.

---

## 7b. 3D keys (option)

A *Key style* setting: *Flat* (today) or *3D*. 3D keys draw a raised top face over a darker lower edge a few dp
deep; a pressed key's top sinks onto the edge. Drawing only: touch targets and key positions don't change. Works
with the bowl depth shading in item 7.

---

## 8. Lock the halves in place

- New setting `SPLIT_LOCKED`, toggled from the quick options panel (and in settings).
- When locked, the grips under the halves and the one-hand switch arrow fade out (about 200 ms; no fade with
  reduced motion) and stop taking touches. Unlocking fades them back in.

---

## Order

1 and 2 first (they're what makes typing irritating), then 3 (log first, tune after), 4 once examples exist,
then 5, 6, 7, 8. iOS doesn't have look-back or sentence re-decoding yet, so items 1 to 3 are Android-only until
that port happens.
