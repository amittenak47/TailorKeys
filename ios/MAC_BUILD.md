# Building TailorKeys for iOS on a Mac

The iOS code has been reviewed but never compiled, so expect a few compile errors on the first build (see step 6).

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
These compile the plain-logic Swift files for the Mac and check layout codes, reset, streaming and the model file.

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
- [ ] Optional: Product › Profile › Allocations on the keyboard; memory stays under about 60 MB.

## 10. Getting fixes later

```bash
git stash        # only if you made the free-account edits
git pull
git stash pop
cd ios && xcodegen
```
