# Compile errors

**0 Certain and 0 Likely compile errors identified in the declared Swift 5 / iOS 15 configuration.** All 21 Swift files (6,732 lines) were read in full, with declarations and call sites checked against each target's source list. This is a source review of `ededc15`, not a successful build: Xcode, the Apple SDK, Swift and XcodeGen were unavailable here. Compiler diagnostics, generated-project validation, signing and device testing remain for the Mac.

No unresolved application symbols, duplicate type definitions within a target, cross-file `private` violations, wrong argument labels, required missing imports, non-exhaustive switches, Kotlin syntax or definite deployment-target violations were found. `CursorJoystick` is defined at the end of `KeyboardView.swift`; `ComposingTaps` and the shared chord types are also present in the app's sources. `UIApplication.shared` occurs only in app-only files, so those calls do not enter the extension. Item 12 addresses a concurrency-isolation cleanup, rather than asserting a Swift 5 build failure.

Findings below are numbered in section order and ranked within each section. **Certain** means the stated input/state follows a definite failing code path; **Likely** means the outcome depends on device limits, host callbacks or workload. Counts: **3 crash risks** (1 Certain, 2 Likely), **8 parity/behavior issues** (7 Certain, 1 Likely), and **3 improvements**.

# Crashes

### 1. Switching languages retains enough data to exceed the extension budget — High · Likely

- **File and line:** `ios/Keyboard/KeyboardViewController.swift:14`, `:53`; `ios/Keyboard/Predictor.swift:11`, `:67`, `:157`; `ios/Keyboard/GestureDecoder.swift:8`, `:24`, `:55`.
- **What goes wrong:** Every visited language remains in `models`, including its 60,000-word predictor, separately constructed gesture-letter arrays, word/rank sets, chord library, n-grams and caches. Disabling a language does not evict it. Across all four bundled dictionaries, the predictor arrays contain 2,079,248 character elements; the gesture indexes contain approximately 2,028,198 more. Assuming the usual 16-byte arm64 `Character` representation, **element storage alone is about 65.7 MB / 62.7 MiB**, before array headers/capacity, 240,000 gesture objects, strings, hash tables, n-grams, the 3.33 MB model and UIKit. This is an allocation estimate, not measured resident memory. The packed n-gram payload alone adds 7.2 MB across four languages. Parsing whole n-gram files and splitting them into lines creates additional temporary allocations. The 2,048-template check runs only before a decode, so a single decode can also overshoot that cache threshold. A multilingual session is likely to be terminated by iOS around the stated 60–70 MB extension budget; a single-language peak also needs measurement.
- **Suggested fix:** Keep the active language and a small, measured cache; evict inactive/disabled predictors and decoders, and handle memory warnings. Store supported letters compactly instead of keeping two `[Character]` copies per word. Enforce the template cap while adding entries and stream n-gram input. On the Mac, measure cold launch, first English inference, repeated glides and switching through all four languages on a physical device, including temporary allocation peaks.

### 2. Imported layouts can pass validation and crash on display — High · Certain

- **File and line:** `ios/Keyboard/KeyGrid.swift:76`, `:203`, `:229`, `:306`; `ios/Keyboard/KeyboardView.swift:665`; `ios/Keyboard/KeyboardSettings.swift:217`.
- **What goes wrong:** `isSound` validates geometry and nonempty key arrays, but never validates `idx`. A raw-deflated `TK1-` code containing `{"rows":4,"items":[{"r":0,"c":0,"w":1,"k":["a","b"],"i":-1}]}` passes `fromCode`. The preview/keyboard then computes `j = -1 % 2` and indexes `keys[-1]` while rebuilding accessibility elements or drawing. Saving the imported layout makes subsequent loads fail again. Separately, a decoded item with `c = Int.max` and `w = 1` traps at `c + w` **inside validation**, before it can be rejected. The normal editor does not generate these values. Stored JSON also bypasses `isSound` entirely, allowing empty key lists or invalid rows to reach indexing/layout code.
- **Suggested fix:** Validate decoded DTOs before constructing/using a grid, for both imports and stored settings. Bound rows, columns and widths before any addition, then check region/overlap rules. Reject negative face indexes or normalize with a positive modulo after ensuring keys are nonempty. Fall back to the default layout for invalid persisted data; bound item/key counts as well.

