import SwiftUI

@main
struct TailorKeysApp: App {
    var body: some Scene {
        WindowGroup { ContentView() }
    }
}

/// The container app only explains setup. All settings live inside the keyboard (the gear in its suggestion bar).
struct ContentView: View {
    @State private var text = ""

    var body: some View {
        NavigationView {
            Form {
                Section(header: Text("Turn on TailorKeys")) {
                    Text("1. Open Settings, then General, Keyboard, Keyboards.")
                    Text("2. Tap Add New Keyboard and choose TailorKeys.")
                    Text("3. In any text field, hold the globe key and pick TailorKeys.")
                    Button("Open Settings") {
                        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                    }
                }
                Section(header: Text("Try it")) {
                    TextField("Type here", text: $text)
                }
                Section(header: Text("Settings")) {
                    Text("Tap the gear in the middle of the suggestion bar to size, split and rearrange the keyboard. Hold the spacebar to switch to another keyboard.")
                    Text("TailorKeys doesn't ask for Full Access, so nothing you type can leave your device.")
                }
            }
            .navigationTitle("TailorKeys")
        }
        .navigationViewStyle(.stack)
    }
}
