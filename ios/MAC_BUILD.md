# Building TailorKeys for iOS on a Mac

The iOS code has been reviewed but never compiled, so expect a few compile errors on the first build (see step 6).

**Updated 22 Oct 2026.** About 7,000 lines of Swift have landed since the first version of this guide (19 Oct), all mirrored from the Android app, which has been tested on a tablet. None of the new Swift has been compiled yet either. New since 19 Oct:
- **Insert panel:** emoji (search, skin tones, recents), kaomoji with pins, clipboard with pinned clips and notes, and a Dictate page that points to the iOS mic.
- **Themes:** custom keyboard themes with a theme editor, next to the built-in ones.
- **Typing test:** free typing and test sentences, live speed and accuracy, and Compare across keyboards.
- **Layout library:** save, rename, share and delete layouts, with thumbnails.
- **Layout editor:** while you drag, a live preview slides the other keys into place. Removed keys wait as mini keys in a tray below the layout. A full row shows "Row is full" and the key snaps back. The trash deletes tray keys for good.
- **Typing:** corrections that remember rejections, bounded learning, "Guess the word" and "Type the letters" swipe styles, and automatic space and punctuation.
- **Tests:** `test-safety.sh` now compiles and checks the editor tray, themes, the layout library, typing metrics, swipe traces and text rules too.

## 1. What you need
- Xcode from the Mac App Store. Open it once and let it install its components.
- Access to the private GitHub repo `amittenak47/TailorKeys`.
- An iPhone or iPad and its cable, for device testing. The Simulator is fine for the first build.
- An Apple ID. A free account works for testing, with the limits in step 7.

## 2. Get the code and tools
Install Homebrew from brew.sh if needed, then:

```bash
brew install xcodegen gh
gh auth login            # GitHub.com, HTTPS, log in with your browser
gh repo clone amittenak47/TailorKeys
cd TailorKeys
```

## 3. Run the quick safety checks
These compile the plain-logic Swift files for the Mac and check layout codes, the editor tray, reset, streaming, the model file, themes, the layout library, typing metrics, swipe traces and text rules. It prints a few "… checks passed" lines and no errors. If it fails, send the output the same way as a build log (step 6).

```bash
bash ios/test-safety.sh
```

If `swiftc` or `xcrun` is missing, run `xcode-select --install`, or open Xcode once and accept the license.

## 4. Generate the Xcode project

```bash
cd ios
xcodegen
open TailorKeys.xcodeproj
```

The targets are **TailorKeys** (the settings app) and **TailorKeysKeyboard** (the keyboard extension). Run `xcodegen` again after every pull.

## 5. First build in the Simulator (no signing needed)
1. Choose the **TailorKeys** scheme and any iPhone simulator, then press ⌘R.
2. In the Simulator: Settings › General › Keyboard › Keyboards › Add New Keyboard › TailorKeys.
3. Open Notes, tap a text field and switch keyboards with the globe key.

From Terminal (in `ios/`), saving a log:

```bash
xcodebuild -project TailorKeys.xcodeproj -scheme TailorKeys \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO build 2>&1 | tee build.log
grep "error:" build.log | sort -u
```

## 6. If it doesn't compile
Send the errors back, and fixes will be pushed to `main`:
- **Push the log:** `git checkout -b mac-build-log && cp build.log build-log.txt && git add build-log.txt && git commit -m "Mac build log" && git push -u origin mac-build-log && git checkout main`, then tell the Windows Claude Code session.
- **Paste the errors:** paste the `grep` output, or Xcode's Issue navigator (⌘5, right-click › Copy), into the Windows session or a claude.ai chat.
- **Install Claude Code on the Mac** (claude.com/claude-code) and fix them there.

## 7. Run it on your iPhone
1. Xcode › Settings › Accounts: add your Apple ID.
2. For **both** targets, Signing & Capabilities › Team: your account (automatic signing on).
3. Plug in the device, unlock it and tap Trust. On iOS 16 or later, turn on Settings › Privacy & Security › Developer Mode.
4. Choose the device and press ⌘R. If it won't open, trust your Apple ID in Settings › General › VPN & Device Management.

**Free Apple ID (Personal Team):**
- **App Groups** aren't available. Delete both `com.apple.security.application-groups: [group.app.tailorkeys]` lines in `project.yml` and run `xcodegen` again. The app and keyboard then don't share settings, so use the keyboard's gear panel.
- **Bundle ID unavailable:** if Xcode says the bundle ID is taken, change both `PRODUCT_BUNDLE_IDENTIFIER` values, for example to `com.yourname.tailorkeys` and `com.yourname.tailorkeys.keyboard`.
- **Expiry:** installs expire after 7 days; press ⌘R to reinstall.
- **Local edits:** keep these edits uncommitted (`git stash` before pulling, `git stash pop` after).

**Paid account:** keep App Groups. Automatic signing registers `group.app.tailorkeys`. If Xcode asks, tick it on both targets.

## 8. Turn the keyboard on
- **Add it:** Settings › General › Keyboard › Keyboards › Add New Keyboard › TailorKeys.
- **Full Access** is optional. It only shares settings with the app; there's no network code.
- **Debug the keyboard:** run the **TailorKeysKeyboard** scheme and pick a host app such as Notes. Crash logs are in Window › Devices and Simulators.

## 9. What to test
- [ ] English suggestions fit the sentence, not just the last word.
- [ ] Tapping "awvwn" near the right keys suggests "seven".
- [ ] Shift mid-sentence stays on for the next letter; double-tap gives caps lock.
- [ ] Tap a wheel word; hold the left or right spacebar to insert that side's word.
- [ ] Cursor: hold and push in the split gap and in the outer edges (Typing › Cursor control).
- [ ] One-hand slide; Layout › Empty side › Cursor shows the switch button.
- [ ] Quick options tiles and Back.
- [ ] Switch through all four languages without the keyboard disappearing.
- [ ] Rearrange, hide, reopen: it's back to typing.
- [ ] Import and share a TK1- layout code; reset all settings.
- [ ] The guide's last page has a practice box.
- [ ] Rotate: keys keep their size.

New since 19 Oct:
- [ ] Insert key: emoji search and skin tones, kaomoji pins, clipboard pins and notes. Dictate shows the arrow to the iOS mic.
- [ ] Look: pick each built-in theme, then Create a custom theme and edit its colours. The keyboard follows.
- [ ] Typing test: finish a test sentence ("✓ Test complete" is readable in light and dark mode), then Compare.
- [ ] Layout library: save, rename, share and delete a layout. Thumbnails match the layouts.
- [ ] Layout editor, drag a key:
  - Between two keys, the neighbours slide apart and a dashed slot opens.
  - Onto a full row, "Row is full" shows and the key snaps back.
  - Into the tray, it waits there as a mini key and its row closes up.
  - From the tray, it can go back into the layout. Dropped on the trash, it's gone for good.
- [ ] Combine two keys into a swipe key, then separate them: the extra face goes to the tray.
- [ ] Curve and tilt on (Size): spacebars sit square under the keys above them.
- [ ] Swipe typing: "Guess the word" adds a space after the word. "Type the letters" types just the letters, with no space. With Type on press on, Swipe typing and Chords show as off.
- [ ] Automatic space: picking a suggestion adds a space, unless Swipe style is "Type the letters".
- [ ] Optional: Product › Profile › Allocations on the keyboard; memory stays under about 60 MB.

## 10. Getting fixes later

```bash
git stash        # only if you made the free-account edits
git pull
git stash pop
cd ios && xcodegen
```