### 3. Layout decompression has no output-size limit — High · Likely

- **File and line:** `ios/Keyboard/KeyGrid.swift:224`, `:228`; `ios/App/AppSettingsModel.swift:158`; `ios/App/AppSheets.swift:74`, `:80`.
- **What goes wrong:** A pasted `TK1-` payload is fully decompressed before JSON validation, without a compressed or decompressed size limit. A small highly compressible payload can allocate a very large `Data`, followed by a `String` and decoder allocations, even if it is ultimately invalid JSON. The app retries decoding during field rendering; clipboard preparation also decodes immediately. The extension uses the same unrestricted decoder. This can cause a freeze or memory termination. Android's `layout/KeyGrid.kt:237` stops inflation at approximately 64 KiB.
- **Suggested fix:** Use bounded streaming decompression and reject output over 64 KiB, plus excessive encoded input. Reject an unfinished/truncated stream. Cache the decoded result for the current import-field value instead of decoding it twice per render. A size check after `decompressed(using:)` is too late to prevent the allocation.

# Parity differences

### 4. Auto-capitalization immediately cancels manual Shift — High · Certain

- **File and line:** `ios/Keyboard/KeyboardViewController.swift:333`, `:344`, `:631`, `:743`.
- **What goes wrong:** With the default `autoCap = true`, `handleKey` calls `updateAutoCap()` immediately after `tapLayerKey` changes `state.shift`. In the middle of a sentence, tapping ABC sets Shift on, then `updateAutoCap` resets it to false before the next letter. Double-tapping there cannot arm caps lock either because the second tap never sees Shift on. At a sentence start, tapping to turn off the automatic capital is similarly overwritten. Android's `TailorKeysService.kt:384` / `:639` preserves the manual layer-key result instead of recalculating capitalization at the end of every key.
- **Suggested fix:** Preserve explicit Shift/caps-lock changes. Skip automatic-capitalization recomputation for explicit ABC Shift toggles, or track manual and automatic Shift separately and consume the manual override after the next insertion. Continue updating automatic capitalization when returning from another layer. Verify mid-sentence Shift, double-tap caps lock and suppressing a sentence-start capital.

### 5. Reset leaves five user preferences in effect — Medium · Certain

- **File and line:** `ios/Keyboard/KeyboardSettings.swift:229`.
- **What goes wrong:** `resetAll()` omits `follow_system_text`, `type_on_press`, `chording`, `key_repeat_guard` and `touch_slack`. For example, resetting with Type on press enabled leaves it enabled, which continues to disable swipe typing despite resetting `swipe_typing` to its default. Both the app's Reset and the keyboard panel call this function. Android's `KeyboardSettings.kt:215` clears all settings.
- **Suggested fix:** Add the five omitted keys while retaining the deliberately preserved learning/setup metadata. Prefer an explicit registry of all setting keys/defaults so both reset and controls use the same definitions.

### 6. Several settings require Full Access despite the keyboard fallback — Medium · Certain

- **File and line:** `ios/Keyboard/SettingsPanel.swift:263`; `ios/App/SettingsTabs.swift:290`, `:329`; `ios/App/SettingsTabs.swift:74`.
- **What goes wrong:** The app tells users without Full Access to change settings from the keyboard gear, but that panel has no Languages control, Highlight likely keys toggle, Smart tap areas toggle or chord list. The app's corresponding controls are disabled without Full Access. Users therefore cannot disable the default smart targets/key tint or enable another language through the advertised local-settings fallback. Android exposes these controls in `settings/SettingsPages.kt:267`, `:320`, `:339`. This is separate from the intentional lack of iOS text selection or vibration.
- **Suggested fix:** Expose language selection and the missing assist controls in the keyboard-local panel, reading/writing the existing `KeyboardSettings` store. Wire language changes through `activateLanguage`, rather than only changing `state.language`. Make the chord reference available there too, or clearly identify its app-only availability.

