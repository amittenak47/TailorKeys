# TailorKeys Android findings

Reviewed source revision `ededc15` on `work/scan-android`, covering Kotlin under `app/`, the manifest, Gradle configuration, and R8 rules. Totals: **0 Critical, 4 High, 4 Medium, 1 Low**; eight Confirmed and one Plausible. Improvements are counted separately.

Validation: `JAVA_HOME=C:\Program Files\Java\jdk-23`; after checking that no other Gradle Java process was running, `.\gradlew.bat --no-daemon testDebugUnitTest assembleDebug --quiet` succeeded. The suite discovered 33 tests: 30 passed and three optional timing/benchmark/evaluation tests were skipped. No device installation or device testing was performed.

A scratch JVM harness outside the repository invoked the unmodified debug class files using small Android superclass, UI, clock, and timer shims, plus an in-memory InputConnection implementing Android's documented selection semantics. It reproduced the cases described below, including a real learned-word file write. This verifies application logic, not native rendering or real editor callback timing. **Confirmed** means reproduced or fully traced; **Plausible** identifies the remaining device-dependent timing case. No application or test source was changed. Static inspection found no app-owned reflective serializer requiring an additional R8 keep rule; release shrinking was not executed.

## Findings

### 1. Choosing a suggestion can delete text outside the selection — High — Confirmed

**File and line:** `app/src/main/java/app/tailorkeys/TailorKeysService.kt:534`, `app/src/main/java/app/tailorkeys/TailorKeysService.kt:539`.

