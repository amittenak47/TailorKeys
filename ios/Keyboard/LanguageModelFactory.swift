import Foundation

struct LanguageModel {
    let predictor: Predictor
    let decoder: GestureDecoder
    let chords: Chords
}

/// No UIKit/controller isolation here. The caller coalesces requests, and all publication
/// (including English score readiness) returns to the main queue.
enum LanguageModelFactory {
    private static let worker = DispatchQueue(label: "app.tailorkeys.languages", qos: .userInitiated)

    static func load(_ language: String, store: UserDefaults, onReady: @escaping () -> Void,
                     completion: @escaping (LanguageModel) -> Void) {
        worker.async {
            autoreleasepool {
                Predictor.waitForLearning()
                let predictor = Predictor(language, store: store) { DispatchQueue.main.async(execute: onReady) }
                let model = LanguageModel(predictor: predictor, decoder: GestureDecoder(predictor.gestureWords()),
                                          chords: Chords(predictor: predictor))
                DispatchQueue.main.async { completion(model) }
            }
        }
    }
}