### 7. External cursor movement can leave suggestions and capitalization stale — Medium · Likely

- **File and line:** `ios/Keyboard/KeyboardViewController.swift:264`.
- **What goes wrong:** `selectionDidChange` validates taps, chosen words and glide state, then returns without refreshing suggestions or auto-capitalization. If the host reports a cursor-only move through this callback, the wheels/key weights and English context still describe the previous position; the next letter is inserted using the previous Shift state. Moving with the keyboard's own joystick does refresh them (`moveCursor`, line 349), so the gap is specifically host/native cursor movement. Android refreshes both in `TailorKeysService.kt:333`–`:350`. Apple documents [separate text and selection change callbacks](https://developer.apple.com/documentation/uikit/handling-text-interactions-in-custom-keyboards); a text-change callback should not be required to repair a cursor-only change.
- **Suggested fix:** After validation, refresh auto-capitalization and suggestions when the keyboard exists and the change is external. Retain the `changingGlide` guard for edits in progress. Verify native cursor dragging, taps in existing text and switching between fields with the keyboard visible.

### 8. The keyboard reopens in rearrangement mode — Medium · Certain

- **File and line:** `ios/Keyboard/KeyboardViewController.swift:217`, `:256`; `ios/Keyboard/KeyboardView.swift:1404`.
- **What goes wrong:** Hiding/switching away while rearranging cancels touches but never clears `keyboard.editMode`. On reuse of that controller, `viewWillAppear` reloads the grid/settings but preserves edit mode, so the user returns to the editor rather than a typing keyboard. Android explicitly sets `editMode = false` in `TailorKeysService.kt:328`.
- **Suggested fix:** Clear edit mode when the input view disappears, after finishing/canceling its current interaction. Keep layout edits already saved; this only ends the editing UI session.

### 9. The guide teaches the wrong spacebar action and has no practice field — Medium · Certain

- **File and line:** `ios/App/GuideView.swift:26`, `:29`, `:36`, `:110`.
- **What goes wrong:** The guide says holding Space switches keyboards, but `KeyboardView.swift:1805` picks the suggestion on that side, matching current Android behavior. It also says to tap the gear and drag immediately, although the gear now opens Quick options first. The final page asks the user to type below, but `SlideView` only renders text, an illustration and an optional Settings button; `AppText.tryField` is unused. Android provides an actual practice `EditText` in `OnboardingActivity.kt:60`.
- **Suggested fix:** Explain the globe/system switch control, hold-space suggestion picking and Gear → Rearrange. Add a practice text field to the final page and keep its keyboard usable when the page scrolls/resizes.

### 10. Double-space timing leaks between documents — Low · Certain

- **File and line:** `ios/Keyboard/KeyboardViewController.swift:217`, `:650`, `:709`.
- **What goes wrong:** `lastSpace` is not reset when the keyboard appears or `documentIdentifier` changes. Type a space in field A, switch within 0.6 seconds to field B ending in a letter plus a space, and press Space once: B's existing space becomes a period plus space. The first space in B is being treated as a second tap from A. Android resets the timestamp at input-view start (`TailorKeysService.kt:314`).
- **Suggested fix:** Reset `lastSpace` on input-session/document changes, alongside taps/choice/model state. Also clear any double-tap timing that should belong to the previous session.

### 11. Symbol suggestions omit Android's space option after an opener — Low · Certain

- **File and line:** `ios/Keyboard/Predictor.swift:373`.
- **What goes wrong:** After `(`, `[`, `{`, `<`, a quote or an apostrophe, Android returns `[closer, " ", ".", ","]`; iOS returns `[closer, ".", ","]`. Thus the corresponding wheel/hold-space picks expose a different set and order on the number/symbol layers. See `Predictor.kt:357`.
- **Suggested fix:** Restore the space entry in the closer branch, keeping the existing symbol-insertion path.

