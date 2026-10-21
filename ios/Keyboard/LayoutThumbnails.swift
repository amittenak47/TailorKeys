import UIKit

/// Rasterise once per grid and rendering configuration. No miniature approximation of keyboard geometry.
final class LayoutThumbnailCache {
    static let shared = LayoutThumbnailCache()
    private var pending: Task<UIImage, Never>?
    private let images = NSCache<NSString, UIImage>()

    init() { images.countLimit = 80 }

    /// UIKit drawing stays on the main actor, after a placeholder frame. Serial requests
    /// leave a frame between tiles instead of rasterising the entire grid during body evaluation.
    @MainActor
    func deferredImage(for choice: LayoutChoice, settings: KeyboardSettings, dark: Bool) async -> UIImage {
        let render = configuration(for: choice, settings: settings, dark: dark, split: choice.split)
        if let cached = images.object(forKey: render.key) { return cached }
        let previous = pending
        let request = Task { @MainActor in
            _ = await previous?.value
            try? await Task.sleep(nanoseconds: 32_000_000)
            return self.image(for: choice, settings: settings, dark: dark, split: choice.split)
        }
        pending = request
        return await request.value
    }

    func image(for choice: LayoutChoice, settings: KeyboardSettings, dark: Bool, split: Bool? = nil) -> UIImage {
        let (prefs, key) = configuration(for: choice, settings: settings, dark: dark, split: split)
        if let cached = images.object(forKey: key) { return cached }
        let keyboard = KeyboardView(settings: prefs, grid: choice.grid)
        keyboard.overrideUserInterfaceStyle = dark ? .dark : .light
        keyboard.reloadSettings()
        let result = keyboard.layoutThumbnail(width: UIScreen.main.bounds.width)
        images.setObject(result, forKey: key)
        return result
    }

    private func configuration(for choice: LayoutChoice, settings: KeyboardSettings, dark: Bool, split: Bool?) -> (prefs: KeyboardSettings, key: NSString) {
        let prefs = settings.thumbnailSettings()
        let landscape = UIScreen.main.bounds.width > UIScreen.main.bounds.height
        prefs.previewOrientation = landscape
        prefs.previewSplit = split ?? choice.split
        let p = prefs.profile(landscape)
        let json = (try? JSONSerialization.jsonObject(with: Data(choice.gridJSON.utf8)))
            .flatMap { try? JSONSerialization.data(withJSONObject: $0, options: [.sortedKeys]) }
        let fingerprint = json.map { String(decoding: $0, as: UTF8.self) } ?? choice.gridJSON
        let key = "\(fingerprint)|\(p)|\(prefs.themeID)|\(CustomTheme.encode(prefs.customThemes))|\(dark)|\(prefs.int(Prefs.curve))|\(prefs.int(Prefs.keyGap))|\(prefs.int(Prefs.cornerRadius))|\(prefs.int(Prefs.textScale))|\(prefs.followSystemText)|\(UITraitCollection.current.preferredContentSizeCategory.rawValue)|\(UIScreen.main.bounds.size)" as NSString
        return (prefs, key)
    }
}
