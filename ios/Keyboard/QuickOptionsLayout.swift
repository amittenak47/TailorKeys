import Foundation

/// Point geometry shared by phone/tablet layouts; a clipped row peeks through an 18-point fade.
struct QuickPanelGeometry {
    let strip: Int, tile: Int, viewport: Int, gap: Int, top: Int
    static func fit(width: Int, height: Int, header: Int, minimumTile: Int = 60) -> QuickPanelGeometry {
        let rows = width < 260 ? 5 : 3
        let body = max(0, height - header)
        let preferredTile = max(minimumTile, width >= 600 ? 72 : 60)
        let preferredStrip = width >= 600 && height >= 250 ? 138 : 100
        let gap = body >= preferredStrip + 24 + rows * (preferredTile + 6) + 4 ? 24 : 8
        let strip = min(preferredStrip, max(56, body - gap - rows * (minimumTile + 6) - 4))
        let tile = min(preferredTile, max(minimumTile, (body - strip - gap - 4) / rows - 6))
        let grid = rows * (tile + 6) + 4
        let available = body - strip - gap
        let viewport = available >= grid || available < tile + 6 + 18 ? grid : ((available - 18) / (tile + 6)) * (tile + 6) + 18
        return QuickPanelGeometry(strip: strip, tile: tile, viewport: viewport, gap: gap, top: max(0, (body - strip - gap - viewport) / 2))
    }
}
