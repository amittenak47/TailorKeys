# 04 · Kaomoji (`FinalInsertKaomoji`)

## Build (look)
- **Chips:** the `key` background, 12 dp radius, 44 dp high, at least 64 dp wide, 14 dp horizontal padding, 17 sp text (16 sp on phone), 8 dp gap, wrapping into rows.
- **Bottom-bar middle:** the category names as text (14 sp). The selected one gets a 34 dp `fillHi` capsule and semibold text. It scrolls on phone.
- Use all **10** categories from `kaomoji.tsv`, not the 8 listed on the old board.

## Add (gap)
- **Hold a chip** to outline it (2 dp accent), dim the panel, and show a menu below the chip with **Pin to Recent** and **Copy**.
  - Pinned faces lead Recent until they are unpinned. In that state the item reads **Unpin from Recent**.
  - Store this in `InsertRepository` as a per-section pinned list, capped at 16.
  - Mirror it on iOS.
- **Accessibility:** the same two actions as custom accessibility actions on each chip (`AccessibilityNodeInfo.AccessibilityAction` on Android, `accessibilityCustomActions` on iOS).

## Acceptance
- Hold, then Pin to Recent: the face is first in Recent and survives a restart.
- TalkBack and VoiceOver can pin a face without a long-press gesture.

## Tests
- Pinned ordering and the cap, in a JVM test and in the iOS checks.
