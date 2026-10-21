import UIKit

/// Rasterise once per grid and rendering configuration. No miniature approximation of keyboard geometry.
final class LayoutThumbnailCache {
    static let shared = LayoutThumbnailCache()
    private let images = NSCache<NSString, UIImage>()

    init() { images.countLimit = 80 }

    func image(for choice: LayoutChoice, settings: KeyboardSettings, dark: Bool, split: Bool? = nil) -> UIImage {
        let prefs = settings.thumbnailSettings()
        let landscape = UIScreen.main.bounds.width > UIScreen.main.bounds.height
        prefs.previewOrientation = landscape
        prefs.previewSplit = split
        let p = prefs.profile(landscape)
        let json = (try? JSONSerialization.jsonObject(with: Data(choice.gridJSON.utf8)))
            .flatMap { try? JSONSerialization.data(withJSONObject: $0, options: [.sortedKeys]) }
        let fingerprint = json.map { String(decoding: $0, as: UTF8.self) } ?? choice.gridJSON
        let key = "\(fingerprint)|\(p)|\(prefs.theme.rawValue)|\(dark)|\(prefs.int(Prefs.curve))|\(prefs.int(Prefs.keyGap))|\(prefs.int(Prefs.cornerRadius))|\(prefs.int(Prefs.textScale))|\(prefs.followSystemText)|\(UITraitCollection.current.preferredContentSizeCategory.rawValue)|\(UIScreen.main.bounds.size)" as NSString
        if let cached = images.object(forKey: key) { return cached }
        let keyboard = KeyboardView(settings: prefs, grid: choice.grid)
        keyboard.overrideUserInterfaceStyle = dark ? .dark : .light
        keyboard.reloadSettings()
        let result = keyboard.layoutThumbnail(width: UIScreen.main.bounds.width)
        images.setObject(result, forKey: key)
        return result
    }
}