# Improvements

### 12. Separate model construction from the UIKit controller and load initially off-main — Medium · Likely

- **File and line:** `ios/Keyboard/KeyboardViewController.swift:20`, `:48`, `:192`.
- **What goes wrong:** Later languages are constructed on `loader`, but the initial 60,000-word predictor, decoder and chord library are constructed synchronously during `viewWillAppear`. This creates avoidable first-show latency. The worker also calls an instance method on a `UIInputViewController` subclass, whose SDK declaration is [main-actor isolated](https://developer.apple.com/documentation/uikit/uiinputviewcontroller). Although its current body only builds data and dispatches UI refreshes to main, that placement makes stricter-concurrency diagnostics and future off-main UI access easier to introduce. This review does **not** count it as a demonstrated compile error with the current Swift 5 settings; [preconcurrency checking depends on the checking mode](https://www.swift.org/migration/documentation/swift-6-concurrency-migration-guide/libraryevolution/).
- **Suggested fix:** Move the pure factory into a non-UIKit helper with an explicit completion callback. Use the same asynchronous loading path for initial and subsequent languages, publishing controller state and updating views only on main. Keep English recurrence and immutable n-gram publication on their existing workers.

### 13. Learning serializes the entire user history on every word — Medium · Likely

- **File and line:** `ios/Keyboard/Predictor.swift:389`, `:393`.
- **What goes wrong:** Each learned word immediately sends all user words, bigrams and trigrams through three `UserDefaults.set` calls on the typing thread. As history grows, bridging/property-list preparation and temporary copies can stall input and add memory pressure. Android marks learning dirty and saves at lifecycle boundaries (`Predictor.kt:379`, `:382`; `TailorKeysService.kt:329`). This is a workload-dependent performance risk, not an assertion that `UserDefaults` writes synchronously to disk.
- **Suggested fix:** Mark learning dirty, debounce persistence, and flush at lifecycle boundaries. If serialization moves to a worker, give it immutable snapshots or queue-owned state. Bound learned dictionaries/context histories to a measured memory budget.

### 14. Make the shared target boundary explicit — Low · Likely

- **File and line:** `ios/project.yml:20`; `ios/Keyboard/KeyboardView.swift:3`, `:10`; `ios/Keyboard/KeyboardSettings.swift:245`.
- **What goes wrong:** The app currently compiles the entire 2,119-line drawing/touch view partly to obtain shared `GlidePoint`/`ComposingTaps`, while chord algorithms live inside the settings file. It works with today's source list, but the dependency boundary is easy to break when moving or extending these types; an app-only compile and extension-only compile exercise different declarations.
- **Suggested fix:** Give platform-independent tap/chord/value types dedicated shared files and a clearly listed shared source group. Keep the `Chords(predictor:)` convenience initializer extension-only. Consolidate repeated preference defaults/control metadata where practical. Build both targets separately after reorganizing; no reorganization is required just to resolve an existing undefined symbol.

# Coverage and checks

## Per-target symbol/source inventory

The app has **13 Swift sources**: all seven `App` files plus six shared `Keyboard` files. The extension has **14 Swift sources**: the entire `Keyboard` directory. Nested implementation types were checked in their enclosing files; these are the file-level declarations and their target membership:

| Swift file | File-level declarations | App | Extension |
| --- | --- | :---: | :---: |
| `Keyboard/KeyGrid.swift` | `Keys`, `Template`, `GridKey`, `KeyGrid` | Yes | Yes |
| `Keyboard/KeyText.swift` | `Layer`, `InputState`, `KeyText` | Yes | Yes |
| `Keyboard/Theme.swift` | `ThemeName`, `Palette` | Yes | Yes |
| `Keyboard/KeyboardSettings.swift` | `Align`, `CursorControl`, `EmptySide`, `IntPref`, `Prefs`, `SizeProfile`, `KeyboardSettings`, `Chord`, `Chords`, `ChordTouches` | Yes | Yes |
| `Keyboard/KeyboardView.swift` | `GlidePoint`, `ComposingTaps`, `KeyboardViewDelegate` and its extension, `KeyboardView`, private `CursorJoystick` | Yes | Yes |
| `Keyboard/CursorZones.swift` | `CursorZone`, `CursorZoneKind`, `cursorZoneKind`, `slideOffset` | Yes | Yes |
| `Keyboard/Chords.swift` | `Chords` extension using `Predictor` | No | Yes |
| `Keyboard/EnglishContext.swift` | `EnglishModel`, `EnglishTokens`, `EnglishContext` | No | Yes |
| `Keyboard/GestureDecoder.swift` | `GestureDecoder` | No | Yes |
| `Keyboard/KeyboardViewController.swift` | `ClickInputView`, `KeyboardViewController` | No | Yes |
| `Keyboard/NextWordModel.swift` | `NextWordModel` | No | Yes |
| `Keyboard/Predictor.swift` | `Predictor`, `SplitMix` | No | Yes |
| `Keyboard/QuickOptions.swift` | `QuickOptions` | No | Yes |
| `Keyboard/SettingsPanel.swift` | `SettingsPanel` | No | Yes |
| `App/AppSettingsModel.swift` | `AppSettingsModel` | Yes | No |
| `App/AppSheets.swift` | `ChordListSheet`, `FullAccessSheet`, `ImportLayoutSheet`, `LayoutShareSheet`, `AboutSheet`, `LanguagesSheet` | Yes | No |
| `App/GuideView.swift` | `AppText`, `GuideView`, `SlideView` | Yes | No |
| `App/KeyboardPreview.swift` | `KeyboardPreview`, private `KeyboardPreviewView` | Yes | No |
| `App/SettingsChrome.swift` | `AppStyle`, `SettingsTab`, `KeycapMark`, `KeycapSeam`, `LogoMark`, `AppHeader`, `SettingSlider`, `MenuRow` | Yes | No |
| `App/SettingsTabs.swift` | `SettingsScreen`, private `themeTitle` function and `ThemeSwatch` | Yes | No |
| `App/TailorKeysApp.swift` | `TailorKeysApp`, `RootView` | Yes | No |

The app preview's delegate methods are supplied either by its Coordinator or the shared protocol defaults. The app's chord list reads dictionary resources from the embedded extension; it does not need to compile `Predictor`, `EnglishContext` or `NextWordModel`. The `Chords` extension and original declaration do not create duplicate definitions.

## Project, resources and availability

- `project.yml:6` sets iOS 15.0 for both targets and `:9` selects Swift 5 language mode. The iOS 15 SwiftUI controls/modifiers used here fit that deployment target; the share sheet and preview avoid their iOS 16 replacements. `Accelerate` is imported where `vDSP_vflt8` / `vDSP_dotpr` are used; the signed-byte input matches [Apple's conversion API](https://developer.apple.com/documentation/accelerate/vdsp_vflt8).
- Bundle identifiers are `app.tailorkeys` and `app.tailorkeys.keyboard`. The app depends on the extension. Both entitlement property dictionaries contain exactly `group.app.tailorkeys`, matching `KeyboardSettings.groupID`. The empty development team is an owner setup step already described in the README, not a Swift compile error. App Group registration/provisioning must be verified with the chosen team on the Mac.
- The generated extension plist specifies `com.apple.keyboard-service`, `$(PRODUCT_MODULE_NAME).KeyboardViewController`, `IsASCIICapable = true`, `PrefersRightToLeft = false`, `PrimaryLanguage = en-US` and `RequestsOpenAccess = true`. App launch-screen and phone/iPad orientation keys are provided. The plist/entitlements files are generated by XcodeGen; their absence in this checkout is expected. Check the generated files/embedding during the first Mac build.
- Every resource path in the spec exists. The extension gets `words.txt`, `bigrams.txt`, `trigrams.txt` and their `_es`, `_de`, `_fr` variants; `lm_en.bin`; and `NOTICE-dictionary.txt`. The app directly gets the notice and obtains dictionaries from the embedded extension. Listing all languages as resources does not itself load all of them into memory; item 1 concerns visited-language retention.
- The model was independently decoded read-only: 3,331,705 bytes, TKLM v1, vocabulary 15,000, embedding width 96, hidden width 672, unique valid UTF-8 tokens, correct five special IDs, finite biases/positive finite scales and exact end-of-file. All four word lists contain 60,000 unique words. Accepted bigram/trigram rows match the shipped vocabularies: English 150,000 each; Spanish/German/French 100,000 each. These checks validate resource structure, not inference accuracy or runtime allocation sizes.
- Missing words fall back to an empty base dictionary, missing n-grams to empty tables, missing/invalid model to remembered failure plus legacy prediction, and an unavailable app chord dictionary to a message. None force-unwrap a missing bundle URL. The hard-coded regex is valid; model pointers follow positive-size validation. The programmatic views' `init(coder:)` fatal errors have no storyboard/nib call path in this project. The app's App Group initializer does contain a fatal error on a nil suite result (`AppSettingsModel.swift:47`), but no normal failing input for that valid suite identifier was established, so it is not counted as a certain/likely crash.
- No ordinary-touch NaN/infinity-to-`Int` path was established: tap distance rows are initialized to finite values and capped, widths are bounded before distance division, joystick reach has an 8-point floor, preference values are clamped, and size-lock ratios start positive. Unsupported arbitrary API inputs alone were not counted as findings.
- Layout sharing uses raw DEFLATE on Android; Apple's [Compression zlib format is also raw DEFLATE](https://developer.apple.com/documentation/compression/compression_zlib). No wrapper mismatch is asserted. Cross-device round trips still need execution, including the size/validation fixes above.

## Behavior and threading checks without additional findings

- Tap distances are transformed through each candidate key's inverse geometry and converted into the same bounded Gaussian costs as Android. Edit-distance penalties, first-letter filtering, transposition handling, model typo weight (32), OOV allocation, context tokenization and closest-candidate limits match the reference. Different seeded random generators mean Explore draws need not be bit-for-bit identical; that alone is not a defect.
- The chosen-word path validates document/context, commits on Space or the specified punctuation, and defers learning. Immediate Backspace restores the original prefix **with its separator**, discarding deferred learning, as Android does. Holding a spacebar chooses the centered word on that side. The guide, rather than this implementation, is out of date.
- Auto / Center gap / Outer edges / Off selection matches Android, including the 32-point minimum outer-room fallback, empty-side cursor regions, switch-button exclusion, one active joystick and reduced-motion behavior. Slide timing is the same 250 ms cubic ease-out; destination geometry is computed once and touches are suppressed during motion. The glow's spring, complementary hue, falloff and movement reach, and the tilt/space-tilt/curve reservation math follow Android. No selection feature is requested or flagged.
- Numeric preferences, ranges, normal boolean defaults, language list and tablet-landscape split defaults match Android. Both app and extension use the same Swift settings type and App Group identifier; without Full Access the extension uses local defaults, with one-time migration into an empty group. Items 5 and 6 cover the reset/control gaps. The app's Full Access indicator is a historical `keyboard_seen_full_access` marker, not proof of the current iOS permission after revocation.
- No definite `EnglishContext` race was found. Recurrence, token buffers and front/back ownership stay on the serial English worker. Requests/reset/publication and readers use the lock; changing a request clears publication before a reusable score buffer is mutated. Predictor cache access occurs within `withScores`; immutable n-gram tables publish under `tableLock`. Controller callbacks dispatch suggestion/UI work to main. UIKit touch timers/display links and app view updates use their main-thread paths. Item 12 recommends making the factory's isolation boundary clearer.

Only this report is intended for the review commit. No app, keyboard, Android, project or resource source was changed, and nothing was pushed.
