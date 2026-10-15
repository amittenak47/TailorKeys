import SwiftUI

@main
struct TailorKeysApp: App {
    var body: some Scene {
        WindowGroup { OnboardingView() }
    }
}

/// The container app is the setup guide, one idea per page. All settings live inside the keyboard
/// (the gear between the suggestions). After the first visit it opens on the last page, "Try it".
struct OnboardingView: View {
    @AppStorage("onboarded") private var onboarded = false
    @State private var page = 0
    @State private var text = ""

    private struct Page { let title: String; let lines: [String] }

    private let pages = [
        Page(title: "TailorKeys", lines: [
            "A keyboard you arrange yourself. Size it, split it, tilt it, and put every key where your thumbs want it.",
            "Suggestions never change what you type. They wait in two wheels above the keys until you pick one.",
        ]),
        Page(title: "Turn it on", lines: [
            "Open Settings, tap Keyboards, and turn on TailorKeys.",
            "TailorKeys doesn't ask for Full Access, so nothing you type can leave your device.",
        ]),
        Page(title: "Switch to it", lines: [
            "In any text field, hold the globe key and pick TailorKeys.",
            "Hold the spacebar to switch to your next keyboard.",
        ]),
        Page(title: "Put keys anywhere", lines: [
            "Tap the gear between the suggestions, then drag keys around.",
            "Drop a key on an empty cell to move it there.",
            "Drop it on another key's edge to insert it. The keys beside it shift over.",
            "Drop it on a key's middle to combine them into one swipe key.",
            "Tap undo if you didn't mean it. The gear in that toolbar opens size, split, templates and sharing.",
        ]),
        Page(title: "Swipe keys and wheels", lines: [
            "A swipe key holds several keys. Swipe up or down once to change it.",
            "abc: tap for one capital letter, double-tap for caps lock.",
            "Spin a wheel sideways to look through suggestions and tap to use one. The left wheel explores, the right stays close to what you typed.",
            "Hold a letter to type the number in its corner.",
        ]),
        Page(title: "Try it", lines: ["Size, split, tilt and themes are behind the gear in the keyboard's rearranging toolbar."]),
    ]

    var body: some View {
        VStack(spacing: 0) {
            TabView(selection: $page) {
                ForEach(pages.indices, id: \.self) { i in
                    ScrollView {
                        VStack(alignment: .leading, spacing: 14) {
                            Text(pages[i].title).font(.largeTitle.bold()).padding(.bottom, 6)
                            ForEach(pages[i].lines, id: \.self) { Text($0).font(.body) }
                            extra(for: i).padding(.top, 10)
                        }
                        .padding(28)
                        .frame(maxWidth: 560, alignment: .leading)
                    }
                    .tag(i)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .always))
            .indexViewStyle(.page(backgroundDisplayMode: .always))

            HStack {
                Button("Back") { withAnimation { page -= 1 } }.opacity(page == 0 ? 0 : 1).disabled(page == 0)
                Spacer()
                if page < pages.count - 1 {
                    Button { withAnimation { page += 1 } } label: { Text("Next").fontWeight(.semibold) }
                }
            }
            .padding(.horizontal, 28)
            .padding(.vertical, 14)
        }
        .onAppear { if onboarded { page = pages.count - 1 } }
        .onChange(of: page) { if $0 == pages.count - 1 { onboarded = true } }
    }

    @ViewBuilder private func extra(for i: Int) -> some View {
        switch i {
        case 1:
            Button("Open Settings") {
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            }
            .buttonStyle(.borderedProminent)
        case pages.count - 1:
            TextField("Type here", text: $text)
                .textFieldStyle(.roundedBorder)
        default:
            EmptyView()
        }
    }
}
