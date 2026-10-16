import Foundation

extension Chords {
    /// The extension loads from Predictor; the app uses the same ranks in its embedded resources.
    init(predictor: Predictor) { self.init(predictor.chordWords()) }
}
