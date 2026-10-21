# 07 · Voice, Android (`FinalInsertVoiceAndroid`)

## Today
Implemented: the permission request goes through the app, on-device versus online is detected, and there are partial results, stop, cancel and level bars (`insert/VoiceInput.kt`, `VoiceLevelView.kt`).

## Build (look)
- **One centred column:**
  1. A status capsule (`fill`, 26 dp): a device icon with "On device", or a **cloud** icon with "Online". Today it shows the text "Online recognition" with no icon.
  2. Live text, 22 sp, centred, at most 560 dp wide. The settled words use `text`; the last unsettled words use `sub`.
  3. The level: 24 rounded 4 dp bars in the accent colour.
  4. Controls: **Cancel** (text button, 88×44) and **Stop** (64 dp accent circle with an `onAccent` square).
- **Rings:** two soft accent rings behind Stop pulse with the RMS level. When animations are off (`ANIMATOR_DURATION_SCALE == 0`), draw one steady ring.
- **States:**
  - **Microphone not allowed:** "Allow the microphone" / "Keyboards cannot ask for it here. TailorKeys opens and asks once." [Allow in TailorKeys]. This already opens the app's request (`SettingsActivity.kt:36`).
  - **No recognizer:** "No speech recognizer" / "This device has no recognizer TailorKeys can use. Switch to a voice keyboard instead." [Use voice keyboard].

## Fix (gaps)
- **Composing text.** While listening, show the partial text in the app's field as composing text (`setComposingText`). On Stop, commit it. On Cancel, replace it with empty text and finish composing. Today only the final text is committed.
- **Voice keyboard fallback.** Find an enabled IME with a subtype whose mode is `"voice"`, using `InputMethodManager.enabledInputMethodList` and `getEnabledInputMethodSubtypeList`. Switch to it with `InputMethodService.switchInputMethod(id, subtype)` (API 28+). Open the picker only if none exists. Today it always opens the generic picker (`TailorKeysService.kt:1093-1095`).
- **No hot mic on reopen.** Today listening starts whenever the Voice section renders (`InsertPanel.kt:457`). Auto-start only when the user picks Voice in this session. When the panel reopens on Voice as its last section, show it idle, with **Start** in Stop's place.

## Acceptance
- While dictating, the field shows the words underlined. Stop leaves plain text; Cancel leaves nothing.
- On a device with Google voice typing enabled, "Use voice keyboard" switches straight to it.
- Reopening the panel on Voice does not start the microphone.
