# 08 · Dictate, iPhone and iPad (`FinalInsertVoiceIos`)

## Today
Implemented: the Dictate explanation and the arrow (`InsertPanel.swift:423-430`).

## Build (look)
- Use the README chrome. In the segmented control the mic segment's accessibility label is "Dictate".
- **Centred content:** the mic icon in a 44 pt `fill` circle, "Use the mic below" (17 pt semibold), then "iOS keeps the microphone for its own dictation. Tap it, speak, then come back with ABC." (14 pt, `sub`).
- **Arrow** in `sub`, pointing from the content toward the system mic at the bottom right. On first open it **pulses twice** (scale 1 → 1.15 → 1, 0.6 s each), then rests. There is no pulse when `UIAccessibility.isReduceMotionEnabled`. Store the "pulsed" flag per install.

## Acceptance
- The pulse plays once per install and never under Reduce Motion.
- VoiceOver reads the title and body as one element.