**What goes wrong:** In `help world`, select offsets `[4,10)` (the space and `world`), then choose the available completion `helped`. Suggestions still use the unselected prefix `help`. `onSuggestion` deletes that prefix with `deleteSurroundingText`, then `commitText` also replaces the selection, producing `helped ` and erasing the unselected `help`. The harness reproduced this exact result. Android explicitly defines surrounding deletion as excluding the selection and text commits as replacing it. [InputConnection contract](https://developer.android.com/reference/android/view/inputmethod/InputConnection#deleteSurroundingText(int,%20int)).

**Suggested fix:** Check the live selection before performing prefix replacement. With a nonempty selection, replace only the selected range, or suppress word suggestions until the selection collapses; batch the replacement.

### 2. Cursor controls can split emoji into invalid UTF-16 — High — Confirmed

**File and line:** `app/src/main/java/app/tailorkeys/TailorKeysService.kt:451`, `app/src/main/java/app/tailorkeys/TailorKeysService.kt:467`.

**What goes wrong:** Put the cursor after the emoji in `A😀B` (UTF-16 offset 3) and move left once using the cursor zone or its accessibility scroll action. The service sets offset 2, between the surrogate halves. Typing `x` then produces the code-unit sequence `A\uD83Dx\uDE00B`, corrupting the emoji. The harness reproduced both the intermediate cursor position and the corrupted text. Selection extension uses the same unit-by-unit arithmetic.

**Suggested fix:** Translate cursor steps into valid code-point boundaries, preferably grapheme boundaries for emoji sequences and combining marks, before calling `setSelection`. Apply the same boundary handling when extending a selection.

### 3. Fields requesting no personalized learning still persist typed words — High — Confirmed

**File and line:** `app/src/main/java/app/tailorkeys/TailorKeysService.kt:313`, `app/src/main/java/app/tailorkeys/TailorKeysService.kt:659`, `app/src/main/java/app/tailorkeys/Predictor.kt:384`.

**What goes wrong:** An ordinary text field with `imeOptions` containing `IME_FLAG_NO_PERSONALIZED_LEARNING` still sets `learn = true`. Typing a word and space updates the personal dictionary and context counts, which are saved when the input view finishes. The harness entered `secret` with this flag, pressed space, and verified `secret\t1` in `user_words.txt`. This flag is Android's request to avoid recording personalized typing data, including in private browsing or chat modes. [EditorInfo flag](https://developer.android.com/reference/android/view/inputmethod/EditorInfo#IME_FLAG_NO_PERSONALIZED_LEARNING). The four supported password variations separately passed checks for both learning and suggestions being disabled.

**Suggested fix:** Include the no-personalized-learning flag in the session's `learn` gate. Use that gate consistently for direct learning and deferred armed-suggestion learning.

### 4. A share code with a negative face index causes a persistent keyboard crash — High — Confirmed

**File and line:** `app/src/main/java/app/tailorkeys/layout/KeyGrid.kt:198`, `app/src/main/java/app/tailorkeys/layout/KeyGrid.kt:302`, `app/src/main/java/app/tailorkeys/KeyboardView.kt:613`, `app/src/main/java/app/tailorkeys/settings/SettingsScreen.kt:151`.

**What goes wrong:** Import a code encoding `{"rows":4,"items":[{"r":0,"c":0,"w":1,"k":["a","b"],"i":-1}]}`. It passes `isSound()` and `fromCode()`, because neither validates the face index. `shownIdx` computes `-1 % 2`, then indexes `keys[-1]`, throwing `IndexOutOfBoundsException`. The harness verified the full JSON/code round trip and the exception in the actual `shownIdx` method. Import saves the grid through `replaceGrid`/`onEdited` before drawing, so reopening settings or the keyboard reloads the crashing layout; `loadGrid` also lacks invariant validation.

**Suggested fix:** Normalize or validate face indices against the key list, and apply shared layout validation to both imported and saved grids. Reject or recover malformed state before persisting or drawing it.

### 5. Overlapping taps reverse letters when the earlier key is a swipe stack — Medium — Confirmed

**File and line:** `app/src/main/java/app/tailorkeys/KeyboardView.kt:1505`, `app/src/main/java/app/tailorkeys/KeyboardView.kt:1551`, `app/src/main/java/app/tailorkeys/KeyboardView.kt:1724`.

**What goes wrong:** With Type on press off, combine `a` and `b` into a swipe key showing `a`. Press and hold that key, press and release a separate `c`, then release the first finger without swiping. Ordinary key rollover flushes only `K_KEY` trackers, leaving the earlier `K_STACK` pending. The result is `ca` instead of press-order `ac`. The harness invoked the actual pointer handlers with this sequence and reproduced `ca`.

**Suggested fix:** Include untouched, unconsumed character stacks in rollover ordering. Commit their currently shown face and mark the tracker consumed so its later release cannot insert it again.

### 6. Selection mode assumes valid, forward selection bounds — Medium — Confirmed

**File and line:** `app/src/main/java/app/tailorkeys/TailorKeysService.kt:437`, `app/src/main/java/app/tailorkeys/TailorKeysService.kt:439`, `app/src/main/java/app/tailorkeys/TailorKeysService.kt:451`.

**What goes wrong:** In a raw/unknown-cursor editor with selection `(-1,-1)` and no text available after the cursor, double-press a cursor zone to select and push it. `selectTotal` becomes `-1`; `coerceIn(0,-1)` throws `IllegalArgumentException`. The non-selection cursor path has a raw/unknown-cursor fallback, but selection mode bypasses it. The harness reproduced the exception. A related bounds error occurs with backward selections: for ten characters and selection `(8,3)`, the computed total is `3 + 2 = 5`, so rightward extension stops at offset 5 instead of 10. Android defines text-after-selection relative to its higher endpoint. [Selection semantics](https://developer.android.com/reference/android/view/inputmethod/InputConnection).

**Suggested fix:** Refuse absolute selection mode or use an appropriate fallback when cursor positions are unknown. For valid positions, calculate the total from `max(selStart, selEnd)` and keep anchor/focus bounds valid.

### 7. Cursor movement can use a position older than the text just committed — Medium — Plausible

**File and line:** `app/src/main/java/app/tailorkeys/TailorKeysService.kt:346`, `app/src/main/java/app/tailorkeys/TailorKeysService.kt:463`, `app/src/main/java/app/tailorkeys/TailorKeysService.kt:633`.

**What goes wrong:** Commit `abc` into an empty field while its selection callbacks are delayed, then move left once. Commits do not advance `selStart`/`selEnd`; cursor movement uses the old `(0,0)` and jumps to offset 0 instead of 2. The harness reproduced this by withholding selection callbacks. A late callback can also overwrite the optimistic cursor position written at line 469. Whether normal typing and joystick events encounter this ordering needs testing with real editors; the JVM sequence alone does not establish device frequency.

**Suggested fix:** Reconcile cursor moves with a live editor selection snapshot or maintain expected selection acknowledgements across commits and cursor moves. Avoid allowing an older acknowledgement to replace a newer expected position.

### 8. Accessibility clicks type into the app while rearranging the keyboard — Medium — Confirmed

**File and line:** `app/src/main/java/app/tailorkeys/KeyboardView.kt:635`, `app/src/main/java/app/tailorkeys/KeyboardView.kt:788`, `app/src/main/java/app/tailorkeys/KeyboardView.kt:861`.

**What goes wrong:** Enter Rearrange from the IME's quick options, then activate a letter node with an accessibility click. Edit mode still exposes all placed keys as clickable text-entry nodes, and `activateAccessibleView` routes the click to `emitKey` without checking edit mode. The harness set edit mode and invoked the actual activation handler; `c` was committed to the connected field. Ordinary edit-mode touches instead rearrange keys. The provider also offers no equivalent action for moving or combining keys.

**Suggested fix:** Give edit-mode keys editing actions and descriptions, and prevent their accessibility actions from reaching text insertion. Supply accessible move/combine/separate operations so rearrangement is usable without dragging.

### 9. Handle gestures break linked portrait and landscape settings — Low — Confirmed

**File and line:** `app/src/main/java/app/tailorkeys/KeyboardView.kt:1927`, `app/src/main/java/app/tailorkeys/KeyboardView.kt:1928`, `app/src/main/java/app/tailorkeys/KeyboardSettings.kt:148`.

**What goes wrong:** Enable Same size when rotated, then drag the bottom handle left from a full-width keyboard. `slideKeyboard` calls the single-orientation `set` and `setAlign` methods instead of the shared-orientation helpers. The harness invoked the actual gesture action and verified portrait width became 78% while landscape stayed 100%, with linking still enabled. Restoring width and centering use the same bypass.

**Suggested fix:** Route every handle-driven width and alignment change through `setFor` and `setAlignFor`, including restoration.

## Improvements

### 1. Bound dictionary work on the typing thread — Confirmed

**File and line:** `app/src/main/java/app/tailorkeys/Predictor.kt:294`, `app/src/main/java/app/tailorkeys/Predictor.kt:330`, `app/src/main/java/app/tailorkeys/TailorKeysService.kt:750`, `app/src/main/java/app/tailorkeys/TailorKeysService.kt:777`.

**What goes wrong:** Every changed nonempty prefix scans the full base dictionary, computes correction costs for eligible words, allocates candidate objects, and sorts matches on the service thread. Caching shares this work between suggestion consumers but does not avoid it for the next character. `isDeadEnd` adds another scan and runs even before the password/suggestions-off early return. Device latency was not benchmarked.

**Suggested fix:** Return early for disabled suggestions, index prefix and correction candidates, and move any remaining broad ranking work to a coalesced worker using immutable inputs and stale-result checks.

### 2. Reduce repeated editor IPC and oversized cursor reads — Confirmed

**File and line:** `app/src/main/java/app/tailorkeys/TailorKeysService.kt:104`, `app/src/main/java/app/tailorkeys/TailorKeysService.kt:110`, `app/src/main/java/app/tailorkeys/TailorKeysService.kt:142`, `app/src/main/java/app/tailorkeys/TailorKeysService.kt:465`, `app/src/main/java/app/tailorkeys/TailorKeysService.kt:738`.

**What goes wrong:** A letter passes through validation, prefix lookup, post-commit tap-context reads, another validation, choice validation, and suggestion-context reads. These independently request overlapping editor text. Each joystick step also requests up to 10,000 characters after the cursor, even when moving one position.

**Suggested fix:** Share a surrounding-text/selection snapshot within each read-only phase, taking a fresh snapshot after mutations. Bound cursor reads to the movement being attempted, and use `getSurroundingText` where supported with a compatible fallback.

### 3. Move initial index construction and personal-file saves off main — Confirmed

**File and line:** `app/src/main/java/app/tailorkeys/TailorKeysService.kt:212`, `app/src/main/java/app/tailorkeys/TailorKeysService.kt:329`, `app/src/main/java/app/tailorkeys/Predictor.kt:60`, `app/src/main/java/app/tailorkeys/Predictor.kt:382`.

**What goes wrong:** Although n-grams and English inference load on workers, the first service model synchronously reads dictionary/personal files and builds gesture/chord indexes. Finishing the input view synchronously formats and rewrites all three learned-data files. These paths grow with the dictionary or accumulated learning and can delay keyboard opening or hiding.

**Suggested fix:** Use the existing loading mechanism for the initial language too. Snapshot learned maps on their owning thread, then serialize and atomically save those snapshots on a serial I/O worker.

### 4. Load chord lists without constructing a full predictor — Confirmed

**File and line:** `app/src/main/java/app/tailorkeys/settings/SettingsState.kt:33`, `app/src/main/java/app/tailorkeys/Predictor.kt:66`, `app/src/main/java/app/tailorkeys/Predictor.kt:107`.

**What goes wrong:** Opening a language's chord list constructs a Predictor solely to obtain its first 20,000 dictionary words. This also reads personal prediction files and starts a background n-gram load whose results the dialog never uses.

**Suggested fix:** Share a dictionary reader/cache between chord construction and Predictor, or reuse an already loaded chord library.
