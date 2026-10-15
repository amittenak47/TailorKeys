# TailorKeys for iOS

A Swift port of the Android keyboard: the same grid, swipe keys, suggestion wheels, split layout with tilt and
ergonomic curve, themes, one-hand slide bar and drag-and-drop editor with undo, templates and share codes.
The gear between the suggestions starts rearranging; the gear in that toolbar opens the settings. Keeping settings
inside the keyboard means it needs no App Group and works with a free Apple ID. The app itself is a paged setup guide.

Layout codes (`TK1-…`) are the same on both platforms. Without Full Access a keyboard can't use the clipboard, so
on iOS the settings panel types your code into the text field, and imports a code you've pasted into the text field.

## Build (no Mac needed)

iOS apps only build on macOS, so the build runs on a GitHub-hosted Mac:

1. Push this repository to GitHub (a private repo is fine).
2. On GitHub, open **Actions**, pick **iOS build**, and **Run workflow** (it also runs on pushes that touch `ios/`).
3. When it finishes, download the **TailorKeys-unsigned-ipa** artifact and unzip it to get `TailorKeys-unsigned.ipa`.

The workflow lives in `.github/workflows/ios.yml`. It generates the Xcode project from `project.yml` with XcodeGen
and builds without signing.

## Sideload from Windows

1. Install [Sideloadly](https://sideloadly.io) and iTunes (from Apple's site, not the Microsoft Store).
2. Connect the iPhone or iPad by USB and trust the computer.
3. Drag `TailorKeys-unsigned.ipa` into Sideloadly, enter your Apple ID, and press Start.
4. On the device: Settings, General, VPN & Device Management, trust your Apple ID's developer profile.
5. Settings, General, Keyboard, Keyboards, Add New Keyboard, TailorKeys.

With a free Apple ID the app expires after 7 days and must be re-signed (Sideloadly can refresh it automatically).
A paid Apple Developer account ($99/year) makes it last a year.

## Differences from Android

- Key presses play the system keyboard click instead of vibrating. iOS only lets keyboards vibrate with Full Access,
  which TailorKeys doesn't request.
- Hold the spacebar to switch to the next keyboard (Android shows a picker).
- iOS never shows third-party keyboards in password fields.
