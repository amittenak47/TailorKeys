# TailorKeys for iOS

A Swift port of the Android keyboard with a four-tab iPhone settings app: Layout, Size, Look and Typing.
The app previews the real keyboard, including its drag-and-drop layout editor and suggestion wheels.

Settings are shared through the `group.app.tailorkeys` App Group when Full Access is allowed. Without Full Access,
use the gear between the suggestions to start rearranging, then the gear in that toolbar to open settings.
Existing keyboard settings migrate once when the keyboard first uses the empty shared store.
Full Access is optional. TailorKeys has no network code and sends nothing.

Layout codes (`TK1-...`) work on both platforms. The app shares and imports codes using the share sheet and clipboard.
The keyboard's fallback panel types a code into the text field, and imports a code pasted into that field.

## Build on a Mac

Full step-by-step instructions, including free-account signing and what to test, are in [MAC_BUILD.md](MAC_BUILD.md).

1. Open this repository on a Mac with Xcode installed.
2. Install XcodeGen: `brew install xcodegen`.
3. In `ios/`, run `xcodegen`.
4. Open `TailorKeys.xcodeproj`.
5. Set your Apple Developer team on both the TailorKeys app and TailorKeysKeyboard extension targets.
6. Enable the `group.app.tailorkeys` App Group in the developer portal if Xcode asks.
7. Select your iPhone or iPad and run the app.
8. In Settings > General > Keyboard > Keyboards > Add New Keyboard, add TailorKeys.
   Optionally allow Full Access, then open the keyboard once to enable settings in the app.

## Differences from Android

- Key presses play the system keyboard click sound. Vibration is not currently implemented on iOS.
- Use the globe key to switch keyboards. Holding a spacebar picks that side's suggestion, as on Android.
- No text selection with the cursor control: iOS keyboards can't select text.
- iOS never shows third-party keyboards in password fields.
