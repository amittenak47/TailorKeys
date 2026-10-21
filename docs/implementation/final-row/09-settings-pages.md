# 09 · Settings pages: Emoji, Clipboard, Voice (`FinalSettingsPanels`)

Files: Android `settings/InsertSettings.kt` and `settings/SettingsPages.kt:250`; iOS `ios/App/InsertSettings.swift` and `SettingsTabs.swift:246`.

## Deltas
- **Kaomoji tab toggle.**
  - Add `InsertPrefs.KAOMOJI` (`"insert_kaomoji"`, default true) on Android and iOS.
  - Add the row "Show Kaomoji tab", with the note "Faces such as (^_^) next to emoji", after the recents rows.
  - When it is off, the Insert panel's segmented control drops the Kaomoji segment.
- **Emoji wording.**
  - Toggle: "Show recent emoji", with the note "Recents come first in the panel".
  - Clear row: "Clear recent emoji", with the subtitle "Removes N recents".
- **Skin tone.**
  - Name the chosen tone beside the "Default skin tone" title.
  - Draw the selected tick on a badge with at least 3:1 contrast on every tone (white tick on a dark disc). Today it is 2.19:1 on Dark (Android kt:101) and 2.72:1 (iOS swift:23).
  - Show the 5-emoji preview row on iOS too.
- **Clipboard.**
  - Clear row: subtitle "Removes N items. Pinned items stay." (N = unpinned clips), with a trailing **Clear** button.
  - Add the heading "Private by design" above the privacy text.
  - Android keep options become real radios: `selectableGroup` with `selectable(role = Role.RadioButton)` rows and a `RadioButton` (kt:128).
  - "Keep copied images": leave it out, because image clips are not stored.
- **Voice (Android).**
  - The note "Adds a Voice tab to the Insert panel".
  - A read-only **Language** row showing the keyboard language that `VoiceInput` passes as `EXTRA_LANGUAGE`.
  - The summary reads e.g. "On-device, English (Australia)" (kt:57).
  - A closing line: "On iPhone and iPad, Apple does not let keyboards use the microphone."
- **Order.** Show the Panels group directly before Assist. Drop the "New" badge.

## Acceptance
- Turning off Kaomoji hides its segment immediately (the panel listens to the pref).
- TalkBack announces the keep options as radio buttons, "1 of 4".
