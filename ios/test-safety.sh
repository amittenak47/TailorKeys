#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
# InsertData includes emoji sizing, kaomoji pins, editor limits and the Dictate hint gate.
swiftc -target "$(uname -m)-apple-macosx12.0" -swift-version 5 -O -o "$scratch/checks" \
  ios/Keyboard/KeyGrid.swift ios/Keyboard/KeyText.swift \
  ios/Keyboard/InsertData.swift ios/Keyboard/KeyboardSettings.swift ios/Keyboard/LearningStore.swift ios/Keyboard/TypingCore.swift ios/Keyboard/CompactLetters.swift \
  ios/Keyboard/CustomThemes.swift ios/Keyboard/LayoutLibrary.swift ios/Keyboard/TypingMetrics.swift ios/Keyboard/QuickOptionsLayout.swift \
  ios/Keyboard/SwipeTrace.swift ios/Keyboard/TextInputRules.swift ios/Keyboard/GestureDecoder.swift \
  ios/Keyboard/ResourceLines.swift ios/Keyboard/NextWordModel.swift ios/Tests/main.swift
"$scratch/checks"
